package org.teenpatti.server.infrastructure.persistence

import org.teenpatti.server.game.RoundHistoryEntry
import org.teenpatti.server.game.TableState
import org.teenpatti.server.privateroom.PrivateRoomState
import org.teenpatti.server.publictable.PublicPlayerSessionState

internal interface TableAggregateRepository {
    fun loadTable(tableId: String): TableState?

    fun saveTable(state: TableState): TableState

    fun claimLease(tableId: String, leaseOwner: String, leaseExpiresAt: String, now: String): TableState?

    fun listActiveTables(tableType: String?, variantId: String?): List<TableState>
}

internal interface PrivateRoomRepository {
    fun loadRoom(roomCode: String): PrivateRoomState?

    fun saveRoom(state: PrivateRoomState): PrivateRoomState

    fun claimLease(roomCode: String, leaseOwner: String, leaseExpiresAt: String, now: String): PrivateRoomState?

    fun listActiveRooms(): List<PrivateRoomState>
}

internal interface PublicSessionRepository {
    fun loadSession(playerId: String): PublicPlayerSessionState?

    fun saveSession(session: PublicPlayerSessionState): PublicPlayerSessionState

    fun listSessionsForTable(tableId: String): List<PublicPlayerSessionState>

    fun listActiveSessions(variantId: String): List<PublicPlayerSessionState>
}

internal interface RoundHistoryRepository {
    fun appendRound(entry: RoundHistoryEntry)

    fun loadRoundsForAggregate(aggregateType: String, aggregateId: String, limit: Int): List<RoundHistoryEntry>

    fun listRecentRoundsForParticipants(participantIds: List<String>, offset: Int, limit: Int): List<RoundHistoryEntry>
}

internal class WalletTransaction {
    var id: String = ""
    var txnId: String = ""
    var operationKey: String = ""
    var playerId: String = ""
    var platformUserId: String = ""
    var platformOperatorId: String = ""
    var roundId: String = ""
    var txnType: String = ""
    var amount: Int = 0
    var status: String = ""
    var txnRefId: String? = null
    var requestPayload: MutableMap<String, Any?> = mutableMapOf()
    var responsePayload: MutableMap<String, Any?> = mutableMapOf()
    var createdAt: String? = null
    var updatedAt: String? = null
}

internal interface WalletTransactionRepository {
    fun loadByOperationKey(operationKey: String): WalletTransaction?

    fun save(transaction: WalletTransaction): WalletTransaction

    fun loadMostRecentDebit(playerId: String, roundId: String): WalletTransaction?

    fun listPendingCredits(limit: Int): List<WalletTransaction>

    fun listRecentTransactions(platformUserId: String, txnType: String, offset: Int, limit: Int): List<WalletTransaction>

    fun listPlayerIdsForPlatformUser(platformUserId: String): List<String>
}
