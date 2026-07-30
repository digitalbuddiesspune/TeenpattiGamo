package org.teenpatti.server


import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.time.Instant


import org.teenpatti.server.common.*
import org.teenpatti.server.config.*
import org.teenpatti.server.game.*
import org.teenpatti.server.infrastructure.persistence.*
import org.teenpatti.server.privateroom.*
import org.teenpatti.server.publictable.*
internal class GameSmokeTest {
    @Test
    fun unseenBotDecisionIgnoresHiddenOpponentCards() {
        var service = roundService()

        service.startRound(listOf(participant("player-1", "Alpha"), botParticipant("public-bot-1", "Guest_100001", "raj")))
        service.state.round!!.status = "active"
        setActivePlayer(service, "public-bot-1")
        var botSeat = seat(service, "public-bot-1")
        var playerSeat = seat(service, "player-1")
        setSeatCards(botSeat, card("2", "spades"), card("6", "hearts"), card("9", "clubs"))
        setSeatCards(playerSeat, card("A", "spades"), card("A", "hearts"), card("A", "diamonds"))

        val firstAction = invokeDecideBotAction(service, botSeat)

        service = roundService()
        service.startRound(listOf(participant("player-1", "Alpha"), botParticipant("public-bot-1", "Guest_100001", "raj")))
        service.state.round!!.status = "active"
        setActivePlayer(service, "public-bot-1")
        botSeat = seat(service, "public-bot-1")
        playerSeat = seat(service, "player-1")
        setSeatCards(botSeat, card("2", "spades"), card("6", "hearts"), card("9", "clubs"))
        setSeatCards(playerSeat, card("4", "spades"), card("7", "hearts"), card("J", "diamonds"))

        assertEquals(firstAction, invokeDecideBotAction(service, botSeat))
        assertEquals("blind", firstAction)
    }

    @Test
    fun seenBotWildcardDecisionIgnoresHiddenOpponentCards() {
        val firstConfig = testGameConfig("ak47").apply { botMaxDecisionTimeMs = 5_000 }
        val firstService = roundService(config = firstConfig)
        firstService.startRound(listOf(participant("player-1", "Alpha"), botParticipant("public-bot-1", "Guest_100001", "raj")))
        firstService.state.round!!.status = "active"
        setActivePlayer(firstService, "public-bot-1")
        val firstBot = seat(firstService, "public-bot-1")
        val firstPlayer = seat(firstService, "player-1")
        firstBot.seen = true
        setSeatCards(firstBot, card("A", "spades"), card("9", "hearts"), card("9", "clubs"))
        setSeatCards(firstPlayer, card("9", "spades"), card("9", "diamonds"), card("4", "hearts"))
        val firstDecision = invokeDecideBotDecision(firstService, firstBot)

        val secondConfig = testGameConfig("ak47").apply { botMaxDecisionTimeMs = 5_000 }
        val secondService = roundService(config = secondConfig)
        secondService.startRound(listOf(participant("player-1", "Alpha"), botParticipant("public-bot-1", "Guest_100001", "raj")))
        secondService.state.round!!.status = "active"
        setActivePlayer(secondService, "public-bot-1")
        val secondBot = seat(secondService, "public-bot-1")
        val secondPlayer = seat(secondService, "player-1")
        secondBot.seen = true
        setSeatCards(secondBot, card("A", "spades"), card("9", "hearts"), card("9", "clubs"))
        setSeatCards(secondPlayer, card("Q", "spades"), card("J", "diamonds"), card("8", "clubs"))
        val secondDecision = invokeDecideBotDecision(secondService, secondBot)

        assertEquals(firstDecision.chosenAction, secondDecision.chosenAction)
        assertEquals(firstDecision.winProbability, secondDecision.winProbability, 0.05)
    }

    @Test
    fun botSeesAfterOpponentRaise() {
        val service = roundService()
        service.startRound(listOf(participant("player-1", "Alpha"), botParticipant("public-bot-1", "Guest_100001", "raj")))
        service.state.round!!.status = "active"
        setActivePlayer(service, "public-bot-1")
        appendAction(service.state.round!!, "player-1", "raise")

        assertEquals("see", invokeDecideBotAction(service, seat(service, "public-bot-1")))
    }

    @Test
    fun botSeesBySecondBlindAction() {
        val service = roundService()
        service.startRound(listOf(participant("player-1", "Alpha"), botParticipant("public-bot-1", "Guest_100001", "raj")))
        service.state.round!!.status = "active"
        setActivePlayer(service, "public-bot-1")
        appendAction(service.state.round!!, "public-bot-1", "blind")

        assertEquals("see", invokeDecideBotAction(service, seat(service, "public-bot-1")))
    }

    @Test
    fun strongSeenBotRaisesWithPairOrBetter() {
        val service = roundService()
        service.startRound(
            listOf(
                participant("player-1", "Alpha"),
                botParticipant("public-bot-1", "Guest_100001", "raj"),
                participant("player-2", "Bravo"),
            ),
        )
        service.state.round!!.status = "active"
        setActivePlayer(service, "public-bot-1")
        val botSeat = seat(service, "public-bot-1")
        botSeat.seen = true
        setSeatCards(botSeat, card("A", "spades"), card("A", "hearts"), card("A", "diamonds"))

        assertEquals("raise", invokeDecideBotAction(service, botSeat))
    }

    @Test
    fun weakSeenBotPacksUnderPressure() {
        val service = roundService()
        service.startRound(listOf(participant("player-1", "Alpha"), botParticipant("public-bot-1", "Guest_100001", "raj")))
        service.state.round!!.status = "active"
        service.state.round!!.currentStake = 4_000
        service.state.round!!.potAmount = 8_000
        setActivePlayer(service, "public-bot-1")
        val botSeat = seat(service, "public-bot-1")
        botSeat.seen = true
        setSeatCards(botSeat, card("2", "spades"), card("5", "hearts"), card("7", "clubs"))
        appendAction(service.state.round!!, "player-1", "see")
        appendAction(service.state.round!!, "player-1", "raise")

        assertEquals("pack", invokeDecideBotAction(service, botSeat))
    }

    @Test
    fun headsUpSeenBotChoosesShowWhenRaiseIsNotAffordable() {
        val service = roundService()
        service.startRound(listOf(participant("player-1", "Alpha"), botParticipant("public-bot-1", "Guest_100001", "raj")))
        service.state.round!!.status = "active"
        setActivePlayer(service, "public-bot-1")
        val botSeat = seat(service, "public-bot-1")
        botSeat.seen = true
        botSeat.balance = 2_500
        service.state.round!!.potAmount = 4_000
        setSeatCards(botSeat, card("A", "spades"), card("A", "hearts"), card("K", "clubs"))

        assertEquals("show", invokeDecideBotAction(service, botSeat))
    }

    @Test
    fun strongSeenBotAcceptsSideShow() {
        val service = roundService()
        service.startRound(
            listOf(
                participant("player-1", "Alpha"),
                botParticipant("public-bot-1", "Guest_100001", "raj"),
                participant("player-2", "Bravo"),
            ),
        )
        service.state.round!!.status = "active"
        val botSeat = seat(service, "public-bot-1")
        botSeat.seen = true
        setSeatCards(botSeat, card("A", "spades"), card("A", "hearts"), card("A", "diamonds"))
        appendAction(service.state.round!!, "player-1", "see")
        appendAction(service.state.round!!, "player-1", "chaal")
        service.state.round!!.pendingSideShow = sideShow("player-1", "Alpha", "public-bot-1", "Guest_100001")

        assertEquals("sideshow_accept", invokeDecideBotPendingSideShowDecision(service, botSeat).chosenAction)
    }

    @Test
    fun weakSeenBotDeniesSideShowAgainstStrongRange() {
        val service = roundService()
        service.startRound(
            listOf(
                participant("player-1", "Alpha"),
                botParticipant("public-bot-1", "Guest_100001", "raj"),
                participant("player-2", "Bravo"),
            ),
        )
        service.state.round!!.status = "active"
        val botSeat = seat(service, "public-bot-1")
        botSeat.seen = true
        setSeatCards(botSeat, card("2", "spades"), card("5", "hearts"), card("7", "clubs"))
        appendAction(service.state.round!!, "player-1", "see")
        appendAction(service.state.round!!, "player-1", "raise")
        service.state.round!!.pendingSideShow = sideShow("player-1", "Alpha", "public-bot-1", "Guest_100001")

        assertEquals("sideshow_deny", invokeDecideBotPendingSideShowDecision(service, botSeat).chosenAction)
    }

    @Test
    fun settlementTracksBootWinCommissionAndDealerTip() {
        val settlement = Engine.calculateSettlement(10_000, 2_000, 5, 10, 500)

        assertEquals(2_000, settlement.bootContributionTotal)
        assertEquals(100, settlement.bootCommission)
        assertEquals(990, settlement.winCommission)
        assertEquals(8_910, settlement.winnerReceivableBeforeTip)
        assertEquals(500, settlement.dealerTip)
        assertEquals(1_590, settlement.casinoCommissionTotal)
        assertEquals(8_410, settlement.payout)
    }

    @Test
    fun openingStakeFollowsBootAmountWhenBootIsBelowConfiguredMinStake() {
        val config =
            testGameConfig("classic").apply {
                bootAmount = 100
                minStake = 1000
            }
        val service = roundService(config = config)

        service.startRound(listOf(participant("player-1", "Alpha"), participant("player-2", "Bravo")))
        service.state.round!!.status = "active"
        setActivePlayer(service, "player-1")

        assertEquals(100, service.state.round!!.currentStake)
        service.performAction("player-1", "blind", emptyMap())
        assertEquals(200, seat(service, "player-1").totalContributed)
        assertEquals(300, service.state.round!!.potAmount)
    }

    @Test
    fun classicSequenceRankingPrefersAkqThenA23ThenKqj() {
        val config = testGameConfig("classic")

        val akq =
            Engine.evaluateHand(
                listOf(card("A", "spades"), card("K", "hearts"), card("Q", "clubs")),
                config,
                emptySet(),
            )
        val a23 =
            Engine.evaluateHand(
                listOf(card("A", "spades"), card("2", "hearts"), card("3", "clubs")),
                config,
                emptySet(),
            )
        val kqj =
            Engine.evaluateHand(
                listOf(card("K", "spades"), card("Q", "hearts"), card("J", "clubs")),
                config,
                emptySet(),
            )

        assertEquals("Sequence", akq.label)
        assertEquals("Sequence", a23.label)
        assertEquals("Sequence", kqj.label)
        assertTrue(Engine.compareEvaluations(akq, a23, config) > 0)
        assertTrue(Engine.compareEvaluations(a23, kqj, config) > 0)
    }

    @Test
    fun ak47WildcardsResolveToBestPossibleHand() {
        val config = testGameConfig("ak47")

        val evaluation =
            Engine.evaluateHand(
                listOf(card("A", "spades"), card("9", "hearts"), card("9", "clubs")),
                config,
                emptySet(),
            )

        assertEquals("Trail", evaluation.label)
        assertEquals(listOf(9), evaluation.ranks)
    }

    @Test
    fun humanWinnerMustResolveDealerTipBeforeSettlementFinalizes() {
        val service = roundService(tableType = "private_room")
        service.startRound(listOf(participant("player-1", "Alpha"), participant("player-2", "Bravo")))
        service.state.round!!.status = "active"
        service.state.round!!.activePlayerIndex = 0

        service.performAction("player-1", "pack", emptyMap())

        assertEquals("player-2", service.state.round!!.result!!.winnerId)
        assertEquals(0, service.state.round!!.result!!.payout)
        assertEquals(true, service.state.round!!.dealerTipState!!.pending)
        assertEquals(1_709, service.state.round!!.dealerTipState!!.maxAmount)
        assertEquals(1_710, service.state.round!!.dealerTipState!!.winnerReceivableBeforeTip)
        assertNotNull(service.state.round!!.nextRoundDecisionExpiresAt)
        assertEquals(service.state.round!!.nextRoundDecisionExpiresAt, service.state.round!!.dealerTipState!!.expiresAt)

        val error = assertThrows(IllegalStateException::class.java) {
            service.performAction("player-1", "dealer_tip", mapOf("amount" to 100))
        }
        assertEquals("Only the winner can submit the dealer tip.", error.message)

        service.performAction("player-2", "dealer_tip", mapOf("amount" to 100))

        assertEquals(100, service.state.round!!.result!!.dealerTip)
        assertEquals(1_610, service.state.round!!.result!!.payout)
        assertEquals(390, service.state.round!!.result!!.casinoCommissionTotal)
        assertEquals(false, service.state.round!!.dealerTipState!!.pending)
    }

    @Test
    fun dealerTipMustBeLessThanWinnerReceivableAmount() {
        val service = roundService(tableType = "private_room")
        service.startRound(listOf(participant("player-1", "Alpha"), participant("player-2", "Bravo")))
        service.state.round!!.status = "active"
        service.state.round!!.activePlayerIndex = 0
        service.performAction("player-1", "pack", emptyMap())

        val error = assertThrows(IllegalStateException::class.java) {
            service.performAction("player-2", "dealer_tip", mapOf("amount" to 1_710))
        }
        assertEquals("Dealer tip must be less than the winning amount.", error.message)
    }

    @Test
    fun dealerTipAutoSkipsAfterTimeout() {
        val scheduler = CapturingScheduler()
        val service = roundService(tableType = "private_room", scheduler = scheduler)
        service.startRound(listOf(participant("player-1", "Alpha"), participant("player-2", "Bravo")))
        service.state.round!!.status = "active"
        service.state.round!!.activePlayerIndex = 0
        service.performAction("player-1", "pack", emptyMap())

        assertEquals(true, service.state.round!!.dealerTipState!!.pending)
        assertNotNull(service.state.round!!.dealerTipState!!.expiresAt)

        scheduler.runLast()

        assertEquals(false, service.state.round!!.dealerTipState!!.pending)
        assertEquals(0, service.state.round!!.result!!.dealerTip)
        assertEquals(1_710, service.state.round!!.result!!.payout)
    }

    @Test
    fun provablyFairDealIsDeterministicAndSensitiveToInputs() {
        val config = testGameConfig("classic")
        val participants =
            listOf(
                participant("p-1", "Alpha"),
                botParticipant("bot-1", "Bot", "raj"),
                participant("p-2", "Bravo"),
            )
        val seeds = listOf(playerSeed("p-1", clientSeed("Alpha")), playerSeed("p-2", clientSeed("Bravo")))

        val first = Engine.createRoundDeal(config, participants, "round-1", "server-seed-1", seeds)
        val second = Engine.createRoundDeal(config, participants, "round-1", "server-seed-1", seeds)
        val changed = Engine.createRoundDeal(config, participants, "round-2", "server-seed-1", seeds)

        assertEquals(first.provablyFair!!.deckHash, second.provablyFair!!.deckHash)
        assertEquals(first.openingPlayerIndex, second.openingPlayerIndex)
        assertEquals(handIds(first), handIds(second))
        assertNotEquals(first.provablyFair!!.deckHash, changed.provablyFair!!.deckHash)
        assertEquals(52, Engine.createDeck().map { it.id }.distinct().count())
    }

    @Test
    fun publicRoundProvablyFairStateHidesServerSeedUntilCompletion() {
        val config = testGameConfig("classic")
        val manager = publicManager(config = config)

        val joined = joinPublic(manager, "Alpha")
        val table = managedTable(manager, joined["tableId"] as String)

        val inProgress = table.service.getTableState(joined["playerId"] as String)
        @Suppress("UNCHECKED_CAST")
        val round = inProgress["round"] as Map<String, Any?>
        val provablyFair = round["provablyFair"] as ProvablyFairState

        assertNotNull(provablyFair.serverSeedHash)
        assertNull(provablyFair.serverSeed)

        table.service.state.round!!.status = "complete"
        table.service.state.round!!.result = RoundResult()

        val completed = table.service.getTableState(joined["playerId"] as String)
        @Suppress("UNCHECKED_CAST")
        val completedRound = completed["round"] as Map<String, Any?>
        val completedProvablyFair = completedRound["provablyFair"] as ProvablyFairState

        assertEquals(table.service.state.round!!.provablyFair!!.serverSeed, completedProvablyFair.serverSeed)
    }

    @Test
    fun muflisReversesNormalHandRanking() {
        val config = testGameConfig("muflis")
        val service = roundService(config = config)
        service.startRound(listOf(participant("player-1", "Alpha"), participant("player-2", "Bravo")))
        service.state.round!!.status = "active"

        val lowHand = seat(service, "player-1")
        val highHand = seat(service, "player-2")
        setSeatCards(lowHand, card("2", "spades"), card("4", "hearts"), card("7", "clubs"))
        setSeatCards(highHand, card("A", "spades"), card("A", "hearts"), card("A", "diamonds"))

        assertTrue(Engine.compareSeatHands(lowHand, highHand, service.state.round!!, config) > 0)
    }

    @Test
    fun muflisReversesTieBreaksWithinTheSameCategory() {
        val config = testGameConfig("muflis")

        val lowerPair =
            Engine.evaluateHand(
                listOf(card("2", "spades"), card("2", "hearts"), card("3", "clubs")),
                config,
                emptySet(),
            )
        val higherPair =
            Engine.evaluateHand(
                listOf(card("A", "spades"), card("A", "hearts"), card("K", "clubs")),
                config,
                emptySet(),
            )

        assertEquals("Pair", lowerPair.label)
        assertEquals("Pair", higherPair.label)
        assertTrue(Engine.compareEvaluations(lowerPair, higherPair, config) > 0)
    }

    @Test
    fun flipperDealsThreeActiveCardsOnePublicCardAndOneReserveCard() {
        val config = testGameConfig("flipper")
        val service = roundService(config = config)
        service.startRound(listOf(participant("player-1", "Alpha"), participant("player-2", "Bravo")))

        val round = service.state.round!!
        assertEquals(2, round.variantState!!.sharedJokerCards.size)
        assertEquals(2, round.variantState!!.wildcardRanks.size)
        round.seats.forEach { seat ->
            assertEquals(3, seat.cards.size)
            assertEquals(1, seat.publicCards.size)
            assertEquals(1, seat.reserveCards.size)
            assertEquals(seat.cards[2].id, seat.publicCards[0].id)
        }
    }

    @Test
    fun flipperPackRevealsReserveCardAsAnotherJoker() {
        val config = testGameConfig("flipper")
        val service = roundService(config = config)
        service.startRound(
            listOf(
                participant("player-1", "Alpha"),
                participant("player-2", "Bravo"),
                participant("player-3", "Charlie"),
            ),
        )
        service.state.round!!.status = "active"
        setActivePlayer(service, "player-1")

        val round = service.state.round!!
        val actor = seat(service, "player-1")
        actor.reserveCards = mutableListOf(card("9", "spades"))
        val beforeCount = round.variantState!!.sharedJokerCards.size

        service.performAction("player-1", "pack", emptyMap())

        assertEquals(beforeCount + 1, round.variantState!!.sharedJokerCards.size)
        assertTrue(actor.publicCards.any { it.rank == "9" })
        assertTrue(round.variantState!!.wildcardRanks.contains("9"))
    }

    @Test
    fun jhanduUnlocksSeeingAfterFirstCycleAndRevealsFirstSharedJoker() {
        val config = testGameConfig("jhandu")
        val service = roundService(config = config)
        service.startRound(
            listOf(
                participant("player-1", "Alpha"),
                participant("player-2", "Bravo"),
                participant("player-3", "Charlie"),
            ),
        )
        service.state.round!!.status = "active"
        setActivePlayer(service, "player-1")

        val lockedError = assertThrows(IllegalStateException::class.java) {
            service.performAction("player-1", "see", emptyMap())
        }
        assertEquals("See is not allowed yet in this variant.", lockedError.message)

        service.performAction("player-1", "blind", emptyMap())
        service.performAction("player-2", "blind", emptyMap())
        service.performAction("player-3", "blind", emptyMap())

        assertEquals(1, service.state.round!!.variantState!!.cycleNumber)
        assertEquals(1, service.state.round!!.variantState!!.revealedSharedJokerCount)
        assertFalse(service.state.round!!.variantState!!.forceBlindActive)

        service.performAction("player-1", "see", emptyMap())
        assertTrue(seat(service, "player-1").seen)
    }

    @Test
    fun jhanduRevealsSharedJokersAcrossThreeCycles() {
        val config = testGameConfig("jhandu")
        val service = roundService(config = config)
        service.startRound(
            listOf(
                participant("player-1", "Alpha"),
                participant("player-2", "Bravo"),
                participant("player-3", "Charlie"),
            ),
        )
        service.state.round!!.status = "active"
        setActivePlayer(service, "player-1")

        repeat(3) {
            service.performAction("player-1", "blind", emptyMap())
            service.performAction("player-2", "blind", emptyMap())
            service.performAction("player-3", "blind", emptyMap())
        }

        val variantState = service.state.round!!.variantState!!
        assertEquals(3, variantState.cycleNumber)
        assertEquals(3, variantState.revealedSharedJokerCount)
        assertEquals(3, variantState.wildcardRanks.size)
    }

    @Test
    fun jhanduKeepsShowLockedUntilAllActivePlayersAreSeen() {
        val config = testGameConfig("jhandu")
        val service = roundService(config = config)
        service.startRound(listOf(participant("player-1", "Alpha"), participant("player-2", "Bravo")))
        service.state.round!!.status = "active"
        val round = service.state.round!!
        round.variantState!!.cycleNumber = 4
        round.variantState!!.showUnlocked = true
        round.variantState!!.forceBlindActive = false

        val first = seat(service, "player-1")
        val second = seat(service, "player-2")
        first.seen = true
        second.seen = false
        setActivePlayer(service, first.id)

        val error = assertThrows(IllegalStateException::class.java) {
            service.performAction(first.id, "show", emptyMap())
        }

        assertEquals("Show is only allowed when two players remain.", error.message)
    }

    @Test
    fun jhanduAutoAcceptsSideShowAndAutoSeesLastUnseenPlayer() {
        val config = testGameConfig("jhandu")
        val service = roundService(config = config)
        service.startRound(
            listOf(
                participant("player-1", "Alpha"),
                participant("player-2", "Bravo"),
                participant("player-3", "Charlie"),
            ),
        )
        service.state.round!!.status = "active"
        val round = service.state.round!!
        round.variantState!!.cycleNumber = 4
        round.variantState!!.showUnlocked = true
        round.variantState!!.forceBlindActive = false

        val first = seat(service, "player-1")
        val second = seat(service, "player-2")
        val third = seat(service, "player-3")
        first.seen = true
        second.seen = true
        third.seen = false
        round.variantState!!.pendingAutoSeePlayerId = third.id
        setActivePlayer(service, third.id)

        service.performAction(third.id, "blind", emptyMap())
        assertTrue(third.seen)

        round.pendingSideShow = sideShow(first.id, first.name, second.id, second.name)
        val denyError = assertThrows(IllegalStateException::class.java) {
            service.performAction(second.id, "sideshow_deny", emptyMap())
        }
        assertEquals("This variant requires the side show to be accepted.", denyError.message)

        setSeatCards(first, card("2", "spades"), card("3", "hearts"), card("5", "clubs"))
        setSeatCards(second, card("A", "spades"), card("A", "hearts"), card("K", "clubs"))
        service.performAction(second.id, "sideshow_accept", emptyMap())

        assertTrue(first.packed || second.packed)
        assertNotNull(round.recentSideShowResult)
    }
}
