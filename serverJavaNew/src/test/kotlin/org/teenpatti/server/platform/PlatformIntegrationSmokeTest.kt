package org.teenpatti.server.platform

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.teenpatti.server.FixedClock
import org.teenpatti.server.InMemoryPrivateRoomRepository
import org.teenpatti.server.InMemoryPublicSessionRepository
import org.teenpatti.server.InMemoryRoundHistoryRepository
import org.teenpatti.server.InMemoryTableRepository
import org.teenpatti.server.ManualScheduler
import org.teenpatti.server.clientSeed
import org.teenpatti.server.common.AppException
import org.teenpatti.server.config.AppEnvironment
import org.teenpatti.server.game.RoundHistoryEntry
import org.teenpatti.server.game.RoundHistoryParticipant
import org.teenpatti.server.game.RoundHistoryWinner
import org.teenpatti.server.infrastructure.persistence.WalletTransaction
import org.teenpatti.server.infrastructure.persistence.WalletTransactionRepository
import org.teenpatti.server.platform.api.PlatformController
import org.teenpatti.server.platform.api.PlatformProfileRequest
import org.teenpatti.server.platform.api.PlatformRoundHistoryRequest
import org.teenpatti.server.platform.api.PlatformSessionRequest
import org.teenpatti.server.platform.api.PlatformTransactionHistoryRequest
import org.teenpatti.server.privateRoomManager
import org.teenpatti.server.publicManager
import java.net.InetSocketAddress

internal class PlatformIntegrationSmokeTest {
    @Test
    fun teenPattiWalletStatementContainsRequiredFields() {
        assertEquals(
            "Teen Patti: 1000 debited for boot; round round-1; lobby lobby-1.",
            TeenPattiWalletStatement.description(1000, "debited", "boot", "round-1", "lobby-1"),
        )
        assertEquals(
            "Teen Patti: 3200 credited for payout; round round-1; lobby lobby-1.",
            TeenPattiWalletStatement.description(3200, "credited", "payout", "round-1", "lobby-1"),
        )
    }

    @Test
    fun platformPublicSessionUsesIncomingTokenAndGameId() {
        withPlatformServer { server ->
            server.respondJson("/service/user/detail") { exchange ->
                assertEquals("platform-token", exchange.requestHeaders.getFirst("token"))
                """{"status":true,"data":{"id":"platform-user","name":"Platform Player","walletBalance":12345,"currency":"INR"}}"""
            }
            val sessionRepository = InMemoryPublicSessionRepository()
            val controller =
                platformController(
                    server.baseUrl(),
                    publicManagers = mapOf("classic" to publicManager(sessionRepository = sessionRepository)),
                )

            val response =
                controller.createPublicSession(
                    PlatformSessionRequest("platform-token", 456, clientSeed("platform-user"), "classic"),
                    MockHttpServletRequest(),
                )

            @Suppress("UNCHECKED_CAST")
            val payload = response["data"] as Map<String, Any?>
            val playerId = payload["playerId"] as String
            val saved = sessionRepository.loadSession(playerId)!!
            assertEquals("platform-user", saved.platformUserId)
            assertEquals("platform-token", saved.platformToken)
            assertEquals(456, saved.platformGameId)
        }
    }

    @Test
    fun platformPublicSessionAcceptsTopLevelUserDetail() {
        withPlatformServer { server ->
            server.respondJson("/service/user/detail") { exchange ->
                assertEquals("platform-token", exchange.requestHeaders.getFirst("token"))
                """{"status":true,"msg":"OK","user":{"name":"Platform Player","user_id":"platform-user","balance":"12345","avatar":null,"operatorId":"operator-1"}}"""
            }
            val sessionRepository = InMemoryPublicSessionRepository()
            val controller =
                platformController(
                    server.baseUrl(),
                    publicManagers = mapOf("classic" to publicManager(sessionRepository = sessionRepository)),
                )

            val response =
                controller.createPublicSession(
                    PlatformSessionRequest("platform-token", 2, clientSeed("platform-user"), "classic"),
                    MockHttpServletRequest(),
                )

            @Suppress("UNCHECKED_CAST")
            val payload = response["data"] as Map<String, Any?>
            val playerId = payload["playerId"] as String
            val saved = sessionRepository.loadSession(playerId)!!
            assertEquals("platform-user", saved.platformUserId)
            assertEquals("platform-token", saved.platformToken)
            assertEquals(2, saved.platformGameId)
            assertEquals("operator-1", saved.platformOperatorId)
        }
    }

    @Test
    fun platformProfileReturnsUserDetailForHomeBalance() {
        withPlatformServer { server ->
            server.respondJson("/service/user/detail") { exchange ->
                assertEquals("platform-token", exchange.requestHeaders.getFirst("token"))
                """{"status":true,"data":{"user":{"userId":"platform-user","username":"Platform Player","balance":"43210","currencyCode":"INR"}}}"""
            }
            val controller =
                platformController(server.baseUrl())

            @Suppress("UNCHECKED_CAST")
            val payload = controller.getProfile(PlatformProfileRequest("platform-token", 2))["data"] as Map<String, Any?>

            assertEquals("platform-user", payload["userId"])
            assertEquals("Platform Player", payload["username"])
            assertEquals(43210, payload["balance"])
            assertEquals("INR", payload["currency"])
            assertEquals(2, payload["gameId"])
        }
    }

    @Test
    fun platformProfilePrefersWalletBalanceOverGenericBalanceWhenBothExist() {
        withPlatformServer { server ->
            server.respondJson("/service/user/detail") { exchange ->
                assertEquals("platform-token", exchange.requestHeaders.getFirst("token"))
                """{"status":true,"data":{"walletBalance":"250000","user":{"userId":"platform-user","username":"Platform Player","balance":"99000","currencyCode":"INR"}}}"""
            }
            val controller =
                platformController(server.baseUrl())

            @Suppress("UNCHECKED_CAST")
            val payload = controller.getProfile(PlatformProfileRequest("platform-token", 2))["data"] as Map<String, Any?>

            assertEquals(250000, payload["balance"])
        }
    }

    @Test
    fun platformSessionRejectsMissingTokenBeforeUserDetailCall() {
        withPlatformServer { server ->
            val controller =
                platformController(server.baseUrl())

            val error =
                assertThrows(AppException::class.java) {
                    controller.createPublicSession(PlatformSessionRequest("", 456, clientSeed("platform-user"), "classic"), MockHttpServletRequest())
                }
            assertEquals("platform_token_required", error.code)
            assertEquals(0, server.requestCount("/service/user/detail"))
        }
    }

    @Test
    fun platformSessionRejectsMissingGameIdBeforeUserDetailCall() {
        withPlatformServer { server ->
            val controller =
                platformController(server.baseUrl(), platformGameId = 0)

            val error =
                assertThrows(AppException::class.java) {
                    controller.createPublicSession(PlatformSessionRequest("platform-token", 0, clientSeed("platform-user"), "classic"), MockHttpServletRequest())
                }
            assertEquals("platform_game_id_required", error.code)
            assertEquals(0, server.requestCount("/service/user/detail"))
        }
    }

    @Test
    fun debitHistoryReturnsAuthenticatedPlatformUsersTransactionsNewestFirst() {
        withPlatformServer { server ->
            server.respondJson("/service/user/detail") { """{"status":true,"data":{"user":{"userId":"platform-user","username":"Platform Player","balance":"43210","currencyCode":"INR"}}}""" }
            val repository = InMemoryWalletTransactionRepository()
            repository.save(walletTransaction("debit-1", "platform-user", "debit", "2026-01-01T00:00:00Z", "Boot debited"))
            repository.save(walletTransaction("debit-2", "platform-user", "debit", "2026-01-03T00:00:00Z", "Call debited", roundId = "round-2", txnRefId = "ref-2"))
            repository.save(walletTransaction("credit-1", "platform-user", "credit", "2026-01-04T00:00:00Z", "Payout credited"))
            repository.save(walletTransaction("debit-3", "other-user", "debit", "2026-01-05T00:00:00Z", "Should stay hidden"))
            val controller = platformController(server.baseUrl(), walletTransactionRepository = repository)

            val payload =
                controller.debitTransactions(PlatformTransactionHistoryRequest("platform-token", 2, 0, 20))["data"] as PlatformWalletService.WalletTransactionHistoryResponse

            assertEquals("debit", payload.txnType)
            assertEquals(2, payload.count)
            assertEquals(listOf("debit-2", "debit-1"), payload.items.map { it.txnId })
            assertEquals("Call debited", payload.items[0].description)
            assertEquals("ref-2", payload.items[0].txnRefId)
            assertEquals(false, payload.hasMore)
        }
    }

    @Test
    fun creditHistoryUsesAuthenticatedUserAndDescriptionFallback() {
        withPlatformServer { server ->
            server.respondJson("/service/user/detail") { """{"status":true,"data":{"user":{"userId":"platform-user","username":"Platform Player","balance":"43210","currencyCode":"INR"}}}""" }
            val repository = InMemoryWalletTransactionRepository()
            repository.save(walletTransaction("credit-1", "platform-user", "credit", "2026-01-02T00:00:00Z", "Payout credited", requestDescriptionOnly = true))
            repository.save(walletTransaction("credit-2", "platform-user", "credit", "2026-01-03T00:00:00Z", "Bonus credited"))
            repository.save(walletTransaction("credit-3", "other-user", "credit", "2026-01-04T00:00:00Z", "Hidden credit"))
            val controller = platformController(server.baseUrl(), walletTransactionRepository = repository)

            val payload =
                controller.creditTransactions(PlatformTransactionHistoryRequest("platform-token", 2, 0, 20))["data"] as PlatformWalletService.WalletTransactionHistoryResponse

            assertEquals("credit", payload.txnType)
            assertEquals(2, payload.items.size)
            assertEquals(listOf("credit-2", "credit-1"), payload.items.map { it.txnId })
            assertEquals("Payout credited", payload.items[1].description)
        }
    }

    @Test
    fun transactionHistoryRejectsMissingTokenBeforeLookup() {
        withPlatformServer { server ->
            val controller = platformController(server.baseUrl())

            val error =
                assertThrows(AppException::class.java) {
                    controller.debitTransactions(PlatformTransactionHistoryRequest("", 2, 0, 20))
                }

            assertEquals("platform_token_required", error.code)
            assertEquals(0, server.requestCount("/service/user/detail"))
        }
    }

    @Test
    fun transactionHistoryRejectsMissingGameIdBeforeLookup() {
        withPlatformServer { server ->
            val controller = platformController(server.baseUrl(), platformGameId = 0)

            val error =
                assertThrows(AppException::class.java) {
                    controller.creditTransactions(PlatformTransactionHistoryRequest("platform-token", 0, 0, 20))
                }

            assertEquals("platform_game_id_required", error.code)
            assertEquals(0, server.requestCount("/service/user/detail"))
        }
    }

    @Test
    fun transactionHistorySupportsPaginationAndHasMoreFlag() {
        val repository = InMemoryWalletTransactionRepository()
        repeat(55) { index ->
            val day = (index % 28) + 1
            repository.save(
                walletTransaction(
                    txnId = "debit-$index",
                    platformUserId = "platform-user",
                    txnType = "debit",
                    updatedAt = "2026-01-${day.toString().padStart(2, '0')}T00:00:00Z",
                    description = "Debit $index",
                ),
            )
        }
        val wallet = PlatformWalletService(platformEnv("http://unused"), repository, InMemoryRoundHistoryRepository(), FixedClock())

        val firstPage = wallet.listTransactionHistory("platform-user", "debit", 0, 20)
        val secondPage = wallet.listTransactionHistory("platform-user", "debit", 20, 20)

        assertEquals(20, firstPage.count)
        assertEquals(true, firstPage.hasMore)
        assertEquals(20, firstPage.nextOffset)
        assertEquals(20, secondPage.count)
        assertEquals(true, secondPage.hasMore)
        assertEquals(40, secondPage.nextOffset)
    }

    @Test
    fun roundHistoryReturnsPerRoundOutcomeAndDealerTip() {
        withPlatformServer { server ->
            server.respondJson("/service/user/detail") { """{"status":true,"data":{"user":{"userId":"platform-user","username":"Platform Player","balance":"43210","currencyCode":"INR"}}}""" }
            val walletRepository = InMemoryWalletTransactionRepository()
            val roundRepository = InMemoryRoundHistoryRepository()
            walletRepository.save(walletTransaction("txn-1", "platform-user", "debit", "2026-01-03T00:00:00Z", "Boot", roundId = "round-1"))
            walletRepository.save(walletTransaction("txn-2", "platform-user", "credit", "2026-01-04T00:00:00Z", "Payout", roundId = "round-2"))
            roundRepository.appendRound(roundHistoryEntry("round-1", "player-platform-user", false, 1200, 0, 0))
            roundRepository.appendRound(roundHistoryEntry("round-2", "player-platform-user", true, 1600, 4000, 300))
            val controller = platformController(server.baseUrl(), walletTransactionRepository = walletRepository, roundHistoryRepository = roundRepository)

            val payload =
                controller.roundHistory(PlatformRoundHistoryRequest("platform-token", 2, 0, 20))["data"] as PlatformWalletService.PlatformRoundHistoryResponse

            assertEquals(2, payload.items.size)
            assertEquals("round-2", payload.items[0].roundId)
            assertEquals("win", payload.items[0].outcome)
            assertEquals(4000, payload.items[0].resultAmount)
            assertEquals(300, payload.items[0].dealerTip)
            assertEquals("loss", payload.items[1].outcome)
            assertEquals(1200, payload.items[1].resultAmount)
        }
    }

    @Test
    fun walletDebitPostsToDebitEndpointAndCreditPostsToCreditEndpoint() {
        withPlatformServer { server ->
            server.respondJson("/service/operator/user/balance/v2") { exchange ->
                assertEquals("platform-token", exchange.requestHeaders.getFirst("token"))
                assertEquals("application/json", exchange.requestHeaders.getFirst("Content-Type"))
                """{"status":true,"msg":"OK"}"""
            }
            server.respondJson("/service/operator/user/credit/v2") { exchange ->
                assertEquals("platform-token", exchange.requestHeaders.getFirst("token"))
                assertEquals("application/json", exchange.requestHeaders.getFirst("Content-Type"))
                """{"status":true,"msg":"OK"}"""
            }
            val wallet =
                PlatformWalletService(
                    platformEnv(server.baseUrl()),
                    InMemoryWalletTransactionRepository(),
                    InMemoryRoundHistoryRepository(),
                    FixedClock(),
                )
            val player = PlatformPlayerRef("player-1", "platform-user", "platform-token", 789, "operator-1", "127.0.0.1", false)

            wallet.debit(player, "round-1", "round-1:player-1:boot", 1000, "Boot")
            wallet.credit(player, "round-1", "round-1:player-1:payout", 2000, "Payout")

            assertEquals(1, server.requestCount("/service/operator/user/balance/v2"))
            val debitBody = server.requestBodies("/service/operator/user/balance/v2").single()
            assertEquals(true, debitBody.contains(""""txn_type":0"""))
            assertEquals(true, debitBody.contains(""""operator_id":"operator-1""""))
            assertEquals(1, server.requestCount("/service/operator/user/credit/v2"))
            val creditBody = server.requestBodies("/service/operator/user/credit/v2").single()
            assertEquals(true, creditBody.contains(""""txn_type":1"""))
            assertEquals(true, creditBody.contains(""""txn_id":"round-1:player-1:payout""""))
            assertEquals(true, creditBody.contains(""""txn_ref_id":"round-1:player-1:boot""""))
            assertEquals(true, creditBody.contains(""""amount":2000"""))
            assertEquals(true, creditBody.contains(""""game_id":789"""))
            assertEquals(true, creditBody.contains(""""user_id":"platform-user""""))
            assertEquals(0, server.requestCount("/operator/user/balance"))
        }
    }

    @Test
    fun walletSkipsGuestPlayersWithoutPlatformLinkage() {
        withPlatformServer { server ->
            server.respondJson("/service/operator/user/balance/v2") { """{"status":true,"msg":"OK"}""" }
            val repository = InMemoryWalletTransactionRepository()
            val wallet = PlatformWalletService(platformEnv(server.baseUrl()), repository, InMemoryRoundHistoryRepository(), FixedClock())
            val guest = PlatformPlayerRef("player-1", null, null, null, null, "127.0.0.1", false)

            wallet.debit(guest, "round-1", "round-1:player-1:boot", 1000, "Boot")
            wallet.credit(guest, "round-1", "round-1:player-1:payout", 2000, "Payout")

            assertEquals(0, server.requestCount("/service/operator/user/balance/v2"))
            assertEquals(0, server.requestCount("/service/operator/user/credit/v2"))
            assertNull(repository.loadByOperationKey("round-1:player-1:boot"))
        }
    }

    @Test
    fun walletDebitIsIdempotentForSucceededTransactions() {
        withPlatformServer { server ->
            server.respondJson("/service/operator/user/balance/v2") { """{"status":true,"msg":"OK"}""" }
            val repository = InMemoryWalletTransactionRepository()
            val wallet = PlatformWalletService(platformEnv(server.baseUrl()), repository, InMemoryRoundHistoryRepository(), FixedClock())
            val player = PlatformPlayerRef("player-1", "platform-user", "platform-token", 789, "operator-1", "127.0.0.1", false)

            wallet.debit(player, "round-1", "round-1:player-1:boot", 1000, "Boot")
            wallet.debit(player, "round-1", "round-1:player-1:boot", 1000, "Boot")

            assertEquals(1, server.requestCount("/service/operator/user/balance/v2"))
            assertEquals("succeeded", repository.loadByOperationKey("round-1:player-1:boot")!!.status)
        }
    }

    @Test
    fun walletCreditMarksTransactionAppliedAfterPost() {
        withPlatformServer { server ->
            server.respondJson("/service/operator/user/credit/v2") { """{"status":true,"msg":"OK"}""" }
            val repository = InMemoryWalletTransactionRepository()
            val wallet = PlatformWalletService(platformEnv(server.baseUrl()), repository, InMemoryRoundHistoryRepository(), FixedClock())
            val player = PlatformPlayerRef("player-1", "platform-user", "platform-token", 789, "operator-1", "127.0.0.1", false)

            repository.save(WalletTransaction().also {
                it.id = "round-1:player-1:boot"
                it.txnId = "round-1:player-1:boot"
                it.operationKey = "round-1:player-1:boot"
                it.playerId = "player-1"
                it.platformUserId = "platform-user"
                it.roundId = "round-1"
                it.txnType = "debit"
                it.amount = 1000
                it.status = "succeeded"
                it.createdAt = "2026-01-01T00:00:00Z"
                it.updatedAt = "2026-01-01T00:00:00Z"
            })

            wallet.credit(player, "round-1", "round-1:player-1:payout", 2000, "Payout")

            assertEquals(1, server.requestCount("/service/operator/user/credit/v2"))
            val creditBody = server.requestBodies("/service/operator/user/credit/v2").single()
            assertEquals(true, creditBody.contains(""""txn_ref_id":"round-1:player-1:boot""""))
            assertEquals("applied", repository.loadByOperationKey("round-1:player-1:payout")!!.status)
        }
    }

    @Test
    fun walletDebitMarksTransactionFailedWhenPostFails() {
        val repository = InMemoryWalletTransactionRepository()
        val wallet =
            PlatformWalletService(
                platformEnv("http://unused"),
                repository,
                InMemoryRoundHistoryRepository(),
                FixedClock(),
            )
        val player = PlatformPlayerRef("player-1", "platform-user", "platform-token", 789, "operator-1", "127.0.0.1", false)

        val error =
            assertThrows(AppException::class.java) {
                wallet.debit(player, "round-1", "round-1:player-1:boot", 1000, "Boot")
            }

        assertEquals("platform_balance_failed", error.code)
        assertEquals("failed", repository.loadByOperationKey("round-1:player-1:boot")!!.status)
    }

    @Test
    fun walletCreditFailureRaisesPendingPayoutAndKeepsFailedStatus() {
        val repository = InMemoryWalletTransactionRepository()
        val wallet =
            PlatformWalletService(
                platformEnv("http://unused"),
                repository,
                InMemoryRoundHistoryRepository(),
                FixedClock(),
            )
        val player = PlatformPlayerRef("player-1", "platform-user", "platform-token", 789, "operator-1", "127.0.0.1", false)
        repository.save(WalletTransaction().also {
            it.id = "round-1:player-1:boot"
            it.txnId = "round-1:player-1:boot"
            it.operationKey = "round-1:player-1:boot"
            it.playerId = "player-1"
            it.platformUserId = "platform-user"
            it.roundId = "round-1"
            it.txnType = "debit"
            it.amount = 1000
            it.status = "succeeded"
            it.createdAt = "2026-01-01T00:00:00Z"
            it.updatedAt = "2026-01-01T00:00:00Z"
        })

        val error =
            assertThrows(AppException::class.java) {
                wallet.credit(player, "round-1", "round-1:player-1:payout", 2000, "Payout")
            }

        assertEquals("platform_payout_pending", error.code)
        assertEquals("failed", repository.loadByOperationKey("round-1:player-1:payout")!!.status)
    }

    private fun platformEnv(baseUrl: String, platformGameId: Int = 2): AppEnvironment =
        AppEnvironment().also {
            it.platformEnabled = true
            it.platformGameId = platformGameId
            it.appOperatorBaseUrl = baseUrl
            it.appOperatorUserDetailPath = "/service/user/detail"
            it.appOperatorBalancePath = "/service/operator/user/balance/v2"
            it.appOperatorCreditPath = "/service/operator/user/credit/v2"
            it.appOperatorLoginPath = "/operator/user/login"
            it.platformUserDetailUrl = "$baseUrl/service/user/detail"
            it.platformDebitUrl = "$baseUrl/service/operator/user/balance/v2"
            it.platformCreditUrl = "$baseUrl/service/operator/user/credit/v2"
            it.platformLoginUrl = "$baseUrl/operator/user/login"
        }

    private fun platformController(
        baseUrl: String,
        publicManagers: Map<String, org.teenpatti.server.publictable.PublicTableManager> = emptyMap(),
        walletTransactionRepository: WalletTransactionRepository = InMemoryWalletTransactionRepository(),
        roundHistoryRepository: InMemoryRoundHistoryRepository = InMemoryRoundHistoryRepository(),
        platformGameId: Int = 2,
    ): PlatformController {
        val env = platformEnv(baseUrl, platformGameId)
        return PlatformController(
            PlatformGatewayClient(env),
            FixedClock(),
            env,
            publicManagers,
            privateRoomManager(InMemoryPrivateRoomRepository(), roundHistoryRepository, FixedClock(), ManualScheduler()),
            PlatformWalletService(env, walletTransactionRepository, roundHistoryRepository, FixedClock()),
        )
    }

    private fun withPlatformServer(block: (RecordingHttpServer) -> Unit) {
        val server = RecordingHttpServer()
        try {
            block(server)
        } finally {
            server.close()
        }
    }
}

private class RecordingHttpServer : AutoCloseable {
    private val responses = linkedMapOf<String, (HttpExchange) -> String>()
    private val counts = linkedMapOf<String, Int>()
    private val bodies = linkedMapOf<String, MutableList<String>>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            counts[path] = (counts[path] ?: 0) + 1
            bodies.getOrPut(path) { mutableListOf() }.add(exchange.requestBody.bufferedReader().use { it.readText() })
            val body = responses[path]?.invoke(exchange) ?: """{"status":false,"msg":"Not found"}"""
            val status = if (responses.containsKey(path)) 200 else 404
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    fun baseUrl(): String = "http://127.0.0.1:${server.address.port}"

    fun respondJson(path: String, response: (HttpExchange) -> String) {
        responses[path] = response
    }

    fun requestCount(path: String): Int = counts[path] ?: 0

    fun requestBodies(path: String): List<String> = bodies[path] ?: emptyList()

    override fun close() {
        server.stop(0)
    }
}

private class InMemoryWalletTransactionRepository : WalletTransactionRepository {
    private val transactions = linkedMapOf<String, WalletTransaction>()

    override fun loadByOperationKey(operationKey: String): WalletTransaction? = transactions[operationKey]

    override fun save(transaction: WalletTransaction): WalletTransaction {
        transactions[transaction.operationKey] = transaction
        return transaction
    }

    override fun loadMostRecentDebit(playerId: String, roundId: String): WalletTransaction? =
        transactions.values
            .filter { it.playerId == playerId && it.roundId == roundId && it.txnType == "debit" && it.status in listOf("succeeded", "applied") }
            .maxByOrNull { it.updatedAt ?: "" }

    override fun listPendingCredits(limit: Int): List<WalletTransaction> =
        transactions.values.filter { it.txnType == "credit" && it.status != "applied" }.take(limit)

    override fun listRecentTransactions(platformUserId: String, txnType: String, offset: Int, limit: Int): List<WalletTransaction> =
        transactions.values
            .filter { it.platformUserId == platformUserId && it.txnType == txnType }
            .sortedByDescending { it.updatedAt ?: "" }
            .drop(offset)
            .take(limit)

    override fun listPlayerIdsForPlatformUser(platformUserId: String): List<String> =
        transactions.values
            .filter { it.platformUserId == platformUserId }
            .map { it.playerId }
            .distinct()
}

private fun walletTransaction(
    txnId: String,
    platformUserId: String,
    txnType: String,
    updatedAt: String,
    description: String,
    roundId: String = "round-1",
    txnRefId: String? = null,
    requestDescriptionOnly: Boolean = false,
): WalletTransaction =
    WalletTransaction().also {
        it.id = txnId
        it.txnId = txnId
        it.operationKey = txnId
        it.playerId = "player-$platformUserId"
        it.platformUserId = platformUserId
        it.roundId = roundId
        it.txnType = txnType
        it.amount = 1000
        it.status = "succeeded"
        it.txnRefId = txnRefId
        it.createdAt = updatedAt
        it.updatedAt = updatedAt
        it.requestPayload = linkedMapOf("description" to description)
        if (!requestDescriptionOnly) {
            it.responsePayload = linkedMapOf("description" to description)
        }
    }

private fun roundHistoryEntry(
    roundId: String,
    playerId: String,
    playerWon: Boolean,
    yourContribution: Int,
    payout: Int,
    dealerTip: Int,
): RoundHistoryEntry =
    RoundHistoryEntry().also { entry ->
        entry.id = roundId
        entry.aggregateType = "table"
        entry.aggregateId = "table-1"
        entry.variantId = "classic"
        entry.potAmount = 5000
        entry.bootCommission = 100
        entry.winCommission = 200
        entry.dealerTip = dealerTip
        entry.casinoCommissionTotal = 300 + dealerTip
        entry.winnerReceivableBeforeTip = payout + dealerTip
        entry.payout = payout
        entry.reason = if (playerWon) "Won on showdown" else "Lost on showdown"
        entry.settledAt = if (playerWon) "2026-01-04T00:00:00Z" else "2026-01-03T00:00:00Z"
        val participant = RoundHistoryParticipant()
        participant.id = playerId
        participant.name = "Platform Player"
        participant.totalContributed = yourContribution
        entry.participants.add(participant)
        val opponent = RoundHistoryParticipant()
        opponent.id = "opponent-1"
        opponent.name = "Opponent"
        opponent.totalContributed = 1400
        opponent.isBot = false
        entry.participants.add(opponent)
        val winner = RoundHistoryWinner()
        winner.id = if (playerWon) playerId else "opponent-1"
        winner.name = if (playerWon) "Platform Player" else "Opponent"
        winner.winningHand = "Trail"
        entry.winner = winner
    }
