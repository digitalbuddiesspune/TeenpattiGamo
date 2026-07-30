package org.teenpatti.server.game

import org.teenpatti.server.common.TokenSupport
import org.teenpatti.server.config.GameConfig

import java.nio.ByteBuffer

internal object ProvablyFairSupport {
    private const val VERSION = "pf_v1"
    private const val ALGORITHM = "HMAC_SHA256_FISHER_YATES"
    private const val UINT32_MODULUS = 1L shl 32

    @JvmStatic
    fun newServerSeed(): String = TokenSupport.newSecureRandomHex(32)

    @JvmStatic
    fun createRoundDeal(
        config: GameConfig,
        participants: List<RoundParticipant>,
        roundId: String,
        serverSeed: String,
        playerSeedInputs: List<ProvablyFairPlayerSeedInput>,
    ): CreatedDeal {
        require(participants.size >= 2) { "At least two participants are required." }
        require(serverSeed.isNotBlank()) { "Server seed is required." }

        val deck = Engine.createDeck().toMutableList()
        val canonicalInput = canonicalRoundInput(config, participants, roundId, playerSeedInputs)
        val masterSeed = TokenSupport.hmacSha256(serverSeed.toByteArray(Charsets.UTF_8), canonicalInput)
        val random = DeterministicRandom(masterSeed)

        val openingPlayerIndex = random.nextInt(participants.size)
        shuffleDeck(deck, random)

        val result = CreatedDeal()
        result.openingPlayerIndex = openingPlayerIndex
        repeat(participants.size) {
            result.hands.add(mutableListOf())
        }

        var cursor = 0
        repeat(config.variant.cardsPerSeat) {
            for (player in participants.indices) {
                result.hands[player].add(deck[cursor++])
            }
        }
        if (config.variant.sharedJokerMode == "progressive_three") {
            repeat(3) {
                result.sharedCards.add(deck[cursor++])
            }
        }

        val state = ProvablyFairState()
        state.version = VERSION
        state.algorithm = ALGORITHM
        state.roundId = roundId
        state.serverSeedHash = TokenSupport.sha256Hex(serverSeed)
        state.serverSeed = serverSeed
        state.deckHash = TokenSupport.sha256Hex(deck.joinToString(",") { it.id })
        state.openingPlayerIndex = openingPlayerIndex
        for (item in playerSeedInputs) {
            val copy = ProvablyFairPlayerSeedInput()
            copy.playerId = item.playerId
            copy.clientSeed = item.clientSeed
            state.playerSeedInputs.add(copy)
        }
        result.provablyFair = state
        return result
    }

    private fun canonicalRoundInput(
        config: GameConfig,
        participants: List<RoundParticipant>,
        roundId: String,
        playerSeedInputs: List<ProvablyFairPlayerSeedInput>,
    ): String {
        val builder = StringBuilder()
        appendField(builder, "variant", config.variant.id)
        appendField(builder, "table", config.tableId)
        appendField(builder, "round", roundId)
        appendField(builder, "players", participants.size.toString())
        for (input in playerSeedInputs) {
            appendField(builder, "playerId", input.playerId)
            appendField(builder, "clientSeed", input.clientSeed)
        }
        return builder.toString()
    }

    private fun appendField(builder: StringBuilder, label: String, value: String) {
        builder.append(label)
            .append(':')
            .append(value.length)
            .append(':')
            .append(value)
            .append('|')
    }

    private fun shuffleDeck(deck: MutableList<Card>, random: DeterministicRandom) {
        for (index in deck.lastIndex downTo 1) {
            val otherIndex = random.nextInt(index + 1)
            val current = deck[index]
            deck[index] = deck[otherIndex]
            deck[otherIndex] = current
        }
    }

    private class DeterministicRandom(
        private val key: ByteArray,
    ) {
        private var counter = 0L

        fun nextInt(bound: Int): Int {
            require(bound > 0) { "bound must be positive." }
            val threshold = UINT32_MODULUS - (UINT32_MODULUS % bound)
            while (true) {
                val block = TokenSupport.hmacSha256(key, "pf:${counter++}")
                val value = Integer.toUnsignedLong(ByteBuffer.wrap(block, 0, Int.SIZE_BYTES).int)
                if (value < threshold) {
                    return (value % bound).toInt()
                }
            }
        }
    }
}
