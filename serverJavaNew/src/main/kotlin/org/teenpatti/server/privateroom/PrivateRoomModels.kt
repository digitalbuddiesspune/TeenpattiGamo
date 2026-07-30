package org.teenpatti.server.privateroom

import org.teenpatti.server.game.ProvablyFairState
import org.teenpatti.server.game.RoundState

internal class PrivateRoomHistoryItem {
    var id: String = ""
    var roundId: String = ""
    var winnerId: String = ""
    var winnerName: String = ""
    var winningHand: String = ""
    var pot: Int = 0
    var bootCommission: Int = 0
    var winCommission: Int = 0
    var dealerTip: Int = 0
    var casinoCommissionTotal: Int = 0
    var winnerReceivableBeforeTip: Int = 0
    var payout: Int = 0
    var reason: String? = null
    var timestamp: String? = null
    var provablyFair: ProvablyFairState? = null
}

internal class PrivateRoomPlayer {
    var id: String = ""
    var tokenHash: String = ""
    var clientSeed: String = ""
    var name: String = ""
    var platformUserId: String? = null
    var platformToken: String? = null
    var platformGameId: Int? = null
    var platformOperatorId: String? = null
    var platformUsername: String? = null
    var platformCurrency: String? = null
    var platformBalanceSnapshot: Int? = null
    var platformTokenIssuedAt: String? = null
    var lastKnownIp: String? = null
    var balance: Int = 0
    var status: String = ""
    var connected: Boolean = false
    var joinedAt: String? = null
    var avatar: String = ""
    var lastSeenAt: String? = null
}

internal class PrivateRoomConfigSnapshot {
    var tableId: String = ""
    var bootAmount: Int = 0
    var minStake: Int = 0
    var maxStake: Int = 0
    var playerCount: Int = 0
    var casinoBootCommissionPercent: Int = 0
    var casinoWinCommissionPercent: Int = 0
    var turnDurationMs: Int = 0
    var variant: MutableMap<String, Any?>? = null
}

internal class PrivateRoomState {
    var roomCode: String = ""
    var roomName: String = ""
    var status: String = ""
    var hostPlayerId: String = ""
    var createdAt: String? = null
    var closedAt: String? = null
    var config: PrivateRoomConfigSnapshot? = null
    var players: MutableList<PrivateRoomPlayer> = mutableListOf()
    var round: RoundState? = null
    var history: MutableList<PrivateRoomHistoryItem> = mutableListOf()
    var acceptedNextRoundPlayerIds: MutableList<String> = mutableListOf()
    var version: Long = 0L
    var leaseOwner: String? = null
    var leaseExpiresAt: String? = null
    var updatedAt: String? = null
    var expiresAt: String? = null
}
