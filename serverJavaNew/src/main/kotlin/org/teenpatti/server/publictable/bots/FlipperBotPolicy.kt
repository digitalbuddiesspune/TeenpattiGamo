package org.teenpatti.server.publictable.bots

import org.teenpatti.server.config.GameConfig
import org.teenpatti.server.game.RoundState
import org.teenpatti.server.game.SeatState
import org.teenpatti.server.publictable.BotDecisionContext

/**
 * Flipper seen-hand policy (evaluates the best hand after the blue flipper card activates):
 * - High Card (with or without Ace) → pack (heads-up → show)
 * - Pair → side show; after deny → side show again; after win → chaal once, then side show again
 * - Sequence / Color → chaal only
 * - Pure Sequence / Trail → raise
 */
internal class FlipperBotPolicy(
    private val config: GameConfig,
) : BotVariantPolicy {
    override fun supports(variantId: String): Boolean = variantId.equals("flipper", ignoreCase = true)

    override fun chooseSeenTurn(
        round: RoundState,
        seat: SeatState,
        context: BotDecisionContext,
        actorIndex: Int,
    ): Boolean {
        val evaluation = BotDecisionSupport.evaluateOwnSeenHand(config, seat, context.visibleState)
        context.winProbability = 0.0
        val label = evaluation.label.ifBlank { "category-${evaluation.category}" }
        val sideShowOutcome = BotDecisionSupport.lastSideShowOutcome(round, seat.id)
        val sideShowWon = sideShowOutcome == "won"
        val sideShowDenied = sideShowOutcome == "denied"
        val canShow = context.legalActions.contains("show")
        val canSideShow = context.legalActions.contains("sideshow")
        val chaalAfterSideShowWin = sideShowWon && seat.lastAction?.type != "chaal"

        BotDecisionSupport.addScore(context, "pack", 0.0, 0.0, 0.0, "Flipper pack option.")
        BotDecisionSupport.addScore(context, "chaal", 0.0, 0.0, 0.0, "Flipper chaal option.")
        BotDecisionSupport.addScore(context, "raise", 0.0, 0.0, 0.0, "Flipper raise option.")
        BotDecisionSupport.addScore(context, "show", 0.0, 0.0, 0.0, "Flipper show option.")
        BotDecisionSupport.addScore(context, "sideshow", 0.0, 0.0, 0.0, "Flipper sideshow option.")

        when (evaluation.category) {
            6, 5 -> {
                context.chosenAction = BotDecisionSupport.firstLegal(context, "raise", "chaal", "pack")
                context.rationale =
                    if (context.chosenAction == "raise") {
                        "Flipper: $label plays a raise."
                    } else {
                        "Flipper: $label wanted a raise, so the bot used the strongest legal continue."
                    }
            }

            4, 3 -> {
                context.chosenAction = BotDecisionSupport.firstLegal(context, "chaal", "pack")
                context.rationale =
                    if (context.chosenAction == "chaal") {
                        "Flipper: $label plays chaal only."
                    } else {
                        "Flipper: $label wanted chaal, so the bot packed."
                    }
            }

            2 -> {
                context.chosenAction =
                    when {
                        canShow -> "show"
                        chaalAfterSideShowWin -> BotDecisionSupport.firstLegal(context, "chaal", "pack")
                        sideShowDenied && canSideShow -> "sideshow"
                        canSideShow -> "sideshow"
                        else -> BotDecisionSupport.firstLegal(context, "chaal", "pack")
                    }
                context.rationale =
                    when (context.chosenAction) {
                        "show" -> "Flipper: Pair is heads-up, so the bot shows down."
                        "chaal" -> "Flipper: Pair won a side show, so the bot chaals on the next turn."
                        "sideshow" ->
                            if (sideShowDenied) {
                                "Flipper: Pair had a side show denied, so the bot requests side show again."
                            } else {
                                "Flipper: Pair requests a side show."
                            }
                        else -> "Flipper: Pair has no legal continue action, so the bot packs."
                    }
            }

            1 -> {
                context.chosenAction = BotDecisionSupport.firstLegal(context, "show", "pack")
                context.rationale =
                    if (context.chosenAction == "show") {
                        "Flipper: High card is heads-up, so the bot shows down."
                    } else {
                        "Flipper: High card is too weak after the flipper combination, so the bot packs."
                    }
            }

            else -> {
                context.chosenAction = BotDecisionSupport.firstLegal(context, "chaal", "pack")
                context.rationale = "Flipper: unrecognized hand category, so the bot used the cheapest continue."
            }
        }
        return true
    }
}
