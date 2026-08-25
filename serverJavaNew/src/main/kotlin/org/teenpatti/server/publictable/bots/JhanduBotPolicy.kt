package org.teenpatti.server.publictable.bots

import org.teenpatti.server.config.GameConfig
import org.teenpatti.server.game.RoundState
import org.teenpatti.server.game.SeatState
import org.teenpatti.server.publictable.BotDecisionContext

/**
 * Jhandu bot gameplay:
 * - Stay blind until 2 shared jokers are revealed
 * - After 2 jokers are revealed → see
 * - High Card → pack (heads-up → show)
 * - Pair / Color / Sequence → side show; after a win → side show again; heads-up → show
 * - Pure Sequence / Trail → raise; heads-up → show
 *
 * Hand strength includes revealed shared joker ranks. Side show is auto-accepted
 * by the variant.
 */
internal class JhanduBotPolicy(
    private val config: GameConfig,
) : BotVariantPolicy {
    override fun supports(variantId: String): Boolean = variantId.equals("jhandu", ignoreCase = true)

    override fun chooseUnseenTurn(round: RoundState, seat: SeatState, context: BotDecisionContext): Boolean {
        val revealedJokers = round.variantState?.revealedSharedJokerCount ?: 0
        val enoughJokersRevealed = revealedJokers >= SEE_AFTER_JOKER_COUNT

        BotDecisionSupport.addScore(context, "blind", 0.0, 0.0, 0.0, "Jhandu blind option.")
        BotDecisionSupport.addScore(context, "see", 0.0, 0.0, 0.0, "Jhandu see option.")
        BotDecisionSupport.addScore(context, "pack", 0.0, 0.0, 0.0, "Jhandu pack option.")

        if (!enoughJokersRevealed) {
            context.chosenAction = BotDecisionSupport.firstLegal(context, "blind", "pack")
            context.rationale =
                if (context.chosenAction == "blind") {
                    "Jhandu: only $revealedJokers joker(s) revealed, so the bot stays blind until 2 are revealed."
                } else {
                    "Jhandu: only $revealedJokers joker(s) revealed and blind is unavailable, so the bot packs."
                }
            return true
        }

        if (context.legalActions.contains("see")) {
            context.chosenAction = "see"
            context.rationale = "Jhandu: 2 jokers are revealed, so the bot sees its cards."
            return true
        }

        context.chosenAction = BotDecisionSupport.firstLegal(context, "blind", "pack")
        context.rationale = "Jhandu: 2 jokers are revealed but see is unavailable, so the bot continues blind."
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
        val sideShowOutcome = BotDecisionSupport.lastSideShowOutcome(round, seat.id)
        val sideShowWon = sideShowOutcome == "won"
        val canShow = context.legalActions.contains("show")
        val canSideShow = context.legalActions.contains("sideshow")

        BotDecisionSupport.addScore(context, "pack", 0.0, 0.0, 0.0, "Jhandu pack option.")
        BotDecisionSupport.addScore(context, "chaal", 0.0, 0.0, 0.0, "Jhandu chaal option.")
        BotDecisionSupport.addScore(context, "raise", 0.0, 0.0, 0.0, "Jhandu raise option.")
        BotDecisionSupport.addScore(context, "show", 0.0, 0.0, 0.0, "Jhandu show option.")
        BotDecisionSupport.addScore(context, "sideshow", 0.0, 0.0, 0.0, "Jhandu sideshow option.")

        when (evaluation.category) {
            // Trail / Pure Sequence → raise; heads-up → show
            6, 5 -> {
                context.chosenAction =
                    when {
                        canShow -> "show"
                        else -> BotDecisionSupport.firstLegal(context, "raise", "chaal", "pack")
                    }
                context.rationale =
                    when (context.chosenAction) {
                        "show" -> "Jhandu: $label is heads-up, so the bot shows down."
                        "raise" -> "Jhandu: $label plays a raise after 2 jokers are revealed."
                        else -> "Jhandu: $label wanted a raise, so the bot used the strongest legal continue."
                    }
            }

            // Sequence / Color / Pair → side show; after win → side show again; heads-up → show
            4, 3, 2 -> {
                context.chosenAction =
                    when {
                        canShow -> "show"
                        sideShowWon && canSideShow -> "sideshow"
                        canSideShow -> "sideshow"
                        else -> BotDecisionSupport.firstLegal(context, "chaal", "pack")
                    }
                context.rationale =
                    when (context.chosenAction) {
                        "show" -> "Jhandu: $label is heads-up, so the bot shows down."
                        "sideshow" ->
                            if (sideShowWon) {
                                "Jhandu: $label won a prior side show, so the bot requests side show again."
                            } else {
                                "Jhandu: $label requests a side show after 2 jokers are revealed."
                            }
                        else -> "Jhandu: $label cannot side show right now, so the bot continues at chaal."
                    }
            }

            // High Card → pack; heads-up → show
            1 -> {
                context.chosenAction = BotDecisionSupport.firstLegal(context, "show", "pack")
                context.rationale =
                    if (context.chosenAction == "show") {
                        "Jhandu: High card is heads-up, so the bot shows down."
                    } else {
                        "Jhandu: High card after 2 jokers is too weak, so the bot packs."
                    }
            }

            else -> {
                context.chosenAction = BotDecisionSupport.firstLegal(context, "chaal", "pack")
                context.rationale = "Jhandu: unrecognized hand category, so the bot used the cheapest continue."
            }
        }
        return true
    }

    companion object {
        private const val SEE_AFTER_JOKER_COUNT = 2
    }
}
