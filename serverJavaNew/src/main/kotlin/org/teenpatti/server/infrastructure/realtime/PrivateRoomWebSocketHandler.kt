package org.teenpatti.server.infrastructure.realtime

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import org.teenpatti.server.common.ApiSupport
import org.teenpatti.server.common.Scheduler
import org.teenpatti.server.config.AppEnvironment
import java.util.concurrent.ConcurrentHashMap

private data class AuthenticatedPrivateSession(
    val roomCode: String,
    val playerId: String,
    val playerToken: String,
)

@Component
@Suppress("UNCHECKED_CAST")
internal class PrivateRoomWebSocketHandler(
    private val objectMapper: ObjectMapper,
    private val bus: RedisRealtimeBus,
    private val presenceService: RedisPresenceService,
    private val scheduler: Scheduler,
    private val env: AppEnvironment,
) : TextWebSocketHandler() {
    private val liveSessions = ConcurrentHashMap<String, WebSocketSession>()
    private val authenticatedSessions = ConcurrentHashMap<String, AuthenticatedPrivateSession>()
    private val roomSessions = ConcurrentHashMap<String, MutableSet<String>>()
    private var consumerId: String? = null

    @PostConstruct
    fun start() {
        consumerId = bus.registerEventConsumer(::handleEvent)
    }

    @PreDestroy
    fun stop() {
        consumerId?.let(bus::removeEventConsumer)
    }

    override fun afterConnectionEstablished(session: WebSocketSession) {
        liveSessions[session.id] = session
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val request = objectMapper.readValue(message.payload, PrivateSocketRequest::class.java)
        try {
            when (request.type) {
                "private_room:authenticate" -> authenticate(session, request)
                "private_room:action" -> handleCommand(session, request, "action")
                "private_room:start_round" -> handleCommand(session, request, "start_round")
                "private_room:next_round" -> handleCommand(session, request, "next_round")
                "private_room:accept_next_round" -> handleCommand(session, request, "accept_next_round")
                "private_room:update_config" -> handleCommand(session, request, "update_config")
                "private_room:leave" -> handleLeave(session, request)
                else -> sendError(session, request.requestId, "unsupported_private_room_websocket_event", "Unsupported websocket event.")
            }
        } catch (error: Exception) {
            sendError(session, request.requestId, ApiSupport.errorCode(error), error.message ?: "Request failed.")
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        unregisterSession(session, true)
        liveSessions.remove(session.id)
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        unregisterSession(session, true)
        liveSessions.remove(session.id)
        if (session.isOpen) {
            session.close(CloseStatus.SERVER_ERROR)
        }
    }

    private fun authenticate(session: WebSocketSession, request: PrivateSocketRequest) {
        unregisterSession(session, false)
        val roomCode = (request.payload["roomCode"] as String).trim().uppercase()
        val playerId = request.payload["playerId"] as String
        val playerToken = request.payload["playerToken"] as String
        val result =
            bus.sendCommand(
                "private_room",
                roomCode,
                null,
                "connect",
                mapOf("roomCode" to roomCode, "playerId" to playerId, "playerToken" to playerToken),
            )
        authenticatedSessions[session.id] = AuthenticatedPrivateSession(roomCode, playerId, playerToken)
        roomSessions.computeIfAbsent(roomCode) { ConcurrentHashMap.newKeySet() }.add(session.id)
        presenceService.markConnected("private:$roomCode", playerId, "${env.appNodeId}:${session.id}")
        sendAck(session, request.requestId, result.data)
        sendSnapshot(session, "player_reconnected", result.data?.get("roomState"))
    }

    private fun handleCommand(session: WebSocketSession, request: PrivateSocketRequest, commandType: String) {
        val auth = authenticatedSessions[session.id]
        if (auth == null) {
            sendError(session, request.requestId, "private_room_auth_required", "Authenticate the private room session first.")
            return
        }
        val commandPayload = linkedMapOf<String, Any?>(
            "roomCode" to auth.roomCode,
            "playerId" to auth.playerId,
            "playerToken" to auth.playerToken,
        )
        if (commandType == "action") {
            commandPayload["actionType"] = request.payload["actionType"]
            commandPayload["payload"] = request.payload["payload"] as? Map<String, Any?> ?: emptyMap<String, Any?>()
        } else if (commandType == "update_config") {
            commandPayload["variant"] = request.payload["variant"] as String?
            commandPayload["bootAmount"] = (request.payload["bootAmount"] as? Number)?.toInt()
        }
        val result = bus.sendCommand("private_room", auth.roomCode, null, commandType, commandPayload)
        sendAck(session, request.requestId, result.data)
    }

    private fun handleLeave(session: WebSocketSession, request: PrivateSocketRequest) {
        val auth = authenticatedSessions[session.id]
        if (auth == null) {
            sendError(session, request.requestId, "private_room_auth_required", "Authenticate the private room session first.")
            return
        }
        val result =
            bus.sendCommand(
                "private_room",
                auth.roomCode,
                null,
                "leave",
                mapOf("roomCode" to auth.roomCode, "playerId" to auth.playerId, "playerToken" to auth.playerToken),
            )
        sendAck(session, request.requestId, result.data)
        unregisterSession(session, false)
        if (session.isOpen) {
            session.close(CloseStatus.NORMAL)
        }
    }

    private fun handleEvent(event: RedisAggregateEvent) {
        if (event.aggregateType != "private_room") {
            return
        }
        for (sessionId in roomSessions[event.aggregateId] ?: emptySet()) {
            val auth = authenticatedSessions[sessionId] ?: continue
            val session = liveSessions[sessionId] ?: continue
            try {
                val result =
                    bus.sendCommand(
                        "private_room",
                        auth.roomCode,
                        null,
                        "snapshot",
                        mapOf("roomCode" to auth.roomCode, "playerId" to auth.playerId, "playerToken" to auth.playerToken),
                    )
                sendSnapshot(session, event.eventType, result.data?.get("roomState"))
            } catch (error: Exception) {
                sendError(session, null, ApiSupport.errorCode(error), error.message ?: "Private room sync failed.")
            }
        }
    }

    private fun unregisterSession(session: WebSocketSession, scheduleDisconnect: Boolean) {
        val auth = authenticatedSessions.remove(session.id) ?: return
        roomSessions[auth.roomCode]?.remove(session.id)
        presenceService.markDisconnected("private:${auth.roomCode}", auth.playerId)
        if (scheduleDisconnect) {
            scheduler.schedule(env.reconnectGraceMs) {
                if (!presenceService.isConnected("private:${auth.roomCode}", auth.playerId)) {
                    try {
                        bus.sendCommand(
                            "private_room",
                            auth.roomCode,
                            null,
                            "disconnect",
                            mapOf("roomCode" to auth.roomCode, "playerId" to auth.playerId, "playerToken" to auth.playerToken),
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun sendAck(session: WebSocketSession, requestId: String?, data: Any?) {
        sendMessage(session, mapOf("type" to "private_room:ack", "requestId" to requestId, "status" to "ok", "data" to data))
    }

    private fun sendError(session: WebSocketSession, requestId: String?, code: String, message: String) {
        val payload = linkedMapOf<String, Any?>(
            "type" to "private_room:error",
            "status" to "error",
            "code" to code,
            "message" to message,
        )
        if (requestId != null) {
            payload["requestId"] = requestId
        }
        sendMessage(session, payload)
    }

    private fun sendSnapshot(session: WebSocketSession, eventType: String, payload: Any?) {
        sendMessage(session, mapOf("type" to "private_room:snapshot", "eventType" to eventType, "payload" to payload))
    }

    private fun sendMessage(session: WebSocketSession, payload: Map<String, Any?>) {
        if (!session.isOpen) {
            return
        }
        synchronized(session) {
            try {
                session.sendMessage(TextMessage(objectMapper.writeValueAsString(payload)))
            } catch (_: Exception) {
            }
        }
    }
}
