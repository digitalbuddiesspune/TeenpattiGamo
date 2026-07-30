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
internal fun publicManager(
    config: GameConfig = testGameConfig("classic"),
    tableRepository: InMemoryTableRepository = InMemoryTableRepository(),
    sessionRepository: InMemoryPublicSessionRepository = InMemoryPublicSessionRepository(),
    roundHistoryRepository: InMemoryRoundHistoryRepository = InMemoryRoundHistoryRepository(),
    clock: ClockProvider = FixedClock(),
    scheduler: Scheduler = ManualScheduler(),
    randomSource: RandomSource = FixedRandomSource(),
): PublicTableManager =
    PublicTableManager(
        config,
        tableRepository,
        sessionRepository,
        roundHistoryRepository,
        clock,
        IncrementingIdGenerator(),
        randomSource,
        scheduler,
        NoOpPublicTableRealtimeGateway(),
        NoOpPlayerPresence(),
        15_000L,
        "node-a",
    ).also { it.initialize() }

internal fun roundService(
    config: GameConfig = testGameConfig("classic"),
    tableRepository: TableAggregateRepository = InMemoryTableRepository(),
    roundHistoryRepository: RoundHistoryRepository = InMemoryRoundHistoryRepository(),
    clock: ClockProvider = FixedClock(),
    randomSource: RandomSource = FixedRandomSource(),
    scheduler: Scheduler = ManualScheduler(),
    tableType: String = "public_table",
): RoundTableService =
    RoundTableService(
        config,
        tableRepository,
        roundHistoryRepository,
        clock,
        IncrementingIdGenerator(),
        randomSource,
        scheduler,
        "node-a",
        tableType,
        null,
    )

internal fun participant(id: String, name: String): RoundParticipant = participant(id, name, false, "player")

internal fun botParticipant(id: String, name: String, avatar: String): RoundParticipant = participant(id, name, true, avatar)

internal fun participant(id: String, name: String, isBot: Boolean, avatar: String): RoundParticipant =
    RoundParticipant().also {
        it.id = id
        it.name = name
        it.avatar = avatar
        it.isBot = isBot
        it.connected = true
        it.clientSeed = if (isBot) null else clientSeed(id)
    }

internal fun playerSeed(playerId: String, clientSeed: String): ProvablyFairPlayerSeedInput =
    ProvablyFairPlayerSeedInput().also {
        it.playerId = playerId
        it.clientSeed = clientSeed
    }

internal fun clientSeed(label: String): String = "client-seed-$label"

internal fun handIds(deal: CreatedDeal): List<List<String>> = deal.hands.map { hand -> hand.map { it.id } }

internal fun joinPublic(manager: PublicTableManager, playerName: String): Map<String, Any?> {
    val joined = manager.joinPublicTable(playerName, clientSeed(playerName))
    if (joined["playerStatus"] == "active_at_table") {
        val tableId = joined["tableId"] as? String
        val table = tableId?.let { managedTable(manager, it) }
        if (table != null && table.service.state.round == null) {
            invokeMaybeStartNextRound(manager, table)
        }
    }
    return joined
}

internal fun createPrivateRoom(
    manager: PrivateRoomManager,
    roomName: String,
    playerName: String,
    variantId: String = "classic",
    bootAmount: Int = 1000,
): Map<String, Any?> = manager.createRoom(roomName, playerName, clientSeed(playerName), variantId, bootAmount)

internal fun joinPrivateRoom(manager: PrivateRoomManager, roomCode: String, playerName: String): Map<String, Any?> =
    manager.joinRoom(roomCode, playerName, clientSeed(playerName))

internal fun setActivePlayer(service: RoundTableService, playerId: String) {
    for (index in service.state.round!!.seats.indices) {
        if (service.state.round!!.seats[index].id == playerId) {
            service.state.round!!.activePlayerIndex = index
            return
        }
    }
    throw IllegalArgumentException("Unknown player id $playerId")
}

internal fun seat(service: RoundTableService, playerId: String): SeatState = service.state.round!!.seats.first { it.id == playerId }

internal fun setSeatCards(seat: SeatState, vararg cards: Card) {
    seat.cards = cards.toMutableList()
}

internal fun card(rank: String, suit: String): Card = Engine.createDeck().first { it.rank == rank && it.suit == suit }

internal fun appendAction(round: RoundState, playerId: String, actionType: String) {
    val action = ActionLogEntry()
    action.id = "$playerId-$actionType-${round.actionLog.size}"
    action.playerId = playerId
    action.actionType = actionType
    round.actionLog.add(action)
}

internal fun sideShow(requesterId: String, requesterName: String, targetId: String, targetName: String): SideShowRequest =
    SideShowRequest().also {
        it.requesterId = requesterId
        it.requesterName = requesterName
        it.targetId = targetId
        it.targetName = targetName
        it.requestedAt = "2026-01-01T00:00:00Z"
        it.expiresAt = "2026-01-01T00:00:15Z"
        it.forcedRaiseAmount = 1000
        it.status = "pending"
    }

@Suppress("UNCHECKED_CAST")
internal fun privateRoomState(response: Map<String, Any?>): Map<String, Any?> = response["roomState"] as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
internal fun roomPlayers(roomState: Map<String, Any?>): List<Map<String, Any?>> = roomState["players"] as List<Map<String, Any?>>

@Suppress("UNCHECKED_CAST")
internal fun nextRoundState(roomState: Map<String, Any?>): Map<String, Any?> = roomState["nextRound"] as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
internal fun privatePlayerIds(payload: Map<String, Any?>, key: String): List<String> = payload[key] as List<String>

@Suppress("UNCHECKED_CAST")
internal fun privateRoundSeatIds(roomState: Map<String, Any?>): List<String> {
    val round = roomState["round"] as? Map<String, Any?> ?: return emptyList()
    return (round["seats"] as List<Map<String, Any?>>).map { it["id"] as String }
}

internal fun privateRoomManager(
    repository: PrivateRoomRepository,
    roundHistoryRepository: RoundHistoryRepository,
    clock: ClockProvider,
    scheduler: Scheduler,
): PrivateRoomManager =
    PrivateRoomManager(
        repository,
        testGameConfig("classic"),
        mapOf(
            "classic" to testGameConfig("classic"),
            "ak47" to testGameConfig("ak47"),
            "muflis" to testGameConfig("muflis"),
            "flipper" to testGameConfig("flipper"),
            "jhandu" to testGameConfig("jhandu"),
        ),
        roundHistoryRepository,
        NoOpPrivateRoomRealtimeGateway(),
        clock,
        IncrementingIdGenerator(),
        FixedRandomSource(),
        scheduler,
        15_000L,
        604_800_000L,
        "node-a",
    )

internal fun testGameConfig(variantId: String): GameConfig {
    val variant = VariantConfig()
    variant.id = variantId
    variant.label = variantId
    variant.wildcardRanks = if (variantId == "ak47") mutableListOf("A", "K", "4", "7") else mutableListOf()
    variant.evaluationMode = if (variantId == "muflis") "lowball" else "standard"
    variant.cardsPerSeat = if (variantId == "flipper") 4 else 3
    variant.publicCardMode = if (variantId == "flipper") "third_card_rank_joker" else "none"
    variant.sharedJokerMode = if (variantId == "jhandu") "progressive_three" else "none"
    variant.forceBlindCycles = if (variantId == "jhandu") 1 else 0
    variant.showUnlockCycle = if (variantId == "jhandu") 4 else 0
    variant.showRequiresAllSeen = variantId == "jhandu"
    variant.autoAcceptSideshow = variantId == "jhandu"

    val autoplay = AutoplayConfig()
    autoplay.defaultRounds = 10
    autoplay.maxRounds = 100
    autoplay.maxProfitTarget = 1_000_000
    autoplay.maxLossLimit = 1_000_000

    val delay = BotActionDelayConfig()
    delay.min = 900
    delay.max = 1800

    val config = GameConfig()
    config.tableId = "test-$variantId"
    config.bootAmount = 1000
    config.maxPotAmount = 320000
    config.minStake = 1000
    config.maxStake = 64000
    config.maxRoundsBeforeForcedShow = 18
    config.playerCount = 5
    config.publicTableMaxBots = config.playerCount - 1
    config.casinoBootCommissionPercent = 5
    config.casinoWinCommissionPercent = 10
    config.maxBalance = 500000
    config.initialBalance = 30000000
    config.turnDurationMs = 15000
    config.blindSeenMultiplier = 2
    config.blindRaiseMultiplier = 2
    config.seenRaiseMultiplier = 4
    config.allowA23Sequence = true
    config.allowAkqSequence = true
    config.sequenceRankingMode = "AKQ_HIGH_A23_SECOND"
    config.botDecisionMode = "expert_public"
    config.botMaxSimulations = 3000
    config.botMaxDecisionTimeMs = 500
    config.botHeadsUpSeeAfterBlindTurns = 1
    config.autoplay = autoplay
    config.botActionDelayMs = delay
    config.variant = variant
    return config
}

internal class FixedClock : ClockProvider {
    override fun now(): Instant = Instant.parse("2026-01-01T00:00:00Z")
}

internal class MutableClock : ClockProvider {
    private var current = Instant.parse("2026-01-01T00:00:00Z")

    override fun now(): Instant = current

    fun advanceMillis(deltaMs: Long) {
        current = current.plusMillis(deltaMs)
    }
}

internal class IncrementingIdGenerator : IdGenerator {
    private var index = 1

    override fun newId(): String = "id-${index++}"
}

internal class FixedRandomSource : RandomSource {
    override fun nextDouble(): Double = 0.1

    override fun nextInt(bound: Int): Int = 0
}

internal class SequenceRandomSource(
    private vararg val doubles: Double,
) : RandomSource {
    private var index = 0

    override fun nextDouble(): Double = if (index >= doubles.size) 0.1 else doubles[index++]

    override fun nextInt(bound: Int): Int = 0
}

internal class ManualScheduler : Scheduler {
    override fun schedule(delayMs: Long, task: Runnable): ScheduledTask = ScheduledTask { }
}

internal class CapturingScheduler : Scheduler {
    private var lastTask: Runnable? = null

    override fun schedule(delayMs: Long, task: Runnable): ScheduledTask {
        lastTask = task
        return ScheduledTask { }
    }

    fun runLast() {
        val task = lastTask ?: return
        lastTask = null
        task.run()
    }
}

internal class RecordingScheduler : Scheduler {
    private val delays = mutableListOf<Long>()

    override fun schedule(delayMs: Long, task: Runnable): ScheduledTask {
        delays.add(delayMs)
        return ScheduledTask { }
    }

    fun nonZeroDelayCount(): Int = delays.count { it > 0L }

    fun lastNonZeroDelay(): Long = delays.lastOrNull { it > 0L } ?: 0L
}

internal class InMemoryRoundHistoryRepository : RoundHistoryRepository {
    val entries = mutableListOf<RoundHistoryEntry>()

    override fun appendRound(entry: RoundHistoryEntry) {
        entries.removeIf { it.id == entry.id }
        entries.add(entry)
    }

    override fun loadRoundsForAggregate(aggregateType: String, aggregateId: String, limit: Int): List<RoundHistoryEntry> =
        entries.filter { it.aggregateType == aggregateType && it.aggregateId == aggregateId }.take(limit)

    override fun listRecentRoundsForParticipants(participantIds: List<String>, offset: Int, limit: Int): List<RoundHistoryEntry> =
        entries
            .filter { entry -> entry.participants.any { participantIds.contains(it.id) } }
            .sortedByDescending { it.settledAt ?: "" }
            .drop(offset)
            .take(limit)
}

internal class InMemoryTableRepository : TableAggregateRepository {
    val state = linkedMapOf<String, TableState>()

    override fun loadTable(tableId: String): TableState? = state[tableId]

    override fun saveTable(state: TableState): TableState {
        state.version += 1
        this.state[state.id] = state
        return state
    }

    override fun claimLease(tableId: String, leaseOwner: String, leaseExpiresAt: String, now: String): TableState? {
        val item = state[tableId] ?: return null
        item.leaseOwner = leaseOwner
        item.leaseExpiresAt = leaseExpiresAt
        return item
    }

    override fun listActiveTables(tableType: String?, variantId: String?): List<TableState> = state.values.toList()
}

internal class InMemoryPublicSessionRepository : PublicSessionRepository {
    val sessions = linkedMapOf<String, PublicPlayerSessionState>()

    override fun loadSession(playerId: String): PublicPlayerSessionState? = sessions[playerId]

    override fun saveSession(session: PublicPlayerSessionState): PublicPlayerSessionState {
        session.version += 1
        sessions[session.id] = session
        return session
    }

    override fun listSessionsForTable(tableId: String): List<PublicPlayerSessionState> = sessions.values.filter { it.tableId == tableId }

    override fun listActiveSessions(variantId: String): List<PublicPlayerSessionState> = sessions.values.filter { it.variantId == variantId }
}

internal class InMemoryPrivateRoomRepository : PrivateRoomRepository {
    val rooms = linkedMapOf<String, PrivateRoomState>()

    override fun loadRoom(roomCode: String): PrivateRoomState? = rooms[roomCode]

    override fun saveRoom(state: PrivateRoomState): PrivateRoomState {
        state.version += 1
        rooms[state.roomCode] = state
        return state
    }

    override fun claimLease(roomCode: String, leaseOwner: String, leaseExpiresAt: String, now: String): PrivateRoomState? {
        val room = rooms[roomCode] ?: return null
        room.leaseOwner = leaseOwner
        room.leaseExpiresAt = leaseExpiresAt
        return room
    }

    override fun listActiveRooms(): List<PrivateRoomState> = rooms.values.toList()
}

internal fun managedTable(manager: PublicTableManager, tableId: String): PublicTableManager.ManagedPublicTable {
    val tablesField: Field = PublicTableManager::class.java.getDeclaredField("tables")
    tablesField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val tables = tablesField.get(manager) as Map<String, PublicTableManager.ManagedPublicTable>
    return tables.getValue(tableId)
}

internal fun invokeMaybeStartNextRound(manager: PublicTableManager, table: PublicTableManager.ManagedPublicTable) {
    val method: Method = PublicTableManager::class.java.getDeclaredMethod("maybeStartNextRound", PublicTableManager.ManagedPublicTable::class.java)
    method.isAccessible = true
    method.invoke(manager, table)
}

internal fun markPublicSessionReady(manager: PublicTableManager, playerId: String) {
    val playersField: Field = PublicTableManager::class.java.getDeclaredField("players")
    playersField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val players = playersField.get(manager) as Map<String, PublicPlayerSessionState>
    players.getValue(playerId).nextRoundReady = true
}

internal fun invokeHandleTableEvent(manager: PublicTableManager, tableId: String, eventType: String) {
    val method: Method = PublicTableManager::class.java.getDeclaredMethod("handleTableEvent", String::class.java, String::class.java)
    method.isAccessible = true
    method.invoke(manager, tableId, eventType)
}

internal fun invokeCreateTable(manager: PublicTableManager): PublicTableManager.ManagedPublicTable {
    val method: Method = PublicTableManager::class.java.getDeclaredMethod("createTable")
    method.isAccessible = true
    return method.invoke(manager) as PublicTableManager.ManagedPublicTable
}

internal fun invokeDecideBotAction(service: RoundTableService, seat: SeatState): String {
    val method: Method = RoundTableService::class.java.getDeclaredMethod("decideBotAction", SeatState::class.java)
    method.isAccessible = true
    return method.invoke(service, seat) as String
}

internal fun invokeDecideBotDecision(service: RoundTableService, seat: SeatState): BotDecisionContext {
    val method: Method = RoundTableService::class.java.getDeclaredMethod("decideBotDecision", SeatState::class.java)
    method.isAccessible = true
    return method.invoke(service, seat) as BotDecisionContext
}

internal fun invokeDecideBotPendingSideShowDecision(service: RoundTableService, seat: SeatState): BotDecisionContext {
    val method: Method = RoundTableService::class.java.getDeclaredMethod("decideBotPendingSideShowDecision", SeatState::class.java)
    method.isAccessible = true
    return method.invoke(service, seat) as BotDecisionContext
}

internal fun invokeFinishRound(service: RoundTableService, winner: SeatState, reason: String) {
    val method: Method = RoundTableService::class.java.getDeclaredMethod("finishRound", SeatState::class.java, String::class.java)
    method.isAccessible = true
    method.invoke(service, winner, reason)
}
