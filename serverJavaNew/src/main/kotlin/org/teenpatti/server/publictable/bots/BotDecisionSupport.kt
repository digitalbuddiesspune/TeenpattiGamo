package org.teenpatti.server.publictable.bots

import org.teenpatti.server.config.GameConfig
import org.teenpatti.server.game.Card
import org.teenpatti.server.game.Engine
import org.teenpatti.server.game.EvaluatedHand
import org.teenpatti.server.game.RoundState
import org.teenpatti.server.game.SeatState
import org.teenpatti.server.publictable.BotActionScore
import org.teenpatti.server.publictable.BotDecisionContext
import org.teenpatti.server.publictable.BotVisibleState

internal object BotDecisionSupport {
    fun firstLegal(context: BotDecisionContext, vararg actions: String): String {
        for (action in actions) {
            if (context.legalActions.contains(action)) {
                return action
            }
        }
        return context.legalActions.firstOrNull() ?: "pack"
    }

    fun addScore(
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

    fun findSeat(round: RoundState, playerId: String): SeatState? =
        round.seats.firstOrNull { it.id == playerId }

    fun evaluateOwnSeenHand(config: GameConfig, seat: SeatState, visibleState: BotVisibleState): EvaluatedHand {
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

    fun lastSideShowOutcome(round: RoundState, botId: String): String? {
        val recent = round.recentSideShowResult
        if (recent != null && recent.winnerId == botId) {
            return "won"
        }
        val seat = findSeat(round, botId) ?: return null
        return when (seat.lastAction?.type) {
            "sideshow-denied" -> "denied"
            else -> null
        }
    }
}
