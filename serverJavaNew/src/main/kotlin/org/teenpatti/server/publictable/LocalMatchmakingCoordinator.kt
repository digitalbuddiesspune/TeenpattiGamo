package org.teenpatti.server.publictable

import org.springframework.stereotype.Component

/**
 * In-process matchmaking queue used by the public Teen Patti tables.
 *
 * The queue is intentionally local to the running JVM: the product requirement
 * keeps the new matchmaking behavior but removes Redis from this flow.
 */
@Component
internal class LocalMatchmakingCoordinator : MatchmakingCoordinator {
    private val queues = linkedMapOf<String, LinkedHashMap<String, Long>>()

    @Synchronized
    override fun enqueue(variantId: String, playerId: String, joinedAtMillis: Long) {
        queues.getOrPut(variantId) { linkedMapOf() }.putIfAbsent(playerId, joinedAtMillis)
    }

    @Synchronized
    override fun remove(variantId: String, playerId: String) {
        queues[variantId]?.remove(playerId)
        removeEmptyQueue(variantId)
    }

    @Synchronized
    override fun resolveReadyBatch(
        variantId: String,
        nowMillis: Long,
        windowMs: Long,
        resolver: (List<String>) -> List<String>,
    ): Boolean {
        val queue = queues[variantId] ?: return false
        val oldestJoinMillis = queue.values.minOrNull() ?: return false
        if (oldestJoinMillis + windowMs > nowMillis) {
            return false
        }

        val snapshot = queue.keys.toList()
        if (snapshot.isEmpty()) {
            return false
        }

        val leftovers = resolver(snapshot).toSet()
        snapshot.forEach(queue::remove)
        leftovers.forEach { playerId -> queue[playerId] = nowMillis }
        removeEmptyQueue(variantId)
        return true
    }

    private fun removeEmptyQueue(variantId: String) {
        if (queues[variantId]?.isEmpty() == true) {
            queues.remove(variantId)
        }
    }
}
