package org.teenpatti.server.infrastructure.realtime

import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.teenpatti.server.config.AppEnvironment

@Configuration
@EnableWebSocket
internal class NewWebSocketConfig(
    private val env: AppEnvironment,
    private val publicTableWebSocketHandler: PublicTableWebSocketHandler,
    private val privateRoomWebSocketHandler: PrivateRoomWebSocketHandler,
) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        val origins = env.clientOrigins()
        registry
            .addHandler(publicTableWebSocketHandler, "/ws/public-tables")
            .setAllowedOrigins(*origins)
        registry
            .addHandler(privateRoomWebSocketHandler, "/ws/private-rooms")
            .setAllowedOrigins(*origins)
    }
}
