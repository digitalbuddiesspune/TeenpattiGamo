package org.teenpatti.server.common

internal object ApiSupport {
    @JvmStatic
    fun normalizeVariantId(rawVariant: String?): String {
        val variantId = rawVariant?.trim()?.lowercase() ?: "classic"
        if (variantId.isBlank()) {
            return "classic"
        }
        if (variantId == "variation" || variantId == "variations") {
            return "ak47"
        }
        return variantId
    }

    @JvmStatic
    fun errorCode(error: Throwable): String =
        if (error is AppException) {
            error.code
        } else {
            "request_failed"
        }

    @JvmStatic
    fun ok(data: Any?): Map<String, Any?> = mapOf("status" to "ok", "data" to data)
}
