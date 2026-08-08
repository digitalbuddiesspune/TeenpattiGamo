package org.teenpatti.server.platform.api

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.teenpatti.server.common.ApiSupport
import org.teenpatti.server.common.AppException
import org.teenpatti.server.common.ClockProvider
import org.teenpatti.server.config.AppEnvironment
import org.teenpatti.server.platform.PlatformGatewayClient
import org.teenpatti.server.platform.PlatformSession
import org.teenpatti.server.platform.PlatformWalletService
import org.teenpatti.server.privateroom.PrivateRoomManager
import org.teenpatti.server.publictable.PublicTableManager

@RestController
@RequestMapping("/api/platform")
internal class PlatformController(
    private val gatewayClient: PlatformGatewayClient,
    private val clockProvider: ClockProvider,
    private val env: AppEnvironment,
    private val publicTableManagers: Map<String, PublicTableManager>,
    private val privateRoomManager: PrivateRoomManager,
    private val platformWalletService: PlatformWalletService,
) {
    @PostMapping("/profile")
    fun getProfile(@RequestBody body: PlatformProfileRequest): Map<String, Any?> {
        val session = authenticatePlatformSession(body.token, body.gameId)
        return ApiSupport.ok(
            linkedMapOf(
                "userId" to session.userId,
                "username" to session.user.username,
                "balance" to session.user.balance,
                "currency" to session.user.currency,
                "gameId" to session.gameId,
            ),
        )
    }

    @PostMapping("/session")
    fun createPublicSession(
        @RequestBody body: PlatformSessionRequest,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val session = authenticatePlatformSession(body.token, body.gameId)
        val variant = ApiSupport.normalizeVariantId(body.variant)
        val manager =
            publicTableManagers[variant]
                ?: throw AppException.badRequest("unsupported_variant", "Unsupported game variant: $variant")
        return ApiSupport.ok(manager.joinPlatformPublicTable(session, body.clientSeed, clientIp(request)))
    }

    @PostMapping("/private-rooms")
    fun createPrivateRoom(
        @RequestBody body: PlatformCreatePrivateRoomRequest,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val session = authenticatePlatformSession(body.token, body.gameId)
        return ApiSupport.ok(
            privateRoomManager.createPlatformRoom(
                body.roomName,
                session,
                body.clientSeed,
                body.variant,
                body.bootAmount,
                clientIp(request),
            ),
        )
    }

    @PostMapping("/private-rooms/join")
    fun joinPrivateRoom(
        @RequestBody body: PlatformJoinPrivateRoomRequest,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val session = authenticatePlatformSession(body.token, body.gameId)
        return ApiSupport.ok(privateRoomManager.joinPlatformRoom(body.roomCode, session, body.clientSeed, clientIp(request)))
    }

    @PostMapping("/transactions/debit")
    fun debitTransactions(@RequestBody body: PlatformTransactionHistoryRequest): Map<String, Any?> {
        val session = authenticatePlatformSession(body.token, body.gameId)
        return ApiSupport.ok(platformWalletService.listTransactionHistory(session.userId, "debit", body.offset ?: 0, body.limit ?: 20))
    }

    @PostMapping("/transactions/credit")
    fun creditTransactions(@RequestBody body: PlatformTransactionHistoryRequest): Map<String, Any?> {
        val session = authenticatePlatformSession(body.token, body.gameId)
        return ApiSupport.ok(platformWalletService.listTransactionHistory(session.userId, "credit", body.offset ?: 0, body.limit ?: 20))
    }

    @PostMapping("/history/rounds")
    fun roundHistory(@RequestBody body: PlatformRoundHistoryRequest): Map<String, Any?> {
        val session = authenticatePlatformSession(body.token, body.gameId)
        return ApiSupport.ok(platformWalletService.listRoundHistory(session.userId, body.offset ?: 0, body.limit ?: 20))
    }

    private fun authenticatePlatformSession(token: String?, gameId: Int?): PlatformSession {
        val normalizedToken = token?.trim().orEmpty()
        if (normalizedToken.isBlank()) {
            throw AppException.badRequest("platform_token_required", "Platform token is required.")
        }
        val normalizedGameId =
            gameId?.takeIf { it > 0 }
                ?: env.platformGameId.takeIf { it > 0 }
                ?: throw AppException.badRequest("platform_game_id_required", "Platform game_id is required.")
        val user = gatewayClient.getUserDetail(normalizedToken)
        if (user.userId.isBlank()) {
            throw AppException.badRequest("platform_user_required", "Platform user detail did not include a user_id.")
        }
        val session = PlatformSession()
        session.userId = user.userId
        session.token = normalizedToken
        session.gameId = normalizedGameId
        session.user = user
        session.issuedAt = clockProvider.nowIso()
        return session
    }

    private fun clientIp(request: HttpServletRequest): String? =
        request.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: request.remoteAddr
}

internal class PlatformProfileRequest(
    val token: String? = null,
    val gameId: Int? = null,
)

internal class PlatformSessionRequest(
    val token: String? = null,
    val gameId: Int? = null,
    val clientSeed: String? = null,
    val variant: String? = null,
)

internal class PlatformCreatePrivateRoomRequest(
    val token: String? = null,
    val gameId: Int? = null,
    val roomName: String? = null,
    val clientSeed: String? = null,
    val variant: String? = null,
    val bootAmount: Int? = null,
)

internal class PlatformJoinPrivateRoomRequest(
    val token: String? = null,
    val gameId: Int? = null,
    val roomCode: String? = null,
    val clientSeed: String? = null,
)

internal class PlatformTransactionHistoryRequest(
    val token: String? = null,
    val gameId: Int? = null,
    val offset: Int? = null,
    val limit: Int? = null,
)

internal class PlatformRoundHistoryRequest(
    val token: String? = null,
    val gameId: Int? = null,
    val offset: Int? = null,
    val limit: Int? = null,
)
