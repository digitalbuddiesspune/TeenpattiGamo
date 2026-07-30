package org.teenpatti.server.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.net.URI

@Configuration
internal class RedisConfig {
    @Bean
    fun redisConnectionFactory(env: AppEnvironment): LettuceConnectionFactory {
        val uri = URI.create(env.redisUrl)
        val config = RedisStandaloneConfiguration()
        config.hostName = uri.host
        config.port = if (uri.port > 0) uri.port else 6379
        if (uri.userInfo != null && uri.userInfo.contains(":")) {
            val parts = uri.userInfo.split(":", limit = 2)
            if (parts.size == 2 && parts[1].isNotBlank()) {
                config.setPassword(parts[1])
            }
        }
        if (uri.path != null && uri.path.length > 1) {
            config.database = uri.path.substring(1).toInt()
        }
        return LettuceConnectionFactory(config, LettuceClientConfiguration.builder().build())
    }

    @Bean
    fun stringRedisTemplate(factory: LettuceConnectionFactory): StringRedisTemplate = StringRedisTemplate(factory)

    @Bean
    fun redisMessageListenerContainer(factory: LettuceConnectionFactory): RedisMessageListenerContainer {
        val container = RedisMessageListenerContainer()
        container.setConnectionFactory(factory)
        return container
    }
}
