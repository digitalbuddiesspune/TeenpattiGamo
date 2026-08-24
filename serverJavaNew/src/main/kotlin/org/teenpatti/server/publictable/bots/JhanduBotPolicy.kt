package org.teenpatti.server.publictable.bots

import org.teenpatti.server.config.GameConfig
import org.teenpatti.server.game.EvaluatedHand
import org.teenpatti.server.game.RoundState
import org.teenpatti.server.game.SeatState
import org.teenpatti.server.publictable.BotDecisionContext

/**
 * Jhandu bot gameplay:
 * - Unseen: always see on the first available turn (including cycle 1)
 * - High Card without Ace → pack (heads-up → show)
 * - High Card with Ace → side show; after a win → side show again; heads-up → show
 * - Pair → side show when available; after a win → keep requesting side show;
 *   if denied → chaal next turn, then side show again; heads-up → show
 * - Sequence / Color → chaal only
 * - Pure Sequence / Trail → raise
 *
 * Hand strength includes revealed shared joker ranks. Side show is auto-accepted
 * by the variant; deny handling is kept for completeness.
 */
internal class JhanduBotPolicy(
    private val config: GameConfig,
) : BotVariantPolicy {
    override fun supports(variantId: String): Boolean = variantId.equals("jhandu", ignoreCase = true)

    override fun chooseUnseenTurn(round: RoundState, seat: SeatState, context: BotDecisionContext): Boolean {
        if (!context.legalActions.contains("see")) {
            return false
        }
        context.chosenAction = "see"
        context.rationale = "Jhandu: the bot sees cards on its first available turn."
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
        val sideShowOutcome = BotDecisionSupport.lastSideShowOutcome(round, seat.id)
        val sideShowWon = sideShowOutcome == "won"
        val sideShowDenied = sideShowOutcome == "denied"
        val canShow = context.legalActions.contains("show")
        val canSideShow = context.legalActions.contains("sideshow")

        BotDecisionSupport.addScore(context, "pack", 0.0, 0.0, 0.0, "Jhandu pack option.")
        BotDecisionSupport.addScore(context, "chaal", 0.0, 0.0, 0.0, "Jhandu chaal option.")
        BotDecisionSupport.addScore(context, "raise", 0.0, 0.0, 0.0, "Jhandu raise option.")
        BotDecisionSupport.addScore(context, "show", 0.0, 0.0, 0.0, "Jhandu show option.")
        BotDecisionSupport.addScore(context, "sideshow", 0.0, 0.0, 0.0, "Jhandu sideshow option.")

        when (evaluation.category) {
            6, 5 -> {
                context.chosenAction = BotDecisionSupport.firstLegal(context, "raise", "chaal", "pack")
                context.rationale =
                    if (context.chosenAction == "raise") {
                        "Jhandu: $label plays a raise."
                    } else {
                        "Jhandu: $label wanted a raise, so the bot used the strongest legal continue."
                    }
            }

            4, 3 -> {
                context.chosenAction = BotDecisionSupport.firstLegal(context, "chaal", "pack")
                context.rationale =
                    if (context.chosenAction == "chaal") {
                        "Jhandu: $label plays chaal only."
                    } else {
                        "Jhandu: $label wanted chaal, so the bot packed."
                    }
            }

            2 -> {
                context.chosenAction =
                    when {
                        canShow -> "show"
                        sideShowDenied -> BotDecisionSupport.firstLegal(context, "chaal", "pack")
                        sideShowWon && canSideShow -> "sideshow"
                        canSideShow -> "sideshow"
                        else -> BotDecisionSupport.firstLegal(context, "chaal", "pack")
                    }
                context.rationale =
                    when (context.chosenAction) {
                        "show" -> "Jhandu: Pair is heads-up, so the bot shows down."
                        "chaal" ->
                            if (sideShowDenied) {
                                "Jhandu: Pair had a side show denied, so the bot chaals before trying again."
                            } else {
                                "Jhandu: Pair cannot side show right now, so the bot continues at chaal."
                            }
                        "sideshow" ->
                            if (sideShowWon) {
                                "Jhandu: Pair won a prior side show, so the bot keeps requesting side show."
                            } else {
                                "Jhandu: Pair requests a side show."
                            }
                        else -> "Jhandu: Pair has no legal continue action, so the bot packs."
                    }
            }

            1 -> {
                if (hasAce(seat, evaluation)) {
                    context.chosenAction =
                        when {
                            canShow -> "show"
                            sideShowWon && canSideShow -> "sideshow"
                            canSideShow -> "sideshow"
                            else -> BotDecisionSupport.firstLegal(context, "pack", "chaal")
                        }
                    context.rationale =
                        when (context.chosenAction) {
                            "show" -> "Jhandu: Ace high card is heads-up, so the bot shows down."
                            "sideshow" ->
                                if (sideShowWon) {
                                    "Jhandu: Ace high card won a side show, so the bot requests another side show."
                                } else {
                                    "Jhandu: Ace high card requests a side show."
                                }
                            else -> "Jhandu: Ace high card has no side show available, so the bot packs."
                        }
                } else {
                    context.chosenAction = BotDecisionSupport.firstLegal(context, "show", "pack")
                    context.rationale =
                        if (context.chosenAction == "show") {
                            "Jhandu: Weak high card is heads-up, so the bot shows down."
                        } else {
                            "Jhandu: High card without Ace is too weak, so the bot packs."
                        }
                }
            }

            else -> {
                context.chosenAction = BotDecisionSupport.firstLegal(context, "chaal", "pack")
                context.rationale = "Jhandu: unrecognized hand category, so the bot used the cheapest continue."
            }
        }
        return true
    }

    private fun hasAce(seat: SeatState, evaluation: EvaluatedHand): Boolean {
        if (seat.cards.any { it.rank.equals("A", ignoreCase = true) || it.value == 14 }) {
            return true
        }
        return evaluation.ranks.isNotEmpty() && evaluation.ranks.first() == 14
    }
}
