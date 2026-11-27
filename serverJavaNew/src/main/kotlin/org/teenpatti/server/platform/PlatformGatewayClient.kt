package org.teenpatti.server.platform

import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.teenpatti.server.common.AppException
import org.teenpatti.server.config.AppEnvironment
import java.net.http.HttpClient
import java.time.Duration

@Component
internal class PlatformGatewayClient(
    private val env: AppEnvironment,
) {
    private val operationsClient: RestClient? =
        env.operationsBalanceUrl.trim().takeIf { it.isNotBlank() }?.let {
            val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            val factory =
                JdkClientHttpRequestFactory(httpClient).apply {
                    setReadTimeout(Duration.ofSeconds(8))
                }
            RestClient.builder().requestFactory(factory).build()
        }

    /**
     * Fetches the player's initial wallet balance via Operations internal endpoint (triggers opType 2051):
     * POST /api/v1/wallet/balance with X-Internal-Key and {"token", "partner_code"}.
     */
    fun fetchInitialBalance(
        token: String,
        partnerCode: String,
        fallbackUserId: String? = null,
        fallbackUsername: String? = null,
        fallbackCurrency: String? = null,
    ): PlatformUser {
        requireEnabled()
        val normalizedToken = token.trim()
        val normalizedPartnerCode = partnerCode.trim().uppercase()
        if (normalizedToken.isBlank()) {
            throw AppException.badRequest("platform_token_required", "Platform token is required.")
        }
        if (normalizedPartnerCode.isBlank()) {
            throw AppException.badRequest("platform_partner_code_required", "Platform partner_code is required.")
        }
        val internalKey = requireInternalServiceKey()
        val response = postBalanceWithRetry(normalizedToken, normalizedPartnerCode, internalKey)

        val dataMap = mapValue(response["data"])
        val candidates =
            listOfNotNull(
                dataMap?.let { mapValue(it["data"]) },
                dataMap?.let { mapValue(it["wallet"]) },
                dataMap?.let { mapValue(it["user"]) },
                mapValue(response["wallet"]),
                mapValue(response["user"]),
                dataMap,
                response,
            )

        val user = PlatformUser()
        val resolvedUsername =
            firstStringValue(candidates, "username", "user_name", "name", "displayName")
                ?: fallbackUsername?.trim()?.takeIf { it.isNotBlank() }
        val resolvedUserId =
            firstStringValue(candidates, "user_id", "userId", "id", "_id", "uid")
                ?: fallbackUserId?.trim()?.takeIf { it.isNotBlank() }
                ?: resolvedUsername?.let { "$normalizedPartnerCode:$it" }
                ?: normalizedToken

        user.userId = resolvedUserId
        user.username = resolvedUsername ?: resolvedUserId
        user.balance =
            firstNumberValue(
                candidates,
                "wallet_balance",
                "walletBalance",
                "total_wallet_balance",
                "totalWalletBalance",
                "balance",
                "coins",
                "amount",
            ) ?: 0
        user.currency =
            (
                firstStringValue(candidates, "currency", "currency_code", "currencyCode")
                    ?: fallbackCurrency?.trim()?.takeIf { it.isNotBlank() }
                    ?: "INR"
            ).uppercase()
        user.operatorId =
            (
                firstStringValue(candidates, "partner_code", "partnerCode", "operator_id", "operatorId")
                    ?: normalizedPartnerCode
            ).uppercase()
        return user
    }

    private fun postBalanceWithRetry(
        token: String,
        partnerCode: String,
        internalKey: String,
    ): Map<String, Any?> {
        val maxAttempts = 2
        var lastFailure: OperationsErrorSupport.ClassifiedFailure? = null
        for (attempt in 1..maxAttempts) {
            try {
                val response =
                    client()
                        .post()
                        .uri(env.operationsBalanceUrl.trim())
                        .header("X-Internal-Key", internalKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(OperationsBalanceRequest(token, partnerCode))
                        .retrieve()
                        .body(object : ParameterizedTypeReference<Map<String, Any?>>() {})
                        ?: throw AppException.badRequest(
                            "platform_balance_failed",
                            "Operations wallet balance returned an empty response.",
                        )
                if (OperationsErrorSupport.isFailurePayload(response)) {
                    val failure =
                        OperationsErrorSupport.classifyPayloadFailure(
                            response,
                            "Unable to fetch initial balance from operations.",
                        )
                    lastFailure = failure
                    if (!failure.retryable || attempt >= maxAttempts) {
                        throw AppException.badRequest(failure.appCode, failure.message)
                    }
                    continue
                }
                return response
            } catch (error: AppException) {
                throw error
            } catch (error: Exception) {
                val failure =
                    OperationsErrorSupport.classifyException(
                        error,
                        "Unable to fetch initial balance from operations.",
                    )
                lastFailure = failure
                if (!failure.retryable || attempt >= maxAttempts) {
                    throw AppException.badRequest(failure.appCode, failure.message)
                }
            }
        }
        throw AppException.badRequest(
            lastFailure?.appCode ?: "platform_balance_failed",
            lastFailure?.message ?: "Unable to fetch initial balance from operations.",
        )
    }

    private fun requireEnabled() {
        if (!env.platformEnabled) {
            throw AppException.badRequest("platform_disabled", "Platform integration is disabled.")
        }
    }

    private fun requireInternalServiceKey(): String =
        env.internalServiceKey.trim().takeIf { it.isNotBlank() }
            ?: throw AppException.badRequest(
                "platform_internal_key_missing",
                "INTERNAL_SERVICE_KEY is not configured.",
            )

    private fun client(): RestClient =
        operationsClient
            ?: throw AppException.badRequest(
                "platform_balance_url_missing",
                "Operations wallet balance URL is not configured.",
            )

    private fun mapValue(value: Any?): Map<*, *>? = value as? Map<*, *>

    private fun stringValue(value: Any?): String? = value?.toString()?.trim()?.takeIf { it.isNotBlank() }

    private fun numberValue(value: Any?): Int? =
        when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toDoubleOrNull()?.toInt()
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
