package org.teenpatti.server.game

import org.teenpatti.server.common.RandomSource
import org.teenpatti.server.config.GameConfig

import java.util.HashSet
import java.util.LinkedHashSet

internal object Engine {
    private val SUITS = listOf("spades", "hearts", "diamonds", "clubs")
    private val RANKS =
        listOf(
            "2" to 2,
            "3" to 3,
            "4" to 4,
            "5" to 5,
            "6" to 6,
            "7" to 7,
            "8" to 8,
            "9" to 9,
            "10" to 10,
            "J" to 11,
            "Q" to 12,
            "K" to 13,
            "A" to 14,
        )

    @JvmStatic
    fun createDeck(): List<Card> {
        val deck = mutableListOf<Card>()
        for (suit in SUITS) {
            for ((rank, value) in RANKS) {
                deck.add(Card("$rank-$suit", suit, rank, value, null))
            }
        }
        check(deck.size == 52) { "A standard Teen Patti deck must contain exactly 52 cards." }
        return deck
    }

    @JvmStatic
    fun shuffleDeck(deck: List<Card>, randomSource: RandomSource): List<Card> {
        require(deck.isNotEmpty()) { "Deck cannot be empty." }
        val clone = deck.toMutableList()
        for (index in clone.lastIndex downTo 1) {
            val otherIndex = randomSource.nextInt(index + 1)
            val current = clone[index]
            clone[index] = clone[otherIndex]
            clone[otherIndex] = current
        }
        return clone
    }

    @JvmStatic
    fun createRoundDeal(
        config: GameConfig,
        participants: List<RoundParticipant>,
        roundId: String,
        serverSeed: String,
        playerSeedInputs: List<ProvablyFairPlayerSeedInput>,
    ): CreatedDeal = ProvablyFairSupport.createRoundDeal(config, participants, roundId, serverSeed, playerSeedInputs)

    @JvmStatic
    fun getActiveSeats(round: RoundState): List<SeatState> = round.seats.filter { it.active && !it.packed }

    @JvmStatic
    fun getPlayerMinimumStake(round: RoundState, seat: SeatState, config: GameConfig): Int =
        if (seat.seen) {
            minOf(round.currentStake * config.blindSeenMultiplier, config.maxStake * config.blindSeenMultiplier)
        } else {
            round.currentStake
        }

    @JvmStatic
    fun getPlayerRaiseStake(round: RoundState, seat: SeatState, config: GameConfig): Int =
        if (seat.seen) {
            minOf(round.currentStake * config.seenRaiseMultiplier, config.maxStake * config.blindSeenMultiplier)
        } else {
            minOf(round.currentStake * config.blindRaiseMultiplier, config.maxStake)
        }

    @JvmStatic
    fun getPreviousActiveSeat(round: RoundState, actorIndex: Int): SeatState? {
        var pointer = actorIndex
        repeat(round.seats.size - 1) {
            pointer = (pointer - 1 + round.seats.size) % round.seats.size
            val seat = round.seats[pointer]
            if (seat.active && !seat.packed) {
                return seat
            }
        }
        return null
    }

    @JvmStatic
    fun canRequestSideshow(round: RoundState, seat: SeatState, actorIndex: Int): Boolean {
        if (!seat.seen || getActiveSeats(round).size <= 2) {
            return false
        }
        val previous = getPreviousActiveSeat(round, actorIndex)
        return previous != null && previous.seen
    }

    @JvmStatic
    fun canShow(round: RoundState): Boolean = getActiveSeats(round).size == 2

    @JvmStatic
    fun evaluateSeatHand(seat: SeatState, round: RoundState, config: GameConfig): EvaluatedHand {
        // Flipper variant: blue card is a conditional match, not a wildcard.
        // Route through the dedicated flipper evaluator which ignores the wildcard mechanism.
        if (config.variant.publicCardMode == "flipper_blue_card") {
            val flipperCards = if (seat.reserveCards.isNotEmpty()) seat.reserveCards else seat.publicCards
            return evaluateFlipperHand(seat.cards, flipperCards, config)
        }
        val unavailableCardIds = collectDealtCardIds(round)
        unavailableCardIds.removeAll(seat.cards.map { it.id }.toSet())
        return evaluateHand(seat.cards, config, unavailableCardIds, getWildcardRanks(round, config))
    }

    @JvmStatic
    fun compareSeatHands(first: SeatState, second: SeatState, round: RoundState, config: GameConfig): Int {
        val firstHand = evaluateSeatHand(first, round, config)
        val secondHand = evaluateSeatHand(second, round, config)
        return compareEvaluations(firstHand, secondHand, config)
    }

    @JvmStatic
    fun resolveWinner(activeSeats: List<SeatState>, round: RoundState, config: GameConfig): SeatState {
        require(activeSeats.size >= 2) { "At least two active players are required to resolve a winner." }
        var winner = activeSeats.first()
        var winnerHand = evaluateSeatHand(winner, round, config)
        for (seat in activeSeats.drop(1)) {
            val hand = evaluateSeatHand(seat, round, config)
            if (compareEvaluations(hand, winnerHand, config) > 0) {
                winner = seat
                winnerHand = hand
            }
        }
        return winner
    }

    @JvmStatic
    fun calculateSettlement(
        potAmount: Int,
        bootContributionTotal: Int,
        casinoBootCommissionPercent: Int,
        casinoWinCommissionPercent: Int,
        dealerTip: Int,
    ): Settlement {
        val settlement = Settlement()
        settlement.bootContributionTotal = bootContributionTotal
        settlement.bootCommission = (bootContributionTotal * casinoBootCommissionPercent) / 100
        settlement.winnerReceivableBeforeTip = potAmount - settlement.bootCommission
        settlement.winCommission = (settlement.winnerReceivableBeforeTip * casinoWinCommissionPercent) / 100
        settlement.winnerReceivableBeforeTip -= settlement.winCommission
        settlement.dealerTip = dealerTip
        settlement.payout = settlement.winnerReceivableBeforeTip - dealerTip
        settlement.casinoCommissionTotal = settlement.bootCommission + settlement.winCommission + settlement.dealerTip
        return settlement
    }

    @JvmStatic
    fun validateSettlementConsistency(round: RoundState, settlement: Settlement) {
        val totalContributed = round.seats.sumOf { it.totalContributed }
        check(totalContributed == round.potAmount) { "Round pot does not match seat contributions." }
        check(settlement.payout + settlement.casinoCommissionTotal == round.potAmount) { "Settlement does not balance to the pot." }
        check(settlement.dealerTip >= 0) { "Dealer tip cannot be negative." }
        check(settlement.payout >= 0) { "Settlement payout cannot be negative." }
    }

    private fun collectDealtCardIds(round: RoundState): MutableSet<String> {
        val ids = LinkedHashSet<String>()
        for (seat in round.seats) {
            for (card in seat.cards) {
                ids.add(card.id)
            }
            for (card in seat.reserveCards) {
                ids.add(card.id)
            }
        }
        val sharedJokers = round.variantState?.sharedJokerCards ?: emptyList()
        for (card in sharedJokers) {
            ids.add(card.id)
        }
        return ids
    }

    private fun getWildcardRanks(round: RoundState, config: GameConfig): Set<String> {
        val ranks = LinkedHashSet<String>()
        ranks.addAll(config.variant.wildcardRanks)
        ranks.addAll(round.variantState?.wildcardRanks ?: emptyList())
        return ranks
    }

    @JvmStatic
    fun evaluateHand(
        cards: List<Card>,
        config: GameConfig,
        unavailableCardIds: Set<String>,
        wildcardRanks: Set<String> = config.variant.wildcardRanks.toSet(),
    ): EvaluatedHand {
        validateHand(cards)
        val wildcardIndexes = mutableListOf<Int>()
        for (index in cards.indices) {
            if (wildcardRanks.contains(cards[index].rank)) {
                wildcardIndexes.add(index)
            }
        }
        if (wildcardIndexes.isEmpty()) {
            return evaluateNaturalHand(cards, config)
        }
        val blocked = HashSet(unavailableCardIds)
        for (card in cards) {
            blocked.remove(card.id)
        }
        val substitutionDeck = createDeck().filter { !blocked.contains(it.id) }
        val working = cards.toMutableList()
        val best = arrayOfNulls<EvaluatedHand>(1)
        searchWildcards(0, wildcardIndexes, working, cards, substitutionDeck, config, best)
        return best[0] ?: evaluateNaturalHand(cards, config)
    }

    private fun searchWildcards(
        depth: Int,
        wildcardIndexes: List<Int>,
        working: MutableList<Card>,
        original: List<Card>,
        substitutionDeck: List<Card>,
        config: GameConfig,
        best: Array<EvaluatedHand?>,
    ) {
        if (depth == wildcardIndexes.size) {
            val ids = HashSet<String>()
            for (card in working) {
                ids.add(card.id)
            }
            if (ids.size != working.size) {
                return
            }
            val evaluation = evaluateNaturalHand(working, config)
            if (best[0] == null || compareEvaluations(evaluation, best[0]!!, config) > 0) {
                best[0] = evaluation
            }
            return
        }
        val wildcardIndex = wildcardIndexes[depth]
        for (candidate in substitutionDeck) {
            working[wildcardIndex] = candidate
            searchWildcards(depth + 1, wildcardIndexes, working, original, substitutionDeck, config, best)
        }
        working[wildcardIndex] = original[wildcardIndex]
    }

    /**
     * Evaluates a Flipper hand.
     *
     * Rules:
     *  - [normalCards] contains exactly 3 standard dealt cards.
     *  - [reserveCards] contains exactly 1 Blue Flipper card.
     *  - The Flipper is NOT a wildcard. It can only activate when its rank already
     *    appears in at least one of the three normal cards.
     *  - When active, the engine considers all C(4,3) = 4 three-card subsets
     *    formed from (normalCards + flipper) and returns the best evaluation.
     *  - When inactive, only the original three normal cards are evaluated.
     */
    @JvmStatic
    fun evaluateFlipperHand(
        normalCards: List<Card>,
        reserveCards: List<Card>,
        config: GameConfig,
    ): EvaluatedHand {
        val baseHand = evaluateNaturalHand(normalCards, config)
        if (reserveCards.isEmpty()) return baseHand

        val flipper = reserveCards[0]
        val flipperRank = flipper.rank ?: return baseHand

        // Activation check: at least one normal card must share the flipper's rank.
        val rankMatches = normalCards.any { it.rank == flipperRank }
        if (!rankMatches) return baseHand

        // Flipper is active — evaluate all 4 three-card subsets and return the best.
        val allFour = normalCards + flipper
        var best = baseHand
        for (skipIndex in allFour.indices) {
            val subset = allFour.filterIndexed { index, _ -> index != skipIndex }
            // subset always has exactly 3 cards
            val eval = evaluateNaturalHand(subset, config)
            if (compareEvaluations(eval, best, config) > 0) {
                best = eval
            }
        }
        return best
    }

    private fun evaluateNaturalHand(cards: List<Card>, config: GameConfig): EvaluatedHand {
        val values = cards.map { it.value!! }.sorted()
        val sortedDesc = cards.map { it.value!! }.sortedDescending()
        val counts = cards.groupingBy { it.value!! }.eachCount().mapValues { it.value.toLong() }
        val sortedCounts =
            counts.entries.sortedWith(
                compareByDescending<Map.Entry<Int, Long>> { it.value }.thenByDescending { it.key },
            )
        val flush = cards.all { it.suit == cards.first().suit }
        val sequenceStrength = getSequenceStrength(values, config)

        val hand = EvaluatedHand()
        if (sortedCounts.first().value == 3L) {
            hand.category = 6
            hand.label = "Trail"
            hand.ranks = mutableListOf(sortedCounts.first().key)
            return hand
        }
        if (sequenceStrength != null && flush) {
            hand.category = 5
            hand.label = "Pure Sequence"
            hand.ranks = mutableListOf(sequenceStrength)
            return hand
        }
        if (sequenceStrength != null) {
            hand.category = 4
            hand.label = "Sequence"
            hand.ranks = mutableListOf(sequenceStrength)
            return hand
        }
        if (flush) {
            hand.category = 3
            hand.label = "Color"
            hand.ranks = sortedDesc.toMutableList()
            return hand
        }
        if (sortedCounts.first().value == 2L) {
            hand.category = 2
            hand.label = "Pair"
            hand.ranks = mutableListOf(sortedCounts[0].key, sortedCounts[1].key)
            return hand
        }
        hand.category = 1
        hand.label = "High Card"
        hand.ranks = sortedDesc.toMutableList()
        return hand
    }

    @JvmStatic
    fun compareEvaluations(first: EvaluatedHand, second: EvaluatedHand, config: GameConfig? = null): Int {
        val lowball = config?.variant?.evaluationMode == "lowball"
        if (first.category != second.category) {
            return if (lowball) second.category.compareTo(first.category) else first.category.compareTo(second.category)
        }
        val max = maxOf(first.ranks.size, second.ranks.size)
        for (index in 0 until max) {
            val left = first.ranks.getOrElse(index) { 0 }
            val right = second.ranks.getOrElse(index) { 0 }
            if (left != right) {
                return if (lowball) right.compareTo(left) else left.compareTo(right)
            }
        }
        return 0
    }

    private fun getSequenceStrength(values: List<Int>, config: GameConfig): Int? {
        val sorted = values.sorted()
        val pattern = "${sorted[0]}-${sorted[1]}-${sorted[2]}"
        if (pattern == "12-13-14" && config.allowAkqSequence) {
            return 100
        }
        if (pattern == "2-3-14" && config.allowA23Sequence) {
            return if (config.sequenceRankingMode == "AKQ_HIGH_A23_SECOND") 99 else 100
        }
        if (sorted[1] == sorted[0] + 1 && sorted[2] == sorted[1] + 1) {
            return sorted[2]
        }
        return null
    }

    private fun validateHand(cards: List<Card>) {
        require(cards.size == 3) { "Teen Patti hands must contain exactly 3 cards." }
        val ids = HashSet<String>()
        for (card in cards) {
            require(!(card.id.isBlank() || card.rank == null || card.suit == null || card.value == null)) {
                "Hand contains incomplete card data."
            }
            ids.add(card.id)
        }
        require(ids.size == cards.size) { "Hand contains duplicate cards." }
    }
}
