package org.teenpatti.server.game

import org.teenpatti.server.common.ClockProvider
import org.teenpatti.server.common.IdGenerator
import org.teenpatti.server.common.RandomSource
import org.teenpatti.server.common.ScheduledTask
import org.teenpatti.server.common.Scheduler
import org.teenpatti.server.common.TokenSupport
import org.teenpatti.server.common.GameEventLog
import org.teenpatti.server.config.GameConfig
import org.teenpatti.server.infrastructure.persistence.RoundHistoryRepository
import org.teenpatti.server.infrastructure.persistence.TableAggregateRepository
import org.teenpatti.server.publictable.BotDecisionContext
import org.teenpatti.server.publictable.PublicBotDecisionEngine
import org.teenpatti.server.publictable.PublicSeatingState
import java.io.Closeable
import java.time.Instant
import java.util.ConcurrentModificationException
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap

@Suppress("UNCHECKED_CAST")
internal class RoundTableService(
    private val config: GameConfig,
    private val store: TableAggregateRepository?,
    private val roundHistoryRepository: RoundHistoryRepository?,
    private val clockProvider: ClockProvider,
    private val idGenerator: IdGenerator,
    private val randomSource: RandomSource,
    private val scheduler: Scheduler,
    private val instanceId: String,
    private val tableType: String,
    existingState: TableState?,
) {
    private val publicBotDecisionEngine = PublicBotDecisionEngine(config, randomSource)
    private val listeners = ConcurrentHashMap<String, (TableEvent) -> Unit>()
    private val botTimers: MutableSet<ScheduledTask> = LinkedHashSet()
    private var turnTimer: ScheduledTask? = null
    private var dealerTipTimer: ScheduledTask? = null

    var state: TableState = existingState ?: createInitialState()

    init {
        state.config = config
        state.tableType = tableType
        if (state.variantId == null) {
            state.variantId = config.variant.id
        }
        restoreRuntime()
    }

    @Synchronized
    fun registerListener(listener: (TableEvent) -> Unit): Closeable {
        val id = idGenerator.newId()
        listeners[id] = listener
        return Closeable { listeners.remove(id) }
    }

    @Synchronized
    fun shutdown() {
        clearTimers()
    }

    @Synchronized
    fun persistSnapshot() {
        persistState()
    }

    @Synchronized
    fun getTableState(viewerId: String?): Map<String, Any?> = getClientState(viewerId)

    @Synchronized
    fun currentRoundId(): String? = state.round?.id

    @Synchronized
    fun currentActionLogSize(): Int = state.round?.actionLog?.size ?: 0

    @Synchronized
    fun quoteDebitForAction(playerId: String, type: String, payload: Map<String, Any?> = emptyMap()): Int {
        if (type == "dealer_tip") {
            val round = state.round ?: throw IllegalStateException("Dealer tip is not available right now.")
            val actorIndex = indexOfSeat(round, playerId)
            val actor = round.seats[actorIndex]
            val rawAmount = payload["amount"] ?: throw IllegalStateException("Dealer tip amount is required.")
            if (rawAmount !is Number) {
                throw IllegalStateException("Dealer tip amount is required.")
            }
            val amount = rawAmount.toInt()
            if (amount < 0) {
                throw IllegalStateException("Dealer tip cannot be negative.")
            }
            if (amount == 0) {
                return 0
            }
            if (actor.balance < amount) {
                throw IllegalStateException("Insufficient balance to tip dealer.")
            }
            if (round.status == "complete" &&
                round.dealerTipState?.pending == true &&
                playerId == round.dealerTipState!!.winnerId &&
                amount >= round.dealerTipState!!.winnerReceivableBeforeTip
            ) {
                throw IllegalStateException("Dealer tip must be less than the winning amount.")
            }
            return amount
        }
        val round = requireActiveRound()
        val actorIndex = indexOfSeat(round, playerId)
        val seat = round.seats[actorIndex]
        if (seat.packed) {
            throw IllegalStateException("Packed players cannot act.")
        }
        if (round.pendingSideShow != null) {
            val pending = round.pendingSideShow!!
            if (seat.id != pending.targetId) {
                throw IllegalStateException("Waiting for the side show response.")
            }
            return 0
        }
        if (actorIndex != round.activePlayerIndex) {
            throw IllegalStateException("It is not this player's turn.")
        }
        return when (type) {
            "see", "pack" -> 0
            "blind", "chaal" -> quotePayableAmount(round, seat, Engine.getPlayerMinimumStake(round, seat, config))
            "raise" -> quotePayableAmount(round, seat, Engine.getPlayerRaiseStake(round, seat, config))
            "sideshow" -> {
                if (!canRequestSideshow(round, seat, actorIndex)) {
                    throw IllegalStateException("Sideshow is not allowed right now.")
                }
                val target = Engine.getPreviousActiveSeat(round, actorIndex)
                if (target == null || !target.seen) {
                    throw IllegalStateException("Sideshow is not allowed right now.")
                }
                quotePayableAmount(round, seat, Engine.getPlayerMinimumStake(round, seat, config))
            }
            "show" -> {
                if (!canShow(round, seat)) {
                    throw IllegalStateException("Show is only allowed when two players remain.")
                }
                quotePayableAmount(round, seat, Engine.getPlayerMinimumStake(round, seat, config))
            }
            else -> throw IllegalStateException("Unsupported action.")
        }
    }

    @Synchronized
    fun startRound(participants: List<RoundParticipant>, requestedRoundId: String? = null) {
        if (state.round != null && state.round?.status != "complete") {
            return
        }
        clearTimers()
        if (participants.size < 2 || participants.size > config.playerCount) {
            throw IllegalArgumentException("Round participants must be between 2 and ${config.playerCount}.")
        }
        val roundId = requestedRoundId ?: idGenerator.newId()
        val openingPlayerIndex = resolveOpeningPlayerIndex(participants)
        val deal =
            Engine.createRoundDeal(
                config,
                participants,
                roundId,
                ProvablyFairSupport.newServerSeed(),
                collectProvablyFairPlayerSeeds(participants),
                openingPlayerIndex,
            )
        scheduleNextOpeningPlayer(participants, openingPlayerIndex)
        val now = clockProvider.nowIso()
        val countdownEndsAt = clockProvider.isoFromMillis(clockProvider.now().toEpochMilli() + ROUND_START_COUNTDOWN_MS)
        val openingPot = config.bootAmount * participants.size
        if (openingPot > config.maxPotAmount) {
            throw IllegalStateException("Boot amount exceeds the maximum pot amount.")
        }
        val bankrollsById = LinkedHashMap<String, Int>()
        for (bankroll in state.playerBankrolls) {
            bankrollsById[bankroll.id] = bankroll.balance
        }
        val variantState = createRoundVariantState(deal, participants, openingPlayerIndex)
        val seats = mutableListOf<SeatState>()
        for (index in participants.indices) {
            val participant = participants[index]
            val seat = SeatState()
            seat.id = participant.id
            seat.name = participant.name
            seat.avatar = participant.avatar
            seat.isBot = participant.isBot
            seat.connected = participant.connected
            seat.active = true
            val dealtCards = deal.hands[index]
            when (config.variant.publicCardMode) {
                "third_card_rank_joker" -> {
                    seat.cards = dealtCards.take(3).toMutableList()
                    seat.publicCards = dealtCards.drop(2).take(1).toMutableList()
                    seat.reserveCards = dealtCards.drop(3).toMutableList()
                }
                "flipper_blue_card" -> {
                    // 3 normal cards are played; the 4th (blue Flipper) is held privately
                    // in reserveCards. It is NOT shown publicly at deal time.
                    seat.cards = dealtCards.take(3).toMutableList()
                    seat.reserveCards = dealtCards.drop(3).take(1).toMutableList()
                }
                else -> {
                    seat.cards = dealtCards.take(3).toMutableList()
                    seat.reserveCards = dealtCards.drop(3).toMutableList()
                }
            }
            seat.totalContributed = config.bootAmount
            seat.lastAction = LastAction("boot", config.bootAmount, now)
            seat.balance = (bankrollsById[participant.id] ?: config.initialBalance) - config.bootAmount
            seats.add(seat)
        }
        state.playerBankrolls = nextBankrollState(seats)
        val round = RoundState()
        round.id = roundId
        round.status = "starting"
        round.bootAmount = config.bootAmount
        round.currentStake = config.bootAmount
        round.potAmount = openingPot
        round.activePlayerIndex = openingPlayerIndex
        round.dealerIndex = openingPlayerIndex
        round.seats = seats.toMutableList()
        round.createdAt = now
        round.message = "Round begins in 5 seconds. Dealer is shuffling the deck."
        round.turnDurationMs = config.turnDurationMs
        round.startCountdownStartedAt = now
        round.startCountdownEndsAt = countdownEndsAt
        round.provablyFair = deal.provablyFair
        round.variantState = variantState
        state.status = "active"
        state.round = round
        logAction("system", "boot", config.bootAmount, "All players posted boot.")
        persistState()
        emitState("round_starting")
        scheduleStartCountdown()
    }

    private fun resolveOpeningPlayerIndex(participants: List<RoundParticipant>): Int {
        val nextOpeningPlayerId = state.nextOpeningPlayerId
        if (nextOpeningPlayerId != null) {
            val existingIndex = participants.indexOfFirst { it.id == nextOpeningPlayerId }
            if (existingIndex >= 0) {
                return existingIndex
            }
        }
        return 0
    }

    private fun scheduleNextOpeningPlayer(participants: List<RoundParticipant>, currentOpeningIndex: Int) {
        if (participants.isEmpty()) {
            state.nextOpeningPlayerId = null
            return
        }
        val nextIndex = (currentOpeningIndex + 1) % participants.size
        state.nextOpeningPlayerId = participants[nextIndex].id
    }

    @Synchronized
    fun performAction(playerId: String, type: String, payload: Map<String, Any?>): Map<String, Any?> {
        if (type == "dealer_tip") {
            return handleDealerTipAction(playerId, payload)
        }

        val round = requireActiveRound()
        if (isPotLimitReached(round)) {
            finishRoundByPotLimit()
            return getClientState(playerId)
        }
        val actorIndex = indexOfSeat(round, playerId)
        val seat = round.seats[actorIndex]
        if (seat.packed) {
            throw IllegalStateException("Packed players cannot act.")
        }

        if (round.pendingSideShow != null) {
            handlePendingSideShowAction(round, seat, actorIndex, type)
        } else {
            if (actorIndex != round.activePlayerIndex) {
                throw IllegalStateException("It is not this player's turn.")
            }
            when (type) {
                "see" -> handleSee(seat)
                "blind", "chaal" -> handleBet(seat, actorIndex, false)
                "raise" -> handleBet(seat, actorIndex, true)
                "pack" -> handlePack(seat, actorIndex, "Packed.")
                "sideshow" -> handleSideshowRequest(seat, actorIndex)
                "show" -> handleShow(seat)
                else -> throw IllegalStateException("Unsupported action.")
            }
        }

        if (state.round?.status != "active") {
            return getClientState(playerId)
        }
        clearTimers()
        armTurnTimer(false)
        persistState()
        emitState("action")
        scheduleBotsIfNeeded()
        return getClientState(playerId)
    }

    @Synchronized
    fun forcePack(playerId: String, note: String, actionType: String) {
        val round = state.round ?: return
        if (round.status != "active") {
            return
        }
        var actorIndex = -1
        for (index in round.seats.indices) {
            if (round.seats[index].id == playerId) {
                actorIndex = index
                break
            }
        }
        if (actorIndex < 0) {
            return
        }
        val seat = round.seats[actorIndex]
        if (seat.packed) {
            return
        }
        seat.packed = true
        revealFlipperReserveCard(round, seat)
        seat.connected = false
        seat.lastAction = LastAction(actionType, 0, clockProvider.nowIso())
        logAction(seat.id, actionType, 0, note)
        val activeSeats = Engine.getActiveSeats(round)
        if (activeSeats.size == 1) {
            finishRound(activeSeats.first(), note)
        } else if (actorIndex == round.activePlayerIndex) {
            advanceTurn(actorIndex)
            updateVariantProgressAfterTurn(round, seat.id)
            armTurnTimer(false)
            persistState()
            emitState("action")
            scheduleBotsIfNeeded()
        } else {
            persistState()
            emitState("action")
        }
    }

    @Synchronized
    fun restoreRuntime() {
        val round = state.round ?: return
        when (round.status) {
            "starting" -> {
                if (round.startCountdownEndsAt != null &&
                    Instant.parse(round.startCountdownEndsAt).toEpochMilli() <= clockProvider.now().toEpochMilli()
                ) {
                    transitionRoundToDealing(round)
                } else {
                    scheduleStartCountdown()
                }
            }
            "dealing" -> {
                if (round.dealingEndsAt != null &&
                    Instant.parse(round.dealingEndsAt).toEpochMilli() <= clockProvider.now().toEpochMilli()
                ) {
                    activateRound(round)
                } else {
                    scheduleDealingTransition()
                }
            }
            "active" -> {
                armTurnTimer(true)
                scheduleBotsIfNeeded()
            }
            "complete" -> {
                if (round.dealerTipState?.pending == true) {
                    if (round.dealerTipState?.expiresAt != null &&
                        Instant.parse(round.dealerTipState!!.expiresAt).toEpochMilli() <= clockProvider.now().toEpochMilli()
                    ) {
                        autoSkipDealerTip()
                    } else {
                        scheduleDealerTipTimeout()
                    }
                }
            }
        }
    }

    @Synchronized
    fun clearTimers() {
        turnTimer?.cancel()
        turnTimer = null
        dealerTipTimer?.cancel()
        dealerTipTimer = null
        val tasks = botTimers.toList()
        botTimers.clear()
        tasks.forEach { it.cancel() }
    }

    private fun createInitialState(): TableState {
        val next = TableState()
        next.id = config.tableId
        next.tableType = tableType
        next.variantId = config.variant.id
        next.status = "idle"
        next.config = config
        next.messages =
            mutableListOf(
                "I Love 6Patti",
                "Please play blind",
                "Please Play Fast",
                "This game is Fast, But are you slow ?",
                "Well played.",
            )
        next.playerBankrolls = mutableListOf()
        next.nextOpeningPlayerId = null
        next.publicSeating = if (tableType == "public_table") PublicSeatingState() else null
        next.leaseOwner = if (tableType == "public_table") instanceId else null
        next.leaseExpiresAt = if (tableType == "public_table") extendLease(clockProvider.nowIso()) else null
        next.createdAt = clockProvider.nowIso()
        next.updatedAt = next.createdAt
        return next
    }

    private fun requireActiveRound(): RoundState {
        val round = state.round
        if (round == null || round.status != "active") {
            throw IllegalStateException("No active round is available.")
        }
        return round
    }

    private fun indexOfSeat(round: RoundState, playerId: String): Int {
        for (index in round.seats.indices) {
            if (round.seats[index].id == playerId) {
                return index
            }
        }
        throw IllegalStateException("Unknown player.")
    }

    private fun getClientState(viewerId: String?): Map<String, Any?> {
        val response = linkedMapOf<String, Any?>()
        response["tableId"] = state.id
        response["status"] = state.status
        response["config"] = getPublicConfig()
        response["round"] = state.round?.let { serializeRoundForClient(it, viewerId) }
        response["history"] = state.history.take(12)
        response["chatMessages"] = state.messages
        return response
    }

    private fun getPublicConfig(): Map<String, Any?> {
        val variant = linkedMapOf<String, Any?>()
        variant["id"] = config.variant.id
        variant["label"] = config.variant.label
        variant["wildcardRanks"] = config.variant.wildcardRanks
        variant["evaluationMode"] = config.variant.evaluationMode
        variant["cardsPerSeat"] = config.variant.cardsPerSeat
        variant["publicCardMode"] = config.variant.publicCardMode
        variant["sharedJokerMode"] = config.variant.sharedJokerMode
        val variantsEnabled = linkedMapOf<String, Any?>()
        variantsEnabled["allowA23Sequence"] = config.allowA23Sequence
        variantsEnabled["allowAkqSequence"] = config.allowAkqSequence
        variantsEnabled["sequenceRankingMode"] = config.sequenceRankingMode
        variantsEnabled["wildcardRanks"] = config.variant.wildcardRanks
        variantsEnabled["sideshow"] = true
        variantsEnabled["autoplay"] = false
        variantsEnabled["forceBlindCycles"] = config.variant.forceBlindCycles
        variantsEnabled["showUnlockCycle"] = config.variant.showUnlockCycle
        variantsEnabled["showRequiresAllSeen"] = config.variant.showRequiresAllSeen
        variantsEnabled["autoAcceptSideshow"] = config.variant.autoAcceptSideshow
        val response = linkedMapOf<String, Any?>()
        response["tableId"] = config.tableId
        response["variant"] = variant
        response["bootAmount"] = config.bootAmount
        response["maxPotAmount"] = config.maxPotAmount
        response["minStake"] = minStakeFloor()
        response["maxStake"] = config.maxStake
        response["playerCount"] = config.playerCount
        response["casinoBootCommissionPercent"] = config.casinoBootCommissionPercent
        response["casinoWinCommissionPercent"] = config.casinoWinCommissionPercent
        response["turnDurationMs"] = config.turnDurationMs
        response["variantsEnabled"] = variantsEnabled
        response["debugControlsAvailable"] = false
        return response
    }

    private fun serializeRoundForClient(round: RoundState, viewerId: String?): Map<String, Any?> {
        clearExpiredSideShowResult(round)
        val winnerId = round.result?.winnerId
        val seats = mutableListOf<Map<String, Any?>>()
        for (index in round.seats.indices) {
            val seat = round.seats[index]
            val isViewer = viewerId != null && viewerId == seat.id
            var privateReveal: SideShowSeatReveal? = null
            if (round.recentSideShowResult != null &&
                viewerId != null &&
                round.recentSideShowResult!!.visibleToPlayerIds.contains(viewerId)
            ) {
                for (reveal in round.recentSideShowResult!!.reveals) {
                    if (reveal.playerId == seat.id) {
                        privateReveal = reveal
                        break
                    }
                }
            }
            val canReveal =
                privateReveal != null ||
                    (isViewer && (seat.seen || round.status == "complete")) ||
                    (!isViewer && (round.status == "complete" || seat.id == winnerId))
            val cards =
                if (canReveal) {
                    privateReveal?.cards ?: seat.cards
                } else {
                    seat.cards.map { card -> Card("hidden-${card.id}", null, null, null, true) }.toMutableList()
                }
            val item = linkedMapOf<String, Any?>()
            item["id"] = seat.id
            item["name"] = seat.name
            item["avatar"] = seat.avatar
            item["seatIndex"] = index
            item["isUser"] = isViewer
            item["isSelf"] = isViewer
            item["isBot"] = seat.isBot
            item["isRealPlayer"] = !seat.isBot
            item["connected"] = seat.connected
            item["active"] = seat.active
            item["packed"] = seat.packed
            item["seen"] = seat.seen
            item["cards"] = cards
            if (seat.cards.isNotEmpty() && canReveal) {
                item["handLabel"] = Engine.evaluateSeatHand(seat, round, config).label
            }
            // Flipper blue card: send to the seat owner always so they can see their own card.
            // On round complete, reveal to all players (same rules as normal card reveal).
            // During active play for opponents: send a hidden placeholder so the UI
            // knows a blue card slot exists without leaking its identity.
            if (config.variant.publicCardMode == "flipper_blue_card") {
                val blueCard: Card? = resolveFlipperCard(seat)
                if (blueCard != null) {
                    val sideShowRevealedForSeat = privateReveal != null
                    val revealBlue =
                        isViewer ||
                            round.status == "complete" ||
                            seat.publicCards.isNotEmpty() ||
                            sideShowRevealedForSeat
                    item["flipperCard"] = linkedMapOf<String, Any?>().also { c ->
                        c["id"] = if (revealBlue) blueCard.id else "hidden-flipper-${seat.id}"
                        c["rank"] = if (revealBlue) blueCard.rank else null
                        c["suit"] = if (revealBlue) blueCard.suit else null
                        c["value"] = if (revealBlue) blueCard.value else null
                        c["hidden"] = !revealBlue
                    }
                }
            }
            item["publicCards"] = seat.publicCards
            item["totalContributed"] = seat.totalContributed
            item["lastAction"] = seat.lastAction
            item["balance"] = seat.balance
            item["isTurn"] = index == round.activePlayerIndex && round.status == "active"
            item["eliminatedBySideshow"] = seat.eliminatedBySideshow
            seats.add(item)
        }
        val response = linkedMapOf<String, Any?>()
        response["id"] = round.id
        response["status"] = round.status
        response["bootAmount"] = round.bootAmount
        response["currentStake"] = round.currentStake
        response["potAmount"] = round.potAmount
        response["activePlayerIndex"] = round.activePlayerIndex
        response["activePlayerId"] =
            if (round.activePlayerIndex in round.seats.indices) round.seats[round.activePlayerIndex].id else null
        response["turnDurationMs"] = round.turnDurationMs
        response["startCountdownStartedAt"] = round.startCountdownStartedAt
        response["startCountdownEndsAt"] = round.startCountdownEndsAt
        response["dealingStartedAt"] = round.dealingStartedAt
        response["dealingEndsAt"] = round.dealingEndsAt
        response["turnStartedAt"] = round.turnStartedAt
        response["turnDeadlineAt"] = round.turnDeadlineAt
        response["remainingPlayers"] = Engine.getActiveSeats(round).map { it.id }
        response["winnerId"] = winnerId
        response["createdAt"] = round.createdAt
        response["settledAt"] = round.settledAt
        response["nextRoundDecisionExpiresAt"] = round.nextRoundDecisionExpiresAt
        response["lastAction"] = round.lastAction
        response["actionLog"] = if (round.actionLog.size > 10) round.actionLog.takeLast(10) else round.actionLog
        response["message"] = round.message
        response["result"] = serializeRoundResult(round.result)
        response["provablyFair"] = TokenSupport.copyProvablyFairState(round.provablyFair, round.status == "complete")
        response["dealerTipPending"] = round.dealerTipState?.pending == true
        response["dealerTipResolvedAt"] = round.dealerTipState?.resolvedAt
        response["dealerTipPrompt"] = getSerializedDealerTipPrompt(round, viewerId)
        response["pendingSideShow"] = getSerializedPendingSideShow(round, viewerId)
        response["sideShowResult"] = getSerializedSideShowResult(round, viewerId)
        response["variantState"] = serializeVariantState(round)
        response["viewerLegalActions"] = getViewerLegalActions(round, viewerId)
        response["seats"] = seats
        return response
    }

    private fun serializeVariantState(round: RoundState): Map<String, Any?>? {
        val variantState = round.variantState ?: return null
        val payload = linkedMapOf<String, Any?>()
        payload["variantId"] = variantState.variantId
        payload["cycleNumber"] = variantState.cycleNumber
        payload["cycleStartPlayerId"] = variantState.cycleStartPlayerId
        payload["forceBlindActive"] = variantState.forceBlindActive
        payload["showUnlocked"] = variantState.showUnlocked
        payload["showRequiresAllSeen"] = variantState.showRequiresAllSeen
        payload["autoAcceptSideshow"] = variantState.autoAcceptSideshow
        payload["wildcardRanks"] = variantState.wildcardRanks
        payload["pendingAutoSeePlayerId"] = variantState.pendingAutoSeePlayerId
        payload["sharedJokers"] =
            variantState.sharedJokerCards.mapIndexed { index, card ->
                if (index < variantState.revealedSharedJokerCount) card else Card("hidden-${card.id}", null, null, null, true)
            }
        payload["revealedSharedJokerCount"] = variantState.revealedSharedJokerCount
        return payload
    }

    private fun serializeRoundResult(result: RoundResult?): Map<String, Any?>? {
        if (result == null) {
            return null
        }
        val payload = linkedMapOf<String, Any?>()
        payload["winnerId"] = result.winnerId
        payload["winnerName"] = result.winnerName
        payload["winningHand"] = result.winningHand
        payload["bootContributionTotal"] = result.bootContributionTotal
        payload["realPlayerContributionTotal"] = result.realPlayerContributionTotal
        payload["botContributionTotal"] = result.botContributionTotal
        payload["bootCommission"] = result.bootCommission
        payload["actualBootCommission"] = result.actualBootCommission
        payload["winCommission"] = result.winCommission
        payload["actualWinCommission"] = result.actualWinCommission
        payload["dealerTip"] = result.dealerTip
        payload["casinoCommissionTotal"] = result.casinoCommissionTotal
        payload["actualCasinoIncomeTotal"] = result.actualCasinoIncomeTotal
        payload["winnerReceivableBeforeTip"] = result.winnerReceivableBeforeTip
        payload["payout"] = result.payout
        payload["reason"] = result.reason
        payload["potLimitReached"] = result.potLimitReached
        return payload
    }

    private fun getViewerLegalActions(round: RoundState, viewerId: String?): List<String> {
        if (viewerId == null) {
            return emptyList()
        }
        val pending = round.pendingSideShow
        if (pending != null) {
            return when {
                viewerId == pending.requesterId -> emptyList()
                viewerId == pending.targetId && config.variant.autoAcceptSideshow -> listOf("sideshow_accept")
                viewerId == pending.targetId -> listOf("sideshow_accept", "sideshow_deny")
                else -> emptyList()
            }
        }
        if (round.status != "active") {
            return emptyList()
        }
        val actorIndex = round.seats.indexOfFirst { it.id == viewerId }
        if (actorIndex < 0 || actorIndex != round.activePlayerIndex) {
            return emptyList()
        }
        return determineLegalTurnActions(round, round.seats[actorIndex], actorIndex)
    }

    private fun determineLegalTurnActions(round: RoundState, seat: SeatState, actorIndex: Int): List<String> {
        val legal = mutableListOf<String>()
        if (!seat.seen && canSee(round)) {
            legal.add("see")
        }
        val callAmount = Engine.getPlayerMinimumStake(round, seat, config)
        val raiseAmount = Engine.getPlayerRaiseStake(round, seat, config)
        if (canPay(round, seat, callAmount)) {
            legal.add(if (seat.seen) "chaal" else "blind")
        }
        if (canPay(round, seat, raiseAmount)) {
            legal.add("raise")
        }
        legal.add("pack")
        if (canRequestSideshow(round, seat, actorIndex) && canPay(round, seat, callAmount)) {
            legal.add("sideshow")
        }
        if (canShow(round, seat) && canPay(round, seat, callAmount)) {
            legal.add("show")
        }
        return legal
    }

    private fun canPay(round: RoundState, seat: SeatState, amount: Int): Boolean {
        if (amount < minStakeFloor() || amount > config.maxStake * config.blindSeenMultiplier) {
            return false
        }
        val payable = capContributionAmount(round, seat, amount)
        return payable > 0
    }

    private fun quotePayableAmount(round: RoundState, seat: SeatState, amount: Int): Int {
        if (amount < minStakeFloor() || amount > config.maxStake * config.blindSeenMultiplier) {
            throw IllegalStateException("Stake is outside the table limits.")
        }
        val payable = capContributionAmount(round, seat, amount)
        if (payable <= 0) {
            throw IllegalStateException("Maximum pot amount reached.")
        }
        return payable
    }

    private fun remainingPotRoom(round: RoundState): Int = maxOf(0, config.maxPotAmount - round.potAmount)

    private fun isPotLimitReached(round: RoundState): Boolean = round.potAmount >= config.maxPotAmount

    private fun capContributionAmount(round: RoundState, seat: SeatState, requestedAmount: Int): Int {
        val potRoom = remainingPotRoom(round)
        if (potRoom <= 0) {
            return 0
        }
        return minOf(requestedAmount, potRoom, seat.balance)
    }

    private fun finishRoundByPotLimit() {
        val round = state.round ?: return
        if (round.status != "active") {
            return
        }
        val activeSeats = Engine.getActiveSeats(round)
        when {
            activeSeats.isEmpty() -> throw IllegalStateException("No active players remain.")
            activeSeats.size == 1 -> finishRound(activeSeats.first(), "Maximum pot amount reached.", potLimitReached = true)
            else -> {
                val winner = Engine.resolveWinner(activeSeats, round, config)
                finishRound(winner, "Maximum pot amount reached.", potLimitReached = true)
            }
        }
    }

    private fun canSee(round: RoundState): Boolean = !(round.variantState?.forceBlindActive == true)

    private fun canRequestSideshow(round: RoundState, seat: SeatState, actorIndex: Int): Boolean {
        if (config.variant.showUnlockCycle > 0 && round.variantState?.showUnlocked != true) {
            return false
        }
        return Engine.canRequestSideshow(round, seat, actorIndex)
    }

    private fun canShow(round: RoundState, seat: SeatState): Boolean {
        if (!seat.seen || !Engine.canShow(round)) {
            return false
        }
        if (config.variant.showUnlockCycle > 0 && round.variantState?.showUnlocked != true) {
            return false
        }
        if (config.variant.showRequiresAllSeen && !allActivePlayersSeen(round)) {
            return false
        }
        return true
    }

    private fun allActivePlayersSeen(round: RoundState): Boolean = Engine.getActiveSeats(round).all { it.seen }

    private fun createRoundVariantState(
        deal: CreatedDeal,
        participants: List<RoundParticipant>,
        openingPlayerIndex: Int,
    ): RoundVariantState {
        val variantState = RoundVariantState()
        variantState.variantId = config.variant.id
        variantState.cycleStartPlayerId = participants[openingPlayerIndex].id
        variantState.forceBlindActive = config.variant.forceBlindCycles > 0
        variantState.showUnlocked = config.variant.showUnlockCycle == 0
        variantState.showRequiresAllSeen = config.variant.showRequiresAllSeen
        variantState.autoAcceptSideshow = config.variant.autoAcceptSideshow
        when (config.variant.sharedJokerMode) {
            "progressive_three" -> {
                variantState.sharedJokerCards = deal.sharedCards.toMutableList()
            }
        }
        // third_card_rank_joker: the 3rd dealt card of every hand becomes a shared wildcard rank.
        if (config.variant.publicCardMode == "third_card_rank_joker") {
            for (hand in deal.hands) {
                if (hand.size >= 3) {
                    variantState.sharedJokerCards.add(hand[2])
                }
            }
        }
        // flipper_blue_card: the blue card is a per-player conditional activator.
        // It is NOT a shared wildcard — no entries are added to sharedJokerCards.
        syncVariantWildcardRanks(variantState)
        return variantState
    }

    private fun syncVariantWildcardRanks(variantState: RoundVariantState) {
        val ranks = linkedSetOf<String>()
        ranks.addAll(config.variant.wildcardRanks)
        when {
            config.variant.sharedJokerMode == "progressive_three" -> {
                for (index in 0 until minOf(variantState.revealedSharedJokerCount, variantState.sharedJokerCards.size)) {
                    variantState.sharedJokerCards[index].rank?.let(ranks::add)
                }
            }
            config.variant.publicCardMode == "third_card_rank_joker" -> {
                for (card in variantState.sharedJokerCards) {
                    card.rank?.let(ranks::add)
                }
            }
        }
        variantState.wildcardRanks = ranks.toMutableList()
    }

    private fun updateVariantProgressAfterTurn(round: RoundState, actorId: String) {
        val variantState = round.variantState ?: return
        if (config.variant.publicCardMode == "third_card_rank_joker") {
            syncVariantWildcardRanks(variantState)
        }
        val nextSeat = round.seats.getOrNull(round.activePlayerIndex)
        val cycleStartPlayerId = variantState.cycleStartPlayerId
        if (cycleStartPlayerId == null || round.seats.none { it.id == cycleStartPlayerId && it.active && !it.packed }) {
            variantState.cycleStartPlayerId = nextSeat?.id
        } else if (nextSeat != null && nextSeat.id == cycleStartPlayerId && actorId != cycleStartPlayerId) {
            variantState.cycleNumber += 1
            if (config.variant.sharedJokerMode == "progressive_three") {
                variantState.revealedSharedJokerCount = minOf(3, maxOf(variantState.revealedSharedJokerCount, variantState.cycleNumber))
            }
        }
        variantState.forceBlindActive = variantState.cycleNumber < config.variant.forceBlindCycles
        variantState.showUnlocked = config.variant.showUnlockCycle == 0 || variantState.cycleNumber >= config.variant.showUnlockCycle
        syncVariantWildcardRanks(variantState)
        refreshPendingAutoSee(round)
    }

    private fun refreshPendingAutoSee(round: RoundState) {
        val variantState = round.variantState ?: return
        if (!config.variant.showRequiresAllSeen || !variantState.showUnlocked) {
            variantState.pendingAutoSeePlayerId = null
            return
        }
        val unseenActivePlayers = Engine.getActiveSeats(round).filter { !it.seen }
        variantState.pendingAutoSeePlayerId =
            if (unseenActivePlayers.size == 1) {
                unseenActivePlayers.first().id
            } else {
                null
            }
    }

    private fun maybeAutoSeeAfterBlindTurn(round: RoundState, actorId: String) {
        val variantState = round.variantState ?: return
        val pendingPlayerId = variantState.pendingAutoSeePlayerId ?: return
        if (actorId != pendingPlayerId) {
            return
        }
        val seat = round.seats.firstOrNull { it.id == pendingPlayerId } ?: return
        if (seat.seen || seat.packed || !seat.active) {
            variantState.pendingAutoSeePlayerId = null
            return
        }
        seat.seen = true
        seat.lastAction = LastAction("see", 0, clockProvider.nowIso())
        logAction(seat.id, "see", 0, "${seat.name} was auto-seen after the final blind turn.")
        round.message = "${seat.name} has seen their cards."
        variantState.pendingAutoSeePlayerId = null
    }

    private fun revealFlipperReserveCard(round: RoundState, seat: SeatState) {
        val isFlipperMode = config.variant.publicCardMode == "third_card_rank_joker" ||
            config.variant.publicCardMode == "flipper_blue_card"
        if (!isFlipperMode || seat.reserveCards.isEmpty()) {
            return
        }
        val revealed = seat.reserveCards.removeAt(0)
        seat.publicCards.add(revealed)
        // For third_card_rank_joker: the revealed card also becomes a shared wildcard rank.
        // For flipper_blue_card: the card is shown publicly for transparency / provably fair,
        // but it is NOT added to the shared wildcard pool.
        if (config.variant.publicCardMode == "third_card_rank_joker") {
            round.variantState?.sharedJokerCards?.add(revealed)
            round.variantState?.let { syncVariantWildcardRanks(it) }
        }
    }

    private fun handleSee(seat: SeatState) {
        if (!canSee(state.round!!)) {
            throw IllegalStateException("See is not allowed yet in this variant.")
        }
        if (seat.seen) {
            throw IllegalStateException("Cards are already seen.")
        }
        seat.seen = true
        seat.lastAction = LastAction("see", 0, clockProvider.nowIso())
        state.round!!.message = "${seat.name} has seen their cards."
        logAction(seat.id, "see", 0, "${seat.name} saw their cards.")
    }

    private fun handleBet(seat: SeatState, actorIndex: Int, raise: Boolean) {
        val round = state.round!!
        val requestedAmount = if (raise) Engine.getPlayerRaiseStake(round, seat, config) else Engine.getPlayerMinimumStake(round, seat, config)
        if (requestedAmount < minStakeFloor() || requestedAmount > config.maxStake * config.blindSeenMultiplier) {
            throw IllegalStateException("Stake is outside the table limits.")
        }
        val amount = capContributionAmount(round, seat, requestedAmount)
        if (amount <= 0) {
            finishRoundByPotLimit()
            return
        }
        seat.balance -= amount
        seat.totalContributed += amount
        seat.lastAction = LastAction(if (raise) "raise" else if (seat.seen) "chaal" else "blind", amount, clockProvider.nowIso())
        round.potAmount += amount
        if (raise) {
            round.currentStake = if (seat.seen) amount / config.blindSeenMultiplier else amount
        }
        syncBankrolls(round)
        logAction(seat.id, seat.lastAction!!.type, amount, "${seat.name} placed $amount.")
        round.message = seat.name + if (raise) " raised" else " matched" + " the stake."
        if (isPotLimitReached(round)) {
            finishRoundByPotLimit()
            return
        }
        advanceTurn(actorIndex)
        updateVariantProgressAfterTurn(round, seat.id)
        if (!seat.seen && seat.lastAction?.type == "blind") {
            maybeAutoSeeAfterBlindTurn(round, seat.id)
        }
    }

    private fun minStakeFloor(): Int = minOf(config.minStake, config.bootAmount)

    private fun handlePack(seat: SeatState, actorIndex: Int, note: String) {
        revealFlipperReserveCard(state.round!!, seat)
        seat.packed = true
        seat.lastAction = LastAction("pack", 0, clockProvider.nowIso())
        logAction(seat.id, "pack", 0, "${seat.name} packed.")
        val activeSeats = Engine.getActiveSeats(state.round!!)
        if (activeSeats.size == 1) {
            finishRound(activeSeats.first(), "All other players packed.")
            return
        }
        state.round!!.message = note
        advanceTurn(actorIndex)
        updateVariantProgressAfterTurn(state.round!!, seat.id)
    }

    private fun handleSideshowRequest(seat: SeatState, actorIndex: Int) {
        val round = state.round!!
        if (!canRequestSideshow(round, seat, actorIndex)) {
            throw IllegalStateException("Sideshow is not allowed right now.")
        }
        val target = Engine.getPreviousActiveSeat(round, actorIndex)
        if (target == null || !target.seen) {
            throw IllegalStateException("Sideshow is not allowed right now.")
        }
        val requestAmount = Engine.getPlayerMinimumStake(round, seat, config)
        val amount = capContributionAmount(round, seat, requestAmount)
        if (amount <= 0) {
            finishRoundByPotLimit()
            return
        }
        clearExpiredSideShowResult(round)
        val nowMs = clockProvider.now().toEpochMilli()
        val now = clockProvider.isoFromMillis(nowMs)
        seat.balance -= amount
        seat.totalContributed += amount
        seat.lastAction = LastAction("sideshow-requested", amount, now)
        round.potAmount += amount
        syncBankrolls(round)
        val request = SideShowRequest()
        request.requesterId = seat.id
        request.targetId = target.id
        request.requesterName = seat.name
        request.targetName = target.name
        request.requestedAt = now
        request.expiresAt = clockProvider.isoFromMillis(nowMs + config.turnDurationMs)
        request.forcedRaiseAmount = amount
        request.status = "pending"
        round.pendingSideShow = request
        round.message = "${seat.name} requested a side show with ${target.name}."
        logAction(
            seat.id,
            "sideshow-requested",
            amount,
            "${seat.name} paid $amount to request a side show against ${target.name}.",
        )
        if (config.variant.autoAcceptSideshow) {
            resolveAcceptedSideShow(round, indexOfSeat(round, target.id), request)
        }
        if (isPotLimitReached(round)) {
            round.pendingSideShow = null
            finishRoundByPotLimit()
        }
    }

    private fun handlePendingSideShowAction(round: RoundState, seat: SeatState, actorIndex: Int, type: String) {
        val pending = round.pendingSideShow ?: throw IllegalStateException("Waiting for the side show response.")
        if (seat.id != pending.targetId) {
            throw IllegalStateException("Waiting for the side show response.")
        }
        if (config.variant.autoAcceptSideshow && type != "sideshow_accept") {
            throw IllegalStateException("This variant requires the side show to be accepted.")
        }
        when (type) {
            "sideshow_accept" -> resolveAcceptedSideShow(round, actorIndex, pending)
            "sideshow_deny" -> resolveDeniedSideShow(round, pending, false)
            else -> throw IllegalStateException("Waiting for the side show response.")
        }
    }

    private fun handleShow(seat: SeatState) {
        val round = state.round!!
        if (!canShow(round, seat)) {
            throw IllegalStateException("Show is only allowed when two players remain.")
        }
        val showCost = Engine.getPlayerMinimumStake(round, seat, config)
        val amount = capContributionAmount(round, seat, showCost)
        if (amount <= 0) {
            finishRoundByPotLimit()
            return
        }
        seat.balance -= amount
        seat.totalContributed += amount
        round.potAmount += amount
        syncBankrolls(round)
        val opponent = Engine.getActiveSeats(round).first { it.id != seat.id }
        val winner = if (Engine.compareSeatHands(seat, opponent, round, config) > 0) seat else opponent
        logAction(seat.id, "show", amount, "${seat.name} called show.")
        finishRound(winner, if (isPotLimitReached(round)) "Maximum pot amount reached." else "Showdown complete.", potLimitReached = isPotLimitReached(round))
    }

    private fun finishRound(winner: SeatState, reason: String, potLimitReached: Boolean = false) {
        val round = state.round!!
        clearTimers()
        round.status = "complete"
        round.settledAt = null
        round.nextRoundDecisionExpiresAt =
            clockProvider.isoFromMillis(clockProvider.now().toEpochMilli() + NEXT_ROUND_DECISION_WINDOW_MS)
        val hand = Engine.evaluateSeatHand(winner, round, config)
        val settlement = calculateSettlement(round, winner, 0)
        Engine.validateSettlementConsistency(round, settlement)
        round.result = buildRoundResult(winner, hand, reason, settlement, false)
        round.result!!.potLimitReached = potLimitReached
        finalizeRoundSettlement(winner, hand, settlement)
        if (round.message.isNullOrBlank()) {
            round.message = "${winner.name} won with ${hand.label}."
        }
        persistState()
        emitState("round_complete")
    }

    private fun handleDealerTipAction(playerId: String, payload: Map<String, Any?>): Map<String, Any?> {
        val round = state.round
            ?: throw IllegalStateException("Dealer tip is not available right now.")
        val rawAmount = payload["amount"]
        if (rawAmount !is Number) {
            throw IllegalStateException("Dealer tip amount is required.")
        }
        val dealerTip = rawAmount.toInt()
        if (dealerTip < 0) {
            throw IllegalStateException("Dealer tip cannot be negative.")
        }

        if (round.status == "complete" && round.dealerTipState?.pending == true && playerId == round.dealerTipState!!.winnerId) {
            if (dealerTip >= round.dealerTipState!!.winnerReceivableBeforeTip) {
                throw IllegalStateException("Dealer tip must be less than the winning amount.")
            }
            val winnerIndex = indexOfSeat(round, playerId)
            val winner = round.seats[winnerIndex]
            val hand = Engine.evaluateSeatHand(winner, round, config)
            val settlement = calculateSettlement(round, winner, dealerTip)
            Engine.validateSettlementConsistency(round, settlement)
            finalizeRoundSettlement(winner, hand, settlement)
            persistState()
            emitState("dealer_tip")
            return getClientState(playerId)
        }

        val actorIndex = indexOfSeat(round, playerId)
        val actor = round.seats[actorIndex]
        if (dealerTip > 0) {
            if (actor.balance < dealerTip) {
                throw IllegalStateException("Insufficient balance to tip dealer.")
            }
            actor.balance -= dealerTip
            syncBankrolls(round)
            logAction(actor.id, "dealer_tip", dealerTip, "${actor.name} tipped the dealer ₹${dealerTip}!")
            round.message = "${actor.name} tipped the dealer ₹${dealerTip}!"
        }
        persistState()
        emitState("dealer_tip")
        return getClientState(playerId)
    }

    private fun calculateSettlement(round: RoundState, winner: SeatState, dealerTip: Int): Settlement {
        val bootContributionTotal = round.bootAmount * round.seats.size
        val settlement =
            Engine.calculateSettlement(
                round.potAmount,
                bootContributionTotal,
                config.casinoBootCommissionPercent,
                config.casinoWinCommissionPercent,
                dealerTip,
            )
        populateSettlementReporting(round, winner, settlement)
        if (dealerTip >= settlement.winnerReceivableBeforeTip) {
            throw IllegalStateException("Dealer tip must be less than the winning amount.")
        }
        return settlement
    }

    private fun populateSettlementReporting(round: RoundState, winner: SeatState?, settlement: Settlement) {
        var realPlayerContributionTotal = 0
        var botContributionTotal = 0
        var realSeatCount = 0
        var hasBot = false
        for (seat in round.seats) {
            if (seat.isBot) {
                hasBot = true
                botContributionTotal += seat.totalContributed
            } else {
                realSeatCount += 1
                realPlayerContributionTotal += seat.totalContributed
            }
        }
        settlement.realPlayerContributionTotal = realPlayerContributionTotal
        settlement.botContributionTotal = botContributionTotal
        settlement.actualBootCommission = (round.bootAmount * realSeatCount * config.casinoBootCommissionPercent) / 100
        settlement.actualWinCommission = settlement.winCommission
        if (tableType == "public_table" && hasBot) {
            settlement.actualWinCommission =
                if (winner != null && !winner.isBot) {
                    val actualWinnerReceivableBeforeTip = maxOf(0, realPlayerContributionTotal - settlement.actualBootCommission)
                    (actualWinnerReceivableBeforeTip * config.casinoWinCommissionPercent) / 100
                } else {
                    0
                }
        }
        val actualPayoutToRealWinner =
            if (winner != null && !winner.isBot) {
                settlement.payout
            } else {
                0
            }
        settlement.actualCasinoIncomeTotal = realPlayerContributionTotal - actualPayoutToRealWinner
    }

    private fun buildRoundResult(
        winner: SeatState,
        hand: EvaluatedHand,
        reason: String,
        settlement: Settlement,
        settled: Boolean,
    ): RoundResult {
        val result = RoundResult()
        result.winnerId = winner.id
        result.winnerName = winner.name
        result.winningHand = hand.label
        result.bootContributionTotal = settlement.bootContributionTotal
        result.realPlayerContributionTotal = settlement.realPlayerContributionTotal
        result.botContributionTotal = settlement.botContributionTotal
        result.bootCommission = settlement.bootCommission
        result.actualBootCommission = settlement.actualBootCommission
        result.winCommission = settlement.winCommission
        result.actualWinCommission = settlement.actualWinCommission
        result.dealerTip = if (settled) settlement.dealerTip else 0
        result.casinoCommissionTotal = if (settled) settlement.casinoCommissionTotal else settlement.bootCommission + settlement.winCommission
        result.actualCasinoIncomeTotal = settlement.actualCasinoIncomeTotal
        result.winnerReceivableBeforeTip = settlement.winnerReceivableBeforeTip
        result.payout = if (settled) settlement.payout else 0
        result.reason = reason
        return result
    }

    private fun finalizeRoundSettlement(winner: SeatState, hand: EvaluatedHand, settlement: Settlement) {
        val round = state.round!!
        dealerTipTimer?.cancel()
        dealerTipTimer = null
        round.settledAt = clockProvider.nowIso()
        winner.balance += settlement.payout
        syncBankrolls(round)
        val potLimitReached = round.result?.potLimitReached == true
        round.result = buildRoundResult(winner, hand, round.result?.reason ?: "", settlement, true)
        round.result!!.potLimitReached = potLimitReached
        if (round.dealerTipState != null) {
            round.dealerTipState!!.pending = false
            round.dealerTipState!!.expiresAt = null
            round.dealerTipState!!.resolvedAt = clockProvider.nowIso()
        }
        round.message = "${winner.name} won ${TokenSupport.formatIndianNumber(round.potAmount)} with ${hand.label}."
        val historyItem = TableHistoryItem()
        val trackedSeat = round.seats.firstOrNull { !it.isBot } ?: round.seats.first()
        historyItem.id = idGenerator.newId()
        historyItem.roundId = round.id
        historyItem.outcome = if (winner.id == trackedSeat.id) "win" else "loss"
        historyItem.winningHand = hand.label
        historyItem.pot = round.potAmount
        historyItem.userContribution = trackedSeat.totalContributed
        historyItem.realPlayerContributionTotal = settlement.realPlayerContributionTotal
        historyItem.botContributionTotal = settlement.botContributionTotal
        historyItem.bootCommission = settlement.bootCommission
        historyItem.actualBootCommission = settlement.actualBootCommission
        historyItem.winCommission = settlement.winCommission
        historyItem.actualWinCommission = settlement.actualWinCommission
        historyItem.dealerTip = if (winner.id == trackedSeat.id) settlement.dealerTip else 0
        historyItem.casinoCommissionTotal = settlement.casinoCommissionTotal
        historyItem.actualCasinoIncomeTotal = settlement.actualCasinoIncomeTotal
        historyItem.winnerReceivableBeforeTip = settlement.winnerReceivableBeforeTip
        historyItem.payout = if (winner.id == trackedSeat.id) settlement.payout else 0
        historyItem.timestamp = round.settledAt
        historyItem.provablyFair = TokenSupport.copyProvablyFairState(round.provablyFair, true)
        state.history.add(0, historyItem)
        appendRoundHistory(round, winner, round.result?.reason ?: "")
    }

    private fun appendRoundHistory(round: RoundState, winner: SeatState, reason: String) {
        if (roundHistoryRepository == null) {
            return
        }
        val entry = RoundHistoryEntry()
        entry.id = round.id
        entry.aggregateType = if (tableType == "private_room") "private_room" else "table"
        entry.aggregateId = state.id
        entry.variantId = state.variantId ?: config.variant.id
        for (seat in round.seats) {
            val participant = RoundHistoryParticipant()
            participant.id = seat.id
            participant.name = seat.name
            participant.avatar = seat.avatar
            participant.isBot = seat.isBot
            participant.totalContributed = seat.totalContributed
            participant.balance = seat.balance
            participant.packed = seat.packed
            participant.seen = seat.seen
            participant.cards = seat.cards.map(::copyHistoryCard).toMutableList()
            participant.publicCards = seat.publicCards.map(::copyHistoryCard).toMutableList()
            participant.reserveCards = seat.reserveCards.map(::copyHistoryCard).toMutableList()
            participant.handLabel = Engine.evaluateSeatHand(seat, round, config).label
            entry.participants.add(participant)
        }
        val winnerInfo = RoundHistoryWinner()
        winnerInfo.id = winner.id
        winnerInfo.name = winner.name
        winnerInfo.winningHand = round.result?.winningHand
        entry.winner = winnerInfo
        entry.bootContributionTotal = round.result?.bootContributionTotal ?: (round.bootAmount * round.seats.size)
        entry.potAmount = round.potAmount
        entry.realPlayerContributionTotal = round.result?.realPlayerContributionTotal ?: 0
        entry.botContributionTotal = round.result?.botContributionTotal ?: 0
        entry.bootCommission = round.result?.bootCommission ?: 0
        entry.actualBootCommission = round.result?.actualBootCommission ?: 0
        entry.winCommission = round.result?.winCommission ?: 0
        entry.actualWinCommission = round.result?.actualWinCommission ?: 0
        entry.dealerTip = round.result?.dealerTip ?: 0
        entry.casinoCommissionTotal = round.result?.casinoCommissionTotal ?: 0
        entry.actualCasinoIncomeTotal = round.result?.actualCasinoIncomeTotal ?: 0
        entry.winnerReceivableBeforeTip = round.result?.winnerReceivableBeforeTip ?: 0
        entry.payout = round.result?.payout ?: 0
        entry.reason = reason
        entry.actionLog = round.actionLog.toMutableList()
        entry.startedAt = round.createdAt
        entry.settledAt = round.settledAt
        entry.createdAt = clockProvider.nowIso()
        entry.provablyFair = TokenSupport.copyProvablyFairState(round.provablyFair, true)
        roundHistoryRepository.appendRound(entry)
    }

    private fun copyHistoryCard(card: Card): Card =
        Card(card.id, card.suit, card.rank, card.value, false)

    private fun collectProvablyFairPlayerSeeds(participants: List<RoundParticipant>): MutableList<ProvablyFairPlayerSeedInput> {
        val inputs = mutableListOf<ProvablyFairPlayerSeedInput>()
        for (participant in participants) {
            if (participant.isBot) {
                continue
            }
            val input = ProvablyFairPlayerSeedInput()
            input.playerId = participant.id
            input.clientSeed = TokenSupport.requireClientSeed(participant.clientSeed)
            inputs.add(input)
        }
        return inputs
    }

    private fun scheduleDealerTipTimeout() {
        val round = state.round
        if (round == null || round.status != "complete" || round.dealerTipState?.pending != true || round.dealerTipState?.expiresAt == null) {
            return
        }
        dealerTipTimer?.cancel()
        val remainingMs =
            maxOf(0L, Instant.parse(round.dealerTipState!!.expiresAt).toEpochMilli() - clockProvider.now().toEpochMilli())
        logTimerScheduled("dealer_tip_timeout", remainingMs, round)
        dealerTipTimer =
            scheduler.schedule(remainingMs) {
                synchronized(this) {
                    dealerTipTimer = null
                    autoSkipDealerTip()
                }
            }
    }

    private fun autoSkipDealerTip() {
        val round = state.round
        if (round == null || round.status != "complete" || round.dealerTipState?.pending != true) {
            return
        }
        val winnerIndex = indexOfSeat(round, round.dealerTipState!!.winnerId)
        val winner = round.seats[winnerIndex]
        val hand = Engine.evaluateSeatHand(winner, round, config)
        val settlement = calculateSettlement(round, winner, 0)
        Engine.validateSettlementConsistency(round, settlement)
        finalizeRoundSettlement(winner, hand, settlement)
        persistState()
        emitState("dealer_tip_timeout")
    }

    private fun getSerializedDealerTipPrompt(round: RoundState, viewerId: String?): Map<String, Any?>? {
        if (round.dealerTipState == null || viewerId == null || viewerId != round.dealerTipState!!.winnerId) {
            return null
        }
        val payload = linkedMapOf<String, Any?>()
        payload["winnerId"] = round.dealerTipState!!.winnerId
        payload["winnerName"] = round.dealerTipState!!.winnerName
        payload["pending"] = round.dealerTipState!!.pending
        payload["winnerReceivableBeforeTip"] = round.dealerTipState!!.winnerReceivableBeforeTip
        payload["maxAmount"] = round.dealerTipState!!.maxAmount
        payload["expiresAt"] = round.dealerTipState!!.expiresAt
        payload["resolvedAt"] = round.dealerTipState!!.resolvedAt
        return payload
    }

    private fun resolveDeniedSideShow(round: RoundState, pending: SideShowRequest, deniedByTimeout: Boolean) {
        val requesterIndex = indexOfSeat(round, pending.requesterId)
        val requester = round.seats[requesterIndex]
        round.pendingSideShow = null
        clearExpiredSideShowResult(round)
        requester.lastAction = LastAction("sideshow-denied", pending.forcedRaiseAmount, clockProvider.nowIso())
        logAction(
            pending.targetId,
            "sideshow-denied",
            0,
            "${pending.targetName} denied ${pending.requesterName}'s side show after the request payment was made.",
        )
        round.message =
            if (deniedByTimeout) {
                "${pending.targetName} timed out on the side show request."
            } else {
                "${pending.targetName} denied the side show request."
            }
        advanceTurn(requesterIndex)
        updateVariantProgressAfterTurn(round, requester.id)
    }

    private fun resolveAcceptedSideShow(round: RoundState, actorIndex: Int, pending: SideShowRequest) {
        val requesterIndex = indexOfSeat(round, pending.requesterId)
        val requester = round.seats[requesterIndex]
        val target = round.seats[actorIndex]
        val loser = if (Engine.compareSeatHands(requester, target, round, config) <= 0) requester else target
        val winner = if (loser.id == requester.id) target else requester
        loser.eliminatedBySideshow = true
        loser.packed = true
        loser.lastAction = LastAction("sideshow-loss", 0, clockProvider.nowIso())
        winner.lastAction = LastAction("sideshow-accepted", 0, clockProvider.nowIso())
        round.pendingSideShow = null
        val result = SideShowResult()
        result.requesterId = requester.id
        result.targetId = target.id
        result.requesterName = requester.name
        result.targetName = target.name
        result.loserId = loser.id
        result.winnerId = winner.id
        result.status = "accepted"
        result.resolvedAt = clockProvider.nowIso()
        result.visibleToPlayerIds = mutableListOf(requester.id, target.id)
        val requesterReveal = SideShowSeatReveal()
        requesterReveal.playerId = requester.id
        requesterReveal.playerName = requester.name
        requesterReveal.cards = requester.cards.toMutableList()
        requesterReveal.flipperCard = resolveFlipperCard(requester)
        val targetReveal = SideShowSeatReveal()
        targetReveal.playerId = target.id
        targetReveal.playerName = target.name
        targetReveal.cards = target.cards.toMutableList()
        targetReveal.flipperCard = resolveFlipperCard(target)
        result.reveals = mutableListOf(requesterReveal, targetReveal)
        round.recentSideShowResult = result
        logAction(target.id, "sideshow-accepted", 0, "${target.name} accepted ${requester.name}'s side show. ${loser.name} packed.")
        val activeSeats = Engine.getActiveSeats(round)
        if (activeSeats.size == 1) {
            finishRound(activeSeats.first(), "Sideshow left one player standing.")
            return
        }
        round.message = "${loser.name} lost the side show."
        advanceTurn(if (requesterIndex >= 0) requesterIndex else actorIndex)
        updateVariantProgressAfterTurn(round, requester.id)
    }

    private fun advanceTurn(currentIndex: Int) {
        val round = state.round!!
        clearExpiredSideShowResult(round)
        var next = currentIndex
        repeat(round.seats.size) {
            next = (next + 1) % round.seats.size
            val candidate = round.seats[next]
            if (candidate.active && !candidate.packed) {
                round.activePlayerIndex = next
                round.message = "${candidate.name}'s turn."
                return
            }
        }
    }

    private fun scheduleStartCountdown() {
        val round = state.round
        if (round == null || round.status != "starting" || round.startCountdownEndsAt == null) {
            return
        }
        val remainingMs = maxOf(0L, Instant.parse(round.startCountdownEndsAt).toEpochMilli() - clockProvider.now().toEpochMilli())
        logTimerScheduled("round_start_countdown", remainingMs, round)
        val task =
            scheduler.schedule(remainingMs) {
                synchronized(this) {
                    val currentRound = state.round
                    if (currentRound == null || currentRound.status != "starting") {
                        return@synchronized
                    }
                    transitionRoundToDealing(currentRound)
                }
            }
        botTimers.add(task)
    }

    private fun transitionRoundToDealing(round: RoundState) {
        if (round.status != "starting") {
            return
        }
        round.status = "dealing"
        round.message = "Dealer is distributing cards."
        round.dealingStartedAt = clockProvider.nowIso()
        round.dealingEndsAt = clockProvider.isoFromMillis(clockProvider.now().toEpochMilli() + DEALING_ANIMATION_MS)
        persistState()
        emitState("round_dealing")
        scheduleDealingTransition()
    }

    private fun scheduleDealingTransition() {
        val round = state.round
        if (round == null || round.status != "dealing" || round.dealingEndsAt == null) {
            return
        }
        val remainingMs = maxOf(0L, Instant.parse(round.dealingEndsAt).toEpochMilli() - clockProvider.now().toEpochMilli())
        logTimerScheduled("round_dealing", remainingMs, round)
        val task =
            scheduler.schedule(remainingMs) {
                synchronized(this) {
                    val currentRound = state.round
                    if (currentRound == null || currentRound.status != "dealing") {
                        return@synchronized
                    }
                    activateRound(currentRound)
                }
            }
        botTimers.add(task)
    }

    private fun activateRound(round: RoundState) {
        if (round.status != "dealing") {
            return
        }
        round.status = "active"
        round.message = "Round started. ${round.seats[round.activePlayerIndex].name}'s turn."
        round.startCountdownStartedAt = null
        round.startCountdownEndsAt = null
        round.dealingStartedAt = null
        round.dealingEndsAt = null
        armTurnTimer(false)
        persistState()
        emitState("round_started")
        scheduleBotsIfNeeded()
    }

    private fun armTurnTimer(preserveExistingDeadline: Boolean) {
        val round = state.round
        if (round == null || round.status != "active") {
            return
        }
        val now = clockProvider.now().toEpochMilli()
        val existingStart = round.turnStartedAt?.let { Instant.parse(it).toEpochMilli() }
        val existingDeadline = round.turnDeadlineAt?.let { Instant.parse(it).toEpochMilli() }
        val startMs = if (preserveExistingDeadline && existingStart != null) existingStart else now
        val deadlineMs = if (preserveExistingDeadline && existingDeadline != null) existingDeadline else startMs + config.turnDurationMs
        val remainingMs = maxOf(0L, deadlineMs - now)
        round.turnDurationMs = config.turnDurationMs
        round.turnStartedAt = clockProvider.isoFromMillis(startMs)
        round.turnDeadlineAt = clockProvider.isoFromMillis(deadlineMs)
        turnTimer?.cancel()
        logTimerScheduled("turn_timeout", remainingMs, round)
        turnTimer =
            scheduler.schedule(remainingMs) {
                synchronized(this) {
                    turnTimer = null
                    handleTurnTimeout()
                }
            }
    }

    private fun handleTurnTimeout() {
        val round = state.round
        if (round == null || round.status != "active") {
            return
        }
        if (round.pendingSideShow != null) {
            if (config.variant.autoAcceptSideshow) {
                val targetIndex = indexOfSeat(round, round.pendingSideShow!!.targetId)
                resolveAcceptedSideShow(round, targetIndex, round.pendingSideShow!!)
            } else {
                resolveDeniedSideShow(round, round.pendingSideShow!!, true)
            }
            armTurnTimer(false)
            persistState()
            emitState("turn_timeout")
            scheduleBotsIfNeeded()
            return
        }
        val actorIndex = round.activePlayerIndex
        val seat = round.seats[actorIndex]
        if (seat.packed) {
            return
        }
        clearTimers()
        revealFlipperReserveCard(round, seat)
        seat.packed = true
        seat.lastAction = LastAction("timeout", 0, clockProvider.nowIso())
        logAction(seat.id, "timeout", 0, "${seat.name} ran out of time and packed.")
        val activeSeats = Engine.getActiveSeats(round)
        if (activeSeats.size == 1) {
            finishRound(activeSeats.first(), "${seat.name} timed out.")
            return
        }
        round.message = "${seat.name} ran out of time and packed."
        advanceTurn(actorIndex)
        updateVariantProgressAfterTurn(round, seat.id)
        armTurnTimer(false)
        persistState()
        emitState("turn_timeout")
        scheduleBotsIfNeeded()
    }

    private fun scheduleBotsIfNeeded() {
        val round = state.round
        if (round == null || round.status != "active") {
            return
        }
        if (round.pendingSideShow != null) {
            val respondingSeat = round.seats.firstOrNull { it.id == round.pendingSideShow!!.targetId }
            if (respondingSeat == null || !respondingSeat.isBot) {
                return
            }
            val delayMs = randomDelay()
            logTimerScheduled("bot_sideshow_action", delayMs, round)
            val task =
                scheduler.schedule(delayMs) {
                    synchronized(this) {
                        if (state.round == null ||
                            state.round!!.pendingSideShow == null ||
                            respondingSeat.id != state.round!!.pendingSideShow!!.targetId
                        ) {
                            return@synchronized
                        }
                        performAction(respondingSeat.id, decideBotSideShowResponse(respondingSeat), emptyMap())
                    }
                }
            botTimers.add(task)
            return
        }
        val activeSeat = round.seats[round.activePlayerIndex]
        if (!activeSeat.isBot) {
            return
        }
        val delayMs = randomDelay()
        logTimerScheduled("bot_action", delayMs, round)
        val task =
            scheduler.schedule(delayMs) {
                synchronized(this) {
                    if (state.round == null || state.round!!.status != "active") {
                        return@synchronized
                    }
                    val seat = state.round!!.seats[state.round!!.activePlayerIndex]
                    if (!seat.isBot) {
                        return@synchronized
                    }
                    performAction(seat.id, decideBotAction(seat), emptyMap())
                }
            }
        botTimers.add(task)
    }

    private fun logTimerScheduled(timerType: String, delayMs: Long, round: RoundState) {
        GameEventLog.info(
            "timer_scheduled",
            "timerType" to timerType,
            "delayMs" to delayMs,
            "tableType" to tableType,
            "lobbyId" to state.id,
            "roundId" to round.id,
        )
    }

    private fun decideBotAction(seat: SeatState): String =
        if (useExpertPublicBotPolicy()) {
            decideBotDecision(seat).chosenAction
        } else {
            decideLegacyBotAction(seat)
        }

    private fun decideBotDecision(seat: SeatState): BotDecisionContext =
        publicBotDecisionEngine.decideTurn(state.round!!, seat, state.round!!.activePlayerIndex)

    private fun decideBotSideShowResponse(seat: SeatState): String =
        if (useExpertPublicBotPolicy()) {
            decideBotPendingSideShowDecision(seat).chosenAction
        } else {
            if (randomSource.nextDouble() > 0.45) "sideshow_accept" else "sideshow_deny"
        }

    private fun decideBotPendingSideShowDecision(seat: SeatState): BotDecisionContext =
        publicBotDecisionEngine.decideSideShowResponse(state.round!!, seat)

    private fun decideLegacyBotAction(seat: SeatState): String {
        val round = state.round!!
        if (!seat.seen) {
            if (config.variant.id.equals("ak47", ignoreCase = true)) {
                return "see"
            }
            if (randomSource.nextDouble() > 0.72) {
                return "see"
            }
        }
        if (canBotChooseShow(round) && randomSource.nextDouble() > 0.65) {
            return "show"
        }
        if (seat.seen) {
            val evaluation = Engine.evaluateSeatHand(seat, round, config)
            if (config.variant.id.equals("ak47", ignoreCase = true) &&
                evaluation.category in 1..3 &&
                Engine.canShow(round)
            ) {
                return "show"
            }
            if (config.variant.id.equals("muflis", ignoreCase = true)) {
                val strongLowball = evaluation.category == 1 && evaluation.ranks.isNotEmpty() && evaluation.ranks.first() <= 9
                if (!strongLowball) {
                    return if (canBotChooseShow(round)) "show" else "pack"
                }
                if (canBotChooseShow(round)) {
                    return "show"
                }
                if (seat.lastAction?.type == "sideshow-denied") {
                    return "chaal"
                }
                if (round.recentSideShowResult?.winnerId == seat.id) {
                    return "raise"
                }
                if (Engine.canRequestSideshow(round, seat, round.activePlayerIndex)) {
                    return "sideshow"
                }
                return "chaal"
            }
            if (evaluation.category <= 1 && randomSource.nextDouble() > 0.55) {
                return "pack"
            }
            if (evaluation.category >= 2 && randomSource.nextDouble() > 0.45) {
                return "raise"
            }
            if (Engine.canRequestSideshow(round, seat, round.activePlayerIndex) && randomSource.nextDouble() > 0.60) {
                return "sideshow"
            }
            return "chaal"
        }
        if (randomSource.nextDouble() > 0.84) {
            return "pack"
        }
        if (randomSource.nextDouble() > 0.58) {
            return "raise"
        }
        return "blind"
    }

    private fun randomDelay(): Long =
        config.botActionDelayMs.min.toLong() +
            randomSource.nextInt(maxOf(1, config.botActionDelayMs.max - config.botActionDelayMs.min)).toLong()

    private fun canBotChooseShow(round: RoundState): Boolean =
        if (useExpertPublicBotPolicy()) {
            publicBotDecisionEngine.canChooseShow(round)
        } else {
            Engine.getActiveSeats(round).size == 2 && countCompletedNonBootActions(round) >= 4
        }

    private fun countCompletedNonBootActions(round: RoundState): Int = round.actionLog.count { it.actionType != "boot" }

    private fun useExpertPublicBotPolicy(): Boolean = tableType == "public_table" && config.botDecisionMode == "expert_public"

    private fun clearExpiredSideShowResult(round: RoundState) {
        val result = round.recentSideShowResult ?: return
        val resolvedAt = Instant.parse(result.resolvedAt).toEpochMilli()
        if (resolvedAt + SIDE_SHOW_RESULT_TTL_MS <= clockProvider.now().toEpochMilli()) {
            round.recentSideShowResult = null
        }
    }

    private fun getSerializedPendingSideShow(round: RoundState, viewerId: String?): Map<String, Any?>? {
        val pending = round.pendingSideShow ?: return null
        val viewerRole =
            when {
                viewerId == null -> null
                viewerId == pending.requesterId -> "requester"
                viewerId == pending.targetId -> "target"
                else -> "observer"
            }
        val payload = linkedMapOf<String, Any?>()
        payload["status"] = pending.status
        payload["requesterId"] = pending.requesterId
        payload["targetId"] = pending.targetId
        payload["requesterName"] = pending.requesterName
        payload["targetName"] = pending.targetName
        payload["requestAmount"] = pending.forcedRaiseAmount
        payload["forcedRaiseAmount"] = pending.forcedRaiseAmount
        payload["requestedAt"] = pending.requestedAt
        payload["expiresAt"] = pending.expiresAt
        payload["viewerRole"] = viewerRole
        payload["canRespond"] = viewerRole == "target"
        return payload
    }

    private fun getSerializedSideShowResult(round: RoundState, viewerId: String?): Map<String, Any?>? {
        val result = round.recentSideShowResult
        if (result == null || viewerId == null || !result.visibleToPlayerIds.contains(viewerId)) {
            return null
        }
        val payload = linkedMapOf<String, Any?>()
        payload["requesterId"] = result.requesterId
        payload["targetId"] = result.targetId
        payload["requesterName"] = result.requesterName
        payload["targetName"] = result.targetName
        payload["loserId"] = result.loserId
        payload["winnerId"] = result.winnerId
        payload["status"] = result.status
        payload["resolvedAt"] = result.resolvedAt
        payload["reveals"] =
            result.reveals.map { reveal ->
                mapOf(
                    "playerId" to reveal.playerId,
                    "playerName" to reveal.playerName,
                    "cards" to reveal.cards,
                    "flipperCard" to reveal.flipperCard,
                )
            }
        return payload
    }

    private fun resolveFlipperCard(seat: SeatState): Card? {
        if (config.variant.publicCardMode != "flipper_blue_card") {
            return null
        }
        return seat.reserveCards.firstOrNull() ?: seat.publicCards.firstOrNull()
    }

    private fun logAction(playerId: String, type: String, amount: Int, note: String) {
        val round = state.round ?: return
        val action = ActionLogEntry()
        action.id = idGenerator.newId()
        action.roundId = round.id
        action.playerId = playerId
        action.actionType = type
        action.amount = amount
        action.note = note
        action.timestamp = clockProvider.nowIso()
        round.lastAction = action
        round.actionLog.add(action)
        GameEventLog.info(
            "game_action",
            "tableType" to tableType,
            "lobbyId" to state.id,
            "roundId" to round.id,
            "playerId" to playerId,
            "actionType" to type,
            "amount" to amount,
        )
    }

    private fun syncBankrolls(round: RoundState) {
        state.playerBankrolls = nextBankrollState(round.seats)
    }

    private fun nextBankrollState(activeSeats: List<SeatState>): MutableList<PlayerBankroll> {
        val existingBalances = LinkedHashMap<String, Int>()
        for (bankroll in state.playerBankrolls) {
            existingBalances[bankroll.id] = bankroll.balance
        }
        val bankrolls = mutableListOf<PlayerBankroll>()
        val activeIds = LinkedHashSet<String>()
        for (seat in activeSeats) {
            val bankroll = PlayerBankroll()
            bankroll.id = seat.id
            bankroll.balance = seat.balance
            bankrolls.add(bankroll)
            activeIds.add(seat.id)
        }
        if (tableType == "public_table" && state.publicSeating?.botSlots != null) {
            for (slot in state.publicSeating!!.botSlots) {
                if (slot.id.isBlank() || activeIds.contains(slot.id)) {
                    continue
                }
                val preservedBalance = existingBalances[slot.id] ?: continue
                val bankroll = PlayerBankroll()
                bankroll.id = slot.id
                bankroll.balance = preservedBalance
                bankrolls.add(bankroll)
            }
        }
        return bankrolls
    }

    private fun emitState(type: String) {
        GameEventLog.info(
            type,
            "tableType" to tableType,
            "lobbyId" to state.id,
            "roundId" to state.round?.id,
            "roundStatus" to state.round?.status,
        )
        val event = TableEvent(type, getClientState(null))
        listeners.values.forEach { it(event) }
    }

    private fun persistState() {
        if (store == null) {
            return
        }
        refreshPersistenceMetadata()
        try {
            state = store.saveTable(state)
            state.config = config
            GameEventLog.info("table_persisted", "tableType" to tableType, "lobbyId" to state.id, "roundId" to state.round?.id)
        } catch (error: Exception) {
            GameEventLog.error("table_persistence_failed", error, "tableType" to tableType, "lobbyId" to state.id, "roundId" to state.round?.id)
            throw error
        }
    }

    private fun refreshPersistenceMetadata() {
        val now = clockProvider.nowIso()
        if (state.createdAt == null) {
            state.createdAt = now
        }
        state.updatedAt = now
        if (state.tableType == "public_table") {
            state.leaseOwner = instanceId
            state.leaseExpiresAt = extendLease(now)
        }
    }

    private fun extendLease(now: String): String =
        clockProvider.isoFromMillis(Instant.parse(now).toEpochMilli() + AGGREGATE_LEASE_DURATION_MS)

    private companion object {
        const val ROUND_START_COUNTDOWN_MS = 5_000L
        const val DEALING_ANIMATION_MS = 1_800L
        const val SIDE_SHOW_RESULT_TTL_MS = 10_000L
        const val NEXT_ROUND_DECISION_WINDOW_MS = 15_000L
        const val AGGREGATE_LEASE_DURATION_MS = 30_000L
    }
}
