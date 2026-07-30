package org.teenpatti.server.game

internal interface PublicTableRealtimeGateway {
    fun tableUpdated(variantId: String, tableId: String, eventType: String)
}

internal fun interface PlayerPresence {
    fun isConnected(scope: String, subjectId: String): Boolean
}

internal class NoOpPublicTableRealtimeGateway : PublicTableRealtimeGateway {
    override fun tableUpdated(variantId: String, tableId: String, eventType: String) {
    }
}

internal class NoOpPlayerPresence : PlayerPresence {
    override fun isConnected(scope: String, subjectId: String): Boolean = true
}
