package org.teenpatti.server.config

internal class VariantConfig {
    var id: String = ""
    var label: String = ""
    var wildcardRanks: MutableList<String> = mutableListOf()
    var evaluationMode: String = "standard"
    var cardsPerSeat: Int = 3
    var publicCardMode: String = "none"
    var sharedJokerMode: String = "none"
    var forceBlindCycles: Int = 0
    var showUnlockCycle: Int = 0
    var showRequiresAllSeen: Boolean = false
    var autoAcceptSideshow: Boolean = false
}

internal class AutoplayConfig {
    var defaultRounds: Int = 0
    var maxRounds: Int = 0
    var maxProfitTarget: Int = 0
    var maxLossLimit: Int = 0
}

internal class BotActionDelayConfig {
    var min: Int = 0
    var max: Int = 0
}

internal class GameConfig {
    var tableId: String = ""
    var bootAmount: Int = 0
    var maxPotAmount: Int = 0
    var minStake: Int = 0
    var maxStake: Int = 0
    var maxRoundsBeforeForcedShow: Int = 0
    var playerCount: Int = 0
    var publicTableMaxBots: Int = 0
    var casinoBootCommissionPercent: Int = 0
    var casinoWinCommissionPercent: Int = 0
    var maxBalance: Int = 0
    var initialBalance: Int = 0
    var turnDurationMs: Int = 0
    var blindSeenMultiplier: Int = 0
    var blindRaiseMultiplier: Int = 0
    var seenRaiseMultiplier: Int = 0
    var allowA23Sequence: Boolean = false
    var allowAkqSequence: Boolean = false
    var sequenceRankingMode: String = ""
    var botDecisionMode: String = ""
    var botMaxSimulations: Int = 0
    var botMaxDecisionTimeMs: Int = 0
    var botHeadsUpSeeAfterBlindTurns: Int = 0
    lateinit var autoplay: AutoplayConfig
    lateinit var botActionDelayMs: BotActionDelayConfig
    lateinit var variant: VariantConfig
}
