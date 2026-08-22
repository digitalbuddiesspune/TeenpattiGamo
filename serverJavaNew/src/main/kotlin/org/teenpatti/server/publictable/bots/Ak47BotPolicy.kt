package org.teenpatti.server.publictable.bots

import org.teenpatti.server.config.GameConfig
import org.teenpatti.server.game.RoundState
import org.teenpatti.server.game.SeatState
import org.teenpatti.server.publictable.BotDecisionContext

/**
 * AK47 seen-hand policy:
 * - Color / Pair / High Card → show heads-up when legal, otherwise pack
 * - Trail / Pure Sequence → 2x raise
 * - Sequence → chaal only
 *
 * Unseen: always see on first available turn.
 */
internal class Ak47BotPolicy(
    private val config: GameConfig,
) : BotVariantPolicy {
    override fun supports(variantId: String): Boolean = variantId.equals("ak47", ignoreCase = true)

    override fun chooseUnseenTurn(round: RoundState, seat: SeatState, context: BotDecisionContext): Boolean {
        if (!context.legalActions.contains("see")) {
            return false
        }
        context.chosenAction = "see"
        context.rationale = "AK47: the bot always sees cards on its first available turn."
        BotDecisionSupport.addScore(context, "see", 3.0, 0.0, 0.0, context.rationale)
        return true
    }

    override fun chooseSeenTurn(
        round: RoundState,
        seat: SeatState,
        context: BotDecisionContext,
        actorIndex: Int,
    ): Boolean {
        val evaluation = BotDecisionSupport.evaluateOwnSeenHand(config, seat, context.visibleState)
        context.winProbability = 0.0
        val label = evaluation.label.ifBlank { "category-${evaluation.category}" }

        BotDecisionSupport.addScore(context, "pack", 0.0, 0.0, 0.0, "AK47 pack option.")
        BotDecisionSupport.addScore(context, "chaal", 0.0, 0.0, 0.0, "AK47 chaal option.")
        BotDecisionSupport.addScore(context, "raise", 0.0, 0.0, 0.0, "AK47 raise option.")
        BotDecisionSupport.addScore(context, "show", 0.0, 0.0, 0.0, "AK47 show option.")

        when (evaluation.category) {
            6, 5 -> {
                context.chosenAction = BotDecisionSupport.firstLegal(context, "raise", "chaal", "pack")
                context.rationale =
                    if (context.chosenAction == "raise") {
                        "AK47: $label plays a 2x raise after seeing cards."
                    } else {
                        "AK47: $label wanted a 2x raise, so the bot used the strongest legal continue."
                    }
            }

            4 -> {
                context.chosenAction = BotDecisionSupport.firstLegal(context, "chaal", "pack")
                context.rationale =
                    if (context.chosenAction == "chaal") {
                        "AK47: Sequence plays chaal only after seeing cards."
                    } else {
                        "AK47: Sequence wanted chaal, so the bot used the strongest legal continue."
                    }
            }

            3, 2, 1 -> {
                context.chosenAction = BotDecisionSupport.firstLegal(context, "show", "pack")
                context.rationale =
                    if (context.chosenAction == "show") {
                        "AK47: $label is weak, but heads-up show is available so the bot shows down."
                    } else {
                        "AK47: $label is too weak to continue, so the bot packs."
                    }
            }

            else -> {
                context.chosenAction = BotDecisionSupport.firstLegal(context, "chaal", "pack")
                context.rationale = "AK47: unrecognized seen hand category, so the bot used the cheapest continue."
            }
        }
        return true
    }
}
