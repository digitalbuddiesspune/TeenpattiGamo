package org.teenpatti.server.publictable.bots

import org.teenpatti.server.config.GameConfig
import org.teenpatti.server.game.EvaluatedHand
import org.teenpatti.server.game.RoundState
import org.teenpatti.server.game.SeatState
import org.teenpatti.server.publictable.BotDecisionContext

/**
 * Muflis (lowball) seen-hand policy:
 * - Trail / Pure Sequence / Sequence / Color / Pair / High Card A-K-Q-J-10 → pack
 *   (heads-up → show when legal)
 * - High Card with top rank 9..2 → sideshow when legal
 *   - after sideshow win → raise
 *   - after sideshow deny → chaal
 *   - heads-up → show
 */
internal class MuflisBotPolicy(
    private val config: GameConfig,
) : BotVariantPolicy {
    override fun supports(variantId: String): Boolean = variantId.equals("muflis", ignoreCase = true)

    override fun chooseSeenTurn(
        round: RoundState,
        seat: SeatState,
        context: BotDecisionContext,
        actorIndex: Int,
    ): Boolean {
        val evaluation = BotDecisionSupport.evaluateOwnSeenHand(config, seat, context.visibleState)
        context.winProbability = 0.0
        val label = evaluation.label.ifBlank { "category-${evaluation.category}" }
        val strongLowball = isStrongHighCard(evaluation)
        val sideShowOutcome = BotDecisionSupport.lastSideShowOutcome(round, seat.id)

        BotDecisionSupport.addScore(context, "pack", 0.0, 0.0, 0.0, "Muflis pack option.")
        BotDecisionSupport.addScore(context, "chaal", 0.0, 0.0, 0.0, "Muflis chaal option.")
        BotDecisionSupport.addScore(context, "raise", 0.0, 0.0, 0.0, "Muflis raise option.")
        BotDecisionSupport.addScore(context, "show", 0.0, 0.0, 0.0, "Muflis show option.")
        BotDecisionSupport.addScore(context, "sideshow", 0.0, 0.0, 0.0, "Muflis sideshow option.")

        if (!strongLowball) {
            context.chosenAction = BotDecisionSupport.firstLegal(context, "show", "pack")
            context.rationale =
                if (context.chosenAction == "show") {
                    "Muflis: $label is weak in lowball, but heads-up show is available so the bot shows down."
                } else {
                    "Muflis: $label is weak in lowball (made hand or high card A/K/Q/J/10), so the bot packs."
                }
            return true
        }

        when {
            context.legalActions.contains("show") -> {
                context.chosenAction = "show"
                context.rationale = "Muflis: $label is strong in lowball and heads-up show is available."
            }

            sideShowOutcome == "won" && context.legalActions.contains("raise") -> {
                context.chosenAction = "raise"
                context.rationale = "Muflis: $label won a prior side show, so the bot raises."
            }

            sideShowOutcome == "denied" && context.legalActions.contains("chaal") -> {
                context.chosenAction = "chaal"
                context.rationale = "Muflis: $label had a side show denied, so the bot continues with chaal."
            }

            context.legalActions.contains("sideshow") -> {
                context.chosenAction = "sideshow"
                context.rationale = "Muflis: $label is a strong low high-card (9-2), so the bot requests a side show."
            }

            else -> {
                context.chosenAction = BotDecisionSupport.firstLegal(context, "chaal", "raise", "pack")
                context.rationale =
                    "Muflis: $label is strong in lowball but side show is unavailable, so the bot continues."
            }
        }
        return true
    }

    override fun sideShowTimeoutAction(evaluation: EvaluatedHand): String? =
        if (isStrongHighCard(evaluation)) "sideshow_accept" else "sideshow_deny"

    /** High Card whose highest rank is 9 or lower — strongest Muflis band. */
    private fun isStrongHighCard(evaluation: EvaluatedHand): Boolean =
        evaluation.category == 1 && evaluation.ranks.isNotEmpty() && evaluation.ranks.first() <= 9
}
