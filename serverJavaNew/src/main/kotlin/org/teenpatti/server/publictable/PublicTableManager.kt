package org.teenpatti.server.publictable

import org.teenpatti.server.common.ClockProvider
import org.teenpatti.server.common.IdGenerator
import org.teenpatti.server.common.BotUsernames
import org.teenpatti.server.common.RandomSource
import org.teenpatti.server.common.ScheduledTask
import org.teenpatti.server.common.Scheduler
import org.teenpatti.server.common.TokenSupport
import org.teenpatti.server.common.GameEventLog
import org.teenpatti.server.config.GameConfig
import org.teenpatti.server.game.*
import org.teenpatti.server.infrastructure.persistence.PublicSessionRepository
import org.teenpatti.server.infrastructure.persistence.RoundHistoryRepository
import org.teenpatti.server.infrastructure.persistence.TableAggregateRepository
import org.teenpatti.server.platform.PlatformPlayerRef
import org.teenpatti.server.platform.PlatformSession
import org.teenpatti.server.platform.PlatformWalletService
import org.teenpatti.server.platform.TeenPattiWalletStatement
import java.io.Closeable
import java.time.Instant
import java.util.ConcurrentModificationException
import java.util.LinkedHashMap

internal class PublicTableManager(
    private val config: GameConfig,
    private val tableRepository: TableAggregateRepository,
    private val publicSessionRepository: PublicSessionRepository,
    private val roundHistoryRepository: RoundHistoryRepository,
    private val clockProvider: ClockProvider,
    private val idGenerator: IdGenerator,
    private val randomSource: RandomSource,
    private val scheduler: Scheduler,
    private val realtimeGateway: PublicTableRealtimeGateway,
    private val playerPresence: PlayerPresence,
    private val reconnectGraceMs: Long,
    private val instanceId: String,
    private val platformWalletService: PlatformWalletService? = null,
    private val matchmakingCoordinator: MatchmakingCoordinator? = null,
    private val matchmakingWindowMs: Long = PUBLIC_TABLE_JOIN_WAIT_MS,
    private val matchmakingPvpThreshold: Int = 1,
) {
    private val tables = LinkedHashMap<String, ManagedPublicTable>()
    private val players = LinkedHashMap<String, PublicPlayerSessionState>()
    private var playerSequence = 1

    @Synchronized
    fun initialize() {
        for (table in tableRepository.listActiveTables("public_table", config.variant.id)) {
            val claimed = claimLease(table.id)
            val managed = createManagedTable(claimed ?: table, claimed != null)
            restoreNextRoundTask(managed)
        }
        if (matchmakingCoordinator != null) {
            scheduleMatchmakingResolution()
        }
    }

    @Synchronized
    fun joinPublicTable(playerName: String?, clientSeed: String?): Map<String, Any?> {
        return joinPublicTable(playerName, clientSeed, null)
    }

    @Synchronized
    fun joinPlatformPublicTable(platformSession: PlatformSession, clientSeed: String?, ip: String?): Map<String, Any?> {
        val name = platformSession.user.username.ifBlank { platformSession.userId }
        return joinPublicTable(name, clientSeed, PlatformJoinContext(platformSession, ip))
    }

    private fun joinPublicTable(playerName: String?, clientSeed: String?, platformContext: PlatformJoinContext?): Map<String, Any?> {
        val now = clockProvider.nowIso()
        val rawToken = idGenerator.newId()
        if (matchmakingCoordinator != null) {
            return enqueueForMatchmaking(playerName, clientSeed, platformContext, now, rawToken)
        }
        var table = findTableForJoin()
        if (table == null) {
            table = createTable()
        }
        val session = PublicPlayerSessionState()
        session.id = idGenerator.newId()
        session.variantId = config.variant.id
        session.tableId = table.tableId
        session.tokenHash = TokenSupport.hashToken(rawToken)
        session.clientSeed = TokenSupport.requireClientSeed(clientSeed)
        session.displayName = normalizePlayerName(playerName)
        if (platformContext != null) {
            session.platformUserId = platformContext.session.userId
            session.platformToken = platformContext.session.token
            session.platformGameId = platformContext.session.gameId
            session.platformOperatorId = platformContext.session.user.operatorId
            session.platformUsername = platformContext.session.user.username
            session.platformCurrency = platformContext.session.user.currency
            session.platformBalanceSnapshot = platformContext.session.user.balance
            session.platformTokenIssuedAt = platformContext.session.issuedAt
            session.lastKnownIp = platformContext.ip
        }
        session.status =
            if (table.service.state.round == null || table.service.state.round?.status == "complete") {
                "active_at_table"
            } else {
                "waiting_for_next_round"
            }
        session.connected = true
        session.joinedAt = now
        session.lastSeenAt = now
        session.createdAt = now
        session.updatedAt = now
        val seating = seating(table)
        if (session.status == "active_at_table") {
            seating.seatedPlayerIds.add(session.id)
        } else {
            seating.waitingPlayerIds.add(session.id)
        }
        val savedSession = saveSession(session)
        table.service.state.expiresAt = null
        table.service.persistSnapshot()
        if (savedSession.status == "active_at_table") {
            scheduleInitialJoinWait(table)
        } else {
            notifyTableUpdated(table, "player_waiting")
        }
        return serializeForPlayer(savedSession.id, rawToken)
    }

    private fun enqueueForMatchmaking(
        playerName: String?,
        clientSeed: String?,
        platformContext: PlatformJoinContext?,
        now: String,
        rawToken: String,
    ): Map<String, Any?> {
        val session = PublicPlayerSessionState()
        session.id = idGenerator.newId()
        session.variantId = config.variant.id
        session.tableId = null
        session.tokenHash = TokenSupport.hashToken(rawToken)
        session.clientSeed = TokenSupport.requireClientSeed(clientSeed)
        session.displayName = normalizePlayerName(playerName)
        if (platformContext != null) {
            session.platformUserId = platformContext.session.userId
            session.platformToken = platformContext.session.token
            session.platformGameId = platformContext.session.gameId
            session.platformOperatorId = platformContext.session.user.operatorId
            session.platformUsername = platformContext.session.user.username
            session.platformCurrency = platformContext.session.user.currency
            session.platformBalanceSnapshot = platformContext.session.user.balance
            session.platformTokenIssuedAt = platformContext.session.issuedAt
            session.lastKnownIp = platformContext.ip
        }
        session.status = "matchmaking"
        session.connected = true
        session.joinedAt = now
        session.lastSeenAt = now
        session.createdAt = now
        session.updatedAt = now
        val saved = saveSession(session)
        try {
            matchmakingCoordinator!!.enqueue(config.variant.id, saved.id, clockProvider.now().toEpochMilli())
        } catch (error: Exception) {
            saved.connected = false
            saved.status = "left"
            saved.leftAt = clockProvider.nowIso()
            saveSession(saved)
            GameEventLog.error("matchmaking_enqueue_failed", error, "variantId" to config.variant.id, "playerId" to saved.id)
            throw error
        }
        GameEventLog.info("matchmaking_joined", "variantId" to config.variant.id, "playerId" to saved.id)
        return serializeForPlayer(saved.id, rawToken)
    }

    @Synchronized
    fun getSession(playerId: String, playerToken: String): Map<String, Any?> {
        val session = validatePlayerSession(playerId, playerToken)
        val updated = markConnected(session)
        return serializeForPlayer(updated.id, playerToken)
    }

    @Synchronized
    fun getSessionSnapshot(playerId: String, playerToken: String): Map<String, Any?> {
        val session = validatePlayerSession(playerId, playerToken)
        val updated = markConnected(session)
        return serializeForPlayer(updated.id, playerToken)
    }

    @Synchronized
    fun connect(playerId: String, playerToken: String): Map<String, Any?> {
        val session = validatePlayerSession(playerId, playerToken)
        val updated = markConnected(session)
        if (updated.tableId != null) {
            notifyTableUpdated(requireTable(updated.tableId!!, true), "player_reconnected")
        }
        return serializeForPlayer(updated.id, playerToken)
    }

    @Synchronized
    fun performAction(playerId: String, playerToken: String, actionType: String, payload: Map<String, Any?>): Map<String, Any?> {
        var session = validatePlayerSession(playerId, playerToken)
        val sessionId = session.id
        val table = requireTable(session.tableId ?: throw IllegalStateException("Assigned table no longer exists."), true)
        if (actionType == "ready_next_round") {
            if (table.service.state.round == null || table.service.state.round?.status != "complete") {
                throw IllegalStateException("The next round can only be confirmed after the current round completes.")
            }
            if (session.status == "left") {
                throw IllegalStateException("Player already left the table.")
            }
            session = markConnected(session)
            session.nextRoundReady = true
            saveSession(session)
            notifyTableUpdated(table, "next_round_ready")
            return serializeForPlayer(session.id, playerToken)
        }
        session = reconcileSessionWithCurrentRoundSeat(table, session)
        if (session.status != "active_at_table") {
            throw IllegalStateException("Player is waiting for the next round.")
        }
        if (table.service.state.round == null || table.service.state.round!!.seats.none { it.id == sessionId }) {
            throw IllegalStateException("Player is not seated at this table.")
        }
        markConnected(session)
        debitActionIfNeeded(table, session, actionType, payload)
        table.service.performAction(sessionId, actionType, payload)
        creditWinnerIfNeeded(table)
        return serializeForPlayer(sessionId, playerToken)
    }

    @Synchronized
    fun leave(playerId: String, playerToken: String): Map<String, Any?> {
        var session = validatePlayerSession(playerId, playerToken)
        val sessionId = session.id
        val table = session.tableId?.let { requireTable(it, true) }
        if (table == null && session.status == "matchmaking") {
            matchmakingCoordinator?.remove(config.variant.id, session.id)
            session.connected = false
            session.status = "left"
            session.leftAt = clockProvider.nowIso()
            session.lastSeenAt = session.leftAt
            session.expiresAt = clockProvider.isoFromMillis(clockProvider.now().toEpochMilli() + PUBLIC_SESSION_TTL_MS)
            session = saveSession(session)
            return serializeForPlayer(sessionId, playerToken)
        }
        if (table != null) {
            val seating = seating(table)
            seating.waitingPlayerIds.remove(sessionId)
            val round = table.service.state.round
            val inActiveRound = round != null && round.status == "active" && round.seats.any { it.id == sessionId && !it.packed }
            if (!inActiveRound) {
                seating.seatedPlayerIds.remove(sessionId)
            }
            session.connected = false
            session.status = "left"
            session.nextRoundReady = false
            session.lastSeenAt = clockProvider.nowIso()
            session.leftAt = session.lastSeenAt
            session.expiresAt = clockProvider.isoFromMillis(clockProvider.now().toEpochMilli() + PUBLIC_SESSION_TTL_MS)
            session = saveSession(session)
            updateSeatConnectivity(table, sessionId, false, false)
            if (round != null && round.activePlayerIndex in round.seats.indices) {
                val activeSeat = round.seats[round.activePlayerIndex]
                if (activeSeat.id == sessionId && round.status == "active" && !activeSeat.packed) {
                    table.service.performAction(sessionId, "pack", emptyMap())
                } else {
                    table.service.persistSnapshot()
                }
            } else {
                table.service.persistSnapshot()
            }
            notifyTableUpdated(table, "player_left")
            cleanupTableIfEmpty(table)
        }
        return serializeForPlayer(sessionId, playerToken)
    }

    @Synchronized
    fun disconnect(playerId: String, playerToken: String) {
        val session = validatePlayerSession(playerId, playerToken)
        disconnectPlayer(session.id)
    }

    @Synchronized
    fun disconnectPlayer(playerId: String?) {
        val session = playerId?.let { loadSession(it) }
        if (session == null || session.status == "left") {
            return
        }
        session.connected = false
        session.lastSeenAt = clockProvider.nowIso()
        if (session.status == "active_at_table") {
            session.status = "disconnected"
        }
        saveSession(session)
        if (session.tableId == null) {
            matchmakingCoordinator?.remove(config.variant.id, session.id)
        }
        if (session.tableId != null) {
            val table = requireTable(session.tableId!!, true)
            updateSeatConnectivity(table, session.id, false, true)
            notifyTableUpdated(table, "player_disconnected")
        }
    }

    @Synchronized
    fun ensureOwnership(tableId: String): Boolean =
        try {
            loadManagedTable(tableId, true) != null
        } catch (_: Exception) {
            false
        }

    private fun createTable(): ManagedPublicTable {
        while (true) {
            val tableId = idGenerator.newId()
            if (tableRepository.loadTable(tableId) != null) {
                continue
            }
            val state = TableState()
            state.id = tableId
            state.tableType = "public_table"
            state.variantId = config.variant.id
            state.status = "idle"
            state.config = config
            state.publicSeating = PublicSeatingState()
            state.createdAt = clockProvider.nowIso()
            state.updatedAt = state.createdAt
            val service =
                RoundTableService(
                    configForTable(tableId),
                    tableRepository,
                    roundHistoryRepository,
                    clockProvider,
                    idGenerator,
                    randomSource,
                    scheduler,
                    instanceId,
                    "public_table",
                    state,
                )
            try {
                service.persistSnapshot()
            } catch (error: ConcurrentModificationException) {
                if (tableRepository.loadTable(tableId) != null) {
                    continue
                }
                throw error
            }
            val managed = ManagedPublicTable(tableId, service, true)
            registerManagedTableListener(managed)
            tables[tableId] = managed
            return managed
        }
    }

    private fun scheduleMatchmakingResolution() {
        scheduler.schedule(matchmakingWindowMs) {
            synchronized(this) {
                try {
                    matchmakingCoordinator?.resolveReadyBatch(
                        config.variant.id,
                        clockProvider.now().toEpochMilli(),
                        matchmakingWindowMs,
                        ::resolveMatchmakingBatch,
                    )
                } catch (error: Exception) {
                    GameEventLog.error("matchmaking_resolution_failed", error, "variantId" to config.variant.id)
                } finally {
                    scheduleMatchmakingResolution()
                }
            }
        }
    }

    private fun resolveMatchmakingBatch(playerIds: List<String>): List<String> {
        val eligible =
            playerIds.mapNotNull(::loadSession)
                .filter { it.status == "matchmaking" && it.tableId == null && isLiveMatchmakingPlayer(it) }
                .toMutableList()
        if (eligible.isEmpty()) {
            return emptyList()
        }
        shuffleSessions(eligible)
        val distinctPlayerCount = eligible.map(::matchmakingIdentity).distinct().size
        if (distinctPlayerCount < matchmakingPvpThreshold) {
            GameEventLog.info(
                "matchmaking_bot_batch",
                "variantId" to config.variant.id,
                "playerCount" to eligible.size,
                "distinctPlayerCount" to distinctPlayerCount,
            )
            eligible.forEach { session -> tryAssignMatchmakingTable(listOf(session), true) }
            return emptyList()
        }
        val groupSize = config.playerCount
        val groups = buildDistinctMatchmakingGroups(eligible, groupSize)
        val assignableCount = groups.sumOf { it.size }
        GameEventLog.info(
            "matchmaking_pvp_batch",
            "variantId" to config.variant.id,
            "playerCount" to eligible.size,
            "distinctPlayerCount" to distinctPlayerCount,
            "assignedCount" to assignableCount,
            "queuedRemainder" to eligible.size - assignableCount,
        )
        groups.forEach { group ->
            tryAssignMatchmakingTable(group, group.size == 1)
        }
        val assignedIds = groups.flatten().map { it.id }.toSet()
        return eligible.filter { !assignedIds.contains(it.id) }.map { it.id }
    }

    private fun shuffleSessions(sessions: MutableList<PublicPlayerSessionState>) {
        for (index in sessions.lastIndex downTo 1) {
            val other = randomSource.nextInt(index + 1)
            val current = sessions[index]
            sessions[index] = sessions[other]
            sessions[other] = current
        }
    }

    private fun buildDistinctMatchmakingGroups(
        sessions: List<PublicPlayerSessionState>,
        groupSize: Int,
    ): List<List<PublicPlayerSessionState>> {
        val remaining = sessions.toMutableList()
        val groups = mutableListOf<List<PublicPlayerSessionState>>()

        while (remaining.isNotEmpty()) {
            val group = mutableListOf<PublicPlayerSessionState>()
            val identities = linkedSetOf<String>()
            val selected = mutableListOf<PublicPlayerSessionState>()

            for (session in remaining) {
                val identity = matchmakingIdentity(session)
                if (identities.add(identity)) {
                    group.add(session)
                    selected.add(session)
                    if (group.size == groupSize) {
                        break
                    }
                }
            }

            if (group.isEmpty()) {
                break
            }

            remaining.removeAll(selected.toSet())
            groups.add(group)
        }

        return groups
    }

    private fun matchmakingIdentity(session: PublicPlayerSessionState): String =
        session.platformUserId?.trim()?.takeIf { it.isNotBlank() }
            ?.lowercase()
            ?: session.id

    private fun tryAssignMatchmakingTable(sessions: List<PublicPlayerSessionState>, botFallback: Boolean) {
        try {
            assignMatchmakingTable(sessions, botFallback)
        } catch (error: Exception) {
            sessions.forEach { session -> failMatchmakingSession(session, error) }
        }
    }

    private fun failMatchmakingSession(session: PublicPlayerSessionState, error: Exception) {
        GameEventLog.error(
            "matchmaking_assignment_failed",
            error,
            "variantId" to config.variant.id,
            "playerId" to session.id,
        )
        matchmakingCoordinator?.remove(config.variant.id, session.id)
        val current = loadSession(session.id) ?: return
        current.connected = false
        current.status = "left"
        current.tableId = null
        current.lastSeenAt = clockProvider.nowIso()
        current.leftAt = current.lastSeenAt
        current.expiresAt = clockProvider.isoFromMillis(clockProvider.now().toEpochMilli() + PUBLIC_SESSION_TTL_MS)
        saveSession(current)
    }

    private fun assignMatchmakingTable(sessions: List<PublicPlayerSessionState>, botFallback: Boolean) {
        val table = createTable()
        val seating = seating(table)
        seating.seatedPlayerIds = sessions.map { it.id }.toMutableList()
        val participants = buildParticipants(table, seating.seatedPlayerIds, if (botFallback) null else 0)
        val roundId = idGenerator.newId()
        try {
            debitBootsIfNeeded(table, participants, roundId)
        } catch (error: Exception) {
            discardUnstartedTable(table)
            throw error
        }
        sessions.forEach { session ->
            session.tableId = table.tableId
            activatePublicSession(session.id, table.tableId)
        }
        table.service.startRound(participants, roundId)
        val botCount = participants.count { it.isBot }
        GameEventLog.info(
            "matchmaking_assigned",
            "variantId" to config.variant.id,
            "lobbyId" to table.tableId,
            "roundId" to roundId,
            "humanPlayers" to sessions.size,
            "botPlayers" to botCount,
        )
        notifyTableUpdated(table, "matchmaking_assigned")
    }

    private fun createManagedTable(state: TableState, leaseOwned: Boolean): ManagedPublicTable {
        val service =
            RoundTableService(
                configForTable(state.id),
                tableRepository,
                roundHistoryRepository,
                clockProvider,
                idGenerator,
                randomSource,
                scheduler,
                instanceId,
                "public_table",
                state,
            )
        val table = ManagedPublicTable(state.id, service, leaseOwned)
        if (service.state.publicSeating == null) {
            service.state.publicSeating = PublicSeatingState()
        }
        if (leaseOwned) {
            registerManagedTableListener(table)
        }
        tables[state.id] = table
        return table
    }

    private fun registerManagedTableListener(table: ManagedPublicTable) {
        table.registration =
            table.service.registerListener { event ->
                scheduler.schedule(0L) {
                    synchronized(this) {
                        handleTableEvent(table.tableId, event.type)
                    }
                }
            }
    }

    private fun findTableForJoin(): ManagedPublicTable? {
        var fallbackTable: ManagedPublicTable? = null

        for (table in tables.values) {
            if (!table.leaseOwned) {
                continue
            }
            var waitingEligible = 0
            for (playerId in seating(table).waitingPlayerIds) {
                if (isEligibleForFutureRound(playerId)) {
                    waitingEligible++
                }
            }
            if (activePlayerCount(table) + waitingEligible >= config.playerCount) {
                continue
            }

            if (table.service.state.round == null || table.service.state.round?.status == "complete") {
                return table
            }

            if (fallbackTable == null) {
                fallbackTable = table
            }
        }

        return fallbackTable
    }

    private fun activePlayerCount(table: ManagedPublicTable): Int {
        var count = 0
        for (playerId in seating(table).seatedPlayerIds) {
            if (isLivePublicPlayer(loadSession(playerId))) {
                count++
            }
        }
        return count
    }

    private fun isEligibleForFutureRound(playerId: String): Boolean = isLivePublicPlayer(loadSession(playerId))

    private fun handleTableEvent(tableId: String, eventType: String) {
        val table = tables[tableId] ?: return
        if (eventType == "round_complete") {
            for (playerId in seating(table).seatedPlayerIds) {
                val session = loadSession(playerId)
                if (session != null) {
                    session.nextRoundReady = false
                    saveSession(session)
                }
            }
            scheduleNextRoundTask(table)
        }
        notifyTableUpdated(table, eventType)
    }

    private fun maybeStartNextRound(table: ManagedPublicTable) {
        var round = table.service.state.round
        if (round != null && round.status != "complete") {
            return
        }
        if (round != null && round.dealerTipState?.pending == true) {
            val winnerSession = loadSession(round.dealerTipState!!.winnerId)
            if (winnerSession == null || !winnerSession.connected || winnerSession.status == "left") {
                table.service.performAction(round.dealerTipState!!.winnerId, "dealer_tip", mapOf("amount" to 0))
                round = table.service.state.round
            }
            if (round != null && round.dealerTipState?.pending == true) {
                table.nextRoundTask =
                    scheduler.schedule(1_000L) {
                        synchronized(this) {
                            table.nextRoundTask = null
                            maybeStartNextRound(table)
                        }
                    }
                return
            }
        }
        table.nextRoundTask?.cancel()
        table.nextRoundTask = null
        val seating = seating(table)
        val retained = seating.seatedPlayerIds.filter { shouldRetainForNextRound(round, loadSession(it)) }
        val waitingEligible = seating.waitingPlayerIds.filter { isLivePublicPlayer(loadSession(it)) }
        val removedSeated = seating.seatedPlayerIds.filter { !retained.contains(it) }
        removedSeated.forEach { removeFromNextRound(table, it) }
        val availableSlots = maxOf(0, config.playerCount - retained.size)
        val promoted = waitingEligible.take(availableSlots)
        val remainingWaiting = waitingEligible.drop(promoted.size).toMutableList()
        val nextSeatedPlayerIds = retained + promoted
        if (nextSeatedPlayerIds.isEmpty()) {
            cleanupTableIfEmpty(table)
            return
        }
        val participants = buildParticipants(table, nextSeatedPlayerIds)
        val roundId = idGenerator.newId()
        debitBootsIfNeeded(table, participants, roundId)
        retained.forEach { activatePublicSession(it, table.tableId) }
        promoted.forEach { activatePublicSession(it, table.tableId) }
        remainingWaiting.forEach { playerId ->
            val session = loadSession(playerId)
            if (session != null) {
                session.status = "waiting_for_next_round"
                session.nextRoundReady = false
                session.tableId = table.tableId
                saveSession(session)
            }
        }
        seating.seatedPlayerIds = mutableListOf<String>().apply {
            addAll(nextSeatedPlayerIds)
        }
        seating.waitingPlayerIds = remainingWaiting
        seating.lastPromotionMessage =
            if (promoted.isEmpty()) {
                null
            } else if (promoted.size == 1) {
                "${loadSession(promoted.first())!!.displayName} joins this round."
            } else {
                "${promoted.size} waiting players joined this round."
            }
        seating.joinWaitStartedAt = null
        seating.joinWaitEndsAt = null
        table.service.state.expiresAt = null
        table.service.startRound(participants, roundId)
    }

    private fun shouldRetainForNextRound(round: RoundState?, session: PublicPlayerSessionState?): Boolean {
        if (!isLivePublicPlayer(session)) {
            return false
        }
        if (round?.status != "complete") {
            return true
        }
        return session?.nextRoundReady == true
    }

    private fun removeFromNextRound(table: ManagedPublicTable, playerId: String) {
        val session = loadSession(playerId) ?: return
        if (session.status == "left") {
            return
        }
        session.connected = false
        session.status = "left"
        session.nextRoundReady = false
        session.lastSeenAt = clockProvider.nowIso()
        session.leftAt = session.lastSeenAt
        session.expiresAt = clockProvider.isoFromMillis(clockProvider.now().toEpochMilli() + PUBLIC_SESSION_TTL_MS)
        session.tableId = table.tableId
        saveSession(session)
    }

    private fun scheduleInitialJoinWait(table: ManagedPublicTable) {
        val round = table.service.state.round
        if (round != null && round.status != "complete") {
            return
        }
        if (round?.status == "complete") {
            maybeStartNextRound(table)
            return
        }
        if (table.nextRoundTask != null) {
            notifyTableUpdated(table, "player_joined_lobby")
            return
        }
        val seating = seating(table)
        seating.joinWaitStartedAt = clockProvider.nowIso()
        seating.joinWaitEndsAt = clockProvider.isoFromMillis(clockProvider.now().toEpochMilli() + PUBLIC_TABLE_JOIN_WAIT_MS)
        table.service.persistSnapshot()
        notifyTableUpdated(table, "player_joined_lobby")
        table.nextRoundTask =
            scheduler.schedule(PUBLIC_TABLE_JOIN_WAIT_MS) {
                synchronized(this) {
                    table.nextRoundTask = null
                    maybeStartNextRound(table)
                }
            }
    }

    private fun debitBootsIfNeeded(table: ManagedPublicTable, participants: List<RoundParticipant>, roundId: String) {
        val debited = mutableListOf<PublicPlayerSessionState>()
        try {
            for (participant in participants) {
                if (participant.isBot) {
                    continue
                }
                val session = loadSession(participant.id) ?: continue
                platformWalletService?.debit(
                    platformRef(session),
                    roundId,
                    "tp:$roundId:${session.id}:boot",
                    config.bootAmount,
                    TeenPattiWalletStatement.description(config.bootAmount, "debited", "boot", roundId, table.tableId),
                )
                debited.add(session)
            }
        } catch (error: Exception) {
            debited.forEach { session ->
                try {
                    platformWalletService?.credit(
                        platformRef(session),
                        roundId,
                        "tp:$roundId:${session.id}:boot-refund",
                        config.bootAmount,
                        TeenPattiWalletStatement.description(config.bootAmount, "credited", "boot refund", roundId, table.tableId),
                    )
                } catch (refundError: Exception) {
                    GameEventLog.error(
                        "wallet_boot_refund_failed",
                        refundError,
                        "playerId" to session.id,
                        "roundId" to roundId,
                        "lobbyId" to table.tableId,
                        "amount" to config.bootAmount,
                    )
                }
            }
            throw error
        }
    }

    private fun discardUnstartedTable(table: ManagedPublicTable) {
        table.registration?.let {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        table.service.state.status = "idle"
        table.service.state.expiresAt = clockProvider.nowIso()
        table.service.persistSnapshot()
        table.service.shutdown()
        tables.remove(table.tableId)
        GameEventLog.info("matchmaking_table_discarded", "variantId" to config.variant.id, "lobbyId" to table.tableId)
    }

    private fun debitActionIfNeeded(
        table: ManagedPublicTable,
        session: PublicPlayerSessionState,
        actionType: String,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        val amount = table.service.quoteDebitForAction(session.id, actionType, payload)
        if (amount <= 0) {
            return
        }
        val roundId = table.service.currentRoundId() ?: throw IllegalStateException("No active round is available.")
        val actionIndex = table.service.currentActionLogSize()
        val operationKey =
            if (actionType == "dealer_tip") {
                "tp:$roundId:${session.id}:dealer_tip:$amount:${clockProvider.now().toEpochMilli()}"
            } else {
                "tp:$roundId:${session.id}:$actionIndex:$actionType"
            }
        platformWalletService?.debit(
            platformRef(session),
            roundId,
            operationKey,
            amount,
            TeenPattiWalletStatement.description(amount, "debited", actionType, roundId, table.tableId),
        )
    }

    private fun creditWinnerIfNeeded(table: ManagedPublicTable) {
        val round = table.service.state.round ?: return
        val result = round.result ?: return
        if (round.settledAt == null || result.payout <= 0) {
            return
        }
        val winnerSession = loadSession(result.winnerId) ?: return
        platformWalletService?.credit(
            platformRef(winnerSession),
            round.id,
            "tp:${round.id}:${winnerSession.id}:payout",
            result.payout,
            TeenPattiWalletStatement.description(result.payout, "credited", "payout", round.id, table.tableId),
        )
    }

    private fun platformRef(session: PublicPlayerSessionState): PlatformPlayerRef =
        PlatformPlayerRef(
            session.id,
            session.platformUserId,
            session.platformToken,
            session.platformGameId,
            session.platformOperatorId,
            session.lastKnownIp,
            false,
        )

    private fun activatePublicSession(playerId: String, tableId: String) {
        val session = loadSession(playerId) ?: return
        session.status = "active_at_table"
        session.nextRoundReady = false
        session.connected = true
        session.tableId = tableId
        session.leftAt = null
        session.expiresAt = null
        saveSession(session)
    }

    private fun buildParticipants(table: ManagedPublicTable): MutableList<RoundParticipant> {
        return buildParticipants(table, seating(table).seatedPlayerIds)
    }

    private fun buildParticipants(table: ManagedPublicTable, playerIds: List<String>): MutableList<RoundParticipant> {
        return buildParticipants(table, playerIds, null)
    }

    private fun buildParticipants(
        table: ManagedPublicTable,
        playerIds: List<String>,
        forcedBotCount: Int?,
    ): MutableList<RoundParticipant> {
        val participants = mutableListOf<RoundParticipant>()
        for (playerId in playerIds) {
            val session = loadSession(playerId) ?: continue
            val participant = RoundParticipant()
            participant.id = session.id
            participant.name = session.displayName
            participant.avatar = "player"
            participant.isBot = false
            participant.connected = session.connected
            participant.clientSeed = session.clientSeed
            participants.add(participant)
        }
        val botCount = forcedBotCount ?: targetBotCount(participants.size)
        val botSlots = activeBotSlots(table, botCount)
        refreshBotSlotNames(botSlots)
        for (botSlot in botSlots) {
            val participant = RoundParticipant()
            participant.id = botSlot.id
            participant.name = botSlot.name
            participant.avatar = botSlot.avatar
            participant.isBot = true
            participant.connected = true
            participant.clientSeed = null
            participants.add(participant)
        }
        return participants
    }

    private fun refreshBotSlotNames(botSlots: List<PublicBotSlot>) {
        val used = mutableListOf<String>()
        for (slot in botSlots) {
            slot.name = BotUsernames.resolve(slot.name, randomSource, used)
            used.add(slot.name)
        }
    }

    private fun targetBotCount(realPlayerCount: Int): Int =
        when {
            realPlayerCount <= 0 -> 0
            realPlayerCount >= 2 -> 0
            else -> minOf(config.publicTableMaxBots, config.playerCount - realPlayerCount)
        }

    private fun activeBotSlots(table: ManagedPublicTable, botCount: Int): MutableList<PublicBotSlot> {
        val seating = seating(table)
        while (seating.botSlots.size < botCount) {
            seating.botSlots.add(createBotSlot(table, seating))
        }
        return seating.botSlots.take(botCount).toMutableList()
    }

    private fun createBotSlot(table: ManagedPublicTable, seating: PublicSeatingState): PublicBotSlot {
        val botNumber = seating.botSequence++
        val usedNames = seating.botSlots.map { it.name }
        val slot = PublicBotSlot()
        slot.id = "${table.tableId}-bot-$botNumber"
        slot.name = BotUsernames.resolve(null, randomSource, usedNames)
        slot.avatar = listOf("raj", "captain", "maya", "ace")[randomSource.nextInt(4)]
        return slot
    }

    private fun markConnected(session: PublicPlayerSessionState): PublicPlayerSessionState {
        session.connected = true
        session.lastSeenAt = clockProvider.nowIso()
        session.leftAt = null
        session.expiresAt = null
        if (session.status == "disconnected") {
            session.status = "active_at_table"
        }
        val updated = saveSession(session)
        if (updated.status == "matchmaking" && updated.tableId == null) {
            matchmakingCoordinator?.enqueue(config.variant.id, updated.id, clockProvider.now().toEpochMilli())
        }
        if (updated.tableId != null) {
            val table = tables[updated.tableId]
            if (table != null && table.leaseOwned) {
                updateSeatConnectivity(table, updated.id, true, true)
            }
        }
        return updated
    }

    private fun updateSeatConnectivity(table: ManagedPublicTable, playerId: String, connected: Boolean, persist: Boolean) {
        val round = table.service.state.round ?: return
        for (seat in round.seats) {
            if (seat.id == playerId) {
                seat.connected = connected
                break
            }
        }
        if (persist) {
            table.service.persistSnapshot()
        }
    }

    private fun notifyTableUpdated(table: ManagedPublicTable, eventType: String) {
        GameEventLog.info(
            eventType,
            "variantId" to config.variant.id,
            "lobbyId" to table.tableId,
            "roundId" to table.service.currentRoundId(),
        )
        realtimeGateway.tableUpdated(config.variant.id, table.tableId, eventType)
    }

    private fun serializeForPlayer(playerId: String, rawToken: String): Map<String, Any?> {
        var session = loadSession(playerId) ?: throw IllegalStateException("Unknown player.")
        val table =
            session.tableId?.let {
                if (tables.containsKey(it)) {
                    tables[it]
                } else {
                    loadManagedTable(it, false)
                }
            }
        if (table != null) {
            session = reconcileSessionWithCurrentRoundSeat(table, session)
        }
        val response = linkedMapOf<String, Any?>()
        response["playerId"] = session.id
        response["playerToken"] = rawToken
        response["playerName"] = session.displayName
        response["tableId"] = session.tableId
        response["playerStatus"] = session.status
        response["nextRoundReady"] = session.nextRoundReady
        response["connected"] = session.connected
        response["joinedAt"] = session.joinedAt
        response["lastSeenAt"] = session.lastSeenAt
        response["table"] = table?.let { serializeTableForPlayer(it, session) }
        return response
    }

    private fun reconcileSessionWithCurrentRoundSeat(
        table: ManagedPublicTable,
        session: PublicPlayerSessionState,
    ): PublicPlayerSessionState {
        val round = table.service.state.round ?: return session
        if (round.status == "complete" || round.seats.none { it.id == session.id }) {
            return session
        }
        if (session.status == "active_at_table") {
            return session
        }
        if (session.status == "left") {
            return session
        }
        val seating = seating(table)
        seating.waitingPlayerIds.remove(session.id)
        if (!seating.seatedPlayerIds.contains(session.id)) {
            seating.seatedPlayerIds.add(session.id)
        }
        session.status = "active_at_table"
        session.nextRoundReady = false
        session.tableId = table.tableId
        session.leftAt = null
        session.expiresAt = null
        table.service.persistSnapshot()
        return saveSession(session)
    }

    private fun serializeTableForPlayer(table: ManagedPublicTable, session: PublicPlayerSessionState): Map<String, Any?> {
        val base = LinkedHashMap(table.service.getTableState(session.id))
        base["viewerPlayerId"] = session.id
        base["viewerPlayerStatus"] = session.status
        base["viewerNextRoundReady"] = session.nextRoundReady
        base["viewerPlatformBalance"] = session.platformBalanceSnapshot
        base["publicJoinWaitStartedAt"] = seating(table).joinWaitStartedAt
        base["publicJoinWaitEndsAt"] = seating(table).joinWaitEndsAt
        base["waitingPlayers"] =
            seating(table).waitingPlayerIds.mapNotNull { playerId ->
                val waiting = loadSession(playerId) ?: return@mapNotNull null
                mapOf("playerId" to waiting.id, "name" to waiting.displayName, "status" to waiting.status)
            }
        base["admissionMessage"] = seating(table).lastPromotionMessage
        return base
    }

    private fun requireTable(tableId: String, requireLease: Boolean): ManagedPublicTable =
        loadManagedTable(tableId, requireLease) ?: throw IllegalStateException("Assigned table no longer exists.")

    private fun loadManagedTable(tableId: String, requireLease: Boolean): ManagedPublicTable? {
        val cached = tables[tableId]
        if (cached != null && (!requireLease || cached.leaseOwned)) {
            return cached
        }
        val claimed = if (requireLease) claimLease(tableId) else null
        val leaseOwned = claimed != null
        val state = claimed ?: tableRepository.loadTable(tableId)
        if (state == null) {
            return null
        }
        if (requireLease && !leaseOwned) {
            throw IllegalStateException("Public table is currently handled by another server node.")
        }
        val managed = createManagedTable(state, leaseOwned)
        restoreNextRoundTask(managed)
        return managed
    }

    private fun restoreNextRoundTask(table: ManagedPublicTable) {
        val round = table.service.state.round
        if (!table.leaseOwned || round == null || round.status != "complete" || round.nextRoundDecisionExpiresAt == null) {
            return
        }
        scheduleNextRoundTask(table)
    }

    private fun scheduleNextRoundTask(table: ManagedPublicTable) {
        val round = table.service.state.round
        if (round == null || round.status != "complete" || round.nextRoundDecisionExpiresAt == null) {
            return
        }
        table.nextRoundTask?.cancel()
        val remaining = maxOf(0L, Instant.parse(round.nextRoundDecisionExpiresAt).toEpochMilli() - clockProvider.now().toEpochMilli())
        table.nextRoundTask =
            scheduler.schedule(remaining) {
                synchronized(this) {
                    table.nextRoundTask = null
                    maybeStartNextRound(table)
                }
            }
    }

    private fun cleanupTableIfEmpty(table: ManagedPublicTable) {
        val activePlayers = mutableListOf<PublicPlayerSessionState>()
        for (playerId in seating(table).seatedPlayerIds.toList()) {
            val session = loadSession(playerId)
            if (isLivePublicPlayer(session)) {
                activePlayers.add(session!!)
            }
        }
        for (playerId in seating(table).waitingPlayerIds.toList()) {
            val session = loadSession(playerId)
            if (isLivePublicPlayer(session)) {
                activePlayers.add(session!!)
            }
        }
        if (activePlayers.isNotEmpty()) {
            return
        }
        table.nextRoundTask?.cancel()
        table.registration?.let {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        table.service.state.status = "idle"
        table.service.state.expiresAt = clockProvider.isoFromMillis(clockProvider.now().toEpochMilli() + PUBLIC_TABLE_TTL_MS)
        table.service.persistSnapshot()
        table.service.shutdown()
        tables.remove(table.tableId)
    }

    private fun validatePlayerSession(playerId: String?, playerToken: String?): PublicPlayerSessionState {
        if (playerId.isNullOrBlank()) {
            throw IllegalStateException("Player id is required.")
        }
        if (playerToken.isNullOrBlank()) {
            throw IllegalStateException("Player token is required.")
        }
        val session = loadSession(playerId) ?: throw IllegalStateException("Unknown player.")
        if (TokenSupport.hashToken(playerToken) != session.tokenHash) {
            throw IllegalStateException("Invalid player token.")
        }
        return session
    }

    private fun isLivePublicPlayer(session: PublicPlayerSessionState?): Boolean {
        if (session == null || session.status == "left" || !session.connected) {
            return false
        }
        if (playerPresence.isConnected("public", session.id)) {
            return true
        }
        if (session.lastSeenAt == null) {
            return false
        }
        val lastSeenMs = Instant.parse(session.lastSeenAt).toEpochMilli()
        return lastSeenMs + reconnectGraceMs > clockProvider.now().toEpochMilli()
    }

    private fun isLiveMatchmakingPlayer(session: PublicPlayerSessionState?): Boolean {
        if (session == null || session.status != "matchmaking" || !session.connected || session.lastSeenAt == null) {
            return false
        }
        val lastSeenMs = Instant.parse(session.lastSeenAt).toEpochMilli()
        return lastSeenMs + MATCHMAKING_HEARTBEAT_GRACE_MS > clockProvider.now().toEpochMilli()
    }

    private fun normalizePlayerName(playerName: String?): String {
        val normalized = playerName?.trim().orEmpty()
        if (normalized.isNotBlank()) {
            return normalized
        }
        playerSequence += 1
        return nextGuestName()
    }

    private fun nextGuestName(): String = "Guest_${100000 + randomSource.nextInt(900000)}"

    private fun loadSession(playerId: String): PublicPlayerSessionState? {
        if (players.containsKey(playerId)) {
            return players[playerId]
        }
        val loaded = publicSessionRepository.loadSession(playerId)
        if (loaded != null) {
            players[playerId] = loaded
        }
        return loaded
    }

    private fun saveSession(session: PublicPlayerSessionState): PublicPlayerSessionState {
        session.updatedAt = clockProvider.nowIso()
        val saved = publicSessionRepository.saveSession(session)
        players[saved.id] = saved
        return saved
    }

    private fun claimLease(tableId: String): TableState? =
        tableRepository.claimLease(
            tableId,
            instanceId,
            clockProvider.isoFromMillis(clockProvider.now().toEpochMilli() + PUBLIC_TABLE_LEASE_DURATION_MS),
            clockProvider.nowIso(),
        )

    private fun seating(table: ManagedPublicTable): PublicSeatingState {
        if (table.service.state.publicSeating == null) {
            table.service.state.publicSeating = PublicSeatingState()
        }
        val seating = table.service.state.publicSeating!!
        if (seating.botSlots.isEmpty()) {
            seating.botSlots = mutableListOf()
        }
        seedBotSlotsFromCurrentRound(table, seating)
        seating.botSequence = nextBotSequence(table, seating)
        return seating
    }

    private fun seedBotSlotsFromCurrentRound(table: ManagedPublicTable, seating: PublicSeatingState) {
        val round = table.service.state.round ?: return
        for (seat in round.seats) {
            if (!seat.isBot || findBotSlot(seating, seat.id) != null) {
                continue
            }
            val slot = PublicBotSlot()
            slot.id = seat.id
            slot.name = BotUsernames.resolve(seat.name, randomSource, seating.botSlots.map { it.name })
            slot.avatar = seat.avatar
            seating.botSlots.add(slot)
            if (BotUsernames.isPlaceholder(seat.name) || seat.name != slot.name) {
                seat.name = slot.name
            }
        }
        refreshBotSlotNames(seating.botSlots)
        for (seat in round.seats) {
            if (!seat.isBot) {
                continue
            }
            val slot = findBotSlot(seating, seat.id) ?: continue
            if (seat.name != slot.name) {
                seat.name = slot.name
            }
        }
    }

    private fun nextBotSequence(table: ManagedPublicTable, seating: PublicSeatingState): Int {
        var nextSequence = maxOf(1, seating.botSequence)
        val prefix = "${table.tableId}-bot-"
        for (slot in seating.botSlots) {
            if (slot.id.isBlank() || !slot.id.startsWith(prefix)) {
                continue
            }
            try {
                val sequence = slot.id.substring(prefix.length).toInt()
                nextSequence = maxOf(nextSequence, sequence + 1)
            } catch (_: NumberFormatException) {
            }
        }
        return nextSequence
    }

    private fun findBotSlot(seating: PublicSeatingState, botId: String?): PublicBotSlot? {
        if (botId == null) {
            return null
        }
        return seating.botSlots.firstOrNull { it.id == botId }
    }

    private fun configForTable(tableId: String): GameConfig {
        val next = GameConfig()
        next.tableId = tableId
        next.bootAmount = config.bootAmount
        next.maxPotAmount = config.maxPotAmount
        next.minStake = config.minStake
        next.maxStake = config.maxStake
        next.maxRoundsBeforeForcedShow = config.maxRoundsBeforeForcedShow
        next.playerCount = config.playerCount
        next.publicTableMaxBots = config.publicTableMaxBots
        next.casinoBootCommissionPercent = config.casinoBootCommissionPercent
        next.casinoWinCommissionPercent = config.casinoWinCommissionPercent
        next.maxBalance = config.maxBalance
        next.initialBalance = config.initialBalance
        next.turnDurationMs = config.turnDurationMs
        next.blindSeenMultiplier = config.blindSeenMultiplier
        next.blindRaiseMultiplier = config.blindRaiseMultiplier
        next.seenRaiseMultiplier = config.seenRaiseMultiplier
        next.allowA23Sequence = config.allowA23Sequence
        next.allowAkqSequence = config.allowAkqSequence
        next.sequenceRankingMode = config.sequenceRankingMode
        next.botDecisionMode = config.botDecisionMode
        next.botMaxSimulations = config.botMaxSimulations
        next.botMaxDecisionTimeMs = config.botMaxDecisionTimeMs
        next.botHeadsUpSeeAfterBlindTurns = config.botHeadsUpSeeAfterBlindTurns
        next.autoplay = config.autoplay
        next.botActionDelayMs = config.botActionDelayMs
        next.variant = config.variant
        return next
    }

    internal class ManagedPublicTable(
        val tableId: String,
        val service: RoundTableService,
        val leaseOwned: Boolean,
    ) {
        var registration: Closeable? = null
        var nextRoundTask: ScheduledTask? = null
    }

    private class PlatformJoinContext(
        val session: PlatformSession,
        val ip: String?,
    )

    private companion object {
        const val PUBLIC_TABLE_LEASE_DURATION_MS = 30_000L
        const val PUBLIC_TABLE_JOIN_WAIT_MS = 5_000L
        const val MATCHMAKING_HEARTBEAT_GRACE_MS = 4_000L
        const val PUBLIC_SESSION_TTL_MS = 604_800_000L
        const val PUBLIC_TABLE_TTL_MS = 604_800_000L
    }
}
