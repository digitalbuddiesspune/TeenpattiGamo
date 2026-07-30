package org.teenpatti.server


import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.time.Instant


import org.teenpatti.server.common.*
import org.teenpatti.server.config.*
import org.teenpatti.server.game.*
import org.teenpatti.server.infrastructure.persistence.*
import org.teenpatti.server.privateroom.*
import org.teenpatti.server.publictable.*
internal class ConfigSmokeTest {
    @Test
    fun appEnvironmentRejectsZeroPublicTableBots() {
        val appConfig = AppEnvironmentConfig()
        val environment = MockEnvironment()
        environment.setProperty("PLAYER_COUNT", "5")
        environment.setProperty("MAX_PUBLIC_TABLE_BOTS", "0")

        val error = assertThrows(IllegalStateException::class.java) { appConfig.appEnvironment(environment) }
        assertEquals("MAX_PUBLIC_TABLE_BOTS must be at least 1.", error.message)
    }

    @Test
    fun appEnvironmentRejectsPublicTableBotCapAtOrAbovePlayerCount() {
        val appConfig = AppEnvironmentConfig()
        val environment = MockEnvironment()
        environment.setProperty("PLAYER_COUNT", "5")
        environment.setProperty("MAX_PUBLIC_TABLE_BOTS", "5")

        val error = assertThrows(IllegalStateException::class.java) { appConfig.appEnvironment(environment) }
        assertEquals("MAX_PUBLIC_TABLE_BOTS must be less than PLAYER_COUNT.", error.message)
    }

    @Test
    fun appEnvironmentRequiresPlatformBalanceUrlWhenPlatformEnabled() {
        val appConfig = AppEnvironmentConfig()
        val environment = MockEnvironment()
        environment.setProperty("PLATFORM_ENABLED", "true")
        environment.setProperty("PLATFORM_AMQP_URL", "amqp://guest:guest@localhost:5672/")
        environment.setProperty("PLATFORM_AMQP_EXCHANGE", "/games/admin")
        environment.setProperty("PLATFORM_AMQP_ROUTING_KEY", "games_cashout")
        environment.setProperty("PLATFORM_DEBIT_URL", "https://platform.example/service/operator/user/balance/v2")

        val error = assertThrows(IllegalStateException::class.java) { appConfig.appEnvironment(environment) }
        assertEquals("PLATFORM_BALANCE_URL is required when PLATFORM_ENABLED=true.", error.message)
    }

    @Test
    fun appEnvironmentRequiresPlatformDebitUrlWhenPlatformEnabled() {
        val appConfig = AppEnvironmentConfig()
        val environment = MockEnvironment()
        environment.setProperty("PLATFORM_ENABLED", "true")
        environment.setProperty("PLATFORM_BALANCE_URL", "https://platform.example/operator/user/detail")
        environment.setProperty("PLATFORM_AMQP_URL", "amqp://guest:guest@localhost:5672/")
        environment.setProperty("PLATFORM_AMQP_EXCHANGE", "/games/admin")
        environment.setProperty("PLATFORM_AMQP_ROUTING_KEY", "games_cashout")

        val error = assertThrows(IllegalStateException::class.java) { appConfig.appEnvironment(environment) }
        assertEquals("PLATFORM_DEBIT_URL is required when PLATFORM_ENABLED=true.", error.message)
    }

    @Test
    fun appEnvironmentRequiresPlatformAmqpUrlWhenPlatformEnabled() {
        val appConfig = AppEnvironmentConfig()
        val environment = MockEnvironment()
        environment.setProperty("PLATFORM_ENABLED", "true")
        environment.setProperty("PLATFORM_BALANCE_URL", "https://platform.example/operator/user/detail")
        environment.setProperty("PLATFORM_DEBIT_URL", "https://platform.example/service/operator/user/balance/v2")
        environment.setProperty("PLATFORM_AMQP_EXCHANGE", "/games/admin")
        environment.setProperty("PLATFORM_AMQP_ROUTING_KEY", "games_cashout")

        val error = assertThrows(IllegalStateException::class.java) { appConfig.appEnvironment(environment) }
        assertEquals("PLATFORM_AMQP_URL is required when PLATFORM_ENABLED=true.", error.message)
    }

    @Test
    fun appEnvironmentRequiresPlatformAmqpExchangeWhenPlatformEnabled() {
        val appConfig = AppEnvironmentConfig()
        val environment = MockEnvironment()
        environment.setProperty("PLATFORM_ENABLED", "true")
        environment.setProperty("PLATFORM_BALANCE_URL", "https://platform.example/operator/user/detail")
        environment.setProperty("PLATFORM_DEBIT_URL", "https://platform.example/service/operator/user/balance/v2")
        environment.setProperty("PLATFORM_AMQP_URL", "amqp://guest:guest@localhost:5672/")
        environment.setProperty("PLATFORM_AMQP_ROUTING_KEY", "games_cashout")

        val error = assertThrows(IllegalStateException::class.java) { appConfig.appEnvironment(environment) }
        assertEquals("PLATFORM_AMQP_EXCHANGE is required when PLATFORM_ENABLED=true.", error.message)
    }

    @Test
    fun appEnvironmentAcceptsPlatformQueueNameAsRoutingKeyFallback() {
        val appConfig = AppEnvironmentConfig()
        val environment = MockEnvironment()
        environment.setProperty("PLATFORM_ENABLED", "true")
        environment.setProperty("PLATFORM_BALANCE_URL", "https://platform.example/operator/user/detail")
        environment.setProperty("PLATFORM_DEBIT_URL", "https://platform.example/service/operator/user/balance/v2")
        environment.setProperty("PLATFORM_AMQP_URL", "amqp://guest:guest@localhost:5672/")
        environment.setProperty("PLATFORM_AMQP_EXCHANGE", "/games/admin")
        environment.setProperty("PLATFORM_AMQP_QUEUE_NAME", "games_cashout")

        val result = appConfig.appEnvironment(environment)

        assertEquals("games_cashout", result.platformAmqpRoutingKey)
    }
}
