package org.teenpatti.server.publictable.api

internal class PublicJoinRequest(
    val playerName: String? = null,
    val clientSeed: String? = null,
)

internal class PublicActionRequest(
    val playerId: String,
    val playerToken: String,
    val actionType: String,
    val payload: Map<String, Any?> = emptyMap(),
)

internal class PublicLeaveRequest(
    val playerId: String,
    val playerToken: String,
)
