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

        val realPlayerIndices = participants.indices.filter { index -> !participants[index].isBot }
        val openingPlayerIndex =
            if (realPlayerIndices.size == 1) {
                realPlayerIndices.first()
            } else {
                random.nextInt(participants.size)
            }
        shuffleDeck(deck, random)

        val remainingDeck = deck.toMutableList()
        val result = CreatedDeal()
        result.openingPlayerIndex = openingPlayerIndex
        repeat(participants.size) {
            result.hands.add(mutableListOf())
        }

        // Deal first 3 normal cards to each participant
        val initialCards = Math.min(3, config.variant.cardsPerSeat)
        repeat(initialCards) {
            for (player in participants.indices) {
                result.hands[player].add(remainingDeck.removeAt(0))
            }
        }

        // For flipper_blue_card mode: weighted random selection of active flipper counts per round
        if (config.variant.publicCardMode == "flipper_blue_card" && config.variant.cardsPerSeat >= 4) {
            val playerCount = participants.size
            val roll = random.nextInt(100)
            val activeTargetCount = when {
                roll < 40 -> if (playerCount == 4) 3 else Math.round(playerCount * 3.0 / 4.0).toInt()
                roll < 70 -> if (playerCount == 4) 2 else Math.round(playerCount * 2.0 / 4.0).toInt()
                roll < 85 -> if (playerCount == 4) 1 else Math.round(playerCount * 1.0 / 4.0).toInt()
                roll < 95 -> playerCount
                else -> 0
            }.coerceIn(0, playerCount)

            val playerIndices = participants.indices.toMutableList()
            for (i in playerIndices.indices.reversed()) {
                val j = random.nextInt(i + 1)
                val temp = playerIndices[i]
                playerIndices[i] = playerIndices[j]
                playerIndices[j] = temp
            }

            val activePlayerSet = playerIndices.take(activeTargetCount).toSet()

            for (player in participants.indices) {
                val normalCards = result.hands[player]
                val normalRanks = normalCards.mapNotNull { it.rank }.toSet()

                if (activePlayerSet.contains(player)) {
                    val matchingIndices = remainingDeck.indices.filter { idx ->
                        normalRanks.contains(remainingDeck[idx].rank)
                    }
                    if (matchingIndices.isNotEmpty()) {
                        val chosenDeckIdx = matchingIndices[random.nextInt(matchingIndices.size)]
                        result.hands[player].add(remainingDeck.removeAt(chosenDeckIdx))
                    } else {
                        result.hands[player].add(remainingDeck.removeAt(0))
                    }
                } else {
                    val nonMatchingIndices = remainingDeck.indices.filter { idx ->
                        !normalRanks.contains(remainingDeck[idx].rank)
                    }
                    if (nonMatchingIndices.isNotEmpty()) {
                        val chosenDeckIdx = nonMatchingIndices[random.nextInt(nonMatchingIndices.size)]
                        result.hands[player].add(remainingDeck.removeAt(chosenDeckIdx))
                    } else {
                        result.hands[player].add(remainingDeck.removeAt(0))
                    }
                }
            }
        } else {
            val remainingPerSeat = config.variant.cardsPerSeat - initialCards
            if (remainingPerSeat > 0) {
                repeat(remainingPerSeat) {
                    for (player in participants.indices) {
                        result.hands[player].add(remainingDeck.removeAt(0))
                    }
                }
            }
        }

        if (config.variant.sharedJokerMode == "progressive_three") {
            repeat(3) {
                result.sharedCards.add(remainingDeck.removeAt(0))
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
