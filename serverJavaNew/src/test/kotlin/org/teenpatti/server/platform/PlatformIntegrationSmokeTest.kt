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
    fun operationsGameTypeMapsTtypePerSpecification() {
        assertEquals(1, OperationsGameType.SPIN.ttype)
        assertEquals(1, OperationsGameType.FREESPIN.ttype)
        assertEquals(1, OperationsGameType.BONUS.ttype)
        assertEquals(1, OperationsGameType.GAMBLE.ttype)
        assertEquals(1, OperationsGameType.GAMBLE_COLLECT.ttype)
        assertEquals(2, OperationsGameType.REFUND.ttype)
        assertEquals(OperationsGameType.GAMBLE_COLLECT, OperationsGameType.fromCode("gamble_collect"))
        assertEquals(OperationsGameType.REFUND, OperationsGameType.fromCode("refund"))
    }

    @Test
    fun platformPublicSessionCallsOperations2051BalanceAndPersistsLaunchContext() {
        withPlatformServer { server ->
            server.respondJson("/api/v1/wallet/balance") { exchange ->
                assertEquals("POST", exchange.requestMethod)
                assertEquals("test-internal-key", exchange.requestHeaders.getFirst("X-Internal-Key"))
                """{"status":true,"data":{"user_id":"platform-user","username":"Platform Player","balance":12345,"currency":"INR"}}"""
            }
            val sessionRepository = InMemoryPublicSessionRepository()
            val controller =
                platformController(
                    server.baseUrl(),
                    publicManagers = mapOf("classic" to publicManager(sessionRepository = sessionRepository)),
                )

            val response =
                controller.createPublicSession(
                    PlatformSessionRequest(
                        token = "1e5e5ad8-a2b09164-b240b1f1-e1ef5c62",
                        gid = 1001,
                        partner_code = "ALPHA-TEST",
                        username = "LaunchPlayer",
                        currency = "INR",
                        clientSeed = clientSeed("platform-user"),
                        variant = "classic",
                    ),
                    MockHttpServletRequest(),
                )

            @Suppress("UNCHECKED_CAST")
            val payload = response["data"] as Map<String, Any?>
            val playerId = payload["playerId"] as String
            val saved = sessionRepository.loadSession(playerId)!!
            assertEquals("platform-user", saved.platformUserId)
            assertEquals("1e5e5ad8-a2b09164-b240b1f1-e1ef5c62", saved.platformToken)
            assertEquals(1001, saved.platformGameId)
            assertEquals("ALPHA-TEST", saved.platformOperatorId)

            val balanceBody = server.requestBodies("/api/v1/wallet/balance").single()
            assertEquals(true, balanceBody.contains(""""token":"1e5e5ad8-a2b09164-b240b1f1-e1ef5c62""""))
            assertEquals(true, balanceBody.contains(""""partner_code":"ALPHA-TEST""""))
        }
    }

    @Test
    fun platformProfileCallsOperations2051AndUsesLaunchFallbacks() {
        withPlatformServer { server ->
            server.respondJson("/api/v1/wallet/balance") { exchange ->
                assertEquals("test-internal-key", exchange.requestHeaders.getFirst("X-Internal-Key"))
                """{"balance":43210}"""
            }
            val controller = platformController(server.baseUrl())

            @Suppress("UNCHECKED_CAST")
            val payload =
                controller.getProfile(
                    PlatformProfileRequest(
                        token = "platform-token",
                        gid = "1001",
                        partner_code = "alpha-test",
                        username = "Raju99",
                        user_id = "op-user-99",
                        currency = "usd",
                    ),
                )["data"] as Map<String, Any?>

            assertEquals("op-user-99", payload["userId"])
            assertEquals("Raju99", payload["username"])
            assertEquals(43210, payload["balance"])
            assertEquals("USD", payload["currency"])
            assertEquals(1001, payload["gameId"])
            assertEquals(1001, payload["gid"])
            assertEquals("ALPHA-TEST", payload["partnerCode"])
        }
    }

    @Test
    fun platformProfileReadsBalanceFromStatusTrueMessagePayload() {
        withPlatformServer { server ->
            server.respondJson("/api/v1/wallet/balance") {
                """{"status":true,"message":100000}"""
            }
            val controller = platformController(server.baseUrl())

            @Suppress("UNCHECKED_CAST")
            val payload =
                controller.getProfile(
                    PlatformProfileRequest(
                        token = "platform-token",
                        gid = 1001,
                        partner_code = "ALPHA-TEST",
                        username = "PlayerOne",
                        currency = "INR",
                    ),
                )["data"] as Map<String, Any?>

            assertEquals(100000, payload["balance"])
            assertEquals("INR", payload["currency"])
        }
    }

    @Test
    fun platformProfileReadsBalanceFromCombinedMessageAndBalancePayload() {
        withPlatformServer { server ->
            server.respondJson("/api/v1/wallet/balance") {
                """{"status":true,"success":true,"message":100000,"balance":100000,"currency":"INR"}"""
            }
            val controller = platformController(server.baseUrl())

            @Suppress("UNCHECKED_CAST")
            val payload =
                controller.getProfile(
                    PlatformProfileRequest(
                        token = "platform-token",
                        gid = 1001,
                        partner_code = "ALPHA-TEST",
                        username = "PlayerOne",
                    ),
                )["data"] as Map<String, Any?>

            assertEquals(100000, payload["balance"])
            assertEquals("INR", payload["currency"])
        }
    }

    @Test
    fun platformSessionRejectsMissingTokenBeforeOperationsCall() {
        withPlatformServer { server ->
            val controller = platformController(server.baseUrl())

            val error =
                assertThrows(AppException::class.java) {
                    controller.createPublicSession(
                        PlatformSessionRequest(
                            token = "",
                            gid = 1001,
                            partner_code = "ALPHA-TEST",
                            clientSeed = clientSeed("platform-user"),
                            variant = "classic",
                        ),
                        MockHttpServletRequest(),
                    )
                }
            assertEquals("platform_token_required", error.code)
            assertEquals(0, server.requestCount("/api/v1/wallet/balance"))
        }
    }

    @Test
    fun platformSessionRejectsMissingPartnerCodeBeforeOperationsCall() {
        withPlatformServer { server ->
            val controller = platformController(server.baseUrl(), defaultPartnerCode = "")

            val error =
                assertThrows(AppException::class.java) {
                    controller.createPublicSession(
                        PlatformSessionRequest(
                            token = "platform-token",
                            gid = 1001,
                            partner_code = "",
                            clientSeed = clientSeed("platform-user"),
                            variant = "classic",
                        ),
                        MockHttpServletRequest(),
                    )
                }
            assertEquals("platform_partner_code_required", error.code)
            assertEquals(0, server.requestCount("/api/v1/wallet/balance"))
        }
    }

    @Test
    fun platformSessionRejectsMissingGidBeforeOperationsCall() {
        withPlatformServer { server ->
            val controller = platformController(server.baseUrl(), platformGameId = 0)

            val error =
                assertThrows(AppException::class.java) {
                    controller.createPublicSession(
                        PlatformSessionRequest(
                            token = "platform-token",
                            gameId = 0,
                            partner_code = "ALPHA-TEST",
                            clientSeed = clientSeed("platform-user"),
                            variant = "classic",
                        ),
                        MockHttpServletRequest(),
                    )
                }
            assertEquals("platform_game_id_required", error.code)
            assertEquals(0, server.requestCount("/api/v1/wallet/balance"))
        }
    }

    @Test
    fun walletDebitCreditAndRefundPostToOperations2052CreditTransfer() {
        withPlatformServer { server ->
            server.respondJson("/api/v1/wallet/credit-transfer") { exchange ->
                assertEquals("POST", exchange.requestMethod)
                assertEquals("test-internal-key", exchange.requestHeaders.getFirst("X-Internal-Key"))
                assertEquals("application/json", exchange.requestHeaders.getFirst("Content-Type"))
                """{"status":true,"balance":9900}"""
            }
            val wallet =
                PlatformWalletService(
                    platformEnv(server.baseUrl()),
                    InMemoryWalletTransactionRepository(),
                    InMemoryRoundHistoryRepository(),
                    FixedClock(),
                )
            val player =
                PlatformPlayerRef(
                    playerId = "player-1",
                    platformUserId = "platform-user",
                    platformToken = "1e5e5ad8-a2b09164-b240b1f1-e1ef5c62",
                    platformGameId = 1001,
                    platformOperatorId = "ALPHA-TEST",
                    ip = "127.0.0.1",
                    isBot = false,
                )

            wallet.debit(player, "rnd8841", "1001-rnd8841-1", 10, "Boot")
            wallet.credit(player, "rnd8841", "1001-rnd8841-2", 25, "Payout")
            wallet.refund(player, "rnd8842", "1001-rnd8842-refund", 10, "Aborted round refund")
            wallet.settleTransfer(player, "rnd8843", "1001-rnd8843-1", 10, 25, OperationsGameType.SPIN, "Combined settle")

            assertEquals(4, server.requestCount("/api/v1/wallet/credit-transfer"))
            val bodies = server.requestBodies("/api/v1/wallet/credit-transfer")

            // 1. Debit (bet=10, win=0, game_type=spin, ttype=1)
            assertEquals(true, bodies[0].contains(""""token":"1e5e5ad8-a2b09164-b240b1f1-e1ef5c62""""))
            assertEquals(true, bodies[0].contains(""""partner_code":"ALPHA-TEST""""))
            assertEquals(true, bodies[0].contains(""""gid":1001"""))
            assertEquals(true, bodies[0].contains(""""bet":10"""))
            assertEquals(true, bodies[0].contains(""""win":0"""))
            assertEquals(true, bodies[0].contains(""""game_type":"spin""""))
            assertEquals(true, bodies[0].contains(""""tranid":"1001-rnd8841-1""""))
            assertEquals(true, bodies[0].contains(""""ttype":1"""))
            assertEquals(true, bodies[0].contains(""""roundid":"rnd8841""""))

            // 2. Credit (bet=0, win=25, game_type=spin, ttype=1)
            assertEquals(true, bodies[1].contains(""""bet":0"""))
            assertEquals(true, bodies[1].contains(""""win":25"""))
            assertEquals(true, bodies[1].contains(""""game_type":"spin""""))
            assertEquals(true, bodies[1].contains(""""ttype":1"""))

            // 3. Refund (bet=10, win=0, game_type=refund, ttype=2)
            assertEquals(true, bodies[2].contains(""""bet":10"""))
            assertEquals(true, bodies[2].contains(""""win":0"""))
            assertEquals(true, bodies[2].contains(""""game_type":"refund""""))
            assertEquals(true, bodies[2].contains(""""ttype":2"""))
            assertEquals(true, bodies[2].contains(""""tranid":"1001-rnd8842-refund""""))
            assertEquals(true, bodies[2].contains(""""roundid":"rnd8842""""))

            // 4. Combined bet + win settlement
            assertEquals(true, bodies[3].contains(""""bet":10"""))
            assertEquals(true, bodies[3].contains(""""win":25"""))
            assertEquals(true, bodies[3].contains(""""game_type":"spin""""))
            assertEquals(true, bodies[3].contains(""""ttype":1"""))
        }
    }

    @Test
    fun walletRetriesWithExactSameTranidOn502AndErr0020() {
        withPlatformServer { server ->
            var attempts = 0
            server.respondWithStatus("/api/v1/wallet/credit-transfer") {
                attempts += 1
                when (attempts) {
                    1 -> 502 to """{"status":false,"code":"ERR-0020","msg":"Upstream gateway timeout"}"""
                    2 -> 200 to """{"status":false,"code":"ERR-0021","msg":"Temporary network failure"}"""
                    else -> 200 to """{"status":true,"balance":990}"""
                }
            }
            val repository = InMemoryWalletTransactionRepository()
            val wallet =
                PlatformWalletService(platformEnv(server.baseUrl()), repository, InMemoryRoundHistoryRepository(), FixedClock()).also {
                    it.retryDelayMs = 0L
                }
            val player = PlatformPlayerRef("player-1", "platform-user", "platform-token", 1001, "ALPHA-TEST", "127.0.0.1", false)

            wallet.debit(player, "rnd8841", "1001-rnd8841-1", 10, "Boot")

            assertEquals(3, server.requestCount("/api/v1/wallet/credit-transfer"))
            val bodies = server.requestBodies("/api/v1/wallet/credit-transfer")
            bodies.forEach { body ->
                assertEquals(true, body.contains(""""tranid":"1001-rnd8841-1""""))
            }
            assertEquals("succeeded", repository.loadByOperationKey("1001-rnd8841-1")!!.status)
        }
    }

    @Test
    fun walletLateWinSettlesWithoutTokenUsingPartnerCodeAndUserId() {
        withPlatformServer { server ->
            server.respondJson("/api/v1/wallet/credit-transfer") { """{"status":true,"balance":1250}""" }
            val repository = InMemoryWalletTransactionRepository()
            val wallet = PlatformWalletService(platformEnv(server.baseUrl()), repository, InMemoryRoundHistoryRepository(), FixedClock())
            val disconnectedPlayer =
                PlatformPlayerRef(
                    playerId = "player-1",
                    platformUserId = "op-user-42",
                    platformToken = null,
                    platformGameId = 1001,
                    platformOperatorId = "ALPHA-TEST",
                    ip = "127.0.0.1",
                    isBot = false,
                )

            wallet.credit(disconnectedPlayer, "rnd9001", "1001-rnd9001-win", 250, "Late win payout")

            assertEquals(1, server.requestCount("/api/v1/wallet/credit-transfer"))
            val body = server.requestBodies("/api/v1/wallet/credit-transfer").single()
            assertEquals(false, body.contains(""""token":"""))
            assertEquals(true, body.contains(""""partner_code":"ALPHA-TEST""""))
            assertEquals(true, body.contains(""""user_id":"op-user-42""""))
            assertEquals(true, body.contains(""""win":250"""))
            assertEquals(true, body.contains(""""bet":0"""))
            assertEquals("applied", repository.loadByOperationKey("1001-rnd9001-win")!!.status)
        }
    }

    @Test
    fun walletClassifiesErr0011AsSessionExpiredWithoutRetrying() {
        withPlatformServer { server ->
            server.respondWithStatus("/api/v1/wallet/credit-transfer") {
                401 to """{"status":false,"code":"ERR-0012","msg":"Session expired"}"""
            }
            val wallet =
                PlatformWalletService(platformEnv(server.baseUrl()), InMemoryWalletTransactionRepository(), InMemoryRoundHistoryRepository(), FixedClock()).also {
                    it.retryDelayMs = 0L
                }
            val player = PlatformPlayerRef("player-1", "platform-user", "expired-token", 1001, "ALPHA-TEST", "127.0.0.1", false)

            val error =
                assertThrows(AppException::class.java) {
                    wallet.debit(player, "rnd9002", "1001-rnd9002-1", 100, "Call")
                }

            assertEquals("platform_session_expired", error.code)
            assertEquals(true, error.message!!.contains("ERR-0012"))
            assertEquals(1, server.requestCount("/api/v1/wallet/credit-transfer"))
        }
    }

    @Test
    fun walletClassifies400BusinessRefusalWithoutRetrying() {
        withPlatformServer { server ->
            server.respondWithStatus("/api/v1/wallet/credit-transfer") {
                400 to """{"status":false,"msg":"Insufficient balance"}"""
            }
            val wallet =
                PlatformWalletService(platformEnv(server.baseUrl()), InMemoryWalletTransactionRepository(), InMemoryRoundHistoryRepository(), FixedClock()).also {
                    it.retryDelayMs = 0L
                }
            val player = PlatformPlayerRef("player-1", "platform-user", "platform-token", 1001, "ALPHA-TEST", "127.0.0.1", false)

            val error =
                assertThrows(AppException::class.java) {
                    wallet.debit(player, "rnd9003", "1001-rnd9003-1", 5000, "Raise")
                }

            assertEquals("platform_operator_refusal", error.code)
            assertEquals("Insufficient balance", error.message)
            assertEquals(1, server.requestCount("/api/v1/wallet/credit-transfer"))
        }
    }

    @Test
    fun walletSkipsGuestPlayersWithoutPlatformLinkage() {
        withPlatformServer { server ->
            server.respondJson("/api/v1/wallet/credit-transfer") { """{"status":true}""" }
            val repository = InMemoryWalletTransactionRepository()
            val wallet = PlatformWalletService(platformEnv(server.baseUrl()), repository, InMemoryRoundHistoryRepository(), FixedClock())
            val guest = PlatformPlayerRef("player-1", null, null, null, null, "127.0.0.1", false)

            wallet.debit(guest, "round-1", "round-1:player-1:boot", 1000, "Boot")
            wallet.credit(guest, "round-1", "round-1:player-1:payout", 2000, "Payout")

            assertEquals(0, server.requestCount("/api/v1/wallet/credit-transfer"))
            assertNull(repository.loadByOperationKey("round-1:player-1:boot"))
        }
    }

    @Test
    fun walletDebitIsIdempotentForSucceededTransactions() {
        withPlatformServer { server ->
            server.respondJson("/api/v1/wallet/credit-transfer") { """{"status":true}""" }
            val repository = InMemoryWalletTransactionRepository()
            val wallet = PlatformWalletService(platformEnv(server.baseUrl()), repository, InMemoryRoundHistoryRepository(), FixedClock())
            val player = PlatformPlayerRef("player-1", "platform-user", "platform-token", 1001, "ALPHA-TEST", "127.0.0.1", false)

            wallet.debit(player, "round-1", "round-1:player-1:boot", 1000, "Boot")
            wallet.debit(player, "round-1", "round-1:player-1:boot", 1000, "Boot")

            assertEquals(1, server.requestCount("/api/v1/wallet/credit-transfer"))
            assertEquals("succeeded", repository.loadByOperationKey("round-1:player-1:boot")!!.status)
        }
    }

    @Test
    fun walletCreditMarksTransactionAppliedAndSupportsZeroBetBonus() {
        withPlatformServer { server ->
            server.respondJson("/api/v1/wallet/credit-transfer") { """{"status":true}""" }
            val repository = InMemoryWalletTransactionRepository()
            val wallet = PlatformWalletService(platformEnv(server.baseUrl()), repository, InMemoryRoundHistoryRepository(), FixedClock())
            val player = PlatformPlayerRef("player-1", "platform-user", "platform-token", 1001, "ALPHA-TEST", "127.0.0.1", false)

            wallet.credit(player, "round-bonus-1", "round-bonus-1:player-1:bonus", 500, "Bonus", gameType = OperationsGameType.BONUS)

            assertEquals(1, server.requestCount("/api/v1/wallet/credit-transfer"))
            val body = server.requestBodies("/api/v1/wallet/credit-transfer").single()
            assertEquals(true, body.contains(""""game_type":"bonus""""))
            assertEquals(true, body.contains(""""ttype":1"""))
            assertEquals(true, body.contains(""""bet":0"""))
            assertEquals(true, body.contains(""""win":500"""))
            assertEquals("applied", repository.loadByOperationKey("round-bonus-1:player-1:bonus")!!.status)
        }
    }

    @Test
    fun walletDebitMarksTransactionFailedWhenPostFails() {
        val repository = InMemoryWalletTransactionRepository()
        val wallet =
            PlatformWalletService(
                platformEnv("http://127.0.0.1:1"),
                repository,
                InMemoryRoundHistoryRepository(),
                FixedClock(),
            ).also { it.retryDelayMs = 0L }
        val player = PlatformPlayerRef("player-1", "platform-user", "platform-token", 1001, "ALPHA-TEST", "127.0.0.1", false)

        val error =
            assertThrows(AppException::class.java) {
                wallet.debit(player, "round-1", "round-1:player-1:boot", 1000, "Boot")
            }

        assertEquals("platform_balance_timeout", error.code)
        assertEquals("failed", repository.loadByOperationKey("round-1:player-1:boot")!!.status)
    }

    @Test
    fun walletCreditFailureRaisesPendingPayoutAndKeepsFailedStatus() {
        val repository = InMemoryWalletTransactionRepository()
        val wallet =
            PlatformWalletService(
                platformEnv("http://127.0.0.1:1"),
                repository,
                InMemoryRoundHistoryRepository(),
                FixedClock(),
            )
        val player = PlatformPlayerRef("player-1", "platform-user", "platform-token", 1001, "ALPHA-TEST", "127.0.0.1", false)

        val error =
            assertThrows(AppException::class.java) {
                wallet.credit(player, "round-1", "round-1:player-1:payout", 2000, "Payout")
            }

        assertEquals("platform_payout_pending", error.code)
        assertEquals("failed", repository.loadByOperationKey("round-1:player-1:payout")!!.status)
    }

    @Test
    fun debitCreditAndRoundHistoryAuthenticateVia2051Balance() {
        withPlatformServer { server ->
            server.respondJson("/api/v1/wallet/balance") {
                """{"status":true,"data":{"user_id":"platform-user","username":"Platform Player","balance":43210,"currency":"INR"}}"""
            }
            val walletRepository = InMemoryWalletTransactionRepository()
            val roundRepository = InMemoryRoundHistoryRepository()
            walletRepository.save(walletTransaction("debit-1", "platform-user", "debit", "2026-01-01T00:00:00Z", "Boot debited", roundId = "round-1"))
            walletRepository.save(walletTransaction("credit-1", "platform-user", "credit", "2026-01-04T00:00:00Z", "Payout credited", roundId = "round-2"))
            roundRepository.appendRound(roundHistoryEntry("round-1", "player-platform-user", false, 1200, 0, 0))
            roundRepository.appendRound(roundHistoryEntry("round-2", "player-platform-user", true, 1600, 4000, 300))
            val controller =
                platformController(
                    server.baseUrl(),
                    walletTransactionRepository = walletRepository,
                    roundHistoryRepository = roundRepository,
                )

            val debits =
                controller.debitTransactions(
                    PlatformTransactionHistoryRequest(token = "platform-token", gid = 1001, partner_code = "ALPHA-TEST", offset = 0, limit = 20),
                )["data"] as PlatformWalletService.WalletTransactionHistoryResponse
            val credits =
                controller.creditTransactions(
                    PlatformTransactionHistoryRequest(token = "platform-token", gid = 1001, partner_code = "ALPHA-TEST", offset = 0, limit = 20),
                )["data"] as PlatformWalletService.WalletTransactionHistoryResponse
            val rounds =
                controller.roundHistory(
                    PlatformRoundHistoryRequest(token = "platform-token", gid = 1001, partner_code = "ALPHA-TEST", offset = 0, limit = 20),
                )["data"] as PlatformWalletService.PlatformRoundHistoryResponse

            assertEquals(listOf("debit-1"), debits.items.map { it.txnId })
            assertEquals(listOf("credit-1"), credits.items.map { it.txnId })
            assertEquals(2, rounds.items.size)
            assertEquals("round-2", rounds.items[0].roundId)
            assertEquals("win", rounds.items[0].outcome)
            assertEquals(4000, rounds.items[0].resultAmount)
            assertEquals(300, rounds.items[0].dealerTip)
        }
    }

    private fun platformEnv(
        baseUrl: String,
        platformGameId: Int = 1001,
        defaultPartnerCode: String = "",
    ): AppEnvironment =
        AppEnvironment().also {
            it.platformEnabled = true
            it.platformGameId = platformGameId
            it.operationsBaseUrl = baseUrl
            it.operationsWalletBalancePath = "/api/v1/wallet/balance"
            it.operationsWalletCreditTransferPath = "/api/v1/wallet/credit-transfer"
            it.operationsBalanceUrl = "$baseUrl/api/v1/wallet/balance"
            it.operationsCreditTransferUrl = "$baseUrl/api/v1/wallet/credit-transfer"
            it.internalServiceKey = "test-internal-key"
            it.operationsDefaultPartnerCode = defaultPartnerCode
            it.platformGameName = "teen-patti"
        }

    private fun platformController(
        baseUrl: String,
        publicManagers: Map<String, org.teenpatti.server.publictable.PublicTableManager> = emptyMap(),
        walletTransactionRepository: WalletTransactionRepository = InMemoryWalletTransactionRepository(),
        roundHistoryRepository: InMemoryRoundHistoryRepository = InMemoryRoundHistoryRepository(),
        platformGameId: Int = 1001,
        defaultPartnerCode: String = "",
    ): PlatformController {
        val env = platformEnv(baseUrl, platformGameId, defaultPartnerCode)
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
    private val responses = linkedMapOf<String, (HttpExchange) -> Pair<Int, String>>()
    private val counts = linkedMapOf<String, Int>()
    private val bodies = linkedMapOf<String, MutableList<String>>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            counts[path] = (counts[path] ?: 0) + 1
            bodies.getOrPut(path) { mutableListOf() }.add(exchange.requestBody.bufferedReader().use { it.readText() })
            val (status, body) = responses[path]?.invoke(exchange) ?: (404 to """{"status":false,"msg":"Not found"}""")
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    fun baseUrl(): String = "http://127.0.0.1:${server.address.port}"

    fun respondJson(path: String, response: (HttpExchange) -> String) {
        responses[path] = { exchange -> 200 to response(exchange) }
    }

    fun respondWithStatus(path: String, response: (HttpExchange) -> Pair<Int, String>) {
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
