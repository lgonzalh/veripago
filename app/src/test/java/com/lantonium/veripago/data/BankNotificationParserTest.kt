package com.lantonium.veripago.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BankNotificationParserTest {
    @Test
    fun `parses Colombian and international grouped amounts as integer COP`() {
        assertEquals(1_234L, BankNotificationParser.parseCopAmount("1.234"))
        assertEquals(1_234L, BankNotificationParser.parseCopAmount("1,234"))
        assertEquals(1_234L, BankNotificationParser.parseCopAmount("1.234,56"))
        assertEquals(1_234L, BankNotificationParser.parseCopAmount("1,234.56"))
        assertEquals(250_000L, BankNotificationParser.parseCopAmount("$ 250.000 COP"))
    }

    @Test
    fun `rejects empty zero and nonnumeric amounts`() {
        assertNull(BankNotificationParser.parseCopAmount(""))
        assertNull(BankNotificationParser.parseCopAmount("abc"))
        assertNull(BankNotificationParser.parseCopAmount("0"))
        assertNull(BankNotificationParser.parseCopAmount("0,00"))
    }

    @Test
    fun `parses amount and sender using the active bank rule`() {
        val parsed = BankNotificationParser.parse(
            "Transferencia de Juan Pérez por $ 1.234,00 recibida",
            rule(),
        )

        assertEquals(1_234L, parsed?.amount)
        assertEquals("Juan Pérez", parsed?.sender)
    }

    @Test
    fun `invalid or unmatched notification rules do not crash parsing`() {
        assertNull(BankNotificationParser.parse("sin monto", rule()))
        assertNull(BankNotificationParser.parse("$ 1.000", rule().copy(amountRegex = "[")))
    }

    @Test
    fun `idempotency keeps exact callbacks stable without merging nearby transfers`() {
        val first = BankNotificationParser.idempotencyHash("com.nequi", 50_000, 100_000, "Ana Pérez")
        val retry = BankNotificationParser.idempotencyHash("com.nequi", 50_000, 100_000, "Ana Perez")
        val nextTransfer = BankNotificationParser.idempotencyHash("com.nequi", 50_000, 101_000, "Ana Pérez")

        assertEquals(first, retry)
        assertNotEquals(first, nextTransfer)
        assertEquals(64, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `normalizes accents and punctuation in sender names`() {
        assertEquals("maria jose nunez", BankNotificationParser.normalizeName("María-José Núñez"))
    }

    @Test
    fun `reconciler prioritizes closest sender and falls back to oldest eligible order`() {
        val older = order("older", "Carlos Gómez", 100)
        val matching = order("matching", "Ana Pérez", 200)

        assertSame(matching, OrderReconciler.bestMatch(listOf(older, matching), "Ana Perez"))
        assertSame(older, OrderReconciler.bestMatch(listOf(matching, older), ""))
        assertSame(older, OrderReconciler.bestMatch(listOf(matching, older), "Persona sin relación"))
        assertNull(OrderReconciler.bestMatch(emptyList(), "Ana Perez"))
    }

    private fun rule() = BankRuleEntity(
        ruleId = "nequi",
        walletName = "Nequi",
        androidPackage = "com.nequi",
        amountRegex = """\$\s*([\d.,]+)""",
        senderRegex = """(?i)de\s+([\p{L} ]+?)\s+por""",
        isActive = true,
        updatedAt = 0,
    )

    private fun order(id: String, customer: String, createdAt: Long) = OrderEntity(
        orderId = id,
        orderCode = id,
        merchantId = "merchant",
        customerUid = null,
        customerName = customer,
        customerPhone = "",
        itemsJson = "[]",
        totalAmount = 50_000,
        checkoutChannel = "WEB_IN_SITU_QR",
        paymentStatus = "PENDING",
        deliveryStatus = "PENDING_DELIVERY",
        matchedNotificationId = null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
