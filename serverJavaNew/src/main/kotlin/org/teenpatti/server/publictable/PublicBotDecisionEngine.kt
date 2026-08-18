package org.teenpatti.server.publictable

import org.teenpatti.server.common.RandomSource
import org.teenpatti.server.config.GameConfig
import org.teenpatti.server.game.*
import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal class PublicBotDecisionEngine(
    private val config: GameConfig,
    private val randomSource: RandomSource,
) {
    private val policy = resolvePolicy(config)

    fun canChooseShow(round: RoundState?): Boolean = round != null && Engine.canShow(round)

    fun decideTurn(round: RoundState, seat: SeatState, actorIndex: Int): BotDecisionContext {
        val context = BotDecisionContext()
        context.playerId = seat.id
        context.mode = policy.decisionMode
        context.activePlayerCount = Engine.getActiveSeats(round).size
        context.potAmount = round.potAmount
        context.currentStake = round.currentStake
        context.minCallAmount = Engine.getPlayerMinimumStake(round, seat, config)
        context.raiseAmount = Engine.getPlayerRaiseStake(round, seat, config)
        context.showAmount = if (canChooseShow(round)) Engine.getPlayerMinimumStake(round, seat, config) else 0
        context.visibleState = buildVisibleState(round, seat)
        context.legalActions = determineLegalTurnActions(round, seat, actorIndex, context)
        context.opponentRanges = buildOpponentRanges(round, seat.id)
        context.aggressiveSignal =
            hasAggressiveSignal(round, seat.id) ||
                round.currentStake >= config.bootAmount * 2 ||
                round.potAmount >= config.bootAmount * 8
        context.pressured = context.aggressiveSignal || isQuarterStackPressure(context.minCallAmount, seat.balance)

        if (!seat.seen) {
            chooseUnseenTurn(round, seat, context)
            return context
        }

        chooseSeenTurn(round, seat, actorIndex, context)
        return context
    }

    fun decideSideShowResponse(round: RoundState, seat: SeatState): BotDecisionContext {
        val context = BotDecisionContext()
        context.playerId = seat.id
        context.mode = policy.decisionMode
        context.activePlayerCount = Engine.getActiveSeats(round).size
        context.potAmount = round.potAmount
        context.currentStake = round.currentStake
        context.minCallAmount = Engine.getPlayerMinimumStake(round, seat, config)
        context.raiseAmount = Engine.getPlayerRaiseStake(round, seat, config)
        context.visibleState = buildVisibleState(round, seat)
        context.opponentRanges = buildOpponentRanges(round, seat.id)
        context.legalActions =
            if (config.variant.autoAcceptSideshow) mutableListOf("sideshow_accept") else mutableListOf("sideshow_accept", "sideshow_deny")

        val pending = round.pendingSideShow
        if (pending == null) {
            context.chosenAction = "sideshow_deny"
            context.rationale = "No pending side show request was available."
            addScore(context, "sideshow_deny", 0.0, 0.0, 0.0, context.rationale)
            return context
        }

        val requester = findSeat(round, pending.requesterId)
        val deadline = System.nanoTime() + max(1, policy.maxDecisionTimeMs).toLong() * 1_000_000L
        val result = estimatePairwiseWinProbability(round, seat, requester, context, deadline)
        context.winProbability = result.winProbability
        context.sideShowWinProbability = result.winProbability
        context.simulationTimedOut = result.timedOut

        val acceptScore = result.winProbability - 0.62
        val denyScore = 0.62 - result.winProbability
        addScore(
            context,
            "sideshow_accept",
            acceptScore,
            expectedValue(round.potAmount, 0, result.winProbability, 0.0),
            result.winProbability,
            "Accept when pairwise win probability clears the side-show threshold.",
        )
        if (!config.variant.autoAcceptSideshow) {
            addScore(
                context,
                "sideshow_deny",
                denyScore,
                0.0,
                1.0 - result.winProbability,
                "Deny when the requester range is too strong.",
            )
        }

        if (result.timedOut) {
            val evaluation = evaluateOwnSeenHand(seat, context.visibleState)
            context.fallbackUsed = true
            context.chosenAction =
                if (config.variant.autoAcceptSideshow) "sideshow_accept" else if (evaluation.category >= 2) "sideshow_accept" else "sideshow_deny"
            context.rationale = "Simulation timed out, so the bot fell back to a deterministic seen-hand response."
            return context
        }

        context.chosenAction =
            if (config.variant.autoAcceptSideshow) "sideshow_accept" else if (result.winProbability >= 0.62) "sideshow_accept" else "sideshow_deny"
        context.rationale =
            if (result.winProbability >= 0.62) {
                "The requester range is weak enough to accept the side show."
            } else {
                "The requester range is strong enough that denying the side show is preferable."
            }
        return context
    }

    private fun chooseUnseenTurn(round: RoundState, seat: SeatState, context: BotDecisionContext) {
        val blindActions = countActions(round, seat.id, "blind")
        val shouldSee =
            context.legalActions.contains("see") &&
                (context.aggressiveSignal || blindActions >= max(0, policy.headsUpSeeAfterBlindTurns))
        val shouldPack = context.legalActions.contains("pack") && isQuarterStackPressure(context.minCallAmount, seat.balance)

        addScore(
            context,
            "see",
            if (shouldSee) 3.0 else 0.25,
            0.0,
            0.0,
            "See once the table applies pressure or the blind threshold is reached.",
        )
        addScore(
            context,
            "blind",
            if (!shouldSee && !shouldPack && context.legalActions.contains("blind")) 1.0 else -1.0,
            0.0,
            0.0,
            "Continue blind while the table remains calm and the stake is cheap.",
        )
        addScore(
            context,
            "pack",
            if (shouldPack) 2.0 else -1.0,
            0.0,
            0.0,
            "Pack unseen only when the current call already consumes too much of the stack.",
        )

        when {
            shouldSee -> {
                context.chosenAction = "see"
                context.rationale = "Pressure is high enough that the bot should switch from blind play to seen play."
            }

            shouldPack -> {
                context.chosenAction = "pack"
                context.rationale = "The minimum continue amount is too large relative to the remaining stack."
            }

            else -> {
                context.chosenAction = if (context.legalActions.contains("blind")) "blind" else "pack"
                context.rationale = "No pressure signal exists yet, so the bot stays blind at the cheapest legal action."
            }
        }
    }

    private fun chooseSeenTurn(round: RoundState, seat: SeatState, actorIndex: Int, context: BotDecisionContext) {
        val activeOpponents = Engine.getActiveSeats(round).filter { it.id != seat.id }
        val deadline = System.nanoTime() + max(1, policy.maxDecisionTimeMs).toLong() * 1_000_000L
        val result =
            if (activeOpponents.size == 1) {
                estimatePairwiseWinProbability(round, seat, activeOpponents.first(), context, deadline)
            } else {
                estimateMultiwayWinProbability(round, seat, activeOpponents, context, deadline)
            }

        context.winProbability = result.winProbability
        context.simulationTimedOut = result.timedOut

        if (result.timedOut) {
            chooseSeenFallback(round, seat, context)
            return
        }

        val headsUp = activeOpponents.size == 1
        val weakThreshold = if (headsUp) 0.33 else 0.24
        val raiseThreshold = if (headsUp) 0.68 else 0.55
        val callEv =
            if (context.legalActions.contains("chaal")) {
                expectedValue(round.potAmount, context.minCallAmount, result.winProbability, 0.0)
            } else {
                Double.NEGATIVE_INFINITY
            }
        val raiseEv =
            if (context.legalActions.contains("raise")) {
                expectedValue(
                    round.potAmount,
                    context.raiseAmount,
                    result.winProbability,
                    max(0, context.raiseAmount - context.minCallAmount) * 0.20,
                )
            } else {
                Double.NEGATIVE_INFINITY
            }
        val showEv =
            if (context.legalActions.contains("show")) {
                expectedValue(round.potAmount, context.showAmount, result.winProbability, 0.0)
            } else {
                Double.NEGATIVE_INFINITY
            }

        addScore(context, "pack", -callEv, 0.0, 1.0 - result.winProbability, "Pack when continuing is negative-EV and the hand is too weak.")
        addScore(context, "chaal", callEv, callEv, result.winProbability, "Use chaal as the baseline continue action.")
        addScore(context, "raise", raiseEv, raiseEv, result.winProbability, "Raise when a strong seen hand can press the range advantage.")
        addScore(context, "show", showEv, showEv, result.winProbability, "Show only when heads-up and at least as good as continuing.")

        var sideShowEv = Double.NEGATIVE_INFINITY
        if (context.legalActions.contains("sideshow")) {
            val target = Engine.getPreviousActiveSeat(round, actorIndex)
            if (target != null && target.seen) {
                val sideShowResult = estimatePairwiseWinProbability(round, seat, target, context, deadline)
                context.sideShowWinProbability = sideShowResult.winProbability
                sideShowEv = expectedValue(round.potAmount, context.minCallAmount, sideShowResult.winProbability, round.potAmount * 0.05)
                addScore(
                    context,
                    "sideshow",
                    sideShowEv,
                    sideShowEv,
                    sideShowResult.winProbability,
                    "Use side show when the previous seen player range is weak enough to justify isolating them.",
                )
            }
        }

        when {
            context.legalActions.contains("show") &&
                result.winProbability >= 0.52 &&
                showEv >= maxOf(callEv, raiseEv, sideShowEv) -> {
                context.chosenAction = "show"
                context.rationale = "Heads-up showdown is the highest-value legal action for this range advantage."
            }

            context.legalActions.contains("raise") &&
                result.winProbability >= raiseThreshold &&
                isAffordableAggression(context.raiseAmount, seat.balance) -> {
                context.chosenAction = "raise"
                context.rationale = "The seen hand clears the raise threshold and the raise size is still stack-efficient."
            }

            context.legalActions.contains("sideshow") &&
                context.sideShowWinProbability >= 0.62 &&
                sideShowEv >= max(callEv, raiseEv) -> {
                context.chosenAction = "sideshow"
                context.rationale = "The previous seen player range is weak enough that a side show dominates a normal continue."
            }

            callEv < 0.0 && result.winProbability < weakThreshold -> {
                context.chosenAction = "pack"
                context.rationale = "Continuing is negative-EV and the seen hand sits below the weakness threshold."
            }

            else -> {
                context.chosenAction = if (context.legalActions.contains("chaal")) "chaal" else "pack"
                context.rationale = "The hand is not strong enough to raise but still profitable enough to continue at the minimum price."
            }
        }
    }

    private fun chooseSeenFallback(round: RoundState, seat: SeatState, context: BotDecisionContext) {
        val evaluation = evaluateOwnSeenHand(seat, context.visibleState)
        context.fallbackUsed = true
        addScore(context, "pack", 0.0, 0.0, 0.0, "Fallback pack score.")
        addScore(context, "chaal", 0.0, 0.0, 0.0, "Fallback chaal score.")
        addScore(context, "raise", 0.0, 0.0, 0.0, "Fallback raise score.")
        addScore(context, "show", 0.0, 0.0, 0.0, "Fallback show score.")

        when {
            context.legalActions.contains("show") && shouldFallbackShow(evaluation, context.pressured) -> {
                context.chosenAction = "show"
                context.rationale = "Simulation timed out, so the bot took the available showdown with a sufficiently strong seen hand."
            }

            evaluation.category >= 2 && context.legalActions.contains("raise") -> {
                context.chosenAction = "raise"
                context.rationale = "Simulation timed out, so the bot fell back to raising with Pair or better."
            }

            evaluation.category <= 1 &&
                context.pressured &&
                context.legalActions.contains("pack") &&
                isWeakHighCard(evaluation) -> {
                context.chosenAction = "pack"
                context.rationale = "Simulation timed out, so the bot folded a weak high-card hand under pressure."
            }

            else -> {
                context.chosenAction = if (context.legalActions.contains("chaal")) "chaal" else "pack"
                context.rationale = "Simulation timed out, so the bot fell back to the cheapest legal continue action."
            }
        }
    }

    private fun buildVisibleState(round: RoundState, seat: SeatState): BotVisibleState {
        val visibleState = BotVisibleState()
        visibleState.seenOwnCards = seat.seen
        val knownIds = linkedSetOf<String>()
        visibleState.wildcardRanks = linkedSetOf<String>().apply {
            addAll(config.variant.wildcardRanks)
            addAll(round.variantState?.wildcardRanks ?: emptyList())
        }.toMutableList()
        if (seat.seen) {
            visibleState.knownSelfCards = copyCards(seat.cards)
            for (card in seat.cards) {
                knownIds.add(card.id)
            }
        }
        for (card in seat.publicCards) {
            knownIds.add(card.id)
        }
        val revealedSharedCards = round.variantState?.sharedJokerCards?.take(round.variantState?.revealedSharedJokerCount ?: 0) ?: emptyList()
        for (card in revealedSharedCards) {
            knownIds.add(card.id)
        }
        val sideShowResult = round.recentSideShowResult
        if (sideShowResult != null && sideShowResult.visibleToPlayerIds.contains(seat.id)) {
            for (reveal in sideShowResult.reveals) {
                val revealCards = copyCards(reveal.cards)
                visibleState.revealedHands[reveal.playerId] = revealCards
                for (card in revealCards) {
                    knownIds.add(card.id)
                }
            }
        }
        visibleState.knownUnavailableCardIds = knownIds.toMutableList()
        return visibleState
    }

    private fun determineLegalTurnActions(round: RoundState, seat: SeatState, actorIndex: Int, context: BotDecisionContext): MutableList<String> {
        val legal = mutableListOf<String>()
        if (!seat.seen && round.variantState?.forceBlindActive != true) {
            legal.add("see")
        }
        if (canPay(round, seat, context.minCallAmount)) {
            legal.add(if (seat.seen) "chaal" else "blind")
        }
        if (canPay(round, seat, context.raiseAmount)) {
            legal.add("raise")
        }
        legal.add("pack")
        if (
            seat.seen &&
            (round.variantState?.showUnlocked != false) &&
            (!(config.variant.showRequiresAllSeen) || Engine.getActiveSeats(round).all { it.seen }) &&
            Engine.canRequestSideshow(round, seat, actorIndex) &&
            canPay(round, seat, context.minCallAmount)
        ) {
            legal.add("sideshow")
        }
        if (
            seat.seen &&
            (round.variantState?.showUnlocked != false) &&
            (!(config.variant.showRequiresAllSeen) || Engine.getActiveSeats(round).all { it.seen }) &&
            canChooseShow(round) &&
            canPay(round, seat, context.showAmount)
        ) {
            legal.add("show")
        }
        return legal
    }

    private fun canPay(round: RoundState, seat: SeatState, amount: Int): Boolean {
        if (amount <= 0) {
            return true
        }
        if (amount < config.minStake || amount > config.maxStake * config.blindSeenMultiplier) {
            return false
        }
        if (seat.balance < amount) {
            return false
        }
        val potRoom = maxOf(0, config.maxPotAmount - round.potAmount)
        return potRoom > 0 && minOf(amount, potRoom) <= seat.balance
    }

    private fun hasAggressiveSignal(round: RoundState, botId: String): Boolean {
        for (action in round.actionLog) {
            if (botId == action.playerId || action.playerId == "system") {
                continue
            }
            if (action.actionType == "raise" || action.actionType == "sideshow-requested") {
                return true
            }
        }
        return false
    }

    private fun countActions(round: RoundState, playerId: String, actionType: String): Int =
        round.actionLog.count { it.playerId == playerId && it.actionType == actionType }

    private fun buildOpponentRanges(round: RoundState, botId: String): MutableList<OpponentRangeModel> {
        val byPlayer = LinkedHashMap<String, OpponentRangeModel>()
        val seenByPlayer = hashMapOf<String, Boolean>()
        for (seat in round.seats) {
            if (!seat.active || seat.packed || botId == seat.id) {
                continue
            }
            val range = OpponentRangeModel()
            range.playerId = seat.id
            range.currentlySeen = seat.seen
            byPlayer[seat.id] = range
            seenByPlayer[seat.id] = false
        }
        for (action in round.actionLog) {
            val range = byPlayer[action.playerId] ?: continue
            when (action.actionType) {
                "see" -> {
                    range.seeCount++
                    seenByPlayer[action.playerId] = true
                }

                "chaal" -> range.chaalCount++
                "raise" -> {
                    if (seenByPlayer[action.playerId] == true) {
                        range.seenRaiseCount++
                    } else {
                        range.blindRaiseCount++
                    }
                }

                "sideshow-requested" -> range.sideshowRequestCount++
                "sideshow-accepted" -> range.sideshowAcceptCount++
            }
        }
        return byPlayer.values.toMutableList()
    }

    private fun estimatePairwiseWinProbability(
        round: RoundState,
        seat: SeatState,
        opponent: SeatState?,
        context: BotDecisionContext,
        deadline: Long,
    ): SimulationResult {
        val result = SimulationResult()
        if (opponent == null) {
            result.winProbability = 0.0
            return result
        }
        val ownEvaluation = evaluateOwnSeenHand(seat, context.visibleState)
        val range = rangeForOpponent(context, opponent.id)
        val candidateDeck = candidateDeck(context.visibleState.knownUnavailableCardIds)

        var winWeight = 0.0
        var tieWeight = 0.0
        var totalWeight = 0.0
        var samples = 0

        for (first in 0 until candidateDeck.size - 2) {
            for (second in first + 1 until candidateDeck.size - 1) {
                for (third in second + 1 until candidateDeck.size) {
                    if ((samples and 255) == 0 && System.nanoTime() > deadline) {
                        result.timedOut = true
                        result.winProbability = if (totalWeight <= 0.0) 0.0 else (winWeight + (tieWeight * 0.5)) / totalWeight
                        if (range != null) {
                            range.candidateHands = samples
                            range.totalWeight = totalWeight
                            range.estimatedStrength = result.winProbability
                        }
                        return result
                    }
                    val opponentHand = listOf(candidateDeck[first], candidateDeck[second], candidateDeck[third])
                    val opponentEvaluation =
                        evaluateCandidateHand(opponentHand, context.visibleState.knownUnavailableCardIds, context.visibleState.wildcardRanks)
                    val weight = rangeWeight(range, opponentEvaluation)
                    val comparison = Engine.compareEvaluations(ownEvaluation, opponentEvaluation, config)
                    totalWeight += weight
                    if (comparison > 0) {
                        winWeight += weight
                    } else if (comparison == 0) {
                        tieWeight += weight
                    }
                    samples++
                }
            }
        }

        result.winProbability = if (totalWeight <= 0.0) 0.0 else (winWeight + (tieWeight * 0.5)) / totalWeight
        if (range != null) {
            range.candidateHands = samples
            range.totalWeight = totalWeight
            range.estimatedStrength = result.winProbability
        }
        return result
    }

    private fun estimateMultiwayWinProbability(
        round: RoundState,
        seat: SeatState,
        opponents: List<SeatState>,
        context: BotDecisionContext,
        deadline: Long,
    ): SimulationResult {
        val result = SimulationResult()
        val ownEvaluation = evaluateOwnSeenHand(seat, context.visibleState)
        val baseDeck = candidateDeck(context.visibleState.knownUnavailableCardIds)
        var winWeight = 0.0
        var tieWeight = 0.0
        var totalWeight = 0.0
        val samples = max(1, policy.maxSimulations)

        for (sample in 0 until samples) {
            if ((sample and 31) == 0 && System.nanoTime() > deadline) {
                result.timedOut = true
                break
            }
            val workingDeck = baseDeck.toMutableList()
            var sampleWeight = 1.0
            var loss = false
            var tie = false

            for (opponent in opponents) {
                val opponentHand = drawHand(workingDeck)
                if (opponentHand.size < 3) {
                    result.timedOut = true
                    break
                }
                val opponentEvaluation =
                    evaluateCandidateHand(opponentHand, context.visibleState.knownUnavailableCardIds, context.visibleState.wildcardRanks)
                sampleWeight *= rangeWeight(rangeForOpponent(context, opponent.id), opponentEvaluation)
                val comparison = Engine.compareEvaluations(ownEvaluation, opponentEvaluation, config)
                if (comparison < 0) {
                    loss = true
                } else if (comparison == 0) {
                    tie = true
                }
            }
            if (result.timedOut) {
                break
            }
            totalWeight += sampleWeight
            if (!loss && !tie) {
                winWeight += sampleWeight
            } else if (!loss) {
                tieWeight += sampleWeight
            }
        }

        result.winProbability = if (totalWeight <= 0.0) 0.0 else (winWeight + (tieWeight * 0.5)) / totalWeight
        return result
    }

    private fun drawHand(deck: MutableList<Card>): MutableList<Card> {
        val hand = mutableListOf<Card>()
        repeat(3) {
            if (deck.isNotEmpty()) {
                hand.add(deck.removeAt(randomSource.nextInt(deck.size)))
            }
        }
        return hand
    }

    private fun evaluateOwnSeenHand(seat: SeatState, visibleState: BotVisibleState): EvaluatedHand {
        if (config.variant.publicCardMode == "flipper_blue_card") {
            val flipperCards = if (seat.reserveCards.isNotEmpty()) seat.reserveCards else seat.publicCards
            return Engine.evaluateFlipperHand(seat.cards, flipperCards, config)
        }
        val unavailable = visibleState.knownUnavailableCardIds.toMutableSet()
        for (card in seat.cards) {
            unavailable.remove(card.id)
        }
        return Engine.evaluateHand(seat.cards, config, unavailable, visibleState.wildcardRanks.toSet())
    }

    private fun evaluateCandidateHand(
        cards: List<Card>,
        knownUnavailableCardIds: List<String>,
        wildcardRanks: List<String>,
    ): EvaluatedHand {
        val unavailable = knownUnavailableCardIds.toMutableSet()
        for (card in cards) {
            unavailable.remove(card.id)
        }
        return Engine.evaluateHand(cards, config, unavailable, wildcardRanks.toSet())
    }

    private fun candidateDeck(knownUnavailableCardIds: List<String>): List<Card> {
        val blocked = knownUnavailableCardIds.toHashSet()
        return Engine.createDeck().filter { !blocked.contains(it.id) }
    }

    private fun rangeForOpponent(context: BotDecisionContext, playerId: String): OpponentRangeModel? =
        context.opponentRanges.firstOrNull { it.playerId == playerId }

    private fun rangeWeight(range: OpponentRangeModel?, hand: EvaluatedHand): Double {
        if (range == null) {
            return 1.0
        }
        val category = max(1, min(hand.category, 6))
        var weight = 1.0
        if (range.currentlySeen) {
            weight *= if (category == 1) 0.97 else 1.05
        }
        weight *= SEE_FACTORS[category].pow(range.seeCount)
        weight *= CHAAL_FACTORS[category].pow(range.chaalCount)
        weight *= SEEN_RAISE_FACTORS[category].pow(range.seenRaiseCount)
        weight *= BLIND_RAISE_FACTORS[category].pow(range.blindRaiseCount)
        weight *= SIDESHOW_FACTORS[category].pow(range.sideshowRequestCount + range.sideshowAcceptCount)
        if (hand.ranks.isNotEmpty()) {
            weight *= 1.0 + max(0, hand.ranks.first() - 10) * 0.01
        }
        return max(0.01, weight)
    }

    private class SimulationResult {
        var winProbability = 0.0
        var timedOut = false
    }

    companion object {
        private val SEE_FACTORS = doubleArrayOf(0.0, 0.92, 1.04, 1.12, 1.22, 1.34, 1.48)
        private val CHAAL_FACTORS = doubleArrayOf(0.0, 0.90, 1.10, 1.20, 1.32, 1.44, 1.58)
        private val SEEN_RAISE_FACTORS = doubleArrayOf(0.0, 0.72, 1.18, 1.34, 1.52, 1.72, 1.92)
        private val BLIND_RAISE_FACTORS = doubleArrayOf(0.0, 0.96, 1.04, 1.08, 1.14, 1.18, 1.24)
        private val SIDESHOW_FACTORS = doubleArrayOf(0.0, 0.78, 1.20, 1.38, 1.54, 1.72, 1.90)

        private fun isQuarterStackPressure(minCallAmount: Int, balance: Int): Boolean = balance > 0 && minCallAmount >= (balance * 0.25)

        private fun isAffordableAggression(amount: Int, balance: Int): Boolean = balance > 0 && amount <= balance * 0.18

        private fun isWeakHighCard(evaluation: EvaluatedHand): Boolean = evaluation.category == 1 && evaluation.ranks.isNotEmpty() && evaluation.ranks.first() <= 11

        private fun shouldFallbackShow(evaluation: EvaluatedHand, pressured: Boolean): Boolean {
            if (evaluation.category >= 2) {
                return true
            }
            if (!pressured || evaluation.category != 1 || evaluation.ranks.size < 2) {
                return false
            }
            return evaluation.ranks.first() >= 13 && evaluation.ranks[1] >= 8
        }

        private fun expectedValue(potAmount: Int, cost: Int, winProbability: Double, bonus: Double): Double =
            (winProbability * (potAmount + cost)) - cost + bonus

        private fun addScore(
            context: BotDecisionContext,
            action: String,
            score: Double,
            expectedValue: Double,
            winProbability: Double,
            rationale: String,
        ) {
            val item = BotActionScore()
            item.action = action
            item.score = score
            item.expectedValue = expectedValue
            item.winProbability = winProbability
            item.rationale = rationale
            context.actionScores.add(item)
        }

        private fun resolvePolicy(config: GameConfig): BotPolicyConfig {
            val next = BotPolicyConfig()
            next.decisionMode = config.botDecisionMode ?: "expert_public"
            next.maxSimulations = if (config.botMaxSimulations > 0) config.botMaxSimulations else 1500
            next.maxDecisionTimeMs = if (config.botMaxDecisionTimeMs > 0) config.botMaxDecisionTimeMs else 40
            next.headsUpSeeAfterBlindTurns = if (config.botHeadsUpSeeAfterBlindTurns >= 0) config.botHeadsUpSeeAfterBlindTurns else 1
            return next
        }

        private fun findSeat(round: RoundState, playerId: String): SeatState? = round.seats.firstOrNull { it.id == playerId }

        private fun copyCards(cards: List<Card>): MutableList<Card> = cards.mapTo(mutableListOf()) { Card(it.id, it.suit, it.rank, it.value, it.hidden) }
    }
}
