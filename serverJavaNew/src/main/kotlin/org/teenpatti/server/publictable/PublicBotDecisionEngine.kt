package org.teenpatti.server.publictable

import org.teenpatti.server.common.RandomSource
import org.teenpatti.server.config.GameConfig
import org.teenpatti.server.game.*
import org.teenpatti.server.publictable.bots.Ak47BotPolicy
import org.teenpatti.server.publictable.bots.BotDecisionSupport
import org.teenpatti.server.publictable.bots.BotSimulationAdvisor
import org.teenpatti.server.publictable.bots.BotVariantPolicy
import org.teenpatti.server.publictable.bots.ClassicBotPolicy
import org.teenpatti.server.publictable.bots.FlipperBotPolicy
import org.teenpatti.server.publictable.bots.JhanduBotPolicy
import org.teenpatti.server.publictable.bots.MuflisBotPolicy
import java.util.LinkedHashMap
import kotlin.math.max

internal class PublicBotDecisionEngine(
    private val config: GameConfig,
    private val randomSource: RandomSource,
) {
    private val policy = resolvePolicy(config)
    private val simulationAdvisor = BotSimulationAdvisor(config, randomSource, policy)
    private val variantPolicies: List<BotVariantPolicy> =
        listOf(
            Ak47BotPolicy(config),
            MuflisBotPolicy(config),
            ClassicBotPolicy(config),
            FlipperBotPolicy(config),
            JhanduBotPolicy(simulationAdvisor),
        )

    private fun activeVariantPolicy(): BotVariantPolicy? =
        variantPolicies.firstOrNull { it.supports(config.variant.id) }

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

        val requester = BotDecisionSupport.findSeat(round, pending.requesterId)
        val deadline = System.nanoTime() + max(1, policy.maxDecisionTimeMs).toLong() * 1_000_000L
        val result = simulationAdvisor.estimatePairwiseWinProbability(round, seat, requester, context, deadline)
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
            val variantPolicy = activeVariantPolicy()
            context.chosenAction =
                when {
                    config.variant.autoAcceptSideshow -> "sideshow_accept"
                    variantPolicy?.sideShowTimeoutAction(evaluation) != null ->
                        variantPolicy.sideShowTimeoutAction(evaluation)!!
                    evaluation.category >= 2 -> "sideshow_accept"
                    else -> "sideshow_deny"
                }
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
        if (activeVariantPolicy()?.chooseUnseenTurn(round, seat, context) == true) {
            return
        }

        simulationAdvisor.chooseUnseenTurn(round, seat, context, variantLabel = "")
    }

    private fun chooseSeenTurn(round: RoundState, seat: SeatState, actorIndex: Int, context: BotDecisionContext) {
        if (activeVariantPolicy()?.chooseSeenTurn(round, seat, context, actorIndex) == true) {
            return
        }

        simulationAdvisor.chooseSeenTurn(round, seat, actorIndex, context, variantLabel = "")
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
                reveal.flipperCard?.let { flipperCard ->
                    knownIds.add(flipperCard.id)
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

    private fun evaluateOwnSeenHand(seat: SeatState, visibleState: BotVisibleState): EvaluatedHand =
        BotDecisionSupport.evaluateOwnSeenHand(config, seat, visibleState)

    companion object {
        private fun isQuarterStackPressure(minCallAmount: Int, balance: Int): Boolean = balance > 0 && minCallAmount >= (balance * 0.25)

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
            BotDecisionSupport.addScore(context, action, score, expectedValue, winProbability, rationale)
        }

        private fun resolvePolicy(config: GameConfig): BotPolicyConfig {
            val next = BotPolicyConfig()
            next.decisionMode = config.botDecisionMode ?: "expert_public"
            next.maxSimulations = if (config.botMaxSimulations > 0) config.botMaxSimulations else 1500
            next.maxDecisionTimeMs = if (config.botMaxDecisionTimeMs > 0) config.botMaxDecisionTimeMs else 40
            next.headsUpSeeAfterBlindTurns = if (config.botHeadsUpSeeAfterBlindTurns >= 0) config.botHeadsUpSeeAfterBlindTurns else 1
            return next
        }

        private fun copyCards(cards: List<Card>): MutableList<Card> = cards.mapTo(mutableListOf()) { Card(it.id, it.suit, it.rank, it.value, it.hidden) }
    }
}
