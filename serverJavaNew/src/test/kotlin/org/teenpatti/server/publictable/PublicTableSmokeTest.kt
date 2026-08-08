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
internal class PublicTableSmokeTest {
    @Test
    fun publicSessionReconnectKeepsTableAssignment() {
        val tableRepository = InMemoryTableRepository()
        val sessionRepository = InMemoryPublicSessionRepository()
        val manager = publicManager(tableRepository = tableRepository, sessionRepository = sessionRepository)

        val joined = joinPublic(manager, "Alpha")
        val playerId = joined["playerId"] as String
        val playerToken = joined["playerToken"] as String
        val tableId = joined["tableId"] as String

        manager.disconnect(playerId, playerToken)
        val reconnected = manager.connect(playerId, playerToken)

        assertEquals(tableId, reconnected["tableId"])
        assertEquals("active_at_table", reconnected["playerStatus"])
        assertNotNull(reconnected["table"])
    }

    @Test
    fun publicJoinCreatesFirstTableAndSeatsImmediately() {
        val tableRepository = InMemoryTableRepository()
        val sessionRepository = InMemoryPublicSessionRepository()
        val manager = publicManager(tableRepository = tableRepository, sessionRepository = sessionRepository)

        val joined = joinPublic(manager, "Alpha")

        assertEquals("active_at_table", joined["playerStatus"])
        assertEquals(1, tableRepository.state.size)
        assertNotNull(joined["tableId"])
        assertNotNull(joined["table"])
    }

    @Test
    fun publicJoinWaitsBeforeStartingInitialRound() {
        val scheduler = CapturingScheduler()
        val tableRepository = InMemoryTableRepository()
        val sessionRepository = InMemoryPublicSessionRepository()
        val manager =
            publicManager(
                tableRepository = tableRepository,
                sessionRepository = sessionRepository,
                scheduler = scheduler,
            )

        val alpha = manager.joinPublicTable("Alpha", clientSeed("Alpha"))
        val table = managedTable(manager, alpha["tableId"] as String)
        val alphaTable = alpha["table"] as Map<*, *>

        assertEquals("active_at_table", alpha["playerStatus"])
        assertNotNull(alphaTable["publicJoinWaitStartedAt"])
        assertNotNull(alphaTable["publicJoinWaitEndsAt"])
        assertNull(table.service.state.round)

        val bravo = manager.joinPublicTable("Bravo", clientSeed("Bravo"))
        val bravoTable = bravo["table"] as Map<*, *>

        assertEquals(alpha["tableId"], bravo["tableId"])
        assertEquals(alphaTable["publicJoinWaitEndsAt"], bravoTable["publicJoinWaitEndsAt"])
        assertNull(table.service.state.round)

        scheduler.runLast()

        val round = table.service.state.round
        assertNotNull(round)
        assertEquals(2L, round!!.seats.count { !it.isBot }.toLong())
    }

    @Test
    fun publicJoinPrefersExistingPlayableTableBeforeQueueing() {
        val tableRepository = InMemoryTableRepository()
        val sessionRepository = InMemoryPublicSessionRepository()
        val manager = publicManager(tableRepository = tableRepository, sessionRepository = sessionRepository)

        val firstJoined = joinPublic(manager, "Alpha")
        val activeTableId = firstJoined["tableId"] as String
        val playableTable = invokeCreateTable(manager)

        val joined = joinPublic(manager, "Bravo")

        assertEquals("active_at_table", joined["playerStatus"])
        assertEquals(playableTable.tableId, joined["tableId"])
        assertNotEquals(activeTableId, joined["tableId"])
        assertEquals(2, tableRepository.state.size)
    }

    @Test
    fun waitingPlayerSnapshotRefreshKeepsThemEligibleForNextRound() {
        val tableRepository = InMemoryTableRepository()
        val sessionRepository = InMemoryPublicSessionRepository()
        val clock = MutableClock()
        val manager = publicManager(tableRepository = tableRepository, sessionRepository = sessionRepository, clock = clock)

        val firstJoined = joinPublic(manager, "Alpha")
        val waitingJoined = joinPublic(manager, "Bravo")
        val waitingPlayerId = waitingJoined["playerId"] as String
        val waitingPlayerToken = waitingJoined["playerToken"] as String
        val tableId = firstJoined["tableId"] as String

        assertEquals("waiting_for_next_round", waitingJoined["playerStatus"])

        clock.advanceMillis(10_000L)
        manager.getSessionSnapshot(waitingPlayerId, waitingPlayerToken)
        clock.advanceMillis(10_000L)

        val table = managedTable(manager, tableId)
        table.service.state.round!!.status = "complete"
        table.service.state.round!!.settledAt = clock.nowIso()
        markPublicSessionReady(manager, firstJoined["playerId"] as String)

        invokeMaybeStartNextRound(manager, table)

        val refreshedWaitingPlayer = sessionRepository.loadSession(waitingPlayerId)!!
        assertEquals("active_at_table", refreshedWaitingPlayer.status)
        assertTrue(table.service.state.round!!.seats.any { it.id == waitingPlayerId }, "waiting player should be promoted into the next round")
    }

    @Test
    fun publicTableUsesConfiguredBotCapForSingleHumanRound() {
        val config = testGameConfig("classic").apply { publicTableMaxBots = 1 }
        val manager = publicManager(config = config)

        val joined = joinPublic(manager, "Alpha")
        val table = managedTable(manager, joined["tableId"] as String)

        assertEquals(2, table.service.state.round!!.seats.size)
        assertEquals(1L, table.service.state.round!!.seats.count { !it.isBot }.toLong())
        assertEquals(1L, table.service.state.round!!.seats.count { it.isBot }.toLong())
    }

    @Test
    fun publicSnapshotReconcilesWaitingStatusWhenPlayerIsAlreadySeated() {
        val config = testGameConfig("classic").apply { publicTableMaxBots = 1 }
        val sessionRepository = InMemoryPublicSessionRepository()
        val manager = publicManager(config = config, sessionRepository = sessionRepository)

        val joined = joinPublic(manager, "Alpha")
        val playerId = joined["playerId"] as String
        val playerToken = joined["playerToken"] as String
        val session = sessionRepository.loadSession(playerId)!!
        session.status = "waiting_for_next_round"
        sessionRepository.saveSession(session)

        val snapshot = manager.getSessionSnapshot(playerId, playerToken)

        @Suppress("UNCHECKED_CAST")
        val table = snapshot["table"] as Map<String, Any?>
        assertEquals("active_at_table", snapshot["playerStatus"])
        assertEquals("active_at_table", table["viewerPlayerStatus"])
        assertEquals("active_at_table", sessionRepository.loadSession(playerId)!!.status)
    }

    @Test
    fun publicActionReconcilesWaitingStatusWhenPlayerIsAlreadySeated() {
        val config = testGameConfig("classic").apply { publicTableMaxBots = 1 }
        val sessionRepository = InMemoryPublicSessionRepository()
        val manager = publicManager(config = config, sessionRepository = sessionRepository)

        val joined = joinPublic(manager, "Alpha")
        val playerId = joined["playerId"] as String
        val playerToken = joined["playerToken"] as String
        val table = managedTable(manager, joined["tableId"] as String)
        table.service.state.round!!.status = "active"
        setActivePlayer(table.service, playerId)
        val session = sessionRepository.loadSession(playerId)!!
        session.status = "waiting_for_next_round"
        sessionRepository.saveSession(session)

        manager.performAction(playerId, playerToken, "pack", emptyMap())

        assertEquals("active_at_table", sessionRepository.loadSession(playerId)!!.status)
    }

    @Test
    fun publicTableRemovesBotsOnceTwoRealPlayersAreSeated() {
        val config = testGameConfig("classic").apply { publicTableMaxBots = 1 }
        val tableRepository = InMemoryTableRepository()
        val sessionRepository = InMemoryPublicSessionRepository()
        val clock = MutableClock()
        val manager = publicManager(config, tableRepository, sessionRepository, InMemoryRoundHistoryRepository(), clock)

        val firstJoined = joinPublic(manager, "Alpha")
        val secondJoined = joinPublic(manager, "Bravo")
        val table = managedTable(manager, firstJoined["tableId"] as String)

        assertEquals("waiting_for_next_round", secondJoined["playerStatus"])
        assertEquals(1, tableRepository.state.size)

        table.service.state.round!!.status = "complete"
        table.service.state.round!!.settledAt = clock.nowIso()
        markPublicSessionReady(manager, firstJoined["playerId"] as String)
        invokeMaybeStartNextRound(manager, table)

        assertEquals(2, table.service.state.round!!.seats.size)
        assertEquals(2L, table.service.state.round!!.seats.count { !it.isBot }.toLong())
        assertEquals(0L, table.service.state.round!!.seats.count { it.isBot }.toLong())
    }

    @Test
    fun publicTableDoesNotRetainSeatedPlayerWhoMissesNextRoundDecision() {
        val config = testGameConfig("classic").apply { publicTableMaxBots = 1 }
        val tableRepository = InMemoryTableRepository()
        val sessionRepository = InMemoryPublicSessionRepository()
        val clock = MutableClock()
        val manager = publicManager(config, tableRepository, sessionRepository, InMemoryRoundHistoryRepository(), clock)

        val firstJoined = joinPublic(manager, "Alpha")
        val alphaId = firstJoined["playerId"] as String
        val secondJoined = joinPublic(manager, "Bravo")
        val bravoId = secondJoined["playerId"] as String
        val table = managedTable(manager, firstJoined["tableId"] as String)

        table.service.state.round!!.status = "complete"
        table.service.state.round!!.settledAt = clock.nowIso()
        invokeMaybeStartNextRound(manager, table)

        val alpha = sessionRepository.loadSession(alphaId)!!
        assertEquals("left", alpha.status)
        assertFalse(alpha.connected)
        assertFalse(table.service.state.round!!.seats.any { it.id == alphaId })
        assertTrue(table.service.state.round!!.seats.any { it.id == bravoId })
    }

    @Test
    fun publicTableCanUseLargerBotCapWithoutFillingEverySeat() {
        val config = testGameConfig("classic").apply { publicTableMaxBots = 3 }
        val manager = publicManager(config = config)

        val joined = joinPublic(manager, "Alpha")
        val table = managedTable(manager, joined["tableId"] as String)

        assertEquals(4, table.service.state.round!!.seats.size)
        assertEquals(1L, table.service.state.round!!.seats.count { !it.isBot }.toLong())
        assertEquals(3L, table.service.state.round!!.seats.count { it.isBot }.toLong())
    }

    @Test
    fun publicManagerSupportsJoiningAllConfiguredVariants() {
        listOf("ak47", "muflis", "flipper", "jhandu").forEach { variantId ->
            val config = testGameConfig(variantId)
            val manager = publicManager(config = config)

            val joined = joinPublic(manager, variantId.uppercase())
            @Suppress("UNCHECKED_CAST")
            val table = joined["table"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val tableConfig = table["config"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val variant = tableConfig["variant"] as Map<String, Any?>

            assertEquals(variantId, variant["id"])
            assertNotNull(joined["table"])
            assertNotNull(tableConfig)
        }
    }

    @Test
    fun publicTablePromotesWaitingHumansAheadOfCreatingBots() {
        val config = testGameConfig("classic").apply { publicTableMaxBots = 3 }
        val tableRepository = InMemoryTableRepository()
        val sessionRepository = InMemoryPublicSessionRepository()
        val clock = MutableClock()
        val manager = publicManager(config, tableRepository, sessionRepository, InMemoryRoundHistoryRepository(), clock)

        val firstJoined = joinPublic(manager, "Alpha")
        val secondJoined = joinPublic(manager, "Bravo")
        val thirdJoined = joinPublic(manager, "Charlie")
        val table = managedTable(manager, firstJoined["tableId"] as String)

        assertEquals("waiting_for_next_round", secondJoined["playerStatus"])
        assertEquals("waiting_for_next_round", thirdJoined["playerStatus"])

        table.service.state.round!!.status = "complete"
        table.service.state.round!!.settledAt = clock.nowIso()
        markPublicSessionReady(manager, firstJoined["playerId"] as String)
        invokeMaybeStartNextRound(manager, table)

        assertEquals(3, table.service.state.round!!.seats.size)
        assertEquals(3L, table.service.state.round!!.seats.count { !it.isBot }.toLong())
        assertEquals(0L, table.service.state.round!!.seats.count { it.isBot }.toLong())
    }

    @Test
    fun publicTableDoesNotStartNextRoundWithoutLiveHumans() {
        val config = testGameConfig("classic").apply { publicTableMaxBots = 1 }
        val tableRepository = InMemoryTableRepository()
        val sessionRepository = InMemoryPublicSessionRepository()
        val clock = MutableClock()
        val manager = publicManager(config, tableRepository, sessionRepository, InMemoryRoundHistoryRepository(), clock)

        val joined = joinPublic(manager, "Alpha")
        val playerId = joined["playerId"] as String
        val table = managedTable(manager, joined["tableId"] as String)

        table.service.state.round!!.status = "complete"
        table.service.state.round!!.settledAt = clock.nowIso()

        val session = sessionRepository.loadSession(playerId)!!
        session.status = "left"
        session.connected = false
        sessionRepository.saveSession(session)
        table.service.state.publicSeating!!.seatedPlayerIds.clear()

        invokeMaybeStartNextRound(manager, table)

        assertEquals("complete", table.service.state.round!!.status)
        assertTrue(table.service.state.publicSeating!!.seatedPlayerIds.isEmpty())
    }

    @Test
    fun publicTableBotKeepsIdentityAcrossRounds() {
        val config = testGameConfig("classic").apply { publicTableMaxBots = 1 }
        val manager = publicManager(config = config, clock = MutableClock())

        val joined = joinPublic(manager, "Alpha")
        val table = managedTable(manager, joined["tableId"] as String)
        val firstBotSeat = table.service.state.round!!.seats.first { it.isBot }

        val botId = firstBotSeat.id
        val botName = firstBotSeat.name
        val botAvatar = firstBotSeat.avatar

        table.service.state.round!!.status = "complete"
        table.service.state.round!!.settledAt = "2026-01-01T00:00:10Z"
        markPublicSessionReady(manager, joined["playerId"] as String)
        invokeMaybeStartNextRound(manager, table)

        val secondBotSeat = table.service.state.round!!.seats.first { it.isBot }
        assertEquals(botId, secondBotSeat.id)
        assertEquals(botName, secondBotSeat.name)
        assertEquals(botAvatar, secondBotSeat.avatar)
    }

    @Test
    fun publicTableBotKeepsBankrollAcrossRounds() {
        val config = testGameConfig("classic").apply { publicTableMaxBots = 1 }
        val manager = publicManager(config = config, clock = MutableClock())

        val joined = joinPublic(manager, "Alpha")
        val table = managedTable(manager, joined["tableId"] as String)
        val firstBotSeat = table.service.state.round!!.seats.first { it.isBot }

        val carriedBalance = 87_500
        for (bankroll in table.service.state.playerBankrolls) {
            if (bankroll.id == firstBotSeat.id) {
                bankroll.balance = carriedBalance
            }
        }
        firstBotSeat.balance = carriedBalance
        table.service.state.round!!.status = "complete"
        table.service.state.round!!.settledAt = "2026-01-01T00:00:10Z"
        markPublicSessionReady(manager, joined["playerId"] as String)
        invokeMaybeStartNextRound(manager, table)

        val nextBotSeat = table.service.state.round!!.seats.first { it.isBot }
        assertEquals(firstBotSeat.id, nextBotSeat.id)
        assertEquals(carriedBalance - config.bootAmount, nextBotSeat.balance)
    }

    @Test
    fun publicTableKeepsStoredBotSlotsWhenHumansFillTheTable() {
        val config = testGameConfig("classic").apply { publicTableMaxBots = 1 }
        val tableRepository = InMemoryTableRepository()
        val sessionRepository = InMemoryPublicSessionRepository()
        val clock = MutableClock()
        val manager = publicManager(config, tableRepository, sessionRepository, InMemoryRoundHistoryRepository(), clock)

        val firstJoined = joinPublic(manager, "Alpha")
        val secondJoined = joinPublic(manager, "Bravo")
        val table = managedTable(manager, firstJoined["tableId"] as String)
        val firstBotId = table.service.state.round!!.seats.first { it.isBot }.id

        assertEquals("waiting_for_next_round", secondJoined["playerStatus"])

        table.service.state.round!!.status = "complete"
        table.service.state.round!!.settledAt = clock.nowIso()
        markPublicSessionReady(manager, firstJoined["playerId"] as String)
        invokeMaybeStartNextRound(manager, table)

        assertEquals(0L, table.service.state.round!!.seats.count { it.isBot }.toLong())
        assertEquals(1, table.service.state.publicSeating!!.botSlots.size)
        assertEquals(firstBotId, table.service.state.publicSeating!!.botSlots.first().id)
    }

    @Test
    fun publicTableReusesStoredBotSlotWhenBotsReturn() {
        val config = testGameConfig("classic").apply { publicTableMaxBots = 1 }
        val tableRepository = InMemoryTableRepository()
        val sessionRepository = InMemoryPublicSessionRepository()
        val clock = MutableClock()
        val manager = publicManager(config, tableRepository, sessionRepository, InMemoryRoundHistoryRepository(), clock)

        val firstJoined = joinPublic(manager, "Alpha")
        val secondJoined = joinPublic(manager, "Bravo")
        val bravoId = secondJoined["playerId"] as String
        val table = managedTable(manager, firstJoined["tableId"] as String)
        val firstBotId = table.service.state.round!!.seats.first { it.isBot }.id

        table.service.state.round!!.status = "complete"
        table.service.state.round!!.settledAt = clock.nowIso()
        markPublicSessionReady(manager, firstJoined["playerId"] as String)
        invokeMaybeStartNextRound(manager, table)

        table.service.state.round!!.status = "complete"
        table.service.state.round!!.settledAt = "2026-01-01T00:00:20Z"
        markPublicSessionReady(manager, firstJoined["playerId"] as String)
        val bravo = sessionRepository.loadSession(bravoId)!!
        bravo.status = "left"
        bravo.connected = false
        sessionRepository.saveSession(bravo)

        invokeMaybeStartNextRound(manager, table)

        val returnedBotSeat = table.service.state.round!!.seats.first { it.isBot }
        assertEquals(firstBotId, returnedBotSeat.id)
    }

    @Test
    fun publicTableSettlementTracksActualCasinoIncomeAndHistoryForRealWinner() {
        val roundHistoryRepository = InMemoryRoundHistoryRepository()
        val service = roundService(roundHistoryRepository = roundHistoryRepository)

        service.startRound(listOf(participant("player-1", "Alpha"), botParticipant("public-bot-1", "Guest_100001", "raj")))
        service.state.round!!.status = "active"
        setActivePlayer(service, "public-bot-1")

        service.performAction("public-bot-1", "pack", emptyMap())
        service.performAction("player-1", "dealer_tip", mapOf("amount" to 0))

        val result = service.state.round!!.result!!
        assertEquals(1_000, result.realPlayerContributionTotal)
        assertEquals(1_000, result.botContributionTotal)
        assertEquals(100, result.bootCommission)
        assertEquals(50, result.actualBootCommission)
        assertEquals(190, result.winCommission)
        assertEquals(95, result.actualWinCommission)
        assertEquals(-710, result.actualCasinoIncomeTotal)

        val historyItem = service.state.history.first()
        assertEquals(1_000, historyItem.realPlayerContributionTotal)
        assertEquals(1_000, historyItem.botContributionTotal)
        assertEquals(50, historyItem.actualBootCommission)
        assertEquals(95, historyItem.actualWinCommission)
        assertEquals(-710, historyItem.actualCasinoIncomeTotal)

        val historyEntry = roundHistoryRepository.entries.first()
        assertEquals(1_000, historyEntry.realPlayerContributionTotal)
        assertEquals(1_000, historyEntry.botContributionTotal)
        assertEquals(50, historyEntry.actualBootCommission)
        assertEquals(95, historyEntry.actualWinCommission)
        assertEquals(-710, historyEntry.actualCasinoIncomeTotal)
    }

    @Test
    fun publicTableCasinoIncomeUsesAllRealPlayerContributionsButNoBotWinnerCommission() {
        val service = roundService()

        service.startRound(
            listOf(
                participant("player-1", "Alpha"),
                botParticipant("public-bot-1", "Guest_100001", "raj"),
                participant("player-2", "Bravo"),
            ),
        )

        val round = service.state.round!!
        val firstHuman = seat(service, "player-1")
        val bot = seat(service, "public-bot-1")
        val secondHuman = seat(service, "player-2")
        firstHuman.totalContributed = 4_000
        bot.totalContributed = 1_000
        secondHuman.totalContributed = 2_000
        round.potAmount = 7_000

        invokeFinishRound(service, bot, "Bot win.")

        val result = service.state.round!!.result!!
        assertEquals(6_000, result.realPlayerContributionTotal)
        assertEquals(1_000, result.botContributionTotal)
        assertEquals(100, result.actualBootCommission)
        assertEquals(0, result.actualWinCommission)
        assertEquals(6_000, result.actualCasinoIncomeTotal)

        val historyItem = service.state.history.first()
        assertEquals(4_000, historyItem.userContribution)
        assertEquals(6_000, historyItem.realPlayerContributionTotal)
        assertEquals(6_000, historyItem.actualCasinoIncomeTotal)
    }

    @Test
    fun noBotRoundsKeepGrossCommissionAsActualAndUseRealPlayerIncomeTotal() {
        val publicService = roundService(tableType = "public_table")
        publicService.startRound(listOf(participant("player-1", "Alpha"), participant("player-2", "Bravo")))
        publicService.state.round!!.status = "active"
        setActivePlayer(publicService, "player-1")
        publicService.performAction("player-1", "pack", emptyMap())
        publicService.performAction("player-2", "dealer_tip", mapOf("amount" to 0))

        val publicResult = publicService.state.round!!.result!!
        assertEquals(publicResult.bootCommission, publicResult.actualBootCommission)
        assertEquals(publicResult.winCommission, publicResult.actualWinCommission)
        assertEquals(publicResult.realPlayerContributionTotal - publicResult.payout, publicResult.actualCasinoIncomeTotal)

        val privateService = roundService(tableType = "private_room")
        privateService.startRound(listOf(participant("player-1", "Alpha"), participant("player-2", "Bravo")))
        privateService.state.round!!.status = "active"
        setActivePlayer(privateService, "player-1")
        privateService.performAction("player-1", "pack", emptyMap())
        privateService.performAction("player-2", "dealer_tip", mapOf("amount" to 0))

        val privateResult = privateService.state.round!!.result!!
        assertEquals(privateResult.bootCommission, privateResult.actualBootCommission)
        assertEquals(privateResult.winCommission, privateResult.actualWinCommission)
        assertEquals(privateResult.realPlayerContributionTotal - privateResult.payout, privateResult.actualCasinoIncomeTotal)
    }

    @Test
    fun publicDealerTipDoesNotClearExistingReadyState() {
        val config = testGameConfig("classic").apply { playerCount = 2 }
        val tableRepository = InMemoryTableRepository()
        val sessionRepository = InMemoryPublicSessionRepository()
        val clock = MutableClock()
        val manager = publicManager(config, tableRepository, sessionRepository, InMemoryRoundHistoryRepository(), clock)

        val firstJoined = joinPublic(manager, "Alpha")
        val alphaId = firstJoined["playerId"] as String
        val alphaToken = firstJoined["playerToken"] as String
        val tableId = firstJoined["tableId"] as String
        val secondJoined = joinPublic(manager, "Bravo")
        val bravoId = secondJoined["playerId"] as String
        val bravoToken = secondJoined["playerToken"] as String

        val table = managedTable(manager, tableId)
        table.service.state.round!!.status = "complete"
        table.service.state.round!!.settledAt = clock.nowIso()
        table.service.state.round!!.nextRoundDecisionExpiresAt = clock.isoFromMillis(clock.now().toEpochMilli() + 15_000L)
        markPublicSessionReady(manager, alphaId)
        invokeMaybeStartNextRound(manager, table)

        table.service.state.round!!.status = "active"
        table.service.state.round!!.activePlayerIndex = table.service.state.round!!.seats.indexOfFirst { it.id == bravoId }

        manager.performAction(bravoId, bravoToken, "pack", emptyMap())
        manager.performAction(bravoId, bravoToken, "ready_next_round", emptyMap())
        manager.performAction(alphaId, alphaToken, "dealer_tip", mapOf("amount" to 100))

        val bravo = sessionRepository.loadSession(bravoId)!!
        assertTrue(bravo.nextRoundReady)
        assertTrue(table.service.state.round!!.dealerTipState?.pending != true)
        assertNotNull(table.service.state.round!!.nextRoundDecisionExpiresAt)
    }

    @Test
    fun publicDealerTipDoesNotRescheduleNextRoundWindow() {
        val scheduler = RecordingScheduler()
        val config = testGameConfig("classic").apply { playerCount = 2 }
        val manager =
            PublicTableManager(
                config,
                InMemoryTableRepository(),
                InMemoryPublicSessionRepository(),
                InMemoryRoundHistoryRepository(),
                MutableClock(),
                IncrementingIdGenerator(),
                FixedRandomSource(),
                scheduler,
                NoOpPublicTableRealtimeGateway(),
                NoOpPlayerPresence(),
                15_000L,
                "node-a",
            )
        manager.initialize()

        val joined = joinPublic(manager, "Alpha")
        val tableId = joined["tableId"] as String
        val table = managedTable(manager, tableId)
        table.service.state.round!!.status = "complete"
        table.service.state.round!!.nextRoundDecisionExpiresAt = "2026-01-01T00:00:15Z"

        val scheduledBefore = scheduler.nonZeroDelayCount()
        invokeHandleTableEvent(manager, tableId, "round_complete")

        assertEquals(scheduledBefore + 1, scheduler.nonZeroDelayCount())
        assertEquals(15_000L, scheduler.lastNonZeroDelay())

        invokeHandleTableEvent(manager, tableId, "dealer_tip")

        assertEquals(scheduledBefore + 1, scheduler.nonZeroDelayCount())
        assertEquals(15_000L, scheduler.lastNonZeroDelay())
    }

    @Test
    fun publicJoinRejectsMissingClientSeed() {
        val manager = publicManager()
        val error = assertThrows(AppException::class.java) { manager.joinPublicTable("Alpha", null) }
        assertEquals("client_seed_required", error.code)
    }
}
