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
    fun appEnvironmentRequiresInternalServiceKeyWhenPlatformEnabled() {
        val appConfig = AppEnvironmentConfig()
        val environment = platformEnvWithout("INTERNAL_SERVICE_KEY")

        val error = assertThrows(IllegalStateException::class.java) { appConfig.appEnvironment(environment) }
        assertEquals("INTERNAL_SERVICE_KEY is required when PLATFORM_ENABLED=true.", error.message)
    }

    @Test
    fun appEnvironmentResolvesOperationsInternalWalletUrlsByDefault() {
        val appConfig = AppEnvironmentConfig()
        val environment = completePlatformEnv()

        val result = appConfig.appEnvironment(environment)

        assertEquals("https://operations.dpbossking.com/api/v1/wallet/balance", result.operationsBalanceUrl)
        assertEquals("https://operations.dpbossking.com/api/v1/wallet/credit-transfer", result.operationsCreditTransferUrl)
        assertEquals("internal-secret-key", result.internalServiceKey)
    }

    @Test
    fun appEnvironmentSupportsCustomOperationsBaseUrlAndInternalKeyAlias() {
        val appConfig = AppEnvironmentConfig()
        val environment =
            MockEnvironment().also {
                it.setProperty("PLATFORM_ENABLED", "true")
                it.setProperty("OPERATIONS_BASE_URL", "https://ops.example.com/")
                it.setProperty("OPERATIONS_INTERNAL_KEY", "alias-secret")
                it.setProperty("OPERATIONS_DEFAULT_PARTNER_CODE", "alpha-test")
            }

        val result = appConfig.appEnvironment(environment)

        assertEquals("https://ops.example.com/api/v1/wallet/balance", result.operationsBalanceUrl)
        assertEquals("https://ops.example.com/api/v1/wallet/credit-transfer", result.operationsCreditTransferUrl)
        assertEquals("alias-secret", result.internalServiceKey)
        assertEquals("ALPHA-TEST", result.operationsDefaultPartnerCode)
    }

    private fun completePlatformEnv(): MockEnvironment =
        MockEnvironment().also {
            it.setProperty("PLATFORM_ENABLED", "true")
            it.setProperty("INTERNAL_SERVICE_KEY", "internal-secret-key")
        }

    private fun platformEnvWithout(property: String): MockEnvironment =
        completePlatformEnv().also { it.setProperty(property, "") }
}
