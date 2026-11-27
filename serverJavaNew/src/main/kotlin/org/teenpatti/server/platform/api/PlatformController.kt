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
        val session = authenticatePlatformSession(body)
        return ApiSupport.ok(
            linkedMapOf(
                "userId" to session.userId,
                "username" to session.user.username,
                "balance" to session.user.balance,
                "currency" to session.user.currency,
                "gameId" to session.gameId,
                "gid" to session.gameId,
                "partnerCode" to session.partnerCode,
            ),
        )
    }

    @PostMapping("/session")
    fun createPublicSession(
        @RequestBody body: PlatformSessionRequest,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val session = authenticatePlatformSession(body)
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
        val session = authenticatePlatformSession(body)
        return ApiSupport.ok(
            privateRoomManager.createPlatformRoom(
                body.roomName,
                session,
                body.clientSeed,
                body.variant,
                null,
                clientIp(request),
            ),
        )
    }

    @PostMapping("/private-rooms/join")
    fun joinPrivateRoom(
        @RequestBody body: PlatformJoinPrivateRoomRequest,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val session = authenticatePlatformSession(body)
        return ApiSupport.ok(privateRoomManager.joinPlatformRoom(body.roomCode, session, body.clientSeed, clientIp(request)))
    }

    @PostMapping("/transactions/debit")
    fun debitTransactions(@RequestBody body: PlatformTransactionHistoryRequest): Map<String, Any?> {
        val session = authenticatePlatformSession(body)
        return ApiSupport.ok(platformWalletService.listTransactionHistory(session.userId, "debit", body.offset ?: 0, body.limit ?: 20))
    }

    @PostMapping("/transactions/credit")
    fun creditTransactions(@RequestBody body: PlatformTransactionHistoryRequest): Map<String, Any?> {
        val session = authenticatePlatformSession(body)
        return ApiSupport.ok(platformWalletService.listTransactionHistory(session.userId, "credit", body.offset ?: 0, body.limit ?: 20))
    }

    @PostMapping("/history/rounds")
    fun roundHistory(@RequestBody body: PlatformRoundHistoryRequest): Map<String, Any?> {
        val session = authenticatePlatformSession(body)
        return ApiSupport.ok(platformWalletService.listRoundHistory(session.userId, body.offset ?: 0, body.limit ?: 20))
    }

    private fun authenticatePlatformSession(body: PlatformLaunchRequest): PlatformSession {
        val normalizedToken = body.token?.trim().orEmpty()
        if (normalizedToken.isBlank()) {
            throw AppException.badRequest("platform_token_required", "Platform token is required.")
        }
        val normalizedPartnerCode =
            (body.partner_code ?: body.partnerCode ?: env.operationsDefaultPartnerCode)
                .trim()
                .uppercase()
        if (normalizedPartnerCode.isBlank()) {
            throw AppException.badRequest("platform_partner_code_required", "Platform partner_code is required.")
        }
        val normalizedGameId =
            parsePositiveInt(body.gid)
                ?: body.gameId?.takeIf { it > 0 }
                ?: env.platformGameId.takeIf { it > 0 }
                ?: throw AppException.badRequest("platform_game_id_required", "Platform gid is required.")

        val user =
            gatewayClient.fetchInitialBalance(
                token = normalizedToken,
                partnerCode = normalizedPartnerCode,
                fallbackUserId = body.user_id ?: body.userId,
                fallbackUsername = body.username,
                fallbackCurrency = body.currency,
            )
        val session = PlatformSession()
        session.userId = user.userId
        session.token = normalizedToken
        session.gameId = normalizedGameId
        session.partnerCode = normalizedPartnerCode
        session.user = user
        session.issuedAt = clockProvider.nowIso()
        return session
    }

    private fun parsePositiveInt(raw: Any?): Int? =
        when (raw) {
            is Number -> raw.toInt().takeIf { it > 0 }
            is String -> raw.trim().toIntOrNull()?.takeIf { it > 0 }
            else -> null
        }

    private fun clientIp(request: HttpServletRequest): String? =
        request.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: request.remoteAddr
}

internal interface PlatformLaunchRequest {
    val token: String?
    val gameId: Int?
    val gid: Any?
    val partnerCode: String?
    val partner_code: String?
    val gameCode: String?
    val game_code: String?
    val username: String?
    val userId: String?
    val user_id: String?
    val currency: String?
    val lang: String?
    val extsess: String?
}

internal class PlatformProfileRequest(
    override val token: String? = null,
    override val gameId: Int? = null,
    override val gid: Any? = null,
    override val partnerCode: String? = null,
    override val partner_code: String? = null,
    override val gameCode: String? = null,
    override val game_code: String? = null,
    override val username: String? = null,
    override val userId: String? = null,
    override val user_id: String? = null,
    override val currency: String? = null,
    override val lang: String? = null,
    override val extsess: String? = null,
) : PlatformLaunchRequest

internal class PlatformSessionRequest(
    override val token: String? = null,
    override val gameId: Int? = null,
    val clientSeed: String? = null,
    val variant: String? = null,
    override val gid: Any? = null,
    override val partnerCode: String? = null,
    override val partner_code: String? = null,
    override val gameCode: String? = null,
    override val game_code: String? = null,
    override val username: String? = null,
    override val userId: String? = null,
    override val user_id: String? = null,
    override val currency: String? = null,
    override val lang: String? = null,
    override val extsess: String? = null,
) : PlatformLaunchRequest

internal class PlatformCreatePrivateRoomRequest(
    override val token: String? = null,
    override val gameId: Int? = null,
    val roomName: String? = null,
    val clientSeed: String? = null,
    val variant: String? = null,
    val bootAmount: Int? = null,
    override val gid: Any? = null,
    override val partnerCode: String? = null,
    override val partner_code: String? = null,
    override val gameCode: String? = null,
    override val game_code: String? = null,
    override val username: String? = null,
    override val userId: String? = null,
    override val user_id: String? = null,
    override val currency: String? = null,
    override val lang: String? = null,
    override val extsess: String? = null,
) : PlatformLaunchRequest

internal class PlatformJoinPrivateRoomRequest(
    override val token: String? = null,
    override val gameId: Int? = null,
    val roomCode: String? = null,
    val clientSeed: String? = null,
    override val gid: Any? = null,
    override val partnerCode: String? = null,
    override val partner_code: String? = null,
    override val gameCode: String? = null,
    override val game_code: String? = null,
    override val username: String? = null,
    override val userId: String? = null,
    override val user_id: String? = null,
    override val currency: String? = null,
    override val lang: String? = null,
    override val extsess: String? = null,
) : PlatformLaunchRequest

internal class PlatformTransactionHistoryRequest(
    override val token: String? = null,
    override val gameId: Int? = null,
    val offset: Int? = null,
    val limit: Int? = null,
    override val gid: Any? = null,
    override val partnerCode: String? = null,
    override val partner_code: String? = null,
    override val gameCode: String? = null,
    override val game_code: String? = null,
    override val username: String? = null,
    override val userId: String? = null,
    override val user_id: String? = null,
    override val currency: String? = null,
    override val lang: String? = null,
    override val extsess: String? = null,
) : PlatformLaunchRequest

internal class PlatformRoundHistoryRequest(
    override val token: String? = null,
    override val gameId: Int? = null,
    val offset: Int? = null,
    val limit: Int? = null,
    override val gid: Any? = null,
    override val partnerCode: String? = null,
    override val partner_code: String? = null,
    override val gameCode: String? = null,
    override val game_code: String? = null,
    override val username: String? = null,
    override val userId: String? = null,
    override val user_id: String? = null,
    override val currency: String? = null,
    override val lang: String? = null,
    override val extsess: String? = null,
) : PlatformLaunchRequest
