package org.teenpatti.server.infrastructure.realtime

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.stereotype.Component
import org.teenpatti.server.common.ApiSupport
import org.teenpatti.server.common.AppException
import org.teenpatti.server.config.AppEnvironment
import org.teenpatti.server.privateroom.PrivateRoomManager
import org.teenpatti.server.publictable.PublicTableManager
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Component
@Suppress("UNCHECKED_CAST")
internal class RedisRealtimeBus(
    private val objectMapper: ObjectMapper,
    private val redis: StringRedisTemplate,
    private val listenerContainer: RedisMessageListenerContainer,
    private val publicTableManagersProvider: ObjectProvider<Map<String, PublicTableManager>>,
    private val privateRoomManagerProvider: ObjectProvider<PrivateRoomManager>,
    private val env: AppEnvironment,
) : MessageListener {
    private val pendingResults = ConcurrentHashMap<String, CompletableFuture<RedisCommandResult>>()
    private val eventConsumers = ConcurrentHashMap<String, (RedisAggregateEvent) -> Unit>()

    @PostConstruct
    fun start() {
        listenerContainer.addMessageListener(this, ChannelTopic.of(commandChannel()))
        listenerContainer.addMessageListener(this, ChannelTopic.of(resultChannel()))
        listenerContainer.addMessageListener(this, ChannelTopic.of(eventChannel()))
    }

    @PreDestroy
    fun stop() {
        listenerContainer.removeMessageListener(this)
    }

    fun registerEventConsumer(consumer: (RedisAggregateEvent) -> Unit): String {
        val id = UUID.randomUUID().toString()
        eventConsumers[id] = consumer
        return id
    }

    fun removeEventConsumer(id: String) {
        eventConsumers.remove(id)
    }

    fun sendCommand(
        aggregateType: String,
        aggregateId: String,
        variantId: String?,
        commandType: String,
        payload: Map<String, Any?>,
    ): RedisCommandResult {
        try {
            val command = RedisCommand()
            command.requestId = UUID.randomUUID().toString()
            command.requesterNodeId = env.appNodeId
            command.aggregateType = aggregateType
            command.aggregateId = aggregateId
            command.variantId = variantId
            command.commandType = commandType
            command.payload = LinkedHashMap(payload)
            val future = CompletableFuture<RedisCommandResult>()
            pendingResults[command.requestId] = future
            redis.convertAndSend(commandChannel(), objectMapper.writeValueAsString(command))
            val result = future.get(8, TimeUnit.SECONDS)
            if (result.status != "ok") {
                throw AppException(result.code ?: "command_failed", result.message ?: "Command failed.")
            }
            return result
        } catch (error: Exception) {
            throw if (error is IllegalStateException) error else AppException("redis_command_failed", "Redis command failed.", error)
        }
    }

    fun publishEvent(aggregateType: String, aggregateId: String, eventType: String) {
        try {
            val event = RedisAggregateEvent()
            event.aggregateType = aggregateType
            event.aggregateId = aggregateId
            event.eventType = eventType
            redis.convertAndSend(eventChannel(), objectMapper.writeValueAsString(event))
        } catch (error: Exception) {
            throw AppException("redis_event_publish_failed", "Unable to publish redis event.", error)
        }
    }

    override fun onMessage(message: Message, pattern: ByteArray?) {
        val channel = String(message.channel, StandardCharsets.UTF_8)
        val payload = String(message.body, StandardCharsets.UTF_8)
        try {
            when (channel) {
                commandChannel() -> handleCommand(objectMapper.readValue(payload, RedisCommand::class.java))
                resultChannel() -> {
                    val result = objectMapper.readValue(payload, RedisCommandResult::class.java)
                    pendingResults.remove(result.requestId)?.complete(result)
                }
                eventChannel() -> {
                    val event = objectMapper.readValue(payload, RedisAggregateEvent::class.java)
                    eventConsumers.values.forEach { it(event) }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun handleCommand(command: RedisCommand) {
        try {
            val result =
                when (command.aggregateType) {
                    "public_table" -> handlePublicCommand(command)
                    "private_room" -> handlePrivateRoomCommand(command)
                    else -> errorResult(command, AppException.badRequest("unsupported_aggregate_type", "Unsupported aggregate type."))
                }
            redis.convertAndSend(resultChannel(), objectMapper.writeValueAsString(result))
        } catch (error: Exception) {
            try {
                redis.convertAndSend(resultChannel(), objectMapper.writeValueAsString(errorResult(command, error)))
            } catch (_: Exception) {
            }
        }
    }

    private fun handlePublicCommand(command: RedisCommand): RedisCommandResult {
        val publicTableManager = publicTableManagersProvider.getObject()[command.variantId]
        if (publicTableManager == null) {
            return errorResult(
                command,
                AppException.badRequest("unsupported_variant", "Unsupported game variant: ${command.variantId}"),
            )
        }
        val payload = command.payload
        val data =
            when (command.commandType) {
                "connect" -> publicTableManager.connect(payload["playerId"] as String, payload["playerToken"] as String)
                "snapshot" -> publicTableManager.getSessionSnapshot(payload["playerId"] as String, payload["playerToken"] as String)
                "action" ->
                    publicTableManager.performAction(
                        payload["playerId"] as String,
                        payload["playerToken"] as String,
                        payload["actionType"] as String,
                        payload["payload"] as? Map<String, Any?> ?: emptyMap(),
                    )
                "leave" -> publicTableManager.leave(payload["playerId"] as String, payload["playerToken"] as String)
                "disconnect" -> {
                    publicTableManager.disconnect(payload["playerId"] as String, payload["playerToken"] as String)
                    null
                }
                else -> throw AppException.badRequest("unsupported_public_table_command", "Unsupported public table command.")
            }
        return okResult(command, data)
    }

    private fun handlePrivateRoomCommand(command: RedisCommand): RedisCommandResult {
        val privateRoomManager = privateRoomManagerProvider.getObject()
        if (!privateRoomManager.ensureOwnership(command.aggregateId)) {
            return errorResult(
                command,
                AppException.badRequest(
                    "private_room_ownership_unavailable",
                    "Private room is currently handled by another server node.",
                ),
            )
        }
        val payload = command.payload
        val data =
            when (command.commandType) {
                "connect" ->
                    privateRoomManager.authenticate(
                        payload["roomCode"] as String,
                        payload["playerId"] as String,
                        payload["playerToken"] as String,
                    )
                "snapshot" ->
                    privateRoomManager.getSession(
                        payload["roomCode"] as String,
                        payload["playerId"] as String,
                        payload["playerToken"] as String,
                    )
                "action" ->
                    privateRoomManager.performAction(
                        payload["roomCode"] as String,
                        payload["playerId"] as String,
                        payload["playerToken"] as String,
                        payload["actionType"] as String,
                        payload["payload"] as? Map<String, Any?> ?: emptyMap(),
                    )
                "start_round" ->
                    privateRoomManager.startRound(
                        payload["roomCode"] as String,
                        payload["playerId"] as String,
                        payload["playerToken"] as String,
                    )
                "next_round" ->
                    privateRoomManager.nextRound(
                        payload["roomCode"] as String,
                        payload["playerId"] as String,
                        payload["playerToken"] as String,
                    )
                "accept_next_round" ->
                    privateRoomManager.acceptNextRound(
                        payload["roomCode"] as String,
                        payload["playerId"] as String,
                        payload["playerToken"] as String,
                    )
                "update_config" ->
                    privateRoomManager.updateConfig(
                        payload["roomCode"] as String,
                        payload["playerId"] as String,
                        payload["playerToken"] as String,
                        payload["variant"] as String?,
                        payload["bootAmount"] as Int?,
                    )
                "leave" ->
                    privateRoomManager.leaveRoom(
                        payload["roomCode"] as String,
                        payload["playerId"] as String,
                        payload["playerToken"] as String,
                    )
                "disconnect" -> {
                    privateRoomManager.disconnect(
                        payload["roomCode"] as String,
                        payload["playerId"] as String,
                        payload["playerToken"] as String,
                    )
                    null
                }
                else -> throw AppException.badRequest("unsupported_private_room_command", "Unsupported private room command.")
            }
        return okResult(command, data)
    }

    private fun okResult(command: RedisCommand, data: Map<String, Any?>?): RedisCommandResult {
        val result = RedisCommandResult()
        result.requestId = command.requestId
        result.responderNodeId = env.appNodeId
        result.status = "ok"
        result.data = data?.let { LinkedHashMap(it) }
        return result
    }

    private fun errorResult(command: RedisCommand, error: Exception): RedisCommandResult {
        val result = RedisCommandResult()
        result.requestId = command.requestId
        result.responderNodeId = env.appNodeId
        result.status = "error"
        result.code = ApiSupport.errorCode(error)
        result.message = error.message ?: "Command failed."
        return result
    }

    private fun commandChannel(): String = "${env.redisKeyPrefix}:bus:commands"

    private fun resultChannel(): String = "${env.redisKeyPrefix}:bus:results"

    private fun eventChannel(): String = "${env.redisKeyPrefix}:bus:events"
}
