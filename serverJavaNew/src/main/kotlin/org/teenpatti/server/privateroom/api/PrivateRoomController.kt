package org.teenpatti.server.privateroom.api

import org.teenpatti.server.common.ApiSupport
import org.teenpatti.server.privateroom.PrivateRoomManager
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/private-rooms")
internal class PrivateRoomController(
    private val privateRoomManager: PrivateRoomManager,
) {
    @PostMapping
    fun createRoom(@RequestBody(required = false) body: CreatePrivateRoomRequest?): Map<String, Any?> =
        ApiSupport.ok(
            privateRoomManager.createRoom(
                body?.roomName,
                body?.playerName,
                body?.clientSeed,
                body?.variant,
                body?.bootAmount,
            ),
        )

    @PostMapping("/join")
    fun joinRoom(@RequestBody(required = false) body: JoinPrivateRoomRequest?): Map<String, Any?> =
        ApiSupport.ok(
            privateRoomManager.joinRoom(
                body?.roomCode,
                body?.playerName,
                body?.clientSeed,
            ),
        )

    @GetMapping("/{roomCode}/session")
    fun getRoomSession(
        @PathVariable roomCode: String,
        @RequestParam playerId: String,
        @RequestParam playerToken: String,
    ): Map<String, Any?> = ApiSupport.ok(privateRoomManager.getSession(roomCode, playerId, playerToken))

    @PostMapping("/{roomCode}/leave")
    fun leaveRoom(
        @PathVariable roomCode: String,
        @RequestBody body: LeavePrivateRoomRequest,
    ): Map<String, Any?> =
        ApiSupport.ok(
            privateRoomManager.leaveRoom(
                roomCode,
                body.playerId,
                body.playerToken,
            ),
        )
}
