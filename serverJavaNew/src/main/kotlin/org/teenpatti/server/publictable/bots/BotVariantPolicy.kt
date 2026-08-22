package org.teenpatti.server.publictable.bots

import org.teenpatti.server.game.EvaluatedHand
import org.teenpatti.server.game.RoundState
import org.teenpatti.server.game.SeatState
import org.teenpatti.server.publictable.BotDecisionContext

/**
 * Variant-specific public bot turn logic.
 * Return false from handler methods when the default engine policy should run.
 */
internal interface BotVariantPolicy {
    fun supports(variantId: String): Boolean

    fun chooseUnseenTurn(round: RoundState, seat: SeatState, context: BotDecisionContext): Boolean = false

    fun chooseSeenTurn(
        round: RoundState,
        seat: SeatState,
        context: BotDecisionContext,
        actorIndex: Int,
    ): Boolean = false

    /** Used when side-show simulation times out; null falls back to generic rules. */
    fun sideShowTimeoutAction(evaluation: EvaluatedHand): String? = null
}
