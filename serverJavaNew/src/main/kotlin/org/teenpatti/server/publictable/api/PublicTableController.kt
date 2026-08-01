package org.teenpatti.server.publictable.api

import org.teenpatti.server.common.ApiSupport
import org.teenpatti.server.common.AppException
import org.teenpatti.server.config.AppEnvironment
import org.teenpatti.server.publictable.PublicTableManager
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/public")
internal class PublicTableController(
    private val env: AppEnvironment,
    private val publicTableManagers: Map<String, PublicTableManager>,
) {
    @GetMapping("/config")
    fun getPublicConfig(): Map<String, Any?> =
        ApiSupport.ok(
            mapOf(
                "initialBalance" to env.initialBalance,
                "bootAmount" to env.bootAmount,
                "platformEnabled" to env.platformEnabled,
            ),
        )

    @PostMapping("/join")
    fun joinPublicTable(
        @RequestParam(required = false) variant: String?,
        @RequestBody(required = false) body: PublicJoinRequest?,
    ): Map<String, Any?> =
        ApiSupport.ok(
            manager(variant).joinPublicTable(
                body?.playerName,
                body?.clientSeed,
            ),
        )

    @GetMapping("/session")
    fun getPublicSession(
        @RequestParam(required = false) variant: String?,
        @RequestParam playerId: String,
        @RequestParam playerToken: String,
    ): Map<String, Any?> = ApiSupport.ok(manager(variant).getSession(playerId, playerToken))

    @PostMapping("/action")
    fun performPublicAction(
        @RequestParam(required = false) variant: String?,
        @RequestBody body: PublicActionRequest,
    ): Map<String, Any?> =
        ApiSupport.ok(
            manager(variant).performAction(
                body.playerId,
                body.playerToken,
                body.actionType,
                body.payload,
            ),
        )

    @PostMapping("/leave")
    fun leavePublicTable(
        @RequestParam(required = false) variant: String?,
        @RequestBody body: PublicLeaveRequest,
    ): Map<String, Any?> = ApiSupport.ok(manager(variant).leave(body.playerId, body.playerToken))

    private fun manager(variant: String?): PublicTableManager {
        val variantId = ApiSupport.normalizeVariantId(variant)
        return publicTableManagers[variantId]
            ?: throw AppException.badRequest("unsupported_variant", "Unsupported game variant: $variantId")
    }
}
