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
internal class PrivateRoomSmokeTest {
    @Test
    fun privateRoomCreateJoinAndReconnectWorks() {
        val manager = privateRoomManager(InMemoryPrivateRoomRepository(), InMemoryRoundHistoryRepository(), FixedClock(), ManualScheduler())
        manager.initialize()

        val created = createPrivateRoom(manager, "Friends", "Host")
        val roomCode = created["roomCode"] as String
        val joined = joinPrivateRoom(manager, roomCode, "Guest")
        val session = manager.getSession(roomCode, joined["playerId"] as String, joined["playerToken"] as String)
        val authenticated = manager.authenticate(roomCode, joined["playerId"] as String, joined["playerToken"] as String)

        assertEquals(roomCode, joined["roomCode"])
        assertNotNull(session["roomState"])
        assertTrue(authenticated.containsKey("players"))
    }

    @Test
    fun privateRoomHostExitBetweenRoundsReassignsHostAndRemovesLeaver() {
        val repository = InMemoryPrivateRoomRepository()
        val manager = privateRoomManager(repository, InMemoryRoundHistoryRepository(), FixedClock(), ManualScheduler())
        manager.initialize()

        val created = createPrivateRoom(manager, "Friends", "Host")
        val roomCode = created["roomCode"] as String
        val hostId = created["playerId"] as String
        val hostToken = created["playerToken"] as String
        val joined = joinPrivateRoom(manager, roomCode, "Guest")
        val guestId = joined["playerId"] as String
        val guestToken = joined["playerToken"] as String

        manager.startRound(roomCode, hostId, hostToken)
        var room = repository.loadRoom(roomCode)!!
        room.round!!.status = "active"
        room.round!!.activePlayerIndex = 0

        manager.performAction(roomCode, hostId, hostToken, "pack", emptyMap())
        manager.performAction(roomCode, guestId, guestToken, "dealer_tip", mapOf("amount" to 0))
        manager.acceptNextRound(roomCode, hostId, hostToken)
        manager.leaveRoom(roomCode, hostId, hostToken)

        room = repository.loadRoom(roomCode)!!
        assertEquals("between_rounds", room.status)
        assertEquals(guestId, room.hostPlayerId)
        assertEquals(1, room.players.size)
        assertEquals(guestId, room.players.first().id)
        assertFalse(room.acceptedNextRoundPlayerIds.contains(hostId))

        val guestRoomState = privateRoomState(manager.getSession(roomCode, guestId, guestToken))
        assertEquals(guestId, guestRoomState["hostPlayerId"])
        assertEquals(listOf(guestId), roomPlayers(guestRoomState).map { it["id"] as String })
    }

    @Test
    fun privateRoomLeaveBetweenRoundsRemovesLeaverFromSerializedSeats() {
        val repository = InMemoryPrivateRoomRepository()
        val manager = privateRoomManager(repository, InMemoryRoundHistoryRepository(), FixedClock(), ManualScheduler())
        manager.initialize()

        val created = createPrivateRoom(manager, "Friends", "Host")
        val roomCode = created["roomCode"] as String
        val hostId = created["playerId"] as String
        val hostToken = created["playerToken"] as String
        val joined = joinPrivateRoom(manager, roomCode, "Guest")
        val guestId = joined["playerId"] as String
        val guestToken = joined["playerToken"] as String

        manager.startRound(roomCode, hostId, hostToken)
        var room = repository.loadRoom(roomCode)!!
        room.round!!.status = "active"
        room.round!!.activePlayerIndex = 0

        manager.performAction(roomCode, hostId, hostToken, "pack", emptyMap())
        manager.performAction(roomCode, guestId, guestToken, "dealer_tip", mapOf("amount" to 0))

        room = repository.loadRoom(roomCode)!!
        assertEquals("between_rounds", room.status)

        manager.leaveRoom(roomCode, hostId, hostToken)

        room = repository.loadRoom(roomCode)!!
        assertEquals("between_rounds", room.status)
        assertEquals(listOf(guestId), room.players.map { it.id })
        assertFalse(room.acceptedNextRoundPlayerIds.contains(hostId))

        val guestRoomState = privateRoomState(manager.getSession(roomCode, guestId, guestToken))
        assertEquals(listOf(guestId), roomPlayers(guestRoomState).map { it["id"] as String })
        assertEquals(listOf(guestId), privateRoundSeatIds(guestRoomState))
        assertFalse(privatePlayerIds(nextRoundState(guestRoomState), "acceptedPlayerIds").contains(hostId))
        assertFalse(privatePlayerIds(nextRoundState(guestRoomState), "pendingPlayerIds").contains(hostId))
    }

    @Test
    fun privateRoomRejoinDuringBetweenRoundsBecomesActiveImmediately() {
        val repository = InMemoryPrivateRoomRepository()
        val manager = privateRoomManager(repository, InMemoryRoundHistoryRepository(), FixedClock(), ManualScheduler())
        manager.initialize()

        val created = createPrivateRoom(manager, "Friends", "Host")
        val roomCode = created["roomCode"] as String
        val hostId = created["playerId"] as String
        val hostToken = created["playerToken"] as String
        val joined = joinPrivateRoom(manager, roomCode, "Guest")
        val guestId = joined["playerId"] as String
        val guestToken = joined["playerToken"] as String

        manager.startRound(roomCode, hostId, hostToken)
        var room = repository.loadRoom(roomCode)!!
        room.round!!.status = "active"
        room.round!!.activePlayerIndex = 0

        manager.performAction(roomCode, hostId, hostToken, "pack", emptyMap())
        manager.performAction(roomCode, guestId, guestToken, "dealer_tip", mapOf("amount" to 0))
        manager.leaveRoom(roomCode, hostId, hostToken)

        val rejoined = joinPrivateRoom(manager, roomCode, "Host Again")
        val rejoinedId = rejoined["playerId"] as String
        val rejoinedRoomState = privateRoomState(rejoined)

        assertEquals(PrivateRoom.PLAYER_STATUS_ACTIVE, rejoinedRoomState["viewerPlayerStatus"])
        assertEquals(null, rejoinedRoomState["admissionMessage"])
        assertEquals(
            PrivateRoom.PLAYER_STATUS_ACTIVE,
            roomPlayers(rejoinedRoomState).first { it["id"] == rejoinedId }["status"],
        )
        assertFalse(privatePlayerIds(nextRoundState(rejoinedRoomState), "acceptedPlayerIds").contains(rejoinedId))
        assertTrue(privatePlayerIds(nextRoundState(rejoinedRoomState), "pendingPlayerIds").contains(rejoinedId))

        manager.acceptNextRound(roomCode, guestId, guestToken)
        manager.acceptNextRound(roomCode, rejoinedId, rejoined["playerToken"] as String)
        room = repository.loadRoom(roomCode)!!
        assertEquals("between_rounds", room.status)

        manager.nextRound(roomCode, guestId, guestToken)

        room = repository.loadRoom(roomCode)!!
        assertEquals("active", room.status)
        assertTrue(room.round!!.seats.any { it.id == rejoinedId })
        assertEquals(PrivateRoom.PLAYER_STATUS_ACTIVE, room.players.first { it.id == rejoinedId }.status)
    }

    @Test
    fun reassignedHostCanStartNextRoundWithNewlyActiveJoiner() {
        val repository = InMemoryPrivateRoomRepository()
        val manager = privateRoomManager(repository, InMemoryRoundHistoryRepository(), FixedClock(), ManualScheduler())
        manager.initialize()

        val created = createPrivateRoom(manager, "Friends", "Host")
        val roomCode = created["roomCode"] as String
        val hostId = created["playerId"] as String
        val hostToken = created["playerToken"] as String
        val joined = joinPrivateRoom(manager, roomCode, "Guest")
        val guestId = joined["playerId"] as String
        val guestToken = joined["playerToken"] as String

        manager.startRound(roomCode, hostId, hostToken)
        var room = repository.loadRoom(roomCode)!!
        room.round!!.status = "active"
        room.round!!.activePlayerIndex = 0

        manager.performAction(roomCode, hostId, hostToken, "pack", emptyMap())
        manager.performAction(roomCode, guestId, guestToken, "dealer_tip", mapOf("amount" to 0))
        manager.leaveRoom(roomCode, hostId, hostToken)

        val lateJoin = joinPrivateRoom(manager, roomCode, "Late")
        val lateJoinId = lateJoin["playerId"] as String
        val lateJoinRoomState = privateRoomState(lateJoin)
        assertEquals(PrivateRoom.PLAYER_STATUS_ACTIVE, lateJoinRoomState["viewerPlayerStatus"])
        assertFalse(privatePlayerIds(nextRoundState(lateJoinRoomState), "acceptedPlayerIds").contains(lateJoinId))
        assertTrue(privatePlayerIds(nextRoundState(lateJoinRoomState), "pendingPlayerIds").contains(lateJoinId))

        val earlyError = assertThrows(AppException::class.java) { manager.nextRound(roomCode, guestId, guestToken) }
        assertEquals("private_room_acceptance_pending", earlyError.code)

        manager.acceptNextRound(roomCode, lateJoinId, lateJoin["playerToken"] as String)

        manager.nextRound(roomCode, guestId, guestToken)

        room = repository.loadRoom(roomCode)!!
        assertEquals("active", room.status)
        assertTrue(room.round!!.seats.any { it.id == guestId })
        assertTrue(room.round!!.seats.any { it.id == lateJoinId })
        assertEquals(PrivateRoom.PLAYER_STATUS_ACTIVE, room.players.first { it.id == lateJoinId }.status)
    }

    @Test
    fun privateRoomJoinDuringActiveHandQueuesUntilFollowingRound() {
        val repository = InMemoryPrivateRoomRepository()
        val manager = privateRoomManager(repository, InMemoryRoundHistoryRepository(), FixedClock(), ManualScheduler())
        manager.initialize()

        val created = createPrivateRoom(manager, "Friends", "Host")
        val roomCode = created["roomCode"] as String
        val hostId = created["playerId"] as String
        val hostToken = created["playerToken"] as String
        val joined = joinPrivateRoom(manager, roomCode, "Guest")
        val guestId = joined["playerId"] as String
        val guestToken = joined["playerToken"] as String

        manager.startRound(roomCode, hostId, hostToken)
        var room = repository.loadRoom(roomCode)!!
        room.round!!.status = "active"
        room.round!!.activePlayerIndex = 0

        val lateJoin = joinPrivateRoom(manager, roomCode, "Late")
        val lateJoinId = lateJoin["playerId"] as String
        val lateJoinRoomState = privateRoomState(lateJoin)
        assertEquals(PrivateRoom.PLAYER_STATUS_WAITING, lateJoinRoomState["viewerPlayerStatus"])
        assertFalse(privateRoundSeatIds(lateJoinRoomState).contains(lateJoinId))

        manager.performAction(roomCode, hostId, hostToken, "pack", emptyMap())
        manager.performAction(roomCode, guestId, guestToken, "dealer_tip", mapOf("amount" to 0))
        manager.acceptNextRound(roomCode, hostId, hostToken)
        manager.acceptNextRound(roomCode, guestId, guestToken)

        room = repository.loadRoom(roomCode)!!
        assertEquals("between_rounds", room.status)

        manager.nextRound(roomCode, hostId, hostToken)

        room = repository.loadRoom(roomCode)!!
        assertEquals("active", room.status)
        assertTrue(room.round!!.seats.any { it.id == lateJoinId })
        assertEquals(PrivateRoom.PLAYER_STATUS_ACTIVE, room.players.first { it.id == lateJoinId }.status)
    }

    @Test
    fun privateRoomOnlyClosesAfterWaitingMembersLeaveToo() {
        val repository = InMemoryPrivateRoomRepository()
        val manager = privateRoomManager(repository, InMemoryRoundHistoryRepository(), FixedClock(), ManualScheduler())
        manager.initialize()

        val created = createPrivateRoom(manager, "Friends", "Host")
        val roomCode = created["roomCode"] as String
        val hostId = created["playerId"] as String
        val hostToken = created["playerToken"] as String
        val joined = joinPrivateRoom(manager, roomCode, "Guest")
        val guestId = joined["playerId"] as String
        val guestToken = joined["playerToken"] as String

        manager.startRound(roomCode, hostId, hostToken)
        var room = repository.loadRoom(roomCode)!!
        room.round!!.status = "active"
        room.round!!.activePlayerIndex = 0

        val waitingJoin = joinPrivateRoom(manager, roomCode, "Late")
        val waitingId = waitingJoin["playerId"] as String
        val waitingToken = waitingJoin["playerToken"] as String

        manager.leaveRoom(roomCode, hostId, hostToken)
        room = repository.loadRoom(roomCode)!!
        assertFalse(room.status == "closed")

        manager.leaveRoom(roomCode, guestId, guestToken)
        room = repository.loadRoom(roomCode)!!
        assertFalse(room.status == "closed")
        assertEquals(waitingId, room.hostPlayerId)

        manager.leaveRoom(roomCode, waitingId, waitingToken)
        room = repository.loadRoom(roomCode)!!
        assertEquals("closed", room.status)
        assertNotNull(room.expiresAt)
    }

    @Test
    fun privateDealerTipDoesNotClearAcceptedNextRoundPlayers() {
        val repository = InMemoryPrivateRoomRepository()
        val manager = privateRoomManager(repository, InMemoryRoundHistoryRepository(), FixedClock(), ManualScheduler())
        manager.initialize()

        val created = createPrivateRoom(manager, "Friends", "Host")
        val roomCode = created["roomCode"] as String
        val hostId = created["playerId"] as String
        val hostToken = created["playerToken"] as String
        val joined = joinPrivateRoom(manager, roomCode, "Guest")
        val guestId = joined["playerId"] as String
        val guestToken = joined["playerToken"] as String

        manager.startRound(roomCode, hostId, hostToken)

        var room = repository.loadRoom(roomCode)!!
        room.round!!.status = "active"
        room.round!!.activePlayerIndex = 1

        manager.performAction(roomCode, guestId, guestToken, "pack", emptyMap())
        manager.acceptNextRound(roomCode, guestId, guestToken)
        manager.performAction(roomCode, hostId, hostToken, "dealer_tip", mapOf("amount" to 100))

        room = repository.loadRoom(roomCode)!!
        assertEquals("between_rounds", room.status)
        assertEquals(listOf(guestId), room.acceptedNextRoundPlayerIds)
    }

    @Test
    fun privateRoomSettlementAppendsRoundHistoryEntry() {
        val repository = InMemoryPrivateRoomRepository()
        val roundHistoryRepository = InMemoryRoundHistoryRepository()
        val manager = privateRoomManager(repository, roundHistoryRepository, FixedClock(), ManualScheduler())
        manager.initialize()

        val created = createPrivateRoom(manager, "Friends", "Host")
        val roomCode = created["roomCode"] as String
        val hostId = created["playerId"] as String
        val hostToken = created["playerToken"] as String
        val joined = joinPrivateRoom(manager, roomCode, "Guest")
        val guestId = joined["playerId"] as String
        val guestToken = joined["playerToken"] as String

        manager.startRound(roomCode, hostId, hostToken)
        val room = repository.loadRoom(roomCode)!!
        room.round!!.status = "active"
        room.round!!.activePlayerIndex = 1

        manager.performAction(roomCode, guestId, guestToken, "pack", emptyMap())
        manager.performAction(roomCode, hostId, hostToken, "dealer_tip", mapOf("amount" to 100))

        assertEquals(1, roundHistoryRepository.entries.size)
        val entry = roundHistoryRepository.entries[0]
        assertEquals("private_room", entry.aggregateType)
        assertEquals(roomCode, entry.aggregateId)
        assertEquals(100, entry.dealerTip)
        assertNotNull(entry.settledAt)
        assertFalse(entry.actionLog.isEmpty())
        assertTrue(entry.participants.all { it.cards.isNotEmpty() })
        assertTrue(entry.participants.all { !it.handLabel.isNullOrBlank() })
    }

    @Test
    fun privateRoomWithoutLiveMembersClosesAndBecomesUnavailable() {
        val repository = InMemoryPrivateRoomRepository()
        val roundHistoryRepository = InMemoryRoundHistoryRepository()
        val clock = MutableClock()
        val manager = privateRoomManager(repository, roundHistoryRepository, clock, ManualScheduler())
        manager.initialize()

        val created = createPrivateRoom(manager, "Friends", "Host")
        val roomCode = created["roomCode"] as String
        val hostId = created["playerId"] as String
        val hostToken = created["playerToken"] as String

        clock.advanceMillis(16_000L)
        manager.disconnect(roomCode, hostId, hostToken)

        val room = repository.loadRoom(roomCode)!!
        assertEquals("closed", room.status)
        assertNotNull(room.expiresAt)

        val error = assertThrows(IllegalStateException::class.java) { manager.getSession(roomCode, hostId, hostToken) }
        assertEquals("This private room expired or is no longer available.", error.message)
    }

    @Test
    fun privateRoomReconnectWithinGraceKeepsRoomActive() {
        val repository = InMemoryPrivateRoomRepository()
        val roundHistoryRepository = InMemoryRoundHistoryRepository()
        val clock = MutableClock()
        val manager = privateRoomManager(repository, roundHistoryRepository, clock, ManualScheduler())
        manager.initialize()

        val created = createPrivateRoom(manager, "Friends", "Host")
        val roomCode = created["roomCode"] as String
        val hostId = created["playerId"] as String
        val hostToken = created["playerToken"] as String

        manager.disconnect(roomCode, hostId, hostToken)
        var room = repository.loadRoom(roomCode)!!
        assertEquals("lobby", room.status)
        assertEquals(null, room.expiresAt)

        clock.advanceMillis(10_000L)
        val restored = manager.getSession(roomCode, hostId, hostToken)

        room = repository.loadRoom(roomCode)!!
        assertEquals("lobby", room.status)
        assertEquals(null, room.expiresAt)
        assertTrue(room.players.first().connected)
        assertNotNull(restored["roomState"])
    }

    @Test
    fun privateRoomCreateRejectsMissingClientSeed() {
        val manager = privateRoomManager(InMemoryPrivateRoomRepository(), InMemoryRoundHistoryRepository(), FixedClock(), ManualScheduler())
        manager.initialize()

        val error = assertThrows(AppException::class.java) { manager.createRoom("Friends", "Host", null, "classic", 1000) }
        assertEquals("client_seed_required", error.code)
    }

    @Test
    fun privateRoomCreateStoresVariantAndBootAmount() {
        val repository = InMemoryPrivateRoomRepository()
        val manager = privateRoomManager(repository, InMemoryRoundHistoryRepository(), FixedClock(), ManualScheduler())
        manager.initialize()

        val created = createPrivateRoom(manager, "Friends", "Host", "ak47", 2500)
        val roomCode = created["roomCode"] as String
        val roomState = privateRoomState(created)
        val config = roomState["config"] as Map<String, Any?>
        val variant = config["variant"] as Map<String, Any?>
        val stored = repository.loadRoom(roomCode)!!

        assertEquals(2500, config["bootAmount"])
        assertEquals("ak47", variant["id"])
        assertEquals(2500, stored.config!!.bootAmount)
        assertEquals("ak47", stored.config!!.variant!!["id"])
    }

    @Test
    fun privateRoomHostCanUpdateLobbyConfig() {
        val repository = InMemoryPrivateRoomRepository()
        val manager = privateRoomManager(repository, InMemoryRoundHistoryRepository(), FixedClock(), ManualScheduler())
        manager.initialize()

        val created = createPrivateRoom(manager, "Friends", "Host")
        val roomCode = created["roomCode"] as String
        val hostId = created["playerId"] as String
        val hostToken = created["playerToken"] as String

        val updated = manager.updateConfig(roomCode, hostId, hostToken, "jhandu", 4000)
        val config = updated["config"] as Map<String, Any?>
        val variant = config["variant"] as Map<String, Any?>
        val stored = repository.loadRoom(roomCode)!!

        assertEquals(4000, config["bootAmount"])
        assertEquals("jhandu", variant["id"])
        assertEquals(4000, stored.config!!.bootAmount)
        assertEquals("jhandu", stored.config!!.variant!!["id"])
    }

    @Test
    fun privateRoomNonHostCannotUpdateLobbyConfig() {
        val manager = privateRoomManager(InMemoryPrivateRoomRepository(), InMemoryRoundHistoryRepository(), FixedClock(), ManualScheduler())
        manager.initialize()

        val created = createPrivateRoom(manager, "Friends", "Host")
        val roomCode = created["roomCode"] as String
        val joined = joinPrivateRoom(manager, roomCode, "Guest")

        val error =
            assertThrows(AppException::class.java) {
                manager.updateConfig(roomCode, joined["playerId"] as String, joined["playerToken"] as String, "muflis", 3000)
            }

        assertEquals("private_room_host_required", error.code)
    }

    @Test
    fun privateRoomCannotUpdateConfigOutsideLobbyAndDoesNotAutoStartNextRound() {
        val repository = InMemoryPrivateRoomRepository()
        val manager = privateRoomManager(repository, InMemoryRoundHistoryRepository(), FixedClock(), ManualScheduler())
        manager.initialize()

        val created = createPrivateRoom(manager, "Friends", "Host")
        val roomCode = created["roomCode"] as String
        val hostId = created["playerId"] as String
        val hostToken = created["playerToken"] as String
        val joined = joinPrivateRoom(manager, roomCode, "Guest")
        val guestId = joined["playerId"] as String
        val guestToken = joined["playerToken"] as String

        manager.startRound(roomCode, hostId, hostToken)
        var room = repository.loadRoom(roomCode)!!
        room.round!!.status = "active"
        room.round!!.activePlayerIndex = 1

        val updateError =
            assertThrows(AppException::class.java) {
                manager.updateConfig(roomCode, hostId, hostToken, "flipper", 5000)
            }
        assertEquals("private_room_config_locked", updateError.code)

        manager.performAction(roomCode, guestId, guestToken, "pack", emptyMap())
        manager.performAction(roomCode, hostId, hostToken, "dealer_tip", mapOf("amount" to 0))
        manager.acceptNextRound(roomCode, guestId, guestToken)

        room = repository.loadRoom(roomCode)!!
        assertEquals("between_rounds", room.status)
        assertFalse(room.acceptedNextRoundPlayerIds.contains(hostId))
        assertEquals(1, room.acceptedNextRoundPlayerIds.size)
    }

    @Test
    fun privateRoomHostManualNextRoundRequiresReadyPlayers() {
        val repository = InMemoryPrivateRoomRepository()
        val manager = privateRoomManager(repository, InMemoryRoundHistoryRepository(), FixedClock(), ManualScheduler())
        manager.initialize()

        val created = createPrivateRoom(manager, "Friends", "Host")
        val roomCode = created["roomCode"] as String
        val hostId = created["playerId"] as String
        val hostToken = created["playerToken"] as String
        val joined = joinPrivateRoom(manager, roomCode, "Guest")
        val guestId = joined["playerId"] as String
        val guestToken = joined["playerToken"] as String

        manager.startRound(roomCode, hostId, hostToken)
        var room = repository.loadRoom(roomCode)!!
        room.round!!.status = "active"
        room.round!!.activePlayerIndex = 1
        manager.performAction(roomCode, guestId, guestToken, "pack", emptyMap())
        manager.performAction(roomCode, hostId, hostToken, "dealer_tip", mapOf("amount" to 0))

        val earlyError = assertThrows(AppException::class.java) { manager.nextRound(roomCode, hostId, hostToken) }
        assertEquals("private_room_acceptance_pending", earlyError.code)

        manager.acceptNextRound(roomCode, guestId, guestToken)
        manager.nextRound(roomCode, hostId, hostToken)

        room = repository.loadRoom(roomCode)!!
        assertEquals("active", room.status)
        assertTrue(room.acceptedNextRoundPlayerIds.isEmpty())
    }
}
