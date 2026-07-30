package org.teenpatti.server.config

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

internal class HealthResponse(
    val status: String,
    val timestamp: String,
    val database: String,
    val redis: String,
    val nodeId: String,
)

@RestController
internal class HealthController(
    private val env: AppEnvironment,
) {
    @GetMapping("/health")
    fun health(): HealthResponse =
        HealthResponse(
            status = "ok",
            timestamp = Instant.now().toString(),
            database = env.mongoDbName,
            redis = env.redisUrl,
            nodeId = env.appNodeId,
        )
}
