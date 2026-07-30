package org.teenpatti.server.privateroom.api

internal class CreatePrivateRoomRequest(
    val roomName: String? = null,
    val playerName: String? = null,
    val clientSeed: String? = null,
    val variant: String? = null,
    val bootAmount: Int? = null,
)

internal class JoinPrivateRoomRequest(
    val roomCode: String? = null,
    val playerName: String? = null,
    val clientSeed: String? = null,
)

internal class LeavePrivateRoomRequest(
    val playerId: String,
    val playerToken: String,
)
