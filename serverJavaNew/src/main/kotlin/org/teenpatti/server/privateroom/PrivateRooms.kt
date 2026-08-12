package org.teenpatti.server.privateroom

import org.teenpatti.server.common.AppException
import org.teenpatti.server.common.ClockProvider
import org.teenpatti.server.common.IdGenerator
import org.teenpatti.server.common.RandomSource
import org.teenpatti.server.common.ScheduledTask
import org.teenpatti.server.common.Scheduler
import org.teenpatti.server.common.TokenSupport
import org.teenpatti.server.common.GameEventLog
import org.teenpatti.server.config.GameConfig
import org.teenpatti.server.config.VariantConfig
import org.teenpatti.server.game.*
import org.teenpatti.server.infrastructure.persistence.PrivateRoomRepository
import org.teenpatti.server.infrastructure.persistence.RoundHistoryRepository
import org.teenpatti.server.platform.PlatformPlayerRef
import org.teenpatti.server.platform.PlatformSession
import org.teenpatti.server.platform.PlatformWalletService
import org.teenpatti.server.platform.TeenPattiWalletStatement
import java.io.Closeable
import java.time.Instant
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

internal class PrivateRoom(
    var state: PrivateRoomState,
    private val repository: PrivateRoomRepository,
    private val roundHistoryRepository: RoundHistoryRepository,
    private val clockProvider: ClockProvider,
    private val idGenerator: IdGenerator,
    private val randomSource: RandomSource,
    private val scheduler: Scheduler,
    private val realtimeGateway: PrivateRoomRealtimeGateway,
    private val defaultConfig: GameConfig,
    private val variantConfigs: Map<String, GameConfig>,
    private val reconnectGraceMs: Long,
    private val privateRoomTtlMs: Long,
    private val instanceId: String,
    private val platformWalletService: PlatformWalletService? = null,
) {
    var runtime: RoundTableService = createRuntime()
    private var runtimeRegistration: Closeable? = null
    private val disconnectTimers = ConcurrentHashMap<String, ScheduledTask>()

    init {
        registerRuntimeListener()
        syncRoomToRuntime()
    }

    companion object {
        const val MAX_ROOM_PLAYERS = 5
        const val ACTIVE_ROUND_DISCONNECT_TIMEOUT_MS = 5_000L
        const val PLAYER_STATUS_ACTIVE = "active_at_table"
        const val PLAYER_STATUS_WAITING = "waiting_for_next_round"
        private const val PRIVATE_ROOM_LEASE_DURATION_MS = 30_000L

        fun createInitialState(
            roomCode: String,
            roomName: String,
            hostPlayer: PrivateRoomPlayer,
            config: GameConfig,
            clockProvider: ClockProvider,
            instanceId: String,
        ): PrivateRoomState {
            val state = PrivateRoomState()
            state.roomCode = roomCode
            state.roomName = roomName
            state.status = "lobby"
            state.hostPlayerId = hostPlayer.id
            state.createdAt = clockProvider.nowIso()
            val snapshot = PrivateRoomConfigSnapshot()
            snapshot.tableId = roomCode
            snapshot.bootAmount = config.bootAmount
            snapshot.minStake = config.minStake
            snapshot.maxStake = config.maxStake
            snapshot.playerCount = MAX_ROOM_PLAYERS
            snapshot.casinoBootCommissionPercent = config.casinoBootCommissionPercent
            snapshot.casinoWinCommissionPercent = config.casinoWinCommissionPercent
            snapshot.turnDurationMs = config.turnDurationMs
            snapshot.variant = serializeVariant(config.variant)
            state.config = snapshot
            state.players = mutableListOf(hostPlayer)
            state.version = 0
            state.leaseOwner = instanceId
            state.leaseExpiresAt = clockProvider.isoFromMillis(Instant.parse(state.createdAt).toEpochMilli() + PRIVATE_ROOM_LEASE_DURATION_MS)
            state.updatedAt = state.createdAt
            return state
        }

        fun serializeVariant(variant: VariantConfig): MutableMap<String, Any?> {
            val payload = linkedMapOf<String, Any?>()
            payload["id"] = variant.id
            payload["label"] = variant.label
            payload["wildcardRanks"] = variant.wildcardRanks
            payload["evaluationMode"] = variant.evaluationMode
            payload["cardsPerSeat"] = variant.cardsPerSeat
            payload["publicCardMode"] = variant.publicCardMode
            payload["sharedJokerMode"] = variant.sharedJokerMode
            payload["forceBlindCycles"] = variant.forceBlindCycles
            payload["showUnlockCycle"] = variant.showUnlockCycle
            payload["showRequiresAllSeen"] = variant.showRequiresAllSeen
            payload["autoAcceptSideshow"] = variant.autoAcceptSideshow
            return payload
        }
    }

    @Synchronized
    fun initialize() {
        normalizePlayerStatuses()
        syncRoomToRuntime()
    }

    @Synchronized
    fun serializeForPlayer(viewerId: String): Map<String, Any?> {
        val connectedPlayerIds = connectedActivePlayers().map { it.id }
        val acceptedPlayerIds = state.acceptedNextRoundPlayerIds.filter { connectedPlayerIds.contains(it) }
        val nextRound = linkedMapOf<String, Any?>()
        nextRound["acceptedPlayerIds"] = acceptedPlayerIds
        nextRound["pendingPlayerIds"] = connectedPlayerIds.filterNot { acceptedPlayerIds.contains(it) }
        nextRound["viewerAccepted"] = acceptedPlayerIds.contains(viewerId)
        nextRound["waitingForAcceptances"] = state.status == "between_rounds"

        val response = linkedMapOf<String, Any?>()
        response["roomCode"] = state.roomCode
        response["roomName"] = state.roomName
        response["status"] = state.status
        response["hostPlayerId"] = state.hostPlayerId
        response["viewerPlayerId"] = viewerId
        response["viewerPlayerStatus"] = viewerPlayerStatus(viewerId)
        response["viewerPlatformBalance"] = getPlayerById(viewerId)?.platformBalanceSnapshot
        response["config"] = getPublicConfig()
        response["admissionMessage"] = getAdmissionMessage(viewerId)
        response["players"] = getSerializedPlayers(viewerId)
        response["nextRound"] = nextRound
        response["round"] = serializeRoundForPlayer(viewerId)
        response["history"] = state.history.take(12)
        return response
    }

    @Synchronized
    fun validatePlayerSession(playerId: String, playerToken: String): PrivateRoomPlayer {
        ensureAvailable()
        val player = requirePlayer(playerId)
        if (TokenSupport.hashToken(playerToken) != player.tokenHash) {
            throw AppException.badRequest("private_room_session_invalid", "Invalid private room session.")
        }
        return player
    }

    @Synchronized
    fun reconnectPlayer(playerId: String): Map<String, Any?> {
        markPlayerSeen(playerId, true)
        notify("player_reconnected")
        return serializeForPlayer(playerId)
    }

    @Synchronized
    fun disconnectPlayer(playerId: String) {
        val player = getPlayerById(playerId) ?: return
        player.connected = false
        updateRuntimeSeatConnectivity(playerId, false)
        val round = state.round
        if (round != null && round.status == "active" && round.seats.any { it.id == playerId && !it.packed }) {
            clearDisconnectTimer(playerId)
            disconnectTimers[playerId] =
                scheduler.schedule(ACTIVE_ROUND_DISCONNECT_TIMEOUT_MS) {
                    synchronized(this) {
                        disconnectTimers.remove(playerId)
                        if (getPlayerById(playerId)?.connected == false) {
                            runtime.forcePack(playerId, "Disconnected player packed.", "disconnect_pack")
                            syncRuntimeToRoom()
                            if (state.round?.status == "complete") {
                                enterBetweenRounds()
                            }
                            notify("player_timeout_pack")
                        }
                    }
                }
        }
        notify("player_disconnected")
    }

    @Synchronized
    fun addPlayer(playerName: String?, clientSeed: String?): IssuedPrivateRoomSession {
        return addPlayer(playerName, clientSeed, null, null)
    }

    @Synchronized
    fun addPlatformPlayer(platformSession: PlatformSession, clientSeed: String?, ip: String?): IssuedPrivateRoomSession {
        val name = platformSession.user.username.ifBlank { platformSession.userId }
        return addPlayer(name, clientSeed, platformSession, ip)
    }

    private fun addPlayer(playerName: String?, clientSeed: String?, platformSession: PlatformSession?, ip: String?): IssuedPrivateRoomSession {
        if (state.players.size >= MAX_ROOM_PLAYERS) {
            throw AppException.badRequest("private_room_full", "Private room is full.")
        }
        val normalizedPlayerName = playerName?.trim().orEmpty()
        if (normalizedPlayerName.isBlank()) {
            throw AppException.badRequest("private_room_player_name_required", "Player name is required.")
        }
        val rawToken = idGenerator.newId()
        val player = PrivateRoomPlayer()
        player.id = idGenerator.newId()
        player.tokenHash = TokenSupport.hashToken(rawToken)
        player.clientSeed = TokenSupport.requireClientSeed(clientSeed)
        player.name = normalizedPlayerName
        applyPlatformSession(player, platformSession, ip)
        player.balance = defaultConfig.initialBalance
        player.status = if (shouldJoinAsWaitingPlayer()) PLAYER_STATUS_WAITING else PLAYER_STATUS_ACTIVE
        player.connected = true
        player.joinedAt = clockProvider.nowIso()
        player.lastSeenAt = player.joinedAt
        player.avatar = "player"
        state.players.add(player)
        state.expiresAt = null
        notify("player_joined")
        return IssuedPrivateRoomSession(player, rawToken)
    }

    private fun applyPlatformSession(player: PrivateRoomPlayer, platformSession: PlatformSession?, ip: String?) {
        if (platformSession == null) {
            return
        }
        player.platformUserId = platformSession.userId
        player.platformToken = platformSession.token
        player.platformGameId = platformSession.gameId
        player.platformOperatorId = platformSession.user.operatorId
        player.platformUsername = platformSession.user.username
        player.platformCurrency = platformSession.user.currency
        player.platformBalanceSnapshot = platformSession.user.balance
        player.platformTokenIssuedAt = platformSession.issuedAt
        player.lastKnownIp = ip
    }

    @Synchronized
    fun removePlayer(playerId: String) {
        val player = getPlayerById(playerId) ?: return
        clearDisconnectTimer(playerId)
        state.players.removeIf { it.id == playerId }
        state.acceptedNextRoundPlayerIds.removeIf { it == playerId }
        val round = state.round
        if (round != null && round.seats.any { it.id == playerId && !it.packed }) {
            runtime.forcePack(playerId, "${player.name} left the room.", "leave_pack")
            syncRuntimeToRoom()
        }
        when {
            state.players.isEmpty() -> markClosed()
            playerId == state.hostPlayerId -> state.hostPlayerId = selectNextHostPlayerId()
        }
        notify("player_left")
    }

    @Synchronized
    fun startRound(requestingPlayerId: String): Map<String, Any?> {
        if (requestingPlayerId != state.hostPlayerId) {
            throw AppException.badRequest("private_room_host_required", "Only the host can start the round.")
        }
        promoteWaitingPlayers()
        val participants = buildParticipants()
        if (participants.size < 2) {
            throw AppException.badRequest("private_room_min_players", "At least 2 connected players are required.")
        }
        val roundId = idGenerator.newId()
        debitBootsIfNeeded(participants, roundId)
        runtime.clearTimers()
        runtime.startRound(participants, roundId)
        syncRuntimeToRoom()
        state.status = "active"
        state.acceptedNextRoundPlayerIds = mutableListOf()
        return serializeForPlayer(requestingPlayerId)
    }

    @Synchronized
    fun nextRound(requestingPlayerId: String): Map<String, Any?> {
        if (state.status != "between_rounds") {
            throw AppException.badRequest("private_room_not_between_rounds", "The room is not waiting for the next round.")
        }
        if (state.round?.result?.potLimitReached == true) {
            throw AppException.badRequest("private_room_pot_limit_reached", "Pot limit reached. No more rounds.")
        }
        if (requestingPlayerId != state.hostPlayerId) {
            throw AppException.badRequest("private_room_host_required", "Only the host can start the next round.")
        }
        if (state.acceptedNextRoundPlayerIds.none { it == requestingPlayerId }) {
            state.acceptedNextRoundPlayerIds.add(requestingPlayerId)
        }
        if (state.round?.dealerTipState?.pending == true) {
            val winner = getPlayerById(state.round!!.dealerTipState!!.winnerId)
            if (winner == null || !winner.connected) {
                runtime.performAction(state.round!!.dealerTipState!!.winnerId, "dealer_tip", mapOf("amount" to 0))
                syncRuntimeToRoom()
            }
            if (state.round?.dealerTipState?.pending == true) {
                throw AppException.badRequest("private_room_dealer_tip_pending", "Resolve the dealer tip before starting the next round.")
            }
        }
        val connectedPlayerIds = connectedActivePlayers().map { it.id }
        val acceptedPlayerIds = state.acceptedNextRoundPlayerIds.filter { connectedPlayerIds.contains(it) }
        if (!acceptedPlayerIds.containsAll(connectedPlayerIds)) {
            throw AppException.badRequest("private_room_acceptance_pending", "All connected players must accept before the host can start the next round.")
        }
        promoteWaitingPlayers()
        if (buildParticipants().size < 2) {
            throw AppException.badRequest("private_room_min_players", "At least 2 connected players are required.")
        }
        return startRound(requestingPlayerId)
    }

    @Synchronized
    fun acceptNextRound(requestingPlayerId: String): Map<String, Any?> {
        if (state.status != "between_rounds") {
            throw AppException.badRequest("private_room_not_between_rounds", "The room is not waiting for the next round.")
        }
        if (state.round?.result?.potLimitReached == true) {
            throw AppException.badRequest("private_room_pot_limit_reached", "Pot limit reached. No more rounds.")
        }
        if (state.acceptedNextRoundPlayerIds.none { it == requestingPlayerId }) {
            state.acceptedNextRoundPlayerIds.add(requestingPlayerId)
        }
        notify("next_round_accepted")
        return serializeForPlayer(requestingPlayerId)
    }

    @Synchronized
    fun updateConfig(requestingPlayerId: String, variantId: String?, bootAmount: Int?): Map<String, Any?> {
        requireHost(requestingPlayerId)
        if (state.status != "lobby") {
            throw AppException.badRequest("private_room_config_locked", "Private room settings can only be updated in the lobby.")
        }
        val nextConfig = createRoomConfig(state.roomCode, variantId, bootAmount)
        state.config = createConfigSnapshot(nextConfig)
        runtime.clearTimers()
        runtime.shutdown()
        runtime = createRuntime()
        registerRuntimeListener()
        syncRoomToRuntime()
        notify("config_updated")
        return serializeForPlayer(requestingPlayerId)
    }

    @Synchronized
    fun performAction(playerId: String, type: String, payload: Map<String, Any?>): Map<String, Any?> {
        debitActionIfNeeded(playerId, type, payload)
        runtime.performAction(playerId, type, payload)
        syncRuntimeToRoom()
        creditWinnerIfNeeded()
        if (state.round?.status == "complete") {
            enterBetweenRounds()
        }
        return serializeForPlayer(playerId)
    }

    private fun debitBootsIfNeeded(participants: List<RoundParticipant>, roundId: String) {
        for (participant in participants) {
            val player = getPlayerById(participant.id) ?: continue
            platformWalletService?.debit(
                platformRef(player),
                roundId,
                "tp:$roundId:${player.id}:boot",
                roomGameConfig().bootAmount,
                TeenPattiWalletStatement.description(roomGameConfig().bootAmount, "debited", "boot", roundId, state.roomCode),
            )
        }
    }

    private fun debitActionIfNeeded(playerId: String, type: String, payload: Map<String, Any?> = emptyMap()) {
        val amount = runtime.quoteDebitForAction(playerId, type, payload)
        if (amount <= 0) {
            return
        }
        val roundId = runtime.currentRoundId() ?: throw IllegalStateException("No active round is available.")
        val actionIndex = runtime.currentActionLogSize()
        val player = requirePlayer(playerId)
        val operationKey =
            if (type == "dealer_tip") {
                "tp:$roundId:${player.id}:dealer_tip:$amount:${clockProvider.now().toEpochMilli()}"
            } else {
                "tp:$roundId:${player.id}:$actionIndex:$type"
            }
        platformWalletService?.debit(
            platformRef(player),
            roundId,
            operationKey,
            amount,
            TeenPattiWalletStatement.description(amount, "debited", type, roundId, state.roomCode),
        )
    }

    private fun creditWinnerIfNeeded() {
        val round = runtime.state.round ?: return
        val result = round.result ?: return
        if (round.settledAt == null || result.payout <= 0) {
            return
        }
        val winner = getPlayerById(result.winnerId) ?: return
        platformWalletService?.credit(
            platformRef(winner),
            round.id,
            "tp:${round.id}:${winner.id}:payout",
            result.payout,
            TeenPattiWalletStatement.description(result.payout, "credited", "payout", round.id, state.roomCode),
        )
    }

    private fun platformRef(player: PrivateRoomPlayer): PlatformPlayerRef =
        PlatformPlayerRef(
            player.id,
            player.platformUserId,
            player.platformToken,
            player.platformGameId,
            player.platformOperatorId,
            player.lastKnownIp,
            false,
        )

    private fun buildParticipants(): MutableList<RoundParticipant> {
        val participants = mutableListOf<RoundParticipant>()
        for (player in state.players) {
            if (!player.connected || playerStatus(player) != PLAYER_STATUS_ACTIVE) {
                continue
            }
            val participant = RoundParticipant()
            participant.id = player.id
            participant.name = player.name
            participant.avatar = player.avatar
            participant.isBot = false
            participant.connected = player.connected
            participant.clientSeed = player.clientSeed
            participants.add(participant)
        }
        return participants
    }

    private fun updateRuntimeSeatConnectivity(playerId: String, connected: Boolean) {
        val round = runtime.state.round ?: return
        for (seat in round.seats) {
            if (seat.id == playerId) {
                seat.connected = connected
                break
            }
        }
        syncRuntimeToRoom()
    }

    private fun clearDisconnectTimer(playerId: String) {
        disconnectTimers.remove(playerId)?.cancel()
    }

    @Synchronized
    fun isActive(): Boolean = state.expiresAt == null && state.status != "closed"

    @Synchronized
    fun closeIfInactive(): Boolean {
        if (isActive() && hasLiveMembers()) {
            return false
        }
        markClosed()
        return true
    }

    @Synchronized
    fun shutdown() {
        clearAllDisconnectTimers()
        runtimeRegistration?.let {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        runtimeRegistration = null
        runtime.shutdown()
    }

    private fun getPlayerById(playerId: String): PrivateRoomPlayer? = state.players.firstOrNull { it.id == playerId }

    @Synchronized
    fun markPlayerSeen(playerId: String, connected: Boolean): PrivateRoomPlayer {
        val player = requirePlayer(playerId)
        player.status = playerStatus(player)
        player.connected = connected
        player.lastSeenAt = clockProvider.nowIso()
        state.expiresAt = null
        clearDisconnectTimer(playerId)
        updateRuntimeSeatConnectivity(playerId, connected)
        return player
    }

    private fun requirePlayer(playerId: String): PrivateRoomPlayer =
        getPlayerById(playerId)
            ?: throw AppException.badRequest(
                "private_room_session_invalid",
                "Player session is no longer valid for this room.",
            )

    private fun connectedActivePlayers(): List<PrivateRoomPlayer> =
        state.players.filter { it.connected && playerStatus(it) == PLAYER_STATUS_ACTIVE }

    private fun hasLiveMembers(): Boolean = state.players.any { isLivePlayer(it) }

    private fun isLivePlayer(player: PrivateRoomPlayer?): Boolean {
        if (player == null) {
            return false
        }
        if (player.connected) {
            return true
        }
        if (player.lastSeenAt == null) {
            return false
        }
        val lastSeenMs = Instant.parse(player.lastSeenAt).toEpochMilli()
        return lastSeenMs + reconnectGraceMs > clockProvider.now().toEpochMilli()
    }

    private fun viewerPlayerStatus(viewerId: String): String? = getPlayerById(viewerId)?.let(::playerStatus)

    private fun getAdmissionMessage(viewerId: String): String? {
        if (viewerPlayerStatus(viewerId) != PLAYER_STATUS_WAITING) {
            return null
        }
        return when (state.status) {
            "active" -> "The current hand is in progress. You will join when it finishes."
            "between_rounds" -> "You are queued to join when the host starts the next round."
            else -> "You are waiting for the host to start the next round."
        }
    }

    private fun shouldJoinAsWaitingPlayer(): Boolean =
        state.status == "active" && state.round?.status != "complete"

    private fun promoteWaitingPlayers() {
        for (player in state.players) {
            if (player.connected && playerStatus(player) == PLAYER_STATUS_WAITING) {
                player.status = PLAYER_STATUS_ACTIVE
            }
        }
    }

    private fun selectNextHostPlayerId(): String {
        val activePlayer = state.players.firstOrNull { playerStatus(it) == PLAYER_STATUS_ACTIVE }
        return activePlayer?.id ?: state.players.first().id
    }

    private fun normalizePlayerStatuses() {
        for (player in state.players) {
            player.status = playerStatus(player)
        }
    }

    private fun ensureAvailable() {
        if (state.expiresAt != null || state.status == "closed") {
            throw AppException.badRequest("private_room_unavailable", "This private room expired or is no longer available.")
        }
    }

    private fun playerStatus(player: PrivateRoomPlayer?): String =
        if (player == null || player.status.isBlank()) PLAYER_STATUS_ACTIVE else player.status

    private fun markClosed() {
        state.status = "closed"
        if (state.closedAt == null) {
            state.closedAt = clockProvider.nowIso()
        }
        if (state.expiresAt == null) {
            state.expiresAt = clockProvider.isoFromMillis(clockProvider.now().toEpochMilli() + privateRoomTtlMs)
        }
        state.acceptedNextRoundPlayerIds = mutableListOf()
    }

    private fun clearAllDisconnectTimers() {
        val tasks = disconnectTimers.values.toList()
        disconnectTimers.clear()
        tasks.forEach { it.cancel() }
    }

    private fun notify(type: String) {
        persist()
        GameEventLog.info(type, "tableType" to "private_room", "lobbyId" to state.roomCode, "roundId" to state.round?.id)
        realtimeGateway.roomUpdated(state.roomCode, type)
    }

    private fun registerRuntimeListener() {
        runtimeRegistration =
            runtime.registerListener { event ->
                scheduler.schedule(0L) {
                    synchronized(this) {
                        handleRuntimeEvent(event.type)
                    }
                }
            }
    }

    private fun handleRuntimeEvent(eventType: String) {
        syncRuntimeToRoom()
        if (state.round != null) {
            if (state.round!!.status == "complete") {
                enterBetweenRounds()
            } else {
                state.status = "active"
            }
        }
        notify(eventType)
    }

    private fun enterBetweenRounds() {
        if (state.status != "between_rounds") {
            state.acceptedNextRoundPlayerIds = mutableListOf()
        }
        state.status = "between_rounds"
    }

    @Synchronized
    fun persist() {
        syncRuntimeToRoom()
        closeIfInactive()
        refreshPersistenceMetadata()
        state = repository.saveRoom(state)
    }

    private fun refreshPersistenceMetadata() {
        val now = clockProvider.nowIso()
        state.updatedAt = now
        state.leaseOwner = instanceId
        state.leaseExpiresAt =
            if (state.status == "closed") {
                now
            } else {
                clockProvider.isoFromMillis(Instant.parse(now).toEpochMilli() + PRIVATE_ROOM_LEASE_DURATION_MS)
            }
    }

    private fun createRuntime(): RoundTableService =
        RoundTableService(
            roomGameConfig(),
            null,
            roundHistoryRepository,
            clockProvider,
            idGenerator,
            randomSource,
            scheduler,
            instanceId,
            "private_room",
            null,
        )

    private fun syncRoomToRuntime() {
        runtime.state.id = state.roomCode
        runtime.state.variantId = currentVariantId()
        runtime.state.status = state.status
        runtime.state.round = state.round
        runtime.state.history = mutableListOf()
        runtime.state.playerBankrolls = mutableListOf()
        for (player in state.players) {
            val bankroll = PlayerBankroll()
            bankroll.id = player.id
            bankroll.balance = player.balance
            runtime.state.playerBankrolls.add(bankroll)
        }
        if (isActive()) {
            runtime.restoreRuntime()
        } else {
            runtime.clearTimers()
        }
    }

    private fun syncRuntimeToRoom() {
        state.round = runtime.state.round
        if (runtime.state.round != null) {
            for (seat in runtime.state.round!!.seats) {
                val player = getPlayerById(seat.id)
                if (player != null) {
                    player.balance = seat.balance
                    player.connected = seat.connected
                }
            }
        }
        if (state.round?.result != null) {
            val item = PrivateRoomHistoryItem()
            item.id = state.round!!.id
            item.roundId = state.round!!.id
            item.winnerId = state.round!!.result!!.winnerId
            item.winnerName = state.round!!.result!!.winnerName
            item.winningHand = state.round!!.result!!.winningHand
            item.pot = state.round!!.potAmount
            item.bootCommission = state.round!!.result!!.bootCommission
            item.winCommission = state.round!!.result!!.winCommission
            item.dealerTip = state.round!!.result!!.dealerTip
            item.casinoCommissionTotal = state.round!!.result!!.casinoCommissionTotal
            item.winnerReceivableBeforeTip = state.round!!.result!!.winnerReceivableBeforeTip
            item.payout = state.round!!.result!!.payout
            item.reason = state.round!!.result!!.reason
            item.timestamp = state.round!!.settledAt
            item.provablyFair = TokenSupport.copyProvablyFairState(state.round!!.provablyFair, true)
            if ((state.round!!.dealerTipState == null || !state.round!!.dealerTipState!!.pending) &&
                state.history.none { it.roundId == item.roundId }
            ) {
                state.history.add(0, item)
            }
        }
    }

    private fun getPublicConfig(): Map<String, Any?> {
        val config = roomGameConfig()
        val response = linkedMapOf<String, Any?>()
        response["tableId"] = state.roomCode
        response["variant"] = serializeVariant(config.variant)
        response["bootAmount"] = config.bootAmount
        response["maxPotAmount"] = config.maxPotAmount
        response["minStake"] = minOf(config.minStake, config.bootAmount)
        response["maxStake"] = config.maxStake
        response["playerCount"] = MAX_ROOM_PLAYERS
        response["casinoBootCommissionPercent"] = config.casinoBootCommissionPercent
        response["casinoWinCommissionPercent"] = config.casinoWinCommissionPercent
        response["turnDurationMs"] = config.turnDurationMs
        response["variantsEnabled"] =
            mapOf(
                "allowA23Sequence" to config.allowA23Sequence,
                "allowAkqSequence" to config.allowAkqSequence,
                "sequenceRankingMode" to config.sequenceRankingMode,
                "wildcardRanks" to config.variant.wildcardRanks,
                "sideshow" to true,
                "autoplay" to false,
                "forceBlindCycles" to config.variant.forceBlindCycles,
                "showUnlockCycle" to config.variant.showUnlockCycle,
                "showRequiresAllSeen" to config.variant.showRequiresAllSeen,
                "autoAcceptSideshow" to config.variant.autoAcceptSideshow,
            )
        return response
    }

    private fun getSerializedPlayers(viewerId: String): MutableList<Map<String, Any?>> {
        val players = mutableListOf<Map<String, Any?>>()
        for (index in state.players.indices) {
            val player = state.players[index]
            val item = linkedMapOf<String, Any?>()
            item["id"] = player.id
            item["name"] = player.name
            item["avatar"] = player.avatar
            item["connected"] = player.connected
            item["status"] = playerStatus(player)
            item["isHost"] = player.id == state.hostPlayerId
            item["isViewer"] = player.id == viewerId
            item["seatIndex"] = index
            item["balance"] = player.balance
            item["joinedAt"] = player.joinedAt
            item["acceptedNextRound"] = state.acceptedNextRoundPlayerIds.contains(player.id)
            players.add(item)
        }
        return players
    }

    @Suppress("UNCHECKED_CAST")
    private fun serializeRoundForPlayer(viewerId: String): Map<String, Any?>? =
        state.round?.let { round ->
            val serialized = runtime.getTableState(viewerId)["round"] as? Map<String, Any?> ?: return null
            if (round.status != "complete" && state.status != "between_rounds") {
                return serialized
            }

            val activePlayerIds =
                state.players
                    .filter { playerStatus(it) != PLAYER_STATUS_WAITING }
                    .map { it.id }
                    .toHashSet()
            val seats = serialized["seats"] as? List<Map<String, Any?>> ?: return serialized
            if (seats.isEmpty()) {
                return serialized
            }

            val filteredSeats = seats.filter { seat -> activePlayerIds.contains(seat["id"] as? String) }
            if (filteredSeats.size == seats.size) {
                return serialized
            }

            LinkedHashMap(serialized).apply {
                put("seats", filteredSeats)
            }
        }

    private fun roomGameConfig(): GameConfig {
        val config = GameConfig()
        val selectedConfig = variantConfigs[currentVariantId()] ?: defaultConfig
        config.tableId = state.roomCode
        config.bootAmount = state.config?.bootAmount ?: selectedConfig.bootAmount
        config.maxPotAmount = selectedConfig.maxPotAmount
        config.minStake = minOf(selectedConfig.minStake, config.bootAmount)
        config.maxStake = selectedConfig.maxStake
        config.maxRoundsBeforeForcedShow = selectedConfig.maxRoundsBeforeForcedShow
        config.playerCount = MAX_ROOM_PLAYERS
        config.casinoBootCommissionPercent = selectedConfig.casinoBootCommissionPercent
        config.casinoWinCommissionPercent = selectedConfig.casinoWinCommissionPercent
        config.maxBalance = selectedConfig.maxBalance
        config.initialBalance = selectedConfig.initialBalance
        config.turnDurationMs = selectedConfig.turnDurationMs
        config.blindSeenMultiplier = selectedConfig.blindSeenMultiplier
        config.blindRaiseMultiplier = selectedConfig.blindRaiseMultiplier
        config.seenRaiseMultiplier = selectedConfig.seenRaiseMultiplier
        config.allowA23Sequence = selectedConfig.allowA23Sequence
        config.allowAkqSequence = selectedConfig.allowAkqSequence
        config.sequenceRankingMode = selectedConfig.sequenceRankingMode
        config.botDecisionMode = selectedConfig.botDecisionMode
        config.botMaxSimulations = selectedConfig.botMaxSimulations
        config.botMaxDecisionTimeMs = selectedConfig.botMaxDecisionTimeMs
        config.botHeadsUpSeeAfterBlindTurns = selectedConfig.botHeadsUpSeeAfterBlindTurns
        config.autoplay = selectedConfig.autoplay
        config.botActionDelayMs = selectedConfig.botActionDelayMs
        val variant = VariantConfig()
        variant.id = selectedConfig.variant.id
        variant.label = selectedConfig.variant.label
        variant.wildcardRanks = selectedConfig.variant.wildcardRanks.toMutableList()
        variant.evaluationMode = selectedConfig.variant.evaluationMode
        variant.cardsPerSeat = selectedConfig.variant.cardsPerSeat
        variant.publicCardMode = selectedConfig.variant.publicCardMode
        variant.sharedJokerMode = selectedConfig.variant.sharedJokerMode
        variant.forceBlindCycles = selectedConfig.variant.forceBlindCycles
        variant.showUnlockCycle = selectedConfig.variant.showUnlockCycle
        variant.showRequiresAllSeen = selectedConfig.variant.showRequiresAllSeen
        variant.autoAcceptSideshow = selectedConfig.variant.autoAcceptSideshow
        config.variant = variant
        return config
    }

    private fun currentVariantId(): String = (state.config?.variant?.get("id") as? String)?.trim()?.lowercase().orEmpty().ifBlank { "classic" }

    private fun createRoomConfig(roomCode: String, variantId: String?, bootAmount: Int?): GameConfig {
        val normalizedVariantId = variantId?.trim()?.lowercase().orEmpty().ifBlank { currentVariantId() }
        val selectedConfig =
            variantConfigs[normalizedVariantId]
                ?: throw AppException.badRequest("unsupported_variant", "Unsupported game variant: $normalizedVariantId")
        val nextBootAmount = bootAmount ?: state.config?.bootAmount ?: selectedConfig.bootAmount
        if (nextBootAmount <= 0) {
            throw AppException.badRequest("private_room_boot_amount_invalid", "Boot amount must be greater than 0.")
        }
        if (nextBootAmount > defaultConfig.initialBalance) {
            throw AppException.badRequest("private_room_boot_amount_invalid", "Boot amount cannot exceed the starting balance.")
        }
        return roomGameConfig().also {
            it.tableId = roomCode
            it.bootAmount = nextBootAmount
            it.minStake = minOf(it.minStake, nextBootAmount)
            it.variant = selectedConfig.variant
        }
    }

    private fun createConfigSnapshot(config: GameConfig): PrivateRoomConfigSnapshot =
        PrivateRoomConfigSnapshot().also { snapshot ->
            snapshot.tableId = state.roomCode
            snapshot.bootAmount = config.bootAmount
            snapshot.minStake = config.minStake
            snapshot.maxStake = config.maxStake
            snapshot.playerCount = MAX_ROOM_PLAYERS
            snapshot.casinoBootCommissionPercent = config.casinoBootCommissionPercent
            snapshot.casinoWinCommissionPercent = config.casinoWinCommissionPercent
            snapshot.turnDurationMs = config.turnDurationMs
            snapshot.variant = serializeVariant(config.variant)
        }

    private fun requireHost(playerId: String) {
        if (playerId != state.hostPlayerId) {
            throw AppException.badRequest("private_room_host_required", "Only the host can update private room settings.")
        }
    }

}

internal class PrivateRoomManager(
    private val store: PrivateRoomRepository,
    private val defaultConfig: GameConfig,
    private val variantConfigs: Map<String, GameConfig>,
    private val roundHistoryRepository: RoundHistoryRepository,
    private val realtimeGateway: PrivateRoomRealtimeGateway,
    private val clockProvider: ClockProvider,
    private val idGenerator: IdGenerator,
    private val randomSource: RandomSource,
    private val scheduler: Scheduler,
    private val reconnectGraceMs: Long,
    private val privateRoomTtlMs: Long,
    private val instanceId: String,
    private val platformWalletService: PlatformWalletService? = null,
) {
    private val rooms = ConcurrentHashMap<String, PrivateRoom>()

    @Synchronized
    fun initialize() {
        for (room in store.listActiveRooms()) {
            val claimed = claimLease(room.roomCode)
            if (claimed != null) {
                val privateRoom = createRoomInstance(claimed)
                privateRoom.initialize()
                trackRoom(privateRoom)
            }
        }
    }

    @Synchronized
    fun createRoom(roomName: String?, playerName: String?, clientSeed: String?, variantId: String?, bootAmount: Int?): Map<String, Any?> {
        return createRoom(roomName, playerName, clientSeed, variantId, bootAmount, null, null)
    }

    @Synchronized
    fun createPlatformRoom(
        roomName: String?,
        platformSession: PlatformSession,
        clientSeed: String?,
        variantId: String?,
        bootAmount: Int?,
        ip: String?,
    ): Map<String, Any?> {
        val name = platformSession.user.username.ifBlank { platformSession.userId }
        return createRoom(roomName, name, clientSeed, variantId, bootAmount, platformSession, ip)
    }

    private fun createRoom(
        roomName: String?,
        playerName: String?,
        clientSeed: String?,
        variantId: String?,
        bootAmount: Int?,
        platformSession: PlatformSession?,
        ip: String?,
    ): Map<String, Any?> {
        val normalizedRoomName = roomName?.trim().orEmpty()
        val normalizedPlayerName = playerName?.trim().orEmpty()
        if (normalizedRoomName.isBlank()) {
            throw AppException.badRequest("private_room_name_required", "Room name is required.")
        }
        if (normalizedPlayerName.isBlank()) {
            throw AppException.badRequest("private_room_player_name_required", "Player name is required.")
        }
        var roomCode = createRoomCode()
        while (store.loadRoom(roomCode) != null) {
            roomCode = createRoomCode()
        }
        val rawToken = idGenerator.newId()
        val host = PrivateRoomPlayer()
        host.id = idGenerator.newId()
        host.tokenHash = TokenSupport.hashToken(rawToken)
        host.clientSeed = TokenSupport.requireClientSeed(clientSeed)
        host.name = normalizedPlayerName
        if (platformSession != null) {
            host.platformUserId = platformSession.userId
            host.platformToken = platformSession.token
            host.platformGameId = platformSession.gameId
            host.platformOperatorId = platformSession.user.operatorId
            host.platformUsername = platformSession.user.username
            host.platformCurrency = platformSession.user.currency
            host.platformBalanceSnapshot = platformSession.user.balance
            host.platformTokenIssuedAt = platformSession.issuedAt
            host.lastKnownIp = ip
        }
        host.balance = defaultConfig.initialBalance
        host.status = PrivateRoom.PLAYER_STATUS_ACTIVE
        host.connected = true
        host.joinedAt = clockProvider.nowIso()
        host.lastSeenAt = host.joinedAt
        host.avatar = "you"
        val room =
            createRoomInstance(
                PrivateRoom.createInitialState(
                    roomCode,
                    normalizedRoomName,
                    host,
                    validateCreateConfig(roomCode, variantId, bootAmount),
                    clockProvider,
                    instanceId,
                ),
            )
        room.initialize()
        room.persist()
        trackRoom(room)
        return buildRoomResponse(room, host, rawToken)
    }

    @Synchronized
    fun joinRoom(roomCode: String?, playerName: String?, clientSeed: String?): Map<String, Any?> {
        val room = getRoom(roomCode, true)
        val issued = room.addPlayer(playerName, clientSeed)
        trackRoom(room)
        return buildRoomResponse(room, issued.player, issued.rawToken)
    }

    @Synchronized
    fun joinPlatformRoom(roomCode: String?, platformSession: PlatformSession, clientSeed: String?, ip: String?): Map<String, Any?> {
        val room = getRoom(roomCode, true)
        val issued = room.addPlatformPlayer(platformSession, clientSeed, ip)
        trackRoom(room)
        return buildRoomResponse(room, issued.player, issued.rawToken)
    }

    @Synchronized
    fun getSession(roomCode: String?, playerId: String, playerToken: String): Map<String, Any?> {
        val room = getRoom(roomCode, false)
        val player = room.validatePlayerSession(playerId, playerToken)
        room.markPlayerSeen(playerId, true)
        room.persist()
        trackRoom(room)
        return buildRoomResponse(room, player, playerToken)
    }

    @Synchronized
    fun authenticate(roomCode: String?, playerId: String, playerToken: String): Map<String, Any?> {
        val room = getRoom(roomCode, true)
        room.validatePlayerSession(playerId, playerToken)
        val response = room.reconnectPlayer(playerId)
        trackRoom(room)
        return response
    }

    @Synchronized
    fun performAction(roomCode: String?, playerId: String, playerToken: String, type: String, payload: Map<String, Any?>): Map<String, Any?> {
        val room = getRoom(roomCode, true)
        room.validatePlayerSession(playerId, playerToken)
        val response = room.performAction(playerId, type, payload)
        trackRoom(room)
        return response
    }

    @Synchronized
    fun startRound(roomCode: String?, playerId: String, playerToken: String): Map<String, Any?> {
        val room = getRoom(roomCode, true)
        room.validatePlayerSession(playerId, playerToken)
        val response = room.startRound(playerId)
        trackRoom(room)
        return response
    }

    @Synchronized
    fun nextRound(roomCode: String?, playerId: String, playerToken: String): Map<String, Any?> {
        val room = getRoom(roomCode, true)
        room.validatePlayerSession(playerId, playerToken)
        val response = room.nextRound(playerId)
        trackRoom(room)
        return response
    }

    @Synchronized
    fun acceptNextRound(roomCode: String?, playerId: String, playerToken: String): Map<String, Any?> {
        val room = getRoom(roomCode, true)
        room.validatePlayerSession(playerId, playerToken)
        val response = room.acceptNextRound(playerId)
        trackRoom(room)
        return response
    }

    @Synchronized
    fun updateConfig(roomCode: String?, playerId: String, playerToken: String, variantId: String?, bootAmount: Int?): Map<String, Any?> {
        val room = getRoom(roomCode, true)
        room.validatePlayerSession(playerId, playerToken)
        val response = room.updateConfig(playerId, variantId, bootAmount)
        trackRoom(room)
        return response
    }

    @Synchronized
    fun leaveRoom(roomCode: String?, playerId: String, playerToken: String): Map<String, Any?> {
        val room = getRoom(roomCode, true)
        room.validatePlayerSession(playerId, playerToken)
        val snapshot = room.serializeForPlayer(playerId)
        room.removePlayer(playerId)
        trackRoom(room)
        return snapshot
    }

    @Synchronized
    fun disconnect(roomCode: String?, playerId: String, playerToken: String) {
        val room = getRoom(roomCode, true)
        room.validatePlayerSession(playerId, playerToken)
        room.disconnectPlayer(playerId)
        trackRoom(room)
    }

    @Synchronized
    fun ensureOwnership(roomCode: String): Boolean =
        try {
            getRoom(roomCode, true)
            true
        } catch (error: AppException) {
            if (error.code == "private_room_ownership_unavailable") {
                false
            } else {
                throw error
            }
        } catch (_: Exception) {
            false
        }

    private fun getRoom(roomCode: String?, requireLease: Boolean): PrivateRoom {
        val normalizedRoomCode = roomCode?.trim()?.uppercase().orEmpty()
        if (normalizedRoomCode.isBlank()) {
            throw AppException.badRequest("private_room_code_required", "Room code is required.")
        }
        val cached = rooms[normalizedRoomCode]
        if (cached != null && (!requireLease || cached.state.leaseOwner == instanceId)) {
            trackRoom(cached)
            ensureRoomAvailable(cached)
            return cached
        }
        var stored = if (requireLease) claimLease(normalizedRoomCode) else null
        if (stored == null) {
            stored = store.loadRoom(normalizedRoomCode)
        }
        if (stored == null) {
            throw AppException.badRequest("private_room_unavailable", "This private room expired or is no longer available.")
        }
        if (requireLease && stored.leaseOwner != instanceId) {
            throw AppException.badRequest(
                "private_room_ownership_unavailable",
                "Private room is currently handled by another server node.",
            )
        }
        val room = createRoomInstance(stored)
        room.initialize()
        trackRoom(room)
        ensureRoomAvailable(room)
        return room
    }

    private fun createRoomInstance(state: PrivateRoomState): PrivateRoom =
        PrivateRoom(
            state,
            store,
            roundHistoryRepository,
            clockProvider,
            idGenerator,
            randomSource,
            scheduler,
            realtimeGateway,
            defaultConfig,
            variantConfigs,
            reconnectGraceMs,
            privateRoomTtlMs,
            instanceId,
            platformWalletService,
        )

    private fun buildRoomResponse(room: PrivateRoom, player: PrivateRoomPlayer, rawToken: String): Map<String, Any?> =
        mapOf(
            "roomCode" to room.state.roomCode,
            "roomName" to room.state.roomName,
            "playerId" to player.id,
            "playerToken" to rawToken,
            "playerName" to player.name,
            "hostPlayerId" to room.state.hostPlayerId,
            "roomState" to room.serializeForPlayer(player.id),
        )

    private fun claimLease(roomCode: String): PrivateRoomState? {
        val now = clockProvider.nowIso()
        return store.claimLease(
            roomCode,
            instanceId,
            clockProvider.isoFromMillis(Instant.parse(now).toEpochMilli() + PRIVATE_ROOM_LEASE_DURATION_MS),
            now,
        )
    }

    private fun trackRoom(room: PrivateRoom) {
        if (room.closeIfInactive()) {
            room.persist()
            room.shutdown()
            rooms.remove(room.state.roomCode)
            return
        }
        rooms[room.state.roomCode] = room
    }

    private fun ensureRoomAvailable(room: PrivateRoom) {
        if (!room.isActive()) {
            rooms.remove(room.state.roomCode)
            room.shutdown()
            throw AppException.badRequest("private_room_unavailable", "This private room expired or is no longer available.")
        }
    }

    private fun createRoomCode(): String {
        val builder = StringBuilder()
        repeat(6) {
            builder.append(ROOM_CODE_ALPHABET[randomSource.nextInt(ROOM_CODE_ALPHABET.length)])
        }
        return builder.toString()
    }

    private fun validateCreateConfig(roomCode: String, variantId: String?, bootAmount: Int?): GameConfig {
        val normalizedVariantId = variantId?.trim()?.lowercase().orEmpty().ifBlank { "classic" }
        val selectedConfig =
            variantConfigs[normalizedVariantId]
                ?: throw AppException.badRequest("unsupported_variant", "Unsupported game variant: $normalizedVariantId")
        val nextBootAmount = bootAmount ?: selectedConfig.bootAmount
        if (nextBootAmount <= 0) {
            throw AppException.badRequest("private_room_boot_amount_invalid", "Boot amount must be greater than 0.")
        }
        if (nextBootAmount > defaultConfig.initialBalance) {
            throw AppException.badRequest("private_room_boot_amount_invalid", "Boot amount cannot exceed the starting balance.")
        }
        val config = GameConfig()
        config.tableId = roomCode
        config.bootAmount = nextBootAmount
        config.maxPotAmount = selectedConfig.maxPotAmount
        config.minStake = minOf(selectedConfig.minStake, nextBootAmount)
        config.maxStake = selectedConfig.maxStake
        config.maxRoundsBeforeForcedShow = selectedConfig.maxRoundsBeforeForcedShow
        config.playerCount = PrivateRoom.MAX_ROOM_PLAYERS
        config.casinoBootCommissionPercent = selectedConfig.casinoBootCommissionPercent
        config.casinoWinCommissionPercent = selectedConfig.casinoWinCommissionPercent
        config.maxBalance = selectedConfig.maxBalance
        config.initialBalance = selectedConfig.initialBalance
        config.turnDurationMs = selectedConfig.turnDurationMs
        config.blindSeenMultiplier = selectedConfig.blindSeenMultiplier
        config.blindRaiseMultiplier = selectedConfig.blindRaiseMultiplier
        config.seenRaiseMultiplier = selectedConfig.seenRaiseMultiplier
        config.allowA23Sequence = selectedConfig.allowA23Sequence
        config.allowAkqSequence = selectedConfig.allowAkqSequence
        config.sequenceRankingMode = selectedConfig.sequenceRankingMode
        config.botDecisionMode = selectedConfig.botDecisionMode
        config.botMaxSimulations = selectedConfig.botMaxSimulations
        config.botMaxDecisionTimeMs = selectedConfig.botMaxDecisionTimeMs
        config.botHeadsUpSeeAfterBlindTurns = selectedConfig.botHeadsUpSeeAfterBlindTurns
        config.autoplay = selectedConfig.autoplay
        config.botActionDelayMs = selectedConfig.botActionDelayMs
        config.variant = selectedConfig.variant
        return config
    }

    private companion object {
        const val ROOM_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        const val PRIVATE_ROOM_LEASE_DURATION_MS = 30_000L
    }
}
