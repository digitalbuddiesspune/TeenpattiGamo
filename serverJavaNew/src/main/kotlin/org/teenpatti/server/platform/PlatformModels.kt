package org.teenpatti.server.platform

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientResponseException
import org.teenpatti.server.common.AppException
import java.io.IOException
import java.net.SocketTimeoutException

internal enum class OperationsGameType(
    val code: String,
    val ttype: Int,
) {
    SPIN("spin", 1),
    FREESPIN("freespin", 1),
    BONUS("bonus", 1),
    GAMBLE("gamble", 1),
    GAMBLE_COLLECT("gamble_collect", 1),
    REFUND("refund", 2),
    ;

    companion object {
        fun fromCode(raw: String?): OperationsGameType {
            val normalized = raw?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.code == normalized }
                ?: throw AppException.badRequest("platform_game_type_invalid", "Unsupported game_type: $raw")
        }
    }
}

internal class OperationsBalanceRequest(
    val token: String,
    val partner_code: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal class OperationsCreditTransferRequest(
    val token: String? = null,
    val partner_code: String? = null,
    val user_id: String? = null,
    val gid: Int,
    val bet: Int,
    val win: Int,
    val game_type: String,
    val tranid: String,
    val ttype: Int,
    val roundid: String,
)

internal object OperationsErrorSupport {
    private val SESSION_EXPIRED_CODES = setOf("ERR-0011", "ERR-0012", "ERR-0013")
    private val RETRYABLE_CODES = setOf("ERR-0020", "ERR-0021")
    private val ERR_CODE_REGEX = Regex("""ERR-\d{4}""", RegexOption.IGNORE_CASE)
    private val mapper = ObjectMapper()

    data class ClassifiedFailure(
        val appCode: String,
        val message: String,
        val retryable: Boolean,
        val operatorCode: String? = null,
    )

    fun extractOperatorCode(response: Map<*, *>?, rawText: String? = null): String? {
        val candidates =
            listOfNotNull(
                response?.get("code"),
                response?.get("error_code"),
                response?.get("errorCode"),
                (response?.get("error") as? Map<*, *>)?.get("code"),
                (response?.get("data") as? Map<*, *>)?.get("code"),
            )
        for (candidate in candidates) {
            val text = candidate?.toString()?.trim()?.uppercase()
            if (!text.isNullOrBlank()) {
                val matched = ERR_CODE_REGEX.find(text)?.value?.uppercase()
                if (matched != null) {
                    return matched
                }
                if (text in SESSION_EXPIRED_CODES || text in RETRYABLE_CODES) {
                    return text
                }
            }
        }
        val combined =
            listOfNotNull(
                rawText,
                response?.get("msg")?.toString(),
                response?.get("message")?.toString(),
                response?.get("error")?.toString(),
            ).joinToString(" ")
        return ERR_CODE_REGEX.find(combined)?.value?.uppercase()
    }

    fun extractMessage(response: Map<*, *>?, fallback: String): String {
        val raw =
            listOfNotNull(
                response?.get("msg"),
                response?.get("message"),
                (response?.get("error") as? Map<*, *>)?.get("message"),
                response?.get("error"),
                (response?.get("data") as? Map<*, *>)?.get("message"),
            ).firstOrNull { it.toString().isNotBlank() }?.toString()?.trim()
        return raw ?: fallback
    }

    fun isFailurePayload(response: Map<String, Any?>): Boolean {
        val statusFlag = response["status"]
        val okFlag = response["ok"]
        val successFlag = response["success"]
        return statusFlag == false ||
            (statusFlag is String && statusFlag.equals("error", ignoreCase = true)) ||
            (statusFlag is String && statusFlag.equals("failed", ignoreCase = true)) ||
            okFlag == false ||
            successFlag == false
    }

    fun classifyPayloadFailure(
        response: Map<String, Any?>,
        defaultMessage: String,
        httpStatus: Int = 200,
    ): ClassifiedFailure {
        val operatorCode = extractOperatorCode(response)
        val baseMessage = extractMessage(response, defaultMessage)
        val messageWithCode =
            if (operatorCode != null && !baseMessage.contains(operatorCode, ignoreCase = true)) {
                "$operatorCode: $baseMessage"
            } else {
                baseMessage
            }
        return when {
            operatorCode in SESSION_EXPIRED_CODES || httpStatus == 401 ->
                ClassifiedFailure("platform_session_expired", messageWithCode, retryable = false, operatorCode = operatorCode)
            operatorCode in RETRYABLE_CODES || httpStatus in setOf(502, 503, 504, 408) ->
                ClassifiedFailure("platform_balance_timeout", messageWithCode, retryable = true, operatorCode = operatorCode)
            httpStatus == 400 || response["status"] == false ->
                ClassifiedFailure("platform_operator_refusal", messageWithCode, retryable = false, operatorCode = operatorCode)
            else ->
                ClassifiedFailure("platform_balance_failed", messageWithCode, retryable = false, operatorCode = operatorCode)
        }
    }

    fun classifyException(error: Exception, defaultMessage: String): ClassifiedFailure {
        if (error is RestClientResponseException) {
            val httpStatus = error.statusCode.value()
            val bodyText = error.responseBodyAsString.trim()
            val parsedBody = parseJsonMap(bodyText)
            if (parsedBody != null) {
                return classifyPayloadFailure(parsedBody, defaultMessage, httpStatus)
            }
            val operatorCode = extractOperatorCode(null, bodyText.ifBlank { error.message })
            val baseMessage = bodyText.ifBlank { error.message ?: defaultMessage }
            val messageWithCode =
                if (operatorCode != null && !baseMessage.contains(operatorCode, ignoreCase = true)) {
                    "$operatorCode: $baseMessage"
                } else {
                    baseMessage
                }
            return when {
                operatorCode in SESSION_EXPIRED_CODES || httpStatus == 401 ->
                    ClassifiedFailure("platform_session_expired", messageWithCode, retryable = false, operatorCode = operatorCode)
                operatorCode in RETRYABLE_CODES || httpStatus in setOf(502, 503, 504, 408) ->
                    ClassifiedFailure("platform_balance_timeout", messageWithCode, retryable = true, operatorCode = operatorCode)
                httpStatus == 400 ->
                    ClassifiedFailure("platform_operator_refusal", messageWithCode, retryable = false, operatorCode = operatorCode)
                else ->
                    ClassifiedFailure("platform_balance_failed", messageWithCode, retryable = false, operatorCode = operatorCode)
            }
        }
        val retryableNetwork =
            error is ResourceAccessException ||
                error is SocketTimeoutException ||
                error is IOException ||
                error.cause is SocketTimeoutException ||
                error.cause is IOException
        if (retryableNetwork) {
            return ClassifiedFailure(
                "platform_balance_timeout",
                error.message ?: defaultMessage,
                retryable = true,
            )
        }
        val operatorCode = extractOperatorCode(null, error.message)
        if (operatorCode in SESSION_EXPIRED_CODES) {
            return ClassifiedFailure("platform_session_expired", error.message ?: defaultMessage, retryable = false, operatorCode = operatorCode)
        }
        if (operatorCode in RETRYABLE_CODES) {
            return ClassifiedFailure("platform_balance_timeout", error.message ?: defaultMessage, retryable = true, operatorCode = operatorCode)
        }
        return ClassifiedFailure("platform_balance_failed", error.message ?: defaultMessage, retryable = false, operatorCode = operatorCode)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJsonMap(raw: String): Map<String, Any?>? {
        if (raw.isBlank() || !raw.startsWith("{")) {
            return null
        }
        return try {
            mapper.readValue(raw, Map::class.java) as? Map<String, Any?>
        } catch (_: Exception) {
            null
        }
    }
}

internal class PlatformUser {
    var userId: String = ""
    var username: String = ""
    var balance: Int = 0
    var currency: String = ""
    var operatorId: String = ""
}

internal class PlatformSession {
    var userId: String = ""
    var token: String = ""
    var gameId: Int = 0
    var partnerCode: String = ""
    var user: PlatformUser = PlatformUser()
    var issuedAt: String = ""
}

internal class PlatformEnvelope {
    var status: Boolean = false
    var msg: String? = null
    var data: Map<String, Any?>? = null
    var user: Map<String, Any?>? = null
}

/**
 * Legacy cashout queue payload kept for existing RabbitPlatformWalletPublisher compatibility.
 */
internal class PlatformCreditQueueMessage {
    var txn_id: String = ""
    var txn_ref_id: String = ""
    var txn_type: Int = 1
    var amount: String = ""
    var user_id: String = ""
    var game_id: String = ""
    var description: String = ""
    var ip: String = ""
    var operatorId: String = ""
    var token: String = ""
}

internal class PlatformPlayerRef(
    val playerId: String,
    val platformUserId: String?,
    val platformToken: String?,
    val platformGameId: Int?,
    val platformOperatorId: String?,
    val ip: String?,
    val isBot: Boolean,
) {
    /**
     * Guest players join without a platform session, so they own no platform wallet and play with
     * table chips only. Partial linkage still counts as linked so misconfigured platform players
     * fail loudly instead of silently playing for free.
     */
    val walletLinked: Boolean
        get() = !platformUserId.isNullOrBlank() || !platformToken.isNullOrBlank()

    /** Uppercase partner_code used for Operations internal wallet calls (2051/2052). */
    val partnerCode: String?
        get() = platformOperatorId?.trim()?.takeIf { it.isNotBlank() }?.uppercase()
}
