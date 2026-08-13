package org.teenpatti.server.config

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.util.UUID

@Configuration
internal class AppEnvironmentConfig {
    @Bean
    fun appEnvironment(environment: Environment): AppEnvironment {
        val env = AppEnvironment()
        env.production = environment.matchesProfiles("production")
        env.port = number(environment, "PORT", 4100)
        env.clientOrigin = text(environment, "CLIENT_ORIGIN", "http://localhost:3000")
        env.mongoUri = text(environment, "MONGODB_URI", "mongodb://localhost:27017/teen_patti_casino")
        env.mongoDbName = text(environment, "MONGODB_DB_NAME", "teen_patti_casino")
        env.redisUrl = text(environment, "REDIS_URL", "redis://localhost:6379")
        env.redisKeyPrefix = text(environment, "REDIS_KEY_PREFIX", "teen-patti")
        env.appNodeId = text(environment, "APP_NODE_ID", UUID.randomUUID().toString())
        env.reconnectGraceMs = longNumber(environment, "WS_RECONNECT_GRACE_MS", 15_000L)
        env.privateRoomTtlMs = longNumber(environment, "PRIVATE_ROOM_TTL_MS", 604_800_000L)
        env.tableId = text(environment, "TABLE_ID", "teen-patti-premium")
        env.bootAmount = number(environment, "BOOT_AMOUNT", 1000)
        env.maxPotAmount = number(environment, "MAX_POT_AMOUNT", 320000)
        env.minStake = number(environment, "MIN_STAKE", 1000)
        env.maxStake = number(environment, "MAX_STAKE", 64000)
        env.maxRoundsBeforeForcedShow = number(environment, "MAX_ROUNDS_BEFORE_FORCED_SHOW", 18)
        env.playerCount = number(environment, "PLAYER_COUNT", 5)
        env.publicTableMaxBots = number(environment, "MAX_PUBLIC_TABLE_BOTS", env.playerCount - 1)
        env.matchmakingWindowMs = longNumber(environment, "MATCHMAKING_WINDOW_MS", 5_000L)
        env.matchmakingPvpThreshold = number(environment, "MATCHMAKING_PVP_THRESHOLD", 1)
        env.casinoBootCommissionPercent = number(environment, "CASINO_BOOT_COMMISSION_PERCENT", 5)
        env.casinoWinCommissionPercent = number(environment, "CASINO_WIN_COMMISSION_PERCENT", 10)
        env.initialBalance = number(environment, "INITIAL_BALANCE", 30000000)
        env.turnDurationMs = number(environment, "TURN_DURATION_MS", 15000)
        env.platformEnabled = boolean(environment, "PLATFORM_ENABLED", false)
        env.appOperatorBaseUrl = text(environment, "APP_OPERATOR_BASE_URL", "")
        env.appOperatorUserDetailPath = text(environment, "APP_OPERATOR_USER_DETAIL_PATH", "")
        env.appOperatorBalancePath = text(environment, "APP_OPERATOR_BALANCE_PATH", "")
        env.appOperatorLoginPath = text(environment, "APP_OPERATOR_LOGIN_PATH", "")
        env.platformUserDetailUrl = joinUrl(env.appOperatorBaseUrl, env.appOperatorUserDetailPath)
        env.platformDebitUrl = joinUrl(env.appOperatorBaseUrl, env.appOperatorBalancePath)
        env.platformLoginUrl = joinUrl(env.appOperatorBaseUrl, env.appOperatorLoginPath)
        env.platformAmqpUrl = text(environment, "PLATFORM_AMQP_URL", "")
        env.platformAmqpExchange = text(environment, "PLATFORM_AMQP_EXCHANGE", "")
        env.platformAmqpRoutingKey =
            text(environment, "PLATFORM_AMQP_ROUTING_KEY", text(environment, "PLATFORM_AMQP_QUEUE_NAME", ""))
        env.platformPubKey = text(environment, "PLATFORM_PUB_KEY", "")
        env.platformSecret = text(environment, "PLATFORM_SECRET", "")
        env.platformGameId = number(environment, "PLATFORM_GAME_ID", 0)
        validatePublicTableBotConfig(env)
        validatePlatformConfig(env)
        return env
    }

    @Bean
    @Qualifier("appNodeId")
    fun appNodeId(env: AppEnvironment): String = env.appNodeId

    private fun validatePublicTableBotConfig(env: AppEnvironment) {
        check(env.publicTableMaxBots >= 1) { "MAX_PUBLIC_TABLE_BOTS must be at least 1." }
        check(env.publicTableMaxBots < env.playerCount) { "MAX_PUBLIC_TABLE_BOTS must be less than PLAYER_COUNT." }
        check(env.matchmakingWindowMs >= 1_000L) { "MATCHMAKING_WINDOW_MS must be at least 1000." }
        check(env.matchmakingPvpThreshold >= 1) {
            "MATCHMAKING_PVP_THRESHOLD must be at least 1."
        }
    }

    private fun validatePlatformConfig(env: AppEnvironment) {
        if (!env.platformEnabled) {
            return
        }
        check(env.appOperatorBaseUrl.isNotBlank()) { "APP_OPERATOR_BASE_URL is required when PLATFORM_ENABLED=true." }
        check(env.appOperatorUserDetailPath.isNotBlank()) {
            "APP_OPERATOR_USER_DETAIL_PATH is required when PLATFORM_ENABLED=true."
        }
        check(env.appOperatorBalancePath.isNotBlank()) {
            "APP_OPERATOR_BALANCE_PATH is required when PLATFORM_ENABLED=true."
        }
        check(env.appOperatorLoginPath.isNotBlank()) {
            "APP_OPERATOR_LOGIN_PATH is required when PLATFORM_ENABLED=true."
        }
        check(env.platformAmqpUrl.isNotBlank()) { "PLATFORM_AMQP_URL is required when PLATFORM_ENABLED=true." }
        check(env.platformAmqpExchange.isNotBlank()) { "PLATFORM_AMQP_EXCHANGE is required when PLATFORM_ENABLED=true." }
        check(env.platformAmqpRoutingKey.isNotBlank()) {
            "PLATFORM_AMQP_ROUTING_KEY or PLATFORM_AMQP_QUEUE_NAME is required when PLATFORM_ENABLED=true."
        }
        check(env.platformGameId > 0) { "PLATFORM_GAME_ID is required when PLATFORM_ENABLED=true." }
    }

    private fun joinUrl(baseUrl: String, path: String): String {
        val base = baseUrl.trim().trimEnd('/')
        val normalizedPath = path.trim().let { if (it.startsWith("/")) it else "/$it" }
        if (base.isBlank() || path.isBlank()) {
            return ""
        }
        return base + normalizedPath
    }

    private fun number(environment: Environment, name: String, fallback: Int): Int {
        val raw = readText(environment, name)
        return if (raw.isNullOrBlank()) fallback else raw.toInt()
    }

    private fun longNumber(environment: Environment, name: String, fallback: Long): Long {
        val raw = readText(environment, name)
        return if (raw.isNullOrBlank()) fallback else raw.toLong()
    }

    private fun text(environment: Environment, name: String, fallback: String): String {
        val raw = readText(environment, name)
        return if (raw.isNullOrBlank()) fallback else raw
    }

    private fun boolean(environment: Environment, name: String, fallback: Boolean): Boolean {
        val raw = readText(environment, name)
        return if (raw.isNullOrBlank()) fallback else raw.equals("true", ignoreCase = true) || raw == "1"
    }

    private fun readText(environment: Environment, name: String): String? {
        val raw = environment.getProperty(name)
        return if (raw.isNullOrBlank()) null else raw
    }
}
