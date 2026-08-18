package org.teenpatti.server.config

import org.teenpatti.server.common.ClockProvider
import org.teenpatti.server.common.IdGenerator
import org.teenpatti.server.common.RandomSource
import org.teenpatti.server.common.Scheduler
import org.teenpatti.server.game.PublicTableRealtimeGateway
import org.teenpatti.server.infrastructure.persistence.PrivateRoomRepository
import org.teenpatti.server.infrastructure.persistence.PublicSessionRepository
import org.teenpatti.server.infrastructure.persistence.RoundHistoryRepository
import org.teenpatti.server.infrastructure.persistence.TableAggregateRepository
import org.teenpatti.server.infrastructure.realtime.RedisPresenceService
import org.teenpatti.server.privateroom.PrivateRoomManager
import org.teenpatti.server.privateroom.PrivateRoomRealtimeGateway
import org.teenpatti.server.platform.PlatformWalletService
import org.teenpatti.server.publictable.PublicTableManager
import org.teenpatti.server.publictable.MatchmakingCoordinator
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
internal class GameRuntimeConfig {
    @Bean
    fun gameVariantConfigs(env: AppEnvironment): Map<String, GameConfig> =
        mapOf(
            "classic" to createGameConfig(env, env.tableId, "classic", "Classic", emptyList()),
            "ak47" to createGameConfig(env, "${env.tableId}-ak47", "ak47", "AK47", listOf("A", "K", "4", "7")),
            "muflis" to createGameConfig(env, "${env.tableId}-muflis", "muflis", "Muflis", emptyList(), evaluationMode = "lowball"),
            "flipper" to createGameConfig(
                env,
                "${env.tableId}-flipper",
                "flipper",
                "Flipper",
                emptyList(),
                cardsPerSeat = 4,
                publicCardMode = "flipper_blue_card",
            ),
            "jhandu" to createGameConfig(
                env,
                "${env.tableId}-jhandu",
                "jhandu",
                "Jhandu",
                emptyList(),
                sharedJokerMode = "progressive_three",
                forceBlindCycles = 1,
                showUnlockCycle = 4,
                showRequiresAllSeen = true,
                autoAcceptSideshow = true,
            ),
        )

    @Bean
    fun publicTableManagers(
        env: AppEnvironment,
        gameVariantConfigs: Map<String, GameConfig>,
        tableRepository: TableAggregateRepository,
        publicSessionRepository: PublicSessionRepository,
        roundHistoryRepository: RoundHistoryRepository,
        clockProvider: ClockProvider,
        idGenerator: IdGenerator,
        randomSource: RandomSource,
        scheduler: Scheduler,
        publicTableRealtimeGateway: PublicTableRealtimeGateway,
        redisPresenceService: RedisPresenceService,
        matchmakingCoordinator: MatchmakingCoordinator,
        platformWalletService: PlatformWalletService,
        @Qualifier("appNodeId") appNodeId: String,
    ): Map<String, PublicTableManager> =
        gameVariantConfigs.entries.associate { (key, value) ->
            val manager =
                PublicTableManager(
                    value,
                    tableRepository,
                    publicSessionRepository,
                    roundHistoryRepository,
                    clockProvider,
                    idGenerator,
                    randomSource,
                    scheduler,
                    publicTableRealtimeGateway,
                    redisPresenceService::isConnected,
                    env.reconnectGraceMs,
                    appNodeId,
                    platformWalletService,
                    matchmakingCoordinator,
                    env.matchmakingWindowMs,
                    env.matchmakingPvpThreshold,
                )
            manager.initialize()
            key to manager
        }

    @Bean
    fun privateRoomManager(
        env: AppEnvironment,
        privateRoomRepository: PrivateRoomRepository,
        gameVariantConfigs: Map<String, GameConfig>,
        roundHistoryRepository: RoundHistoryRepository,
        privateRoomRealtimeGateway: PrivateRoomRealtimeGateway,
        clockProvider: ClockProvider,
        idGenerator: IdGenerator,
        randomSource: RandomSource,
        scheduler: Scheduler,
        platformWalletService: PlatformWalletService,
        @Qualifier("appNodeId") appNodeId: String,
    ): PrivateRoomManager {
        val manager =
            PrivateRoomManager(
                privateRoomRepository,
                gameVariantConfigs.getValue("classic"),
                gameVariantConfigs,
                roundHistoryRepository,
                privateRoomRealtimeGateway,
                clockProvider,
                idGenerator,
                randomSource,
                scheduler,
                env.reconnectGraceMs,
                env.privateRoomTtlMs,
                appNodeId,
                platformWalletService,
            )
        manager.initialize()
        return manager
    }

    private fun createGameConfig(
        env: AppEnvironment,
        tableId: String,
        variantId: String,
        label: String,
        wildcards: List<String>,
        evaluationMode: String = "standard",
        cardsPerSeat: Int = 3,
        publicCardMode: String = "none",
        sharedJokerMode: String = "none",
        forceBlindCycles: Int = 0,
        showUnlockCycle: Int = 0,
        showRequiresAllSeen: Boolean = false,
        autoAcceptSideshow: Boolean = false,
    ): GameConfig {
        val variant = VariantConfig()
        variant.id = variantId
        variant.label = label
        variant.wildcardRanks = wildcards.toMutableList()
        variant.evaluationMode = evaluationMode
        variant.cardsPerSeat = cardsPerSeat
        variant.publicCardMode = publicCardMode
        variant.sharedJokerMode = sharedJokerMode
        variant.forceBlindCycles = forceBlindCycles
        variant.showUnlockCycle = showUnlockCycle
        variant.showRequiresAllSeen = showRequiresAllSeen
        variant.autoAcceptSideshow = autoAcceptSideshow

        val autoplay = AutoplayConfig()
        autoplay.defaultRounds = 10
        autoplay.maxRounds = 100
        autoplay.maxProfitTarget = 1_000_000
        autoplay.maxLossLimit = 1_000_000

        val botDelay = BotActionDelayConfig()
        botDelay.min = 900
        botDelay.max = 1800

        val config = GameConfig()
        config.tableId = tableId
        config.bootAmount = env.bootAmount
        config.maxPotAmount = env.maxPotAmount
        config.minStake = env.minStake
        config.maxStake = env.maxStake
        config.maxRoundsBeforeForcedShow = env.maxRoundsBeforeForcedShow
        config.playerCount = env.playerCount
        config.publicTableMaxBots = env.publicTableMaxBots
        config.casinoBootCommissionPercent = env.casinoBootCommissionPercent
        config.casinoWinCommissionPercent = env.casinoWinCommissionPercent
        config.maxBalance = 500000
        config.initialBalance = env.initialBalance
        config.turnDurationMs = env.turnDurationMs
        config.blindSeenMultiplier = 2
        config.blindRaiseMultiplier = 2
        config.seenRaiseMultiplier = 4
        config.allowA23Sequence = true
        config.allowAkqSequence = true
        config.sequenceRankingMode = "AKQ_HIGH_A23_SECOND"
        config.botDecisionMode = "expert_public"
        config.botMaxSimulations = 1500
        config.botMaxDecisionTimeMs = 40
        config.botHeadsUpSeeAfterBlindTurns = 1
        config.autoplay = autoplay
        config.botActionDelayMs = botDelay
        config.variant = variant
        return config
    }
}
