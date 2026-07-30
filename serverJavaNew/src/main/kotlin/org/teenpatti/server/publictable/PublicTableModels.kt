package org.teenpatti.server.publictable

internal class BotPolicyConfig {
    var decisionMode: String = ""
    var maxSimulations: Int = 0
    var maxDecisionTimeMs: Int = 0
    var headsUpSeeAfterBlindTurns: Int = 0
}

internal class BotActionScore {
    var action: String = ""
    var score: Double = 0.0
    var expectedValue: Double = 0.0
    var winProbability: Double = 0.0
    var rationale: String? = null
}

internal class OpponentRangeModel {
    var playerId: String = ""
    var currentlySeen: Boolean = false
    var seeCount: Int = 0
    var chaalCount: Int = 0
    var seenRaiseCount: Int = 0
    var blindRaiseCount: Int = 0
    var sideshowRequestCount: Int = 0
    var sideshowAcceptCount: Int = 0
    var candidateHands: Int = 0
    var totalWeight: Double = 0.0
    var estimatedStrength: Double = 0.0
}

internal class BotVisibleState {
    var seenOwnCards: Boolean = false
    var knownSelfCards: MutableList<org.teenpatti.server.game.Card> = mutableListOf()
    var revealedHands: MutableMap<String, MutableList<org.teenpatti.server.game.Card>> = linkedMapOf()
    var knownUnavailableCardIds: MutableList<String> = mutableListOf()
    var wildcardRanks: MutableList<String> = mutableListOf()
}

internal class BotDecisionContext {
    var playerId: String = ""
    var mode: String = ""
    var chosenAction: String = ""
    var rationale: String = ""
    var aggressiveSignal: Boolean = false
    var pressured: Boolean = false
    var fallbackUsed: Boolean = false
    var simulationTimedOut: Boolean = false
    var activePlayerCount: Int = 0
    var potAmount: Int = 0
    var currentStake: Int = 0
    var minCallAmount: Int = 0
    var raiseAmount: Int = 0
    var showAmount: Int = 0
    var winProbability: Double = 0.0
    var sideShowWinProbability: Double = 0.0
    lateinit var visibleState: BotVisibleState
    var legalActions: MutableList<String> = mutableListOf()
    var opponentRanges: MutableList<OpponentRangeModel> = mutableListOf()
    var actionScores: MutableList<BotActionScore> = mutableListOf()
}

internal class AutoplaySummary {
    var profit: Int = 0
    var totalWagered: Int = 0
    var totalPayout: Int = 0
    var wins: Int = 0
    var losses: Int = 0
}

internal class AutoplayStrategy {
    var seeAfterTurns: Int = 0
    var blindRoundsBeforeSee: Int = 0
    var packBelowRank: String? = null
    var raiseOnPairOrBetter: Boolean = false
}

internal class AutoplaySession {
    var id: String = ""
    var active: Boolean = false
    var baseStake: Int = 0
    var roundsPlanned: Int = 0
    var roundsCompleted: Int = 0
    var strategy: AutoplayStrategy? = null
    var stopProfit: Int = 0
    var stopLoss: Int = 0
    var summary: AutoplaySummary? = null
    var createdAt: String? = null
    var stoppedAt: String? = null
}

internal class PublicBotSlot {
    var id: String = ""
    var name: String = ""
    var avatar: String = ""
}

internal class PublicSeatingState {
    var seatedPlayerIds: MutableList<String> = mutableListOf()
    var waitingPlayerIds: MutableList<String> = mutableListOf()
    var botSlots: MutableList<PublicBotSlot> = mutableListOf()
    var botSequence: Int = 1
    var lastPromotionMessage: String? = null
    var joinWaitStartedAt: String? = null
    var joinWaitEndsAt: String? = null
}

internal class PublicPlayerSessionState {
    var id: String = ""
    var variantId: String = ""
    var tableId: String? = null
    var tokenHash: String = ""
    var clientSeed: String = ""
    var displayName: String = ""
    var platformUserId: String? = null
    var platformToken: String? = null
    var platformGameId: Int? = null
    var platformOperatorId: String? = null
    var platformUsername: String? = null
    var platformCurrency: String? = null
    var platformBalanceSnapshot: Int? = null
    var platformTokenIssuedAt: String? = null
    var lastKnownIp: String? = null
    var status: String = ""
    var nextRoundReady: Boolean = false
    var connected: Boolean = false
    var joinedAt: String? = null
    var lastSeenAt: String? = null
    var createdAt: String? = null
    var updatedAt: String? = null
    var leftAt: String? = null
    var expiresAt: String? = null
    var version: Long = 0L
}
