package org.teenpatti.server.publictable

internal interface MatchmakingCoordinator {
    fun enqueue(variantId: String, playerId: String, joinedAtMillis: Long)

    fun remove(variantId: String, playerId: String)

    /**
     * Resolves one ready queue snapshot while holding the coordinator lock.
     * The resolver returns player ids which must remain queued for the next window.
     */
    fun resolveReadyBatch(
        variantId: String,
        nowMillis: Long,
        windowMs: Long,
        resolver: (List<String>) -> List<String>,
    ): Boolean
}
