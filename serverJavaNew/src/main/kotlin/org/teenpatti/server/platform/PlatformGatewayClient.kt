package org.teenpatti.server.platform

import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.teenpatti.server.common.AppException
import org.teenpatti.server.config.AppEnvironment

@Component
internal class PlatformGatewayClient(
    private val env: AppEnvironment,
) {
    private val balanceClient: RestClient? =
        env.platformBalanceUrl.trim().takeIf { it.isNotBlank() }?.let { RestClient.builder().build() }

    fun getUserDetail(token: String): PlatformUser {
        requireEnabled()
        val response =
            client()
                .get()
                .uri(env.platformBalanceUrl.trim())
                .header("token", token)
                .retrieve()
                .body(PlatformEnvelope::class.java)
                ?: throw AppException.badRequest("platform_user_detail_failed", "Platform user detail returned an empty response.")
        if (!response.status) {
            throw AppException.badRequest("platform_user_detail_failed", response.msg ?: "Unable to fetch platform user.")
        }
        val data = response.data ?: emptyMap()
        val userDataCandidates =
            listOfNotNull(
                mapValue(data["data"]),
                mapValue(data["user"]),
                response.user,
                data,
            )
        val userData = userDataCandidates.firstOrNull() ?: data
        val user = PlatformUser()
        user.userId =
            firstStringValue(userDataCandidates, "user_id", "userId", "id", "_id", "uid")
                ?: ""
        user.username =
            firstStringValue(userDataCandidates, "username", "user_name", "name", "displayName")
                ?: user.userId
        user.balance =
            firstNumberValue(
                userDataCandidates,
                "wallet_balance",
                "walletBalance",
                "total_wallet_balance",
                "totalWalletBalance",
                "coins",
                "balance",
            ) ?: 0
        user.currency = firstStringValue(userDataCandidates, "currency", "currency_code", "currencyCode") ?: ""
        user.operatorId = firstStringValue(userDataCandidates, "operator_id", "operatorId", "operatorID") ?: ""
        return user
    }

    private fun requireEnabled() {
        if (!env.platformEnabled) {
            throw AppException.badRequest("platform_disabled", "Platform integration is disabled.")
        }
    }

    private fun client(): RestClient =
        balanceClient
            ?: throw AppException.badRequest("platform_balance_url_missing", "Platform balance URL is not configured.")

    private fun mapValue(value: Any?): Map<*, *>? = value as? Map<*, *>

    private fun stringValue(value: Any?): String? = value?.toString()?.takeIf { it.isNotBlank() }

    private fun numberValue(value: Any?): Int? =
        when (value) {
            is Number -> value.toInt()
            is String -> value.toDoubleOrNull()?.toInt()
            else -> null
        }

    private fun firstStringValue(sources: List<Map<*, *>>, vararg keys: String): String? {
        for (key in keys) {
            for (source in sources) {
                val value = stringValue(source[key])
                if (value != null) {
                    return value
                }
            }
        }
        return null
    }

    private fun firstNumberValue(sources: List<Map<*, *>>, vararg keys: String): Int? {
        for (key in keys) {
            for (source in sources) {
                val value = numberValue(source[key])
                if (value != null) {
                    return value
                }
            }
        }
        return null
    }
}
