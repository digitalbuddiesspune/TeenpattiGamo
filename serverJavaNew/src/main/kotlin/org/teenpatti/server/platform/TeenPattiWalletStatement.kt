package org.teenpatti.server.platform

internal object TeenPattiWalletStatement {
    fun description(amount: Int, direction: String, reason: String, roundId: String, lobbyId: String): String =
        "Teen Patti: $amount $direction for $reason; round $roundId; lobby $lobbyId."
}
