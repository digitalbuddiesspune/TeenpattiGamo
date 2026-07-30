package org.teenpatti.server.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class ApiSupportTest {
    @Test
    fun normalizeVariantIdDefaultsBlankValuesToClassic() {
        assertEquals("classic", ApiSupport.normalizeVariantId(null))
        assertEquals("classic", ApiSupport.normalizeVariantId(""))
        assertEquals("classic", ApiSupport.normalizeVariantId("   "))
    }

    @Test
    fun normalizeVariantIdKeepsVariationAliasesCompatible() {
        assertEquals("ak47", ApiSupport.normalizeVariantId("variation"))
        assertEquals("ak47", ApiSupport.normalizeVariantId("variations"))
        assertEquals("ak47", ApiSupport.normalizeVariantId(" Variations "))
    }

    @Test
    fun normalizeVariantIdPassesThroughExplicitVariantIds() {
        assertEquals("classic", ApiSupport.normalizeVariantId("classic"))
        assertEquals("muflis", ApiSupport.normalizeVariantId("muflis"))
        assertEquals("jhandu", ApiSupport.normalizeVariantId("JHANDU"))
    }
}
