package org.teenpatti.server.publictable.bots

import org.teenpatti.server.game.RoundState
import org.teenpatti.server.game.SeatState
import org.teenpatti.server.publictable.BotDecisionContext

/**
 * Jhandu bot policy.
 * - Cycle 1 forced blind: stay blind while See is locked
 * - Later cycles / seen play: expert simulation
 * - Side-show accept/deny is handled by the engine using variant auto-accept rules
 */
internal class JhanduBotPolicy(
    private val advisor: BotSimulationAdvisor,
) : BotVariantPolicy {
    override fun supports(variantId: String): Boolean = variantId.equals("jhandu", ignoreCase = true)

    override fun chooseUnseenTurn(round: RoundState, seat: SeatState, context: BotDecisionContext): Boolean {
        if (round.variantState?.forceBlindActive == true) {
            context.chosenAction =
                when {
                    context.legalActions.contains("blind") -> "blind"
                    context.legalActions.contains("pack") -> "pack"
                    else -> context.legalActions.firstOrNull() ?: "pack"
                }
            context.rationale = "Jhandu: cycle 1 is blind-only, so the bot stays blind."
            BotDecisionSupport.addScore(context, "blind", 1.0, 0.0, 0.0, context.rationale)
            return true
        }
        return advisor.chooseUnseenTurn(round, seat, context, VARIANT_LABEL)
    }

    override fun chooseSeenTurn(
        round: RoundState,
        seat: SeatState,
        context: BotDecisionContext,
        actorIndex: Int,
    ): Boolean = advisor.chooseSeenTurn(round, seat, actorIndex, context, VARIANT_LABEL)

    companion object {
        private const val VARIANT_LABEL = "Jhandu"
    }
}
