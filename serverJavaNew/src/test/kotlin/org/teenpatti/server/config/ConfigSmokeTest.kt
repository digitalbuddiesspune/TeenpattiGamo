package org.teenpatti.server


import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import org.teenpatti.server.config.AppEnvironmentConfig

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
    fun appEnvironmentRequiresOperatorBaseUrlWhenPlatformEnabled() {
        val appConfig = AppEnvironmentConfig()
        val environment = platformEnvWithout("APP_OPERATOR_BASE_URL")

        val error = assertThrows(IllegalStateException::class.java) { appConfig.appEnvironment(environment) }
        assertEquals("APP_OPERATOR_BASE_URL is required when PLATFORM_ENABLED=true.", error.message)
    }

    @Test
    fun appEnvironmentRequiresOperatorUserDetailPathWhenPlatformEnabled() {
        val appConfig = AppEnvironmentConfig()
        val environment = platformEnvWithout("APP_OPERATOR_USER_DETAIL_PATH")

        val error = assertThrows(IllegalStateException::class.java) { appConfig.appEnvironment(environment) }
        assertEquals("APP_OPERATOR_USER_DETAIL_PATH is required when PLATFORM_ENABLED=true.", error.message)
    }

    @Test
    fun appEnvironmentRequiresOperatorBalancePathWhenPlatformEnabled() {
        val appConfig = AppEnvironmentConfig()
        val environment = platformEnvWithout("APP_OPERATOR_BALANCE_PATH")

        val error = assertThrows(IllegalStateException::class.java) { appConfig.appEnvironment(environment) }
        assertEquals("APP_OPERATOR_BALANCE_PATH is required when PLATFORM_ENABLED=true.", error.message)
    }

    @Test
    fun appEnvironmentRequiresOperatorCreditPathWhenPlatformEnabled() {
        val appConfig = AppEnvironmentConfig()
        val environment = platformEnvWithout("APP_OPERATOR_CREDIT_PATH")

        val error = assertThrows(IllegalStateException::class.java) { appConfig.appEnvironment(environment) }
        assertEquals("APP_OPERATOR_CREDIT_PATH is required when PLATFORM_ENABLED=true.", error.message)
    }

    @Test
    fun appEnvironmentRequiresOperatorLoginPathWhenPlatformEnabled() {
        val appConfig = AppEnvironmentConfig()
        val environment = platformEnvWithout("APP_OPERATOR_LOGIN_PATH")

        val error = assertThrows(IllegalStateException::class.java) { appConfig.appEnvironment(environment) }
        assertEquals("APP_OPERATOR_LOGIN_PATH is required when PLATFORM_ENABLED=true.", error.message)
    }

    @Test
    fun appEnvironmentRequiresPlatformAmqpUrlWhenPlatformEnabled() {
        val appConfig = AppEnvironmentConfig()
        val environment = platformEnvWithout("PLATFORM_AMQP_URL")

        val error = assertThrows(IllegalStateException::class.java) { appConfig.appEnvironment(environment) }
        assertEquals("PLATFORM_AMQP_URL is required when PLATFORM_ENABLED=true.", error.message)
    }

    @Test
    fun appEnvironmentRequiresPlatformAmqpExchangeWhenPlatformEnabled() {
        val appConfig = AppEnvironmentConfig()
        val environment = platformEnvWithout("PLATFORM_AMQP_EXCHANGE")

        val error = assertThrows(IllegalStateException::class.java) { appConfig.appEnvironment(environment) }
        assertEquals("PLATFORM_AMQP_EXCHANGE is required when PLATFORM_ENABLED=true.", error.message)
    }

    @Test
    fun appEnvironmentAcceptsPlatformQueueNameAsRoutingKeyFallback() {
        val appConfig = AppEnvironmentConfig()
        val environment = completePlatformEnv()
        environment.setProperty("PLATFORM_AMQP_ROUTING_KEY", "")
        environment.setProperty("PLATFORM_AMQP_QUEUE_NAME", "games_cashout")

        val result = appConfig.appEnvironment(environment)

        assertEquals("games_cashout", result.platformAmqpRoutingKey)
        assertEquals("https://platform.example/service/user/detail", result.platformUserDetailUrl)
        assertEquals("https://platform.example/service/operator/user/balance/v2", result.platformDebitUrl)
        assertEquals("https://platform.example/api/wallet/credit/user", result.platformCreditUrl)
        assertEquals("https://platform.example/operator/user/login", result.platformLoginUrl)
    }

    private fun completePlatformEnv(): MockEnvironment =
        MockEnvironment().also {
            it.setProperty("PLATFORM_ENABLED", "true")
            it.setProperty("APP_OPERATOR_BASE_URL", "https://platform.example")
            it.setProperty("APP_OPERATOR_USER_DETAIL_PATH", "/service/user/detail")
            it.setProperty("APP_OPERATOR_BALANCE_PATH", "/service/operator/user/balance/v2")
            it.setProperty("APP_OPERATOR_CREDIT_PATH", "/api/wallet/credit/user")
            it.setProperty("APP_OPERATOR_LOGIN_PATH", "/operator/user/login")
            it.setProperty("PLATFORM_GAME_NAME", "teen-patti")
            it.setProperty("PLATFORM_AMQP_URL", "amqp://guest:guest@localhost:5672/")
            it.setProperty("PLATFORM_AMQP_EXCHANGE", "/games/admin")
            it.setProperty("PLATFORM_AMQP_ROUTING_KEY", "games_cashout")
            it.setProperty("PLATFORM_GAME_ID", "2")
        }

    private fun platformEnvWithout(property: String): MockEnvironment =
        completePlatformEnv().also { it.setProperty(property, "") }
}
