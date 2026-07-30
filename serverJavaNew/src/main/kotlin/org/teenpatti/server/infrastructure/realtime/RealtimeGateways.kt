package org.teenpatti.server.infrastructure.realtime

import org.springframework.stereotype.Component
import org.teenpatti.server.game.PublicTableRealtimeGateway
import org.teenpatti.server.privateroom.PrivateRoomRealtimeGateway

@Component
internal class RedisBackedPublicTableRealtimeGateway(
    private val bus: RedisRealtimeBus,
) : PublicTableRealtimeGateway {
    override fun tableUpdated(variantId: String, tableId: String, eventType: String) {
        bus.publishEvent("public_table", "$variantId:$tableId", eventType)
    }
}

@Component
internal class RedisBackedPrivateRoomRealtimeGateway(
    private val bus: RedisRealtimeBus,
) : PrivateRoomRealtimeGateway {
    override fun roomUpdated(roomCode: String, eventType: String) {
        bus.publishEvent("private_room", roomCode, eventType)
    }
}
