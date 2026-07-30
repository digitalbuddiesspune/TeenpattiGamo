package org.teenpatti.server.platform

import org.springframework.amqp.core.MessageDeliveryMode
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter
import org.springframework.stereotype.Component
import org.teenpatti.server.common.AppException
import org.teenpatti.server.config.AppEnvironment

internal interface PlatformWalletPublisher {
    fun publish(message: PlatformCreditQueueMessage)
}

@Component
internal class RabbitPlatformWalletPublisher(
    private val env: AppEnvironment,
    connectionFactory: ConnectionFactory,
    messageConverter: Jackson2JsonMessageConverter,
) : PlatformWalletPublisher {
    private val rabbitTemplate =
        RabbitTemplate(connectionFactory).apply {
            this.messageConverter = messageConverter
        }

    override fun publish(message: PlatformCreditQueueMessage) {
        try {
            rabbitTemplate.convertAndSend(env.platformAmqpExchange, env.platformAmqpRoutingKey, message) { outbound ->
                outbound.messageProperties.contentType = "application/json"
                outbound.messageProperties.deliveryMode = MessageDeliveryMode.PERSISTENT
                outbound
            }
        } catch (error: Exception) {
            throw AppException.badRequest("platform_balance_failed", error.message ?: "Platform balance publish failed.")
        }
    }
}
