package org.teenpatti.server.infrastructure.realtime

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.teenpatti.server.config.AppEnvironment
import java.util.concurrent.TimeUnit

@Component
internal class RedisPresenceService(
    private val redis: StringRedisTemplate,
    private val env: AppEnvironment,
) {
    fun markConnected(scope: String, subjectId: String, marker: String) {
        redis.opsForValue().set(key(scope, subjectId), marker, env.reconnectGraceMs * 4, TimeUnit.MILLISECONDS)
    }

    fun markDisconnected(scope: String, subjectId: String) {
        redis.delete(key(scope, subjectId))
    }

    fun isConnected(scope: String, subjectId: String): Boolean = redis.hasKey(key(scope, subjectId)) == true

    private fun key(scope: String, subjectId: String): String = "${env.redisKeyPrefix}:presence:$scope:$subjectId"
}
