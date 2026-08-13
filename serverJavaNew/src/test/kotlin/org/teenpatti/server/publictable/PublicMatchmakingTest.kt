package org.teenpatti.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.teenpatti.server.game.NoOpPlayerPresence
import org.teenpatti.server.game.NoOpPublicTableRealtimeGateway
import org.teenpatti.server.platform.PlatformSession
import org.teenpatti.server.platform.PlatformUser
import org.teenpatti.server.publictable.LocalMatchmakingCoordinator
import org.teenpatti.server.publictable.MatchmakingCoordinator
import org.teenpatti.server.publictable.PublicTableManager

internal class PublicMatchmakingTest {
    @Test
    fun localMatchmakingCoordinatorWaitsForWindowAndRequeuesRemainders() {
        val coordinator = LocalMatchmakingCoordinator()
        coordinator.enqueue("classic", "p1", 1_000L)
        coordinator.enqueue("classic", "p2", 2_000L)

        val earlyResolved =
            coordinator.resolveReadyBatch("classic", nowMillis = 5_999L, windowMs = 5_000L) { players ->
                error("Teen Patti matchmaking should not resolve before the window expires: $players")
            }
        assertEquals(false, earlyResolved)

        val firstResolved =
            coordinator.resolveReadyBatch("classic", nowMillis = 6_000L, windowMs = 5_000L) { players ->
                assertEquals(listOf("p1", "p2"), players)
                listOf("p2")
            }
        assertTrue(firstResolved)

        val leftoverResolved =
            coordinator.resolveReadyBatch("classic", nowMillis = 11_000L, windowMs = 5_000L) { players ->
                assertEquals(listOf("p2"), players)
                emptyList()
            }
        assertTrue(leftoverResolved)
    }

    @Test
    fun soloPlayerReceivesBotsInMatchmaking() {
        val fixture = fixture()
        val joined = joinPlayers(fixture.manager, 1)
        assertTrue(joined.all { it["playerStatus"] == "matchmaking" && it["tableId"] == null })

        fixture.scheduler.runLast()

        assertEquals(1, fixture.tableRepository.state.size)
        val table = fixture.tableRepository.state.values.single()
        val seats = table.round!!.seats
        assertEquals(1, seats.count { !it.isBot })
        assertEquals(4, seats.count { it.isBot })
        assertTrue(fixture.sessionRepository.sessions.values.all { it.status == "active_at_table" })
    }

    @Test
    fun multipleHumanPlayersAreGroupedTogetherOnSameTable() {
        listOf(2, 3, 5).forEach { playerCount ->
            val fixture = fixture()
            joinPlayers(fixture.manager, playerCount)

            fixture.scheduler.runLast()

            assertEquals(1, fixture.tableRepository.state.size)
            val table = fixture.tableRepository.state.values.single()
            val seats = table.round!!.seats
            assertEquals(playerCount, seats.size)
            assertEquals(playerCount, seats.count { !it.isBot })
            assertEquals(0, seats.count { it.isBot })
            assertTrue(fixture.sessionRepository.sessions.values.all { it.status == "active_at_table" })
        }
    }

    @Test
    fun batchesAboveCapacityCreateFullTablesAndGroupRemaindersTogether() {
        mapOf(6 to listOf(5, 1), 7 to listOf(5, 2), 12 to listOf(5, 5, 2)).forEach { (playerCount, expectedGroupSizes) ->
            val fixture = fixture()
            joinPlayers(fixture.manager, playerCount)

            fixture.scheduler.runLast()

            assertEquals(expectedGroupSizes.size, fixture.tableRepository.state.size)
            val sortedTables = fixture.tableRepository.state.values.sortedByDescending { it.round!!.seats.size }
            sortedTables.zip(expectedGroupSizes).forEach { (table, expectedSize) ->
                val humanCount = table.round!!.seats.count { !it.isBot }
                assertEquals(expectedSize, humanCount)
            }
            assertTrue(fixture.sessionRepository.sessions.values.all { it.status == "active_at_table" })
        }
    }

    @Test
    fun duplicatePlatformUserDoesNotCountTwiceInSameTableGroup() {
        val fixture = fixture()
        val platformUserIds = (1..4).map { "platform-user-$it" } + "platform-user-1"
        joinPlatformPlayers(fixture.manager, platformUserIds)

        fixture.scheduler.runLast()

        assertEquals(2, fixture.tableRepository.state.size)
        fixture.tableRepository.state.values.forEach { table ->
            val seats = table.round!!.seats
            val platformIds = seats.filter { !it.isBot }.map { seat ->
                fixture.sessionRepository.sessions.getValue(seat.id).platformUserId
            }
            assertEquals(platformIds.size, platformIds.toSet().size)
        }
        assertTrue(fixture.sessionRepository.sessions.values.all { it.status == "active_at_table" })
    }

    @Test
    fun pvpMatchmakingNeverSeatsDuplicatePlatformUserTogether() {
        val fixture = fixture()
        val platformUserIds = (1..25).map { "platform-user-$it" } + "platform-user-1"
        joinPlatformPlayers(fixture.manager, platformUserIds)

        fixture.scheduler.runLast()

        assertEquals(6, fixture.tableRepository.state.size)
        fixture.tableRepository.state.values.forEach { table ->
            val humanSeatIds = table.round!!.seats.filter { !it.isBot }.map { it.id }
            val platformIds =
                humanSeatIds.map { playerId ->
                    fixture.sessionRepository.sessions.getValue(playerId).platformUserId
                }
            assertEquals(platformIds.size, platformIds.toSet().size)
        }
        assertTrue(fixture.sessionRepository.sessions.values.all { it.status == "active_at_table" })
    }

    @Test
    fun leavingDuringMatchmakingRemovesPlayerBeforeAssignment() {
        val fixture = fixture()
        val joined = fixture.manager.joinPublicTable("Alpha", clientSeed("Alpha"))

        fixture.manager.leave(joined["playerId"] as String, joined["playerToken"] as String)
        fixture.scheduler.runLast()

        assertTrue(fixture.coordinator.queued.isEmpty())
        assertTrue(fixture.tableRepository.state.isEmpty())
        assertNull(fixture.sessionRepository.sessions.values.single().tableId)
        assertEquals("left", fixture.sessionRepository.sessions.values.single().status)
    }

    @Test
    fun staleMatchmakingPlayerIsRemovedInsteadOfReceivingAnAbandonedTable() {
        val clock = MutableClock()
        val fixture = fixture(clock)
        val joined = fixture.manager.joinPublicTable("Alpha", clientSeed("Alpha"))
        clock.advanceMillis(5_000L)

        fixture.scheduler.runLast()

        assertTrue(fixture.coordinator.queued.isEmpty())
        assertTrue(fixture.tableRepository.state.isEmpty())
        assertEquals("matchmaking", fixture.sessionRepository.sessions.values.single().status)

        fixture.manager.getSession(joined["playerId"] as String, joined["playerToken"] as String)
        assertEquals(1, fixture.coordinator.queued.size)
    }

    private fun fixture(clock: org.teenpatti.server.common.ClockProvider = FixedClock()): MatchmakingFixture {
        val tableRepository = InMemoryTableRepository()
        val sessionRepository = InMemoryPublicSessionRepository()
        val scheduler = CapturingScheduler()
        val coordinator = InMemoryMatchmakingCoordinator()
        val manager =
            PublicTableManager(
                config = testGameConfig("classic"),
                tableRepository = tableRepository,
                publicSessionRepository = sessionRepository,
                roundHistoryRepository = InMemoryRoundHistoryRepository(),
                clockProvider = clock,
                idGenerator = IncrementingIdGenerator(),
                randomSource = FixedRandomSource(),
                scheduler = scheduler,
                realtimeGateway = NoOpPublicTableRealtimeGateway(),
                playerPresence = NoOpPlayerPresence(),
                reconnectGraceMs = 15_000L,
                instanceId = "node-a",
                matchmakingCoordinator = coordinator,
                matchmakingWindowMs = 5_000L,
                matchmakingPvpThreshold = 1,
            )
        manager.initialize()
        return MatchmakingFixture(manager, tableRepository, sessionRepository, scheduler, coordinator)
    }

    private fun joinPlayers(manager: PublicTableManager, count: Int): List<Map<String, Any?>> =
        (1..count).map { index -> manager.joinPublicTable("Player $index", clientSeed("Player $index")) }

    private fun joinPlatformPlayers(manager: PublicTableManager, platformUserIds: List<String>): List<Map<String, Any?>> =
        platformUserIds.mapIndexed { index, platformUserId ->
            manager.joinPlatformPublicTable(
                platformSession(platformUserId, index + 1),
                clientSeed("$platformUserId-$index"),
                "127.0.0.1",
            )
        }

    private fun platformSession(platformUserId: String, index: Int): PlatformSession =
        PlatformSession().also { session ->
            session.userId = platformUserId
            session.token = "platform-token-$platformUserId-$index"
            session.gameId = 3
            session.issuedAt = "2026-01-01T00:00:00Z"
            session.user =
                PlatformUser().also { user ->
                    user.userId = platformUserId
                    user.username = "Player $index"
                    user.balance = 100_000
                    user.currency = "INR"
                    user.operatorId = "operator-1"
                }
        }
}

private data class MatchmakingFixture(
    val manager: PublicTableManager,
    val tableRepository: InMemoryTableRepository,
    val sessionRepository: InMemoryPublicSessionRepository,
    val scheduler: CapturingScheduler,
    val coordinator: InMemoryMatchmakingCoordinator,
)

private class InMemoryMatchmakingCoordinator : MatchmakingCoordinator {
    val queued = linkedSetOf<String>()

    override fun enqueue(variantId: String, playerId: String, joinedAtMillis: Long) {
        queued.add(playerId)
    }

    override fun remove(variantId: String, playerId: String) {
        queued.remove(playerId)
    }

    override fun resolveReadyBatch(
        variantId: String,
        nowMillis: Long,
        windowMs: Long,
        resolver: (List<String>) -> List<String>,
    ): Boolean {
        if (queued.isEmpty()) {
            return false
        }
        val snapshot = queued.toList()
        val leftovers = resolver(snapshot)
        queued.removeAll(snapshot.toSet())
        queued.addAll(leftovers)
        return true
    }
}
