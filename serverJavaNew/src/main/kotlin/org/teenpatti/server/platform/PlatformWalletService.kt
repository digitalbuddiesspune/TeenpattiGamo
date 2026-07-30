package org.teenpatti.server.platform

import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.teenpatti.server.common.AppException
import org.teenpatti.server.common.ClockProvider
import org.teenpatti.server.common.GameEventLog
import org.teenpatti.server.config.AppEnvironment
import org.teenpatti.server.game.RoundHistoryEntry
import org.teenpatti.server.infrastructure.persistence.WalletTransaction
import org.teenpatti.server.infrastructure.persistence.WalletTransactionRepository
import org.teenpatti.server.infrastructure.persistence.RoundHistoryRepository

@Component
internal class PlatformWalletService(
    private val env: AppEnvironment,
    private val walletPublisher: PlatformWalletPublisher,
    private val transactionRepository: WalletTransactionRepository,
    private val roundHistoryRepository: RoundHistoryRepository,
    private val clockProvider: ClockProvider,
) {
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

    private val debitClient: RestClient? =
        env.platformDebitUrl.trim().takeIf { it.isNotBlank() }?.let { RestClient.builder().build() }

    fun enabled(): Boolean = env.platformEnabled

    fun debit(
        player: PlatformPlayerRef,
        roundId: String,
        operationKey: String,
        amount: Int,
        description: String,
    ) {
        if (!env.platformEnabled || player.isBot || amount <= 0) {
            return
        }
        val platformUserId = requirePlatformUserId(player)
        val platformGameId = requirePlatformGameId(player)
        val platformToken = requirePlatformToken(player)
        val operatorId = requirePlatformOperatorId(player)
        val transaction = loadOrCreate(operationKey, "debit", player.playerId, platformUserId, operatorId, roundId, amount, null)
        if (transaction.status == "succeeded" || transaction.status == "applied") {
            return
        }
        val request = balanceRequest(transaction.txnId, amount, description, platformUserId, platformGameId, player.ip, null, 0, operatorId)
        GameEventLog.info("wallet_debit_sent", "playerId" to player.playerId, "roundId" to roundId, "transactionId" to transaction.txnId, "amount" to amount)
        transaction.requestPayload = requestToPayload(request)
        transaction.status = "sent"
        transaction.updatedAt = clockProvider.nowIso()
        transactionRepository.save(transaction)
        try {
            postDebit(request, platformToken)
            transaction.responsePayload = mutableMapOf("posted" to true)
            transaction.status = "succeeded"
            transaction.updatedAt = clockProvider.nowIso()
            transactionRepository.save(transaction)
            GameEventLog.info("wallet_debit_succeeded", "playerId" to player.playerId, "roundId" to roundId, "transactionId" to transaction.txnId, "amount" to amount)
        } catch (error: Exception) {
            transaction.status = "failed"
            transaction.responsePayload = mutableMapOf("error" to (error.message ?: "Platform debit failed."))
            transaction.updatedAt = clockProvider.nowIso()
            transactionRepository.save(transaction)
            GameEventLog.error("wallet_debit_failed", error, "playerId" to player.playerId, "roundId" to roundId, "transactionId" to transaction.txnId, "amount" to amount)
            throw error
        }
    }

    fun credit(
        player: PlatformPlayerRef,
        roundId: String,
        operationKey: String,
        amount: Int,
        description: String,
        txnRefId: String? = null,
    ) {
        if (!env.platformEnabled || player.isBot || amount <= 0) {
            return
        }
        val platformUserId = requirePlatformUserId(player)
        val platformGameId = requirePlatformGameId(player)
        val platformToken = requirePlatformToken(player)
        val operatorId = requirePlatformOperatorId(player)
        val resolvedTxnRefId =
            txnRefId?.takeIf { it.isNotBlank() }
                ?: transactionRepository.loadMostRecentDebit(player.playerId, roundId)?.txnId
                ?: throw AppException.badRequest("platform_debit_reference_required", "A successful debit transaction is required before credit.")
        val transaction = loadOrCreate(operationKey, "credit", player.playerId, platformUserId, operatorId, roundId, amount, resolvedTxnRefId)
        if (transaction.status == "applied") {
            return
        }
        if (transaction.status != "succeeded") {
            val request =
                creditQueueMessage(
                    transaction.txnId,
                    amount,
                    description,
                    platformUserId,
                    platformGameId,
                    player.ip,
                    resolvedTxnRefId,
                    operatorId,
                    platformToken,
                )
            GameEventLog.info("wallet_credit_sent", "playerId" to player.playerId, "roundId" to roundId, "transactionId" to transaction.txnId, "amount" to amount)
            transaction.requestPayload = creditRequestToPayload(request)
            transaction.status = "sent"
            transaction.updatedAt = clockProvider.nowIso()
            transactionRepository.save(transaction)
            try {
                walletPublisher.publish(request)
                transaction.responsePayload = mutableMapOf("published" to true)
                transaction.status = "succeeded"
                transaction.updatedAt = clockProvider.nowIso()
                transactionRepository.save(transaction)
                GameEventLog.info("wallet_credit_published", "playerId" to player.playerId, "roundId" to roundId, "transactionId" to transaction.txnId, "amount" to amount)
            } catch (error: Exception) {
                transaction.status = "failed"
                transaction.responsePayload = mutableMapOf("error" to (error.message ?: "Platform credit failed."))
                transaction.updatedAt = clockProvider.nowIso()
                transactionRepository.save(transaction)
                GameEventLog.error("wallet_credit_failed", error, "playerId" to player.playerId, "roundId" to roundId, "transactionId" to transaction.txnId, "amount" to amount)
                throw AppException("platform_payout_pending", "Payout credit is pending. Please retry before starting the next round.", error)
            }
        }
        transaction.status = "applied"
        transaction.updatedAt = clockProvider.nowIso()
        transactionRepository.save(transaction)
        GameEventLog.info("wallet_credit_applied", "playerId" to player.playerId, "roundId" to roundId, "transactionId" to transaction.txnId, "amount" to amount)
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

    private fun loadOrCreate(
        operationKey: String,
        txnType: String,
        playerId: String,
        platformUserId: String,
        platformOperatorId: String,
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
        transaction.platformOperatorId = platformOperatorId
        transaction.roundId = roundId
        transaction.txnType = txnType
        transaction.amount = amount
        transaction.status = "created"
        transaction.txnRefId = txnRefId
        transaction.createdAt = now
        transaction.updatedAt = now
        return transactionRepository.save(transaction)
    }

    private fun balanceRequest(
        txnId: String,
        amount: Int,
        description: String,
        platformUserId: String,
        platformGameId: Int,
        ip: String?,
        txnRefId: String?,
        txnType: Int,
        operatorId: String,
    ): PlatformBalanceRequest {
        val request = PlatformBalanceRequest()
        request.txn_id = txnId
        request.amount = amount
        request.description = description
        request.txn_type = txnType
        request.ip = ip?.takeIf { it.isNotBlank() } ?: "0.0.0.0"
        request.game_id = platformGameId
        request.user_id = platformUserId
        request.txn_ref_id = txnRefId
        request.operator_id = operatorId
        return request
    }

    private fun creditQueueMessage(
        txnId: String,
        amount: Int,
        description: String,
        platformUserId: String,
        platformGameId: Int,
        ip: String?,
        txnRefId: String,
        operatorId: String,
        token: String,
    ): PlatformCreditQueueMessage {
        val request = PlatformCreditQueueMessage()
        request.txn_id = txnId
        request.amount = amount
        request.description = description
        request.ip = ip?.takeIf { it.isNotBlank() } ?: "0.0.0.0"
        request.game_id = platformGameId
        request.user_id = platformUserId
        request.txn_ref_id = txnRefId
        request.operatorId = operatorId
        request.token = token
        return request
    }

    private fun requestToPayload(request: PlatformBalanceRequest): MutableMap<String, Any?> =
        linkedMapOf(
            "txn_id" to request.txn_id,
            "amount" to request.amount,
            "description" to request.description,
            "txn_type" to request.txn_type,
            "ip" to request.ip,
            "game_id" to request.game_id,
            "user_id" to request.user_id,
            "txn_ref_id" to request.txn_ref_id,
            "operator_id" to request.operator_id,
        )

    private fun creditRequestToPayload(request: PlatformCreditQueueMessage): MutableMap<String, Any?> =
        linkedMapOf(
            "amount" to request.amount,
            "txn_id" to request.txn_id,
            "txn_ref_id" to request.txn_ref_id,
            "ip" to request.ip,
            "game_id" to request.game_id,
            "user_id" to request.user_id,
            "operatorId" to request.operatorId,
            "token" to request.token,
            "description" to request.description,
        )

    private fun postDebit(request: PlatformBalanceRequest, token: String) {
        try {
            val response =
                (debitClient ?: throw AppException.badRequest("platform_debit_url_missing", "Platform debit URL is not configured."))
                    .post()
                    .uri(env.platformDebitUrl.trim())
                    .header("token", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(PlatformEnvelope::class.java)
            if (response != null && !response.status) {
                throw AppException.badRequest("platform_balance_failed", response.msg ?: "Platform debit failed.")
            }
        } catch (error: AppException) {
            throw error
        } catch (error: Exception) {
            throw AppException.badRequest("platform_balance_failed", error.message ?: "Platform debit failed.")
        }
    }

    private fun requirePlatformUserId(player: PlatformPlayerRef): String =
        player.platformUserId?.takeIf { it.isNotBlank() }
            ?: throw AppException.badRequest("platform_user_required", "Platform user id is required for wallet play.")

    private fun requirePlatformToken(player: PlatformPlayerRef): String =
        player.platformToken?.takeIf { it.isNotBlank() }
            ?: throw AppException.badRequest("platform_token_required", "Platform token is required for wallet play.")

    private fun requirePlatformGameId(player: PlatformPlayerRef): Int =
        player.platformGameId?.takeIf { it > 0 }
            ?: throw AppException.badRequest("platform_game_id_required", "Platform game_id is required for wallet play.")

    private fun requirePlatformOperatorId(player: PlatformPlayerRef): String =
        player.platformOperatorId?.takeIf { it.isNotBlank() }
            ?: throw AppException.badRequest("platform_operator_id_required", "Platform operatorId is required for wallet play.")

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
