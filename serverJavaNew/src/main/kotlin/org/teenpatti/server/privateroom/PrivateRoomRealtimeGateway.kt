package org.teenpatti.server.privateroom

internal interface PrivateRoomRealtimeGateway {
    fun roomUpdated(roomCode: String, eventType: String)
}

internal class NoOpPrivateRoomRealtimeGateway : PrivateRoomRealtimeGateway {
    override fun roomUpdated(roomCode: String, eventType: String) {
    }
}
