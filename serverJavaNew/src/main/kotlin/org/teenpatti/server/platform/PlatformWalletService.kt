package org.teenpatti.server.platform

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.teenpatti.server.common.AppException
import org.teenpatti.server.common.ClockProvider
import org.teenpatti.server.common.GameEventLog
import org.teenpatti.server.config.AppEnvironment
import org.teenpatti.server.game.RoundHistoryEntry
import org.teenpatti.server.infrastructure.persistence.RoundHistoryRepository
import org.teenpatti.server.infrastructure.persistence.WalletTransaction
import org.teenpatti.server.infrastructure.persistence.WalletTransactionRepository
import java.net.http.HttpClient
import java.time.Duration

@Component
internal class PlatformWalletService @Autowired constructor(
    private val env: AppEnvironment,
    private val transactionRepository: WalletTransactionRepository,
    private val roundHistoryRepository: RoundHistoryRepository,
    private val clockProvider: ClockProvider,
) {
    internal var maxRetryAttempts: Int = 3
    internal var retryDelayMs: Long = 150L

    internal class WalletTransactionHistoryItem {
        var txnId: String = ""
        var roundId: String = ""
        var amount: Int = 0
        var status: String = ""
        var txnRefId: String? = null
        var createdAt: String? = null
        var updatedAt: String? = null
        var description: String? = null
    }

    internal class WalletTransactionHistoryResponse {
        var txnType: String = ""
        var items: List<WalletTransactionHistoryItem> = emptyList()
        var count: Int = 0
        var offset: Int = 0
        var nextOffset: Int? = null
        var hasMore: Boolean = false
    }

    internal class PlatformRoundHistoryItem {
        var roundId: String = ""
        var outcome: String = ""
        var resultAmount: Int = 0
        var yourContribution: Int = 0
        var payout: Int = 0
        var dealerTip: Int = 0
        var potAmount: Int = 0
        var winningHand: String? = null
        var bootCommission: Int = 0
        var winCommission: Int = 0
        var casinoCommissionTotal: Int = 0
        var winnerReceivableBeforeTip: Int = 0
        var reason: String? = null
        var settledAt: String? = null
    }

    internal class PlatformRoundHistoryResponse {
        var items: List<PlatformRoundHistoryItem> = emptyList()
        var count: Int = 0
        var offset: Int = 0
        var nextOffset: Int? = null
        var hasMore: Boolean = false
    }

    private val creditTransferClient: RestClient? =
        env.operationsCreditTransferUrl.trim().takeIf { it.isNotBlank() }?.let {
            val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            val factory =
                JdkClientHttpRequestFactory(httpClient).apply {
                    setReadTimeout(Duration.ofSeconds(8))
                }
            RestClient.builder().requestFactory(factory).build()
        }

    fun enabled(): Boolean = env.platformEnabled

    /**
     * Debits a player bet via Operations credit-transfer (triggers opType 2052):
     * POST /api/v1/wallet/credit-transfer with bet = amount, win = 0, game_type = "spin" (or gamble), ttype = 1.
     */
    fun debit(
        player: PlatformPlayerRef,
        roundId: String,
        operationKey: String,
        amount: Int,
        description: String,
        gameType: OperationsGameType = OperationsGameType.SPIN,
    ) {
        if (skipsWallet(player, amount)) {
            return
        }
        val platformUserId = requirePlatformUserId(player)
        val platformGameId = requirePlatformGameId(player)
        val platformToken = requirePlatformToken(player)
        val partnerCode = requirePartnerCode(player)
        val transaction = loadOrCreate(operationKey, "debit", player.playerId, platformUserId, partnerCode, roundId, amount, null)
        if (transaction.status == "succeeded" || transaction.status == "applied") {
            return
        }
        val request =
            OperationsCreditTransferRequest(
                token = platformToken,
                partner_code = partnerCode,
                gid = platformGameId,
                bet = amount,
                win = 0,
                game_type = gameType.code,
                tranid = transaction.txnId,
                ttype = gameType.ttype,
                roundid = roundId,
            )
        GameEventLog.info(
            "wallet_debit_sent",
            "playerId" to player.playerId,
            "roundId" to roundId,
            "transactionId" to transaction.txnId,
            "amount" to amount,
            "gameType" to gameType.code,
            "ttype" to gameType.ttype,
        )
        transaction.requestPayload = transferRequestToPayload(request, description)
        transaction.status = "sent"
        transaction.updatedAt = clockProvider.nowIso()
        transactionRepository.save(transaction)
        try {
            val response = postCreditTransferWithRetry(request)
            transaction.responsePayload = response.toMutableMap().apply { putIfAbsent("posted", true) }
            transaction.status = "succeeded"
            transaction.updatedAt = clockProvider.nowIso()
            transactionRepository.save(transaction)
            GameEventLog.info(
                "wallet_debit_succeeded",
                "playerId" to player.playerId,
                "roundId" to roundId,
                "transactionId" to transaction.txnId,
                "amount" to amount,
            )
        } catch (error: Exception) {
            transaction.status = "failed"
            transaction.responsePayload = mutableMapOf("error" to (error.message ?: "Platform debit failed."))
            transaction.updatedAt = clockProvider.nowIso()
            transactionRepository.save(transaction)
            GameEventLog.error(
                "wallet_debit_failed",
                error,
                "playerId" to player.playerId,
                "roundId" to roundId,
                "transactionId" to transaction.txnId,
                "amount" to amount,
            )
            throw error
        }
    }

    /**
     * Credits a player win/feature payout via Operations credit-transfer (triggers opType 2052):
     * POST /api/v1/wallet/credit-transfer with bet = 0, win = amount.
     * Supports late win settlement after session close using token OR (partner_code + user_id).
     */
    fun credit(
        player: PlatformPlayerRef,
        roundId: String,
        operationKey: String,
        amount: Int,
        description: String,
        txnRefId: String? = null,
        gameType: OperationsGameType = OperationsGameType.SPIN,
    ) {
        if (skipsWallet(player, amount)) {
            return
        }
        val platformUserId = requirePlatformUserId(player)
        val platformGameId = requirePlatformGameId(player)
        val platformToken = player.platformToken?.trim()?.takeIf { it.isNotBlank() }
        val partnerCode = requirePartnerCode(player)
        if (platformToken == null && platformUserId.isBlank()) {
            throw AppException.badRequest("platform_token_required", "Platform token or partner_code + user_id is required for credit.")
        }
        val resolvedTxnRefId =
            txnRefId?.takeIf { it.isNotBlank() }
                ?: transactionRepository.loadMostRecentDebit(player.playerId, roundId)?.txnId
        val transaction = loadOrCreate(operationKey, "credit", player.playerId, platformUserId, partnerCode, roundId, amount, resolvedTxnRefId)
        if (transaction.status == "applied") {
            return
        }
        if (transaction.status != "succeeded") {
            val request =
                OperationsCreditTransferRequest(
                    token = platformToken,
                    partner_code = partnerCode,
                    user_id = if (platformToken == null) platformUserId else null,
                    gid = platformGameId,
                    bet = 0,
                    win = amount,
                    game_type = gameType.code,
                    tranid = transaction.txnId,
                    ttype = gameType.ttype,
                    roundid = roundId,
                )
            GameEventLog.info(
                "wallet_credit_sent",
                "playerId" to player.playerId,
                "roundId" to roundId,
                "transactionId" to transaction.txnId,
                "amount" to amount,
                "gameType" to gameType.code,
                "ttype" to gameType.ttype,
            )
            transaction.requestPayload = transferRequestToPayload(request, description)
            transaction.status = "sent"
            transaction.updatedAt = clockProvider.nowIso()
            transactionRepository.save(transaction)
            try {
                val response = postCreditTransferWithRetry(request)
                transaction.responsePayload = response.toMutableMap().apply { putIfAbsent("posted", true) }
                transaction.status = "succeeded"
                transaction.updatedAt = clockProvider.nowIso()
                transactionRepository.save(transaction)
                GameEventLog.info(
                    "wallet_credit_succeeded",
                    "playerId" to player.playerId,
                    "roundId" to roundId,
                    "transactionId" to transaction.txnId,
                    "amount" to amount,
                )
            } catch (error: Exception) {
                transaction.status = "failed"
                transaction.responsePayload = mutableMapOf("error" to (error.message ?: "Platform credit failed."))
                transaction.updatedAt = clockProvider.nowIso()
                transactionRepository.save(transaction)
                GameEventLog.error(
                    "wallet_credit_failed",
                    error,
                    "playerId" to player.playerId,
                    "roundId" to roundId,
                    "transactionId" to transaction.txnId,
                    "amount" to amount,
                )
                if (error is AppException && error.code == "platform_session_expired") {
                    throw error
                }
                throw AppException("platform_payout_pending", "Payout credit is pending. Please retry before starting the next round.", error)
            }
        }
        transaction.status = "applied"
        transaction.updatedAt = clockProvider.nowIso()
        transactionRepository.save(transaction)
        GameEventLog.info(
            "wallet_credit_applied",
            "playerId" to player.playerId,
            "roundId" to roundId,
            "transactionId" to transaction.txnId,
            "amount" to amount,
        )
    }

    /**
     * Reverses a bet for an aborted/crashed round via Operations 2052 refund:
     * {"token","partner_code","gid","bet":amount,"win":0,"game_type":"refund","ttype":2,"tranid","roundid"}.
     * Supports late refunds after session close using token OR (partner_code + user_id).
     */
    fun refund(
        player: PlatformPlayerRef,
        roundId: String,
        operationKey: String,
        amount: Int,
        description: String,
        txnRefId: String? = null,
    ) {
        if (skipsWallet(player, amount)) {
            return
        }
        val platformUserId = requirePlatformUserId(player)
        val platformGameId = requirePlatformGameId(player)
        val platformToken = player.platformToken?.trim()?.takeIf { it.isNotBlank() }
        val partnerCode = requirePartnerCode(player)
        val resolvedTxnRefId =
            txnRefId?.takeIf { it.isNotBlank() }
                ?: transactionRepository.loadMostRecentDebit(player.playerId, roundId)?.txnId
        val transaction = loadOrCreate(operationKey, "credit", player.playerId, platformUserId, partnerCode, roundId, amount, resolvedTxnRefId)
        if (transaction.status == "applied" || transaction.status == "succeeded") {
            return
        }
        val request =
            OperationsCreditTransferRequest(
                token = platformToken,
                partner_code = partnerCode,
                user_id = if (platformToken == null) platformUserId else null,
                gid = platformGameId,
                bet = amount,
                win = 0,
                game_type = OperationsGameType.REFUND.code,
                tranid = transaction.txnId,
                ttype = OperationsGameType.REFUND.ttype,
                roundid = roundId,
            )
        GameEventLog.info(
            "wallet_refund_sent",
            "playerId" to player.playerId,
            "roundId" to roundId,
            "transactionId" to transaction.txnId,
            "amount" to amount,
            "gameType" to OperationsGameType.REFUND.code,
            "ttype" to OperationsGameType.REFUND.ttype,
        )
        transaction.requestPayload = transferRequestToPayload(request, description)
        transaction.status = "sent"
        transaction.updatedAt = clockProvider.nowIso()
        transactionRepository.save(transaction)
        try {
            val response = postCreditTransferWithRetry(request)
            transaction.responsePayload = response.toMutableMap().apply { putIfAbsent("posted", true) }
            transaction.status = "applied"
            transaction.updatedAt = clockProvider.nowIso()
            transactionRepository.save(transaction)
            GameEventLog.info(
                "wallet_refund_succeeded",
                "playerId" to player.playerId,
                "roundId" to roundId,
                "transactionId" to transaction.txnId,
                "amount" to amount,
            )
        } catch (error: Exception) {
            transaction.status = "failed"
            transaction.responsePayload = mutableMapOf("error" to (error.message ?: "Platform refund failed."))
            transaction.updatedAt = clockProvider.nowIso()
            transactionRepository.save(transaction)
            GameEventLog.error(
                "wallet_refund_failed",
                error,
                "playerId" to player.playerId,
                "roundId" to roundId,
                "transactionId" to transaction.txnId,
                "amount" to amount,
            )
            throw error
        }
    }

    /**
     * Settles a combined bet/win transfer (e.g. spin with bet & win, freespin, bonus, gamble, gamble_collect, or refund)
     * in a single 2052 call to POST /api/v1/wallet/credit-transfer.
     */
    fun settleTransfer(
        player: PlatformPlayerRef,
        roundId: String,
        operationKey: String,
        bet: Int,
        win: Int,
        gameType: OperationsGameType,
        description: String,
        txnRefId: String? = null,
    ) {
        if (!env.platformEnabled || player.isBot || !player.walletLinked || (bet <= 0 && win <= 0)) {
            return
        }
        val platformUserId = requirePlatformUserId(player)
        val platformGameId = requirePlatformGameId(player)
        val platformToken = player.platformToken?.trim()?.takeIf { it.isNotBlank() }
        val partnerCode = requirePartnerCode(player)
        val allowsTokenlessFallback = win > 0 || gameType == OperationsGameType.REFUND
        if (platformToken == null && !allowsTokenlessFallback) {
            throw AppException.badRequest("platform_token_required", "Platform token is required for wallet play.")
        }
        val txnType = if (win > 0 || gameType == OperationsGameType.REFUND) "credit" else "debit"
        val ledgerAmount = if (win > 0) win else bet
        val resolvedTxnRefId =
            txnRefId?.takeIf { it.isNotBlank() }
                ?: if (txnType == "credit") transactionRepository.loadMostRecentDebit(player.playerId, roundId)?.txnId else null
        val transaction =
            loadOrCreate(operationKey, txnType, player.playerId, platformUserId, partnerCode, roundId, ledgerAmount, resolvedTxnRefId)
        if (transaction.status == "succeeded" || transaction.status == "applied") {
            return
        }
        val request =
            OperationsCreditTransferRequest(
                token = platformToken,
                partner_code = partnerCode,
                user_id = if (platformToken == null) platformUserId else null,
                gid = platformGameId,
                bet = bet.coerceAtLeast(0),
                win = win.coerceAtLeast(0),
                game_type = gameType.code,
                tranid = transaction.txnId,
                ttype = gameType.ttype,
                roundid = roundId,
            )
        transaction.requestPayload = transferRequestToPayload(request, description)
        transaction.status = "sent"
        transaction.updatedAt = clockProvider.nowIso()
        transactionRepository.save(transaction)
        try {
            val response = postCreditTransferWithRetry(request)
            transaction.responsePayload = response.toMutableMap().apply { putIfAbsent("posted", true) }
            transaction.status = if (txnType == "credit") "applied" else "succeeded"
            transaction.updatedAt = clockProvider.nowIso()
            transactionRepository.save(transaction)
        } catch (error: Exception) {
            transaction.status = "failed"
            transaction.responsePayload = mutableMapOf("error" to (error.message ?: "Platform transfer failed."))
            transaction.updatedAt = clockProvider.nowIso()
            transactionRepository.save(transaction)
            throw error
        }
    }

    fun listTransactionHistory(
        platformUserId: String,
        txnType: String,
        offset: Int = 0,
        limit: Int = 50,
    ): WalletTransactionHistoryResponse {
        val normalizedTxnType = txnType.trim().lowercase()
        if (normalizedTxnType !in setOf("debit", "credit")) {
            throw AppException.badRequest("platform_transaction_type_invalid", "Unsupported transaction type: $txnType")
        }
        val normalizedOffset = offset.coerceAtLeast(0)
        val normalizedLimit = limit.coerceIn(1, 50)
        val rows = transactionRepository.listRecentTransactions(platformUserId, normalizedTxnType, normalizedOffset, normalizedLimit + 1)
        val response = WalletTransactionHistoryResponse()
        response.txnType = normalizedTxnType
        response.offset = normalizedOffset
        response.hasMore = rows.size > normalizedLimit
        response.items =
            rows.take(normalizedLimit).map { transaction ->
                WalletTransactionHistoryItem().also { item ->
                    item.txnId = transaction.txnId
                    item.roundId = transaction.roundId
                    item.amount = transaction.amount
                    item.status = transaction.status
                    item.txnRefId = transaction.txnRefId
                    item.createdAt = transaction.createdAt
                    item.updatedAt = transaction.updatedAt
                    item.description = transaction.description()
                }
            }
        response.count = response.items.size
        response.nextOffset = if (response.hasMore) normalizedOffset + response.items.size else null
        return response
    }

    fun listRoundHistory(
        platformUserId: String,
        offset: Int = 0,
        limit: Int = 20,
    ): PlatformRoundHistoryResponse {
        val normalizedOffset = offset.coerceAtLeast(0)
        val normalizedLimit = limit.coerceIn(1, 50)
        val playerIds = transactionRepository.listPlayerIdsForPlatformUser(platformUserId)
        val response = PlatformRoundHistoryResponse()
        response.offset = normalizedOffset
        if (playerIds.isEmpty()) {
            return response
        }
        val rows = roundHistoryRepository.listRecentRoundsForParticipants(playerIds, normalizedOffset, normalizedLimit + 1)
        response.hasMore = rows.size > normalizedLimit
        response.items = rows.take(normalizedLimit).mapNotNull { entry -> entry.toPlatformRoundHistoryItem(playerIds) }
        response.count = response.items.size
        response.nextOffset = if (response.hasMore) normalizedOffset + response.items.size else null
        return response
    }

    private fun skipsWallet(player: PlatformPlayerRef, amount: Int): Boolean =
        !env.platformEnabled || player.isBot || amount <= 0 || !player.walletLinked

    private fun loadOrCreate(
        operationKey: String,
        txnType: String,
        playerId: String,
        platformUserId: String,
        partnerCode: String,
        roundId: String,
        amount: Int,
        txnRefId: String?,
    ): WalletTransaction {
        val existing = transactionRepository.loadByOperationKey(operationKey)
        if (existing != null) {
            return existing
        }
        val now = clockProvider.nowIso()
        val transaction = WalletTransaction()
        transaction.id = operationKey
        transaction.txnId = operationKey
        transaction.operationKey = operationKey
        transaction.playerId = playerId
        transaction.platformUserId = platformUserId
        transaction.platformOperatorId = partnerCode
        transaction.roundId = roundId
        transaction.txnType = txnType
        transaction.amount = amount
        transaction.status = "created"
        transaction.txnRefId = txnRefId
        transaction.createdAt = now
        transaction.updatedAt = now
        return transactionRepository.save(transaction)
    }

    /**
     * Posts a 2052 credit-transfer request and retries with the EXACT SAME tranid on network timeouts,
     * HTTP 502/503/504, or ERR-0020 / ERR-0021. Never retries session-expired (ERR-0011/0012/0013)
     * or operator business refusals (400 status: false).
     */
    private fun postCreditTransferWithRetry(request: OperationsCreditTransferRequest): Map<String, Any?> {
        val url = env.operationsCreditTransferUrl.trim()
        val internalKey = requireInternalServiceKey()
        val client =
            creditTransferClient
                ?: throw AppException.badRequest("platform_balance_url_missing", "Operations credit-transfer URL is not configured.")
        val attempts = maxRetryAttempts.coerceAtLeast(1)
        var lastFailure: OperationsErrorSupport.ClassifiedFailure? = null

        for (attempt in 1..attempts) {
            try {
                val response =
                    client
                        .post()
                        .uri(url)
                        .header("X-Internal-Key", internalKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(object : ParameterizedTypeReference<Map<String, Any?>>() {})
                        ?: emptyMap()
                if (OperationsErrorSupport.isFailurePayload(response)) {
                    val failure =
                        OperationsErrorSupport.classifyPayloadFailure(
                            response,
                            "Operations credit transfer failed.",
                        )
                    lastFailure = failure
                    if (!failure.retryable || attempt >= attempts) {
                        throw AppException.badRequest(failure.appCode, failure.message)
                    }
                    GameEventLog.info(
                        "wallet_transfer_retrying",
                        "tranid" to request.tranid,
                        "roundId" to request.roundid,
                        "attempt" to attempt,
                        "reason" to failure.message,
                    )
                    sleepBeforeRetry(attempt)
                    continue
                }
                return response
            } catch (error: AppException) {
                throw error
            } catch (error: Exception) {
                val failure =
                    OperationsErrorSupport.classifyException(
                        error,
                        "Operations credit transfer failed.",
                    )
                lastFailure = failure
                if (!failure.retryable || attempt >= attempts) {
                    throw AppException.badRequest(failure.appCode, failure.message)
                }
                GameEventLog.info(
                    "wallet_transfer_retrying",
                    "tranid" to request.tranid,
                    "roundId" to request.roundid,
                    "attempt" to attempt,
                    "reason" to failure.message,
                )
                sleepBeforeRetry(attempt)
            }
        }

        throw AppException.badRequest(
            lastFailure?.appCode ?: "platform_balance_failed",
            lastFailure?.message ?: "Operations credit transfer failed.",
        )
    }

    private fun sleepBeforeRetry(attempt: Int) {
        val delay = retryDelayMs * attempt
        if (delay <= 0L) {
            return
        }
        try {
            Thread.sleep(delay)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun transferRequestToPayload(
        request: OperationsCreditTransferRequest,
        description: String,
    ): MutableMap<String, Any?> =
        linkedMapOf<String, Any?>(
            "token" to request.token,
            "partner_code" to request.partner_code,
            "user_id" to request.user_id,
            "gid" to request.gid,
            "bet" to request.bet,
            "win" to request.win,
            "game_type" to request.game_type,
            "tranid" to request.tranid,
            "ttype" to request.ttype,
            "roundid" to request.roundid,
            "description" to description,
        )

    private fun requireInternalServiceKey(): String =
        env.internalServiceKey.trim().takeIf { it.isNotBlank() }
            ?: throw AppException.badRequest("platform_internal_key_missing", "INTERNAL_SERVICE_KEY is not configured.")

    private fun requirePlatformUserId(player: PlatformPlayerRef): String =
        player.platformUserId?.takeIf { it.isNotBlank() }
            ?: throw AppException.badRequest("platform_user_required", "Platform user id is required for wallet play.")

    private fun requirePlatformToken(player: PlatformPlayerRef): String =
        player.platformToken?.takeIf { it.isNotBlank() }
            ?: throw AppException.badRequest("platform_token_required", "Platform token is required for wallet play.")

    private fun requirePlatformGameId(player: PlatformPlayerRef): Int =
        player.platformGameId?.takeIf { it > 0 }
            ?: env.platformGameId.takeIf { it > 0 }
            ?: throw AppException.badRequest("platform_game_id_required", "Platform gid is required for wallet play.")

    private fun requirePartnerCode(player: PlatformPlayerRef): String =
        player.partnerCode
            ?: env.operationsDefaultPartnerCode.takeIf { it.isNotBlank() }
            ?: throw AppException.badRequest("platform_partner_code_required", "Platform partner_code is required for wallet play.")

    private fun WalletTransaction.description(): String? =
        (responsePayload["description"] as? String)?.takeIf { it.isNotBlank() }
            ?: (requestPayload["description"] as? String)?.takeIf { it.isNotBlank() }

    private fun RoundHistoryEntry.toPlatformRoundHistoryItem(playerIds: List<String>): PlatformRoundHistoryItem? {
        val participant = participants.firstOrNull { playerIds.contains(it.id) } ?: return null
        val playerWon = winner?.id == participant.id
        return PlatformRoundHistoryItem().also { item ->
            item.roundId = id
            item.outcome = if (playerWon) "win" else "loss"
            item.resultAmount = if (playerWon) payout else participant.totalContributed
            item.yourContribution = participant.totalContributed
            item.payout = if (playerWon) payout else 0
            item.dealerTip = if (playerWon) dealerTip else 0
            item.potAmount = potAmount
            item.winningHand = winner?.winningHand
            item.bootCommission = bootCommission
            item.winCommission = winCommission
            item.casinoCommissionTotal = casinoCommissionTotal
            item.winnerReceivableBeforeTip = if (playerWon) winnerReceivableBeforeTip else 0
            item.reason = reason
            item.settledAt = settledAt
        }
    }
}
