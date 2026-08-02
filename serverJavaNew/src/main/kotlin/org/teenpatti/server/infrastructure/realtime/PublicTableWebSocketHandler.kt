package org.teenpatti.server.infrastructure.realtime

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.PingMessage
import org.springframework.web.socket.PongMessage
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import org.teenpatti.server.common.ApiSupport
import org.teenpatti.server.common.AppException
import org.teenpatti.server.common.ScheduledTask
import org.teenpatti.server.common.Scheduler
import org.teenpatti.server.common.GameEventLog
import org.teenpatti.server.config.AppEnvironment
import java.util.concurrent.ConcurrentHashMap

private data class AuthenticatedPublicSession(
    val variantId: String,
    val tableId: String,
    val playerId: String,
    val playerToken: String,
)

@Component
@Suppress("UNCHECKED_CAST")
internal class PublicTableWebSocketHandler(
    private val objectMapper: ObjectMapper,
    private val bus: RedisRealtimeBus,
    private val presenceService: RedisPresenceService,
    private val scheduler: Scheduler,
    private val env: AppEnvironment,
) : TextWebSocketHandler() {
    private val liveSessions = ConcurrentHashMap<String, WebSocketSession>()
    private val authenticatedSessions = ConcurrentHashMap<String, AuthenticatedPublicSession>()
    private val tableSessions = ConcurrentHashMap<String, MutableSet<String>>()
    private var consumerId: String? = null
    private var heartbeatTask: ScheduledTask? = null

    @Volatile
    private var running = false

    @PostConstruct
    fun start() {
        consumerId = bus.registerEventConsumer(::handleEvent)
        running = true
        scheduleHeartbeat()
    }

    @PreDestroy
    fun stop() {
        running = false
        heartbeatTask?.cancel()
        heartbeatTask = null
        consumerId?.let(bus::removeEventConsumer)
    }

    override fun afterConnectionEstablished(session: WebSocketSession) {
        liveSessions[session.id] = session
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val request =
            try {
                objectMapper.readValue(message.payload, PublicSocketRequest::class.java)
            } catch (error: Exception) {
                GameEventLog.error("public_websocket_payload_invalid", error)
                sendError(session, null, "invalid_websocket_payload", "Unable to read the websocket payload.")
                return
            }
        try {
            when (request.type) {
                "public_table:authenticate" -> authenticate(session, request)
                "public_table:action" -> handleAction(session, request)
                "public_table:leave" -> handleLeave(session, request)
                "public_table:ping" -> handlePing(session, request)
                else -> sendError(session, request.requestId, "unsupported_public_websocket_event", "Unsupported websocket event.")
            }
        } catch (error: Exception) {
            GameEventLog.error("public_websocket_event_failed", error, "eventType" to request.type)
            sendError(session, request.requestId, ApiSupport.errorCode(error), error.message ?: "Request failed.")
        }
    }

    override fun handlePongMessage(session: WebSocketSession, message: PongMessage) {
        refreshPresence(session)
    }

    /**
     * Idle websocket connections are dropped by reverse proxies and load balancers
     * (nginx and ALB default to a 60s idle timeout), so keep a frame flowing in both
     * directions and re-arm the Redis presence key, which expires on the same order.
     */
    private fun scheduleHeartbeat() {
        heartbeatTask =
            scheduler.schedule(HEARTBEAT_INTERVAL_MS) {
                try {
                    sendHeartbeats()
                } catch (error: Exception) {
                    GameEventLog.error("public_websocket_heartbeat_failed", error)
                } finally {
                    if (running) {
                        scheduleHeartbeat()
                    }
                }
            }
    }

    private fun sendHeartbeats() {
        for ((sessionId, session) in liveSessions) {
            if (!session.isOpen) {
                liveSessions.remove(sessionId)
                continue
            }
            refreshPresence(session)
            synchronized(session) {
                try {
                    session.sendMessage(PingMessage())
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun refreshPresence(session: WebSocketSession) {
        val auth = authenticatedSessions[session.id] ?: return
        try {
            presenceService.markConnected("public", auth.playerId, "${env.appNodeId}:${session.id}")
        } catch (_: Exception) {
        }
    }

    private fun handlePing(session: WebSocketSession, request: PublicSocketRequest) {
        refreshPresence(session)
        sendAck(session, request.requestId, mapOf("pong" to true))
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

    private fun authenticate(session: WebSocketSession, request: PublicSocketRequest) {
        unregisterSession(session, false)
        val variant = ApiSupport.normalizeVariantId(request.payload["variant"] as String?)
        val playerId = request.payload["playerId"] as String
        val playerToken = request.payload["playerToken"] as String
        val result =
            bus.sendCommand(
                "public_table",
                "",
                variant,
                "connect",
                mapOf("playerId" to playerId, "playerToken" to playerToken),
            )
        val snapshot = result.data ?: linkedMapOf()
        val tableId =
            snapshot["tableId"] as? String
                ?: throw AppException.badRequest(
                    "public_table_not_assigned",
                    "Matchmaking has not assigned a table for this session yet.",
                )
        authenticatedSessions[session.id] = AuthenticatedPublicSession(variant, tableId, playerId, playerToken)
        tableSessions.computeIfAbsent(tableId) { ConcurrentHashMap.newKeySet() }.add(session.id)
        presenceService.markConnected("public", playerId, "${env.appNodeId}:${session.id}")
        sendAck(session, request.requestId, snapshot)
        sendSnapshot(session, "snapshot", snapshot)
    }

    private fun handleAction(session: WebSocketSession, request: PublicSocketRequest) {
        val auth = authenticatedSessions[session.id]
        if (auth == null) {
            sendError(session, request.requestId, "public_table_auth_required", "Authenticate the public table session first.")
            return
        }
        val result =
            bus.sendCommand(
                "public_table",
                auth.tableId,
                auth.variantId,
                "action",
                mapOf(
                    "playerId" to auth.playerId,
                    "playerToken" to auth.playerToken,
                    "actionType" to request.payload["actionType"],
                    "payload" to (request.payload["payload"] as? Map<String, Any?> ?: emptyMap<String, Any?>()),
                ),
            )
        sendAck(session, request.requestId, result.data)
    }

    private fun handleLeave(session: WebSocketSession, request: PublicSocketRequest) {
        val auth = authenticatedSessions[session.id]
        if (auth == null) {
            sendError(session, request.requestId, "public_table_auth_required", "Authenticate the public table session first.")
            return
        }
        val result =
            bus.sendCommand(
                "public_table",
                auth.tableId,
                auth.variantId,
                "leave",
                mapOf("playerId" to auth.playerId, "playerToken" to auth.playerToken),
            )
        sendAck(session, request.requestId, result.data)
        unregisterSession(session, false)
        if (session.isOpen) {
            session.close(CloseStatus.NORMAL)
        }
    }

    private fun handleEvent(event: RedisAggregateEvent) {
        if (event.aggregateType != "public_table") {
            return
        }
        val parts = event.aggregateId.split(":", limit = 2)
        if (parts.size != 2) {
            return
        }
        val variantId = parts[0]
        val tableId = parts[1]
        for (sessionId in tableSessions[tableId] ?: emptySet()) {
            val auth = authenticatedSessions[sessionId] ?: continue
            val session = liveSessions[sessionId] ?: continue
            try {
                val result =
                    bus.sendCommand(
                        "public_table",
                        auth.tableId,
                        variantId,
                        "snapshot",
                        mapOf("playerId" to auth.playerId, "playerToken" to auth.playerToken),
                    )
                sendSnapshot(session, event.eventType, result.data)
            } catch (error: Exception) {
                // Transient Redis/command timeouts during dealing must not kill the
                // live session — the client will refresh on the next successful event.
                GameEventLog.error(
                    "public_websocket_snapshot_failed",
                    error,
                    "tableId" to tableId,
                    "eventType" to event.eventType,
                )
            }
        }
    }

    private fun unregisterSession(session: WebSocketSession, scheduleDisconnect: Boolean) {
        val auth = authenticatedSessions.remove(session.id) ?: return
        tableSessions[auth.tableId]?.remove(session.id)
        presenceService.markDisconnected("public", auth.playerId)
        if (scheduleDisconnect) {
            scheduler.schedule(env.reconnectGraceMs) {
                if (!presenceService.isConnected("public", auth.playerId)) {
                    try {
                        bus.sendCommand(
                            "public_table",
                            auth.tableId,
                            auth.variantId,
                            "disconnect",
                            mapOf("playerId" to auth.playerId, "playerToken" to auth.playerToken),
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun sendAck(session: WebSocketSession, requestId: String?, data: Any?) {
        sendMessage(session, mapOf("type" to "public_table:ack", "requestId" to requestId, "status" to "ok", "data" to data))
    }

    private fun sendError(session: WebSocketSession, requestId: String?, code: String, message: String) {
        val payload = linkedMapOf<String, Any?>(
            "type" to "public_table:error",
            "status" to "error",
            "code" to code,
            "message" to message,
        )
        if (requestId != null) {
            payload["requestId"] = requestId
        }
        sendMessage(session, payload)
    }

    private fun sendSessionClosed(session: WebSocketSession, code: String, message: String) {
        sendMessage(session, mapOf("type" to "public_table:session_closed", "code" to code, "message" to message))
    }

    private fun sendSnapshot(session: WebSocketSession, eventType: String, payload: Any?) {
        sendMessage(session, mapOf("type" to "public_table:snapshot", "eventType" to eventType, "payload" to payload))
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

    private companion object {
        const val HEARTBEAT_INTERVAL_MS = 25_000L
    }
}
