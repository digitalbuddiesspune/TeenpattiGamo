package org.teenpatti.server.config

internal class AppEnvironment {
    var production: Boolean = false
    var port: Int = 0
    var clientOrigin: String = ""
    var mongoUri: String = ""
    var mongoDbName: String = ""
    var redisUrl: String = ""
    var redisKeyPrefix: String = ""
    var appNodeId: String = ""
    var reconnectGraceMs: Long = 0L
    var privateRoomTtlMs: Long = 0L
    var tableId: String = ""
    var bootAmount: Int = 0
    var maxPotAmount: Int = 0
    var minStake: Int = 0
    var maxStake: Int = 0
    var maxRoundsBeforeForcedShow: Int = 0
    var playerCount: Int = 0
    var publicTableMaxBots: Int = 0
    var matchmakingWindowMs: Long = 0L
    var matchmakingPvpThreshold: Int = 0
    var casinoBootCommissionPercent: Int = 0
    var casinoWinCommissionPercent: Int = 0
    var initialBalance: Int = 0
    var turnDurationMs: Int = 0
    var platformEnabled: Boolean = false
    var appOperatorBaseUrl: String = ""
    var appOperatorUserDetailPath: String = ""
    var appOperatorBalancePath: String = ""
    var appOperatorCreditPath: String = ""
    var appOperatorLoginPath: String = ""
    var platformUserDetailUrl: String = ""
    var platformDebitUrl: String = ""
    var platformCreditUrl: String = ""
    var platformLoginUrl: String = ""
    var platformAmqpUrl: String = ""
    var platformAmqpExchange: String = ""
    var platformAmqpRoutingKey: String = ""
    var platformPubKey: String = ""
    var platformSecret: String = ""
    var platformGameId: Int = 0

    /** Supports a single origin or a comma-separated list in CLIENT_ORIGIN. */
    fun clientOrigins(): Array<String> {
        val parsed =
            clientOrigin
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        return if (parsed.isEmpty()) {
            arrayOf("http://localhost:3000")
        } else {
            parsed.toTypedArray()
        }
    }
}
