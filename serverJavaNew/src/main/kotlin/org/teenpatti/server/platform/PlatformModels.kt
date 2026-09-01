package org.teenpatti.server.platform

internal class PlatformUser {
    var userId: String = ""
    var username: String = ""
    var balance: Int = 0
    var currency: String = ""
    var operatorId: String = ""
}

internal class PlatformSession {
    var userId: String = ""
    var token: String = ""
    var gameId: Int = 0
    var user: PlatformUser = PlatformUser()
    var issuedAt: String = ""
}

internal class PlatformSessionRequest(
    val token: String? = null,
    val gameId: Int? = null,
    val clientSeed: String? = null,
    val variant: String? = null,
)

internal class PlatformEnvelope {
    var status: Boolean = false
    var msg: String? = null
    var data: Map<String, Any?>? = null
    var user: Map<String, Any?>? = null
}

internal class PlatformBalanceRequest {
    var txn_id: String = ""
    var amount: Int = 0
    var description: String = ""
    var txn_type: Int = 0
    var ip: String = ""
    var game_id: Int = 0
    var user_id: String = ""
    var txn_ref_id: String? = null
    var operator_id: String = ""
}

internal class PlatformCreditRequest {
    var userId: String = ""
    var gameName: String = ""
    var amount: Int = 0
    var description: String = ""
}

/**
 * Cashout payload published to RabbitMQ — field names/types match the operator integration guide
 * (and Ludo). Amount and game_id are strings (e.g. "700.00", "2"); txn_type 1 = credit.
 */
internal class PlatformCreditQueueMessage {
    var txn_id: String = ""
    var txn_ref_id: String = ""
    var txn_type: Int = 1
    var amount: String = ""
    var user_id: String = ""
    var game_id: String = ""
    var description: String = ""
    var ip: String = ""
    var operatorId: String = ""
    var token: String = ""
}

internal class PlatformPlayerRef(
    val playerId: String,
    val platformUserId: String?,
    val platformToken: String?,
    val platformGameId: Int?,
    val platformOperatorId: String?,
    val ip: String?,
    val isBot: Boolean,
) {
    /**
     * Guest players join without a platform session, so they own no platform wallet and play with
     * table chips only. Partial linkage still counts as linked so misconfigured platform players
     * fail loudly instead of silently playing for free.
     */
    val walletLinked: Boolean
        get() = !platformUserId.isNullOrBlank() || !platformToken.isNullOrBlank()
}
