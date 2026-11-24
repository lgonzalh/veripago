package com.lantonium.veripago.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MerchantPricingTest {
    @Test
    fun `calculates VAT when entered price excludes tax`() {
        assertEquals(19.0, parseVatRate("19"))
        assertEquals(ProductPriceBreakdown(100_000, 19_000, 119_000), calculateProductPricing(100_000, 19.0, false))
    }

    @Test
    fun `extracts VAT when entered price already includes tax`() {
        assertEquals(ProductPriceBreakdown(84_034, 15_966, 100_000), calculateProductPricing(100_000, 19.0, true))
    }

    @Test
    fun `rejects VAT outside zero to one hundred percent`() {
        assertNull(parseVatRate("-1"))
        assertNull(parseVatRate("100.1"))
        assertEquals(0.0, parseVatRate(""))
    }

    @Test
    fun `applies per-product and per-order adjustments to a multi-item total`() {
        val lines = listOf(
            ChargeDraftLine(name = "Café", unitPriceText = "10000", quantityText = "2"),
            ChargeDraftLine(name = "Pan", unitPriceText = "5000", quantityText = "1"),
        )
        val perProduct = parseAdjustmentDraft(enabled = true, mode = "PERCENT", raw = "10")
        val perOrder = parseAdjustmentDraft(enabled = true, mode = "FIXED", raw = "2000")

        assertEquals(11_000L, adjustedUnitPrice(10_000L, perProduct))
        assertEquals(29_500L, calculateChargeTotal(lines, perProduct, perOrder))
    }

    @Test
    fun `rejects an enabled zero fee and percentages above one hundred`() {
        assertThrows(IllegalStateException::class.java) { parseAdjustmentDraft(true, "FIXED", "0") }
        assertThrows(IllegalStateException::class.java) { parseAdjustmentDraft(true, "PERCENT", "100.01") }
    }

    @Test
    fun `normalizes Colombian WhatsApp numbers and rejects invalid values`() {
        assertEquals("+573001234567", normalizeWhatsappNumber("300 123 4567"))
        assertEquals("+573001234567", normalizeWhatsappNumber("+57 300 123 4567"))
        assertNull(normalizeWhatsappNumber("12345"))
    }

    @Test
    fun `enables wallets only when their app is installed and Bre-B has a supported wallet`() {
        assertFalse(isWalletProviderAvailable("NEQUI", emptySet()))
        assertFalse(isWalletProviderAvailable("DAVIPLATA", emptySet()))
        assertFalse(isWalletProviderAvailable("BRE_B", emptySet()))

        val nequiOnly = setOf("com.nequi.MobileApp")
        assertTrue(isWalletProviderAvailable("NEQUI", nequiOnly))
        assertFalse(isWalletProviderAvailable("DAVIPLATA", nequiOnly))
        assertTrue(isWalletProviderAvailable("BRE_B", nequiOnly))

        val daviplataOnly = setOf("com.davivienda.daviplataapp")
        assertFalse(isWalletProviderAvailable("NEQUI", daviplataOnly))
        assertTrue(isWalletProviderAvailable("DAVIPLATA", daviplataOnly))
        assertTrue(isWalletProviderAvailable("BRE_B", daviplataOnly))
    }
}
