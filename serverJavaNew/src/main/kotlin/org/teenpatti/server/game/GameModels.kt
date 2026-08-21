package org.teenpatti.server.game

import org.teenpatti.server.config.GameConfig
import org.teenpatti.server.publictable.AutoplaySession
import org.teenpatti.server.publictable.PublicSeatingState

internal class Card() {
    var id: String = ""
    var suit: String? = null
    var rank: String? = null
    var value: Int? = null
    var hidden: Boolean? = null

    constructor(id: String, suit: String?, rank: String?, value: Int?, hidden: Boolean?) : this() {
        this.id = id
        this.suit = suit
        this.rank = rank
        this.value = value
        this.hidden = hidden
    }
}

internal class PlayerBankroll {
    var id: String = ""
    var balance: Int = 0
}

internal class LastAction() {
    var type: String = ""
    var amount: Int = 0
    var at: String? = null

    constructor(type: String, amount: Int, at: String?) : this() {
        this.type = type
        this.amount = amount
        this.at = at
    }
}

internal class ActionLogEntry {
    var id: String = ""
    var roundId: String = ""
    var playerId: String = ""
    var actionType: String = ""
    var amount: Int = 0
    var note: String? = null
    var timestamp: String? = null
}

internal class DebugInfo {
    var controlled: Boolean = false
    var revealAllCards: Boolean = false
    var label: String? = null
    var mode: String? = null
    var winnerSeatIndex: Int? = null
    var winnerCategory: String? = null
}

internal class RoundResult {
    var winnerId: String = ""
    var winnerName: String = ""
    var winningHand: String = ""
    var bootContributionTotal: Int = 0
    var realPlayerContributionTotal: Int = 0
    var botContributionTotal: Int = 0
    var bootCommission: Int = 0
    var actualBootCommission: Int = 0
    var winCommission: Int = 0
    var actualWinCommission: Int = 0
    var dealerTip: Int = 0
    var casinoCommissionTotal: Int = 0
    var actualCasinoIncomeTotal: Int = 0
    var winnerReceivableBeforeTip: Int = 0
    var payout: Int = 0
    var reason: String? = null
    var potLimitReached: Boolean = false
}

internal class DealerTipState {
    var winnerId: String = ""
    var winnerName: String = ""
    var winnerReceivableBeforeTip: Int = 0
    var maxAmount: Int = 0
    var pending: Boolean = false
    var expiresAt: String? = null
    var resolvedAt: String? = null
}

internal class ProvablyFairPlayerSeedInput {
    var playerId: String = ""
    var clientSeed: String = ""
}

internal class ProvablyFairState {
    var version: String = ""
    var algorithm: String = ""
    var roundId: String = ""
    var serverSeedHash: String = ""
    var serverSeed: String? = null
    var deckHash: String = ""
    var openingPlayerIndex: Int = 0
    var playerSeedInputs: MutableList<ProvablyFairPlayerSeedInput> = mutableListOf()
}

internal class SideShowRequest {
    var requesterId: String = ""
    var targetId: String = ""
    var requesterName: String = ""
    var targetName: String = ""
    var requestedAt: String? = null
    var expiresAt: String? = null
    var forcedRaiseAmount: Int = 0
    var status: String = ""
}

internal class SideShowSeatReveal {
    var playerId: String = ""
    var playerName: String = ""
    var cards: MutableList<Card> = mutableListOf()
}

internal class SideShowResult {
    var requesterId: String = ""
    var targetId: String = ""
    var requesterName: String = ""
    var targetName: String = ""
    var loserId: String = ""
    var winnerId: String = ""
    var status: String = ""
    var resolvedAt: String? = null
    var visibleToPlayerIds: MutableList<String> = mutableListOf()
    var reveals: MutableList<SideShowSeatReveal> = mutableListOf()
}

internal class SeatState {
    var id: String = ""
    var name: String = ""
    var avatar: String = ""
    var isBot: Boolean = false
    var connected: Boolean = true
    var active: Boolean = true
    var packed: Boolean = false
    var seen: Boolean = false
    var cards: MutableList<Card> = mutableListOf()
    var publicCards: MutableList<Card> = mutableListOf()
    var reserveCards: MutableList<Card> = mutableListOf()
    var totalContributed: Int = 0
    var lastAction: LastAction? = null
    var balance: Int = 0
    var eliminatedBySideshow: Boolean = false
}

internal class RoundVariantState {
    var variantId: String = ""
    var cycleNumber: Int = 0
    var cycleStartPlayerId: String? = null
    var forceBlindActive: Boolean = false
    var showUnlocked: Boolean = true
    var showRequiresAllSeen: Boolean = false
    var autoAcceptSideshow: Boolean = false
    var wildcardRanks: MutableList<String> = mutableListOf()
    var sharedJokerCards: MutableList<Card> = mutableListOf()
    var revealedSharedJokerCount: Int = 0
    var pendingAutoSeePlayerId: String? = null
}

internal class RoundState {
    var id: String = ""
    var status: String = ""
    var bootAmount: Int = 0
    var currentStake: Int = 0
    var potAmount: Int = 0
    var activePlayerIndex: Int = 0
    var dealerIndex: Int = 0
    var seats: MutableList<SeatState> = mutableListOf()
    var actionLog: MutableList<ActionLogEntry> = mutableListOf()
    var createdAt: String? = null
    var settledAt: String? = null
    var nextRoundDecisionExpiresAt: String? = null
    var debug: DebugInfo? = null
    var result: RoundResult? = null
    var lastAction: ActionLogEntry? = null
    var message: String? = null
    var turnDurationMs: Int = 0
    var startCountdownStartedAt: String? = null
    var startCountdownEndsAt: String? = null
    var dealingStartedAt: String? = null
    var dealingEndsAt: String? = null
    var turnStartedAt: String? = null
    var turnDeadlineAt: String? = null
    var pendingSideShow: SideShowRequest? = null
    var recentSideShowResult: SideShowResult? = null
    var dealerTipState: DealerTipState? = null
    var provablyFair: ProvablyFairState? = null
    var variantState: RoundVariantState? = null
}

internal class TableHistoryItem {
    var id: String = ""
    var roundId: String = ""
    var outcome: String = ""
    var winningHand: String = ""
    var pot: Int = 0
    var userContribution: Int = 0
    var realPlayerContributionTotal: Int = 0
    var botContributionTotal: Int = 0
    var bootCommission: Int = 0
    var actualBootCommission: Int = 0
    var winCommission: Int = 0
    var actualWinCommission: Int = 0
    var dealerTip: Int = 0
    var casinoCommissionTotal: Int = 0
    var actualCasinoIncomeTotal: Int = 0
    var winnerReceivableBeforeTip: Int = 0
    var payout: Int = 0
    var timestamp: String? = null
    var provablyFair: ProvablyFairState? = null
}

internal class TableEvent() {
    var type: String = ""
    var payload: Any? = null

    constructor(type: String, payload: Any?) : this() {
        this.type = type
        this.payload = payload
    }
}

internal class RoundParticipant {
    var id: String = ""
    var name: String = ""
    var avatar: String = ""
    var isBot: Boolean = false
    var connected: Boolean = true
    var clientSeed: String? = null
}

internal class TableState {
    var id: String = ""
    var tableType: String = "variant_table"
    var variantId: String? = null
    var status: String = ""
    lateinit var config: GameConfig
    var history: MutableList<TableHistoryItem> = mutableListOf()
    var round: RoundState? = null
    var autoplay: AutoplaySession? = null
    var messages: MutableList<String> = mutableListOf()
    var playerBankrolls: MutableList<PlayerBankroll> = mutableListOf()
    var nextOpeningPlayerId: String? = null
    var publicSeating: PublicSeatingState? = null
    var version: Long = 0L
    var leaseOwner: String? = null
    var leaseExpiresAt: String? = null
    var createdAt: String? = null
    var updatedAt: String? = null
    var expiresAt: String? = null
}

internal class RoundHistoryParticipant {
    var id: String = ""
    var name: String = ""
    var avatar: String = ""
    var isBot: Boolean = false
    var totalContributed: Int = 0
    var balance: Int = 0
    var packed: Boolean = false
    var seen: Boolean = false
    var cards: MutableList<Card> = mutableListOf()
    var publicCards: MutableList<Card> = mutableListOf()
    var reserveCards: MutableList<Card> = mutableListOf()
    var handLabel: String? = null
}

internal class RoundHistoryWinner {
    var id: String = ""
    var name: String = ""
    var winningHand: String? = null
}

internal class RoundHistoryEntry {
    var id: String = ""
    var aggregateType: String = ""
    var aggregateId: String = ""
    var variantId: String = ""
    var participants: MutableList<RoundHistoryParticipant> = mutableListOf()
    var winner: RoundHistoryWinner? = null
    var bootContributionTotal: Int = 0
    var potAmount: Int = 0
    var realPlayerContributionTotal: Int = 0
    var botContributionTotal: Int = 0
    var bootCommission: Int = 0
    var actualBootCommission: Int = 0
    var winCommission: Int = 0
    var actualWinCommission: Int = 0
    var dealerTip: Int = 0
    var casinoCommissionTotal: Int = 0
    var actualCasinoIncomeTotal: Int = 0
    var winnerReceivableBeforeTip: Int = 0
    var payout: Int = 0
    var reason: String? = null
    var actionLog: MutableList<ActionLogEntry> = mutableListOf()
    var startedAt: String? = null
    var settledAt: String? = null
    var createdAt: String? = null
    var provablyFair: ProvablyFairState? = null
}

internal class Settlement {
    var bootContributionTotal: Int = 0
    var realPlayerContributionTotal: Int = 0
    var botContributionTotal: Int = 0
    var bootCommission: Int = 0
    var actualBootCommission: Int = 0
    var winCommission: Int = 0
    var actualWinCommission: Int = 0
    var dealerTip: Int = 0
    var casinoCommissionTotal: Int = 0
    var actualCasinoIncomeTotal: Int = 0
    var winnerReceivableBeforeTip: Int = 0
    var payout: Int = 0
}

internal class EvaluatedHand {
    var category: Int = 0
    var ranks: MutableList<Int> = mutableListOf()
    var label: String = ""
}

internal class CreatedDeal {
    var hands: MutableList<MutableList<Card>> = mutableListOf()
    var sharedCards: MutableList<Card> = mutableListOf()
    var openingPlayerIndex: Int = 0
    var provablyFair: ProvablyFairState? = null
}
