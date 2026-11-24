package com.lantonium.veripago.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaymentProcessorRoomTest {
    private lateinit var database: VeriPagoDatabase
    private lateinit var processor: PaymentProcessor

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, VeriPagoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        processor = PaymentProcessor(database)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun matchingIsAtomicAndRepeatedNotificationIsIdempotent() = runBlocking {
        val receivedAt = 1_800_000_000_000L
        database.orderDao().upsert(order("order-1", "Ana Pérez", receivedAt - 5_000L))

        val first = processor.process(
            merchantId = "merchant-1",
            sourcePackage = "com.nequi",
            maskedText = "Transferencia por $50.000, remitente [oculto]",
            text = "Transferencia de Ana Pérez por $ 50.000 recibida",
            rule = rule(),
            receivedAt = receivedAt,
        )
        val retry = processor.process(
            merchantId = "merchant-1",
            sourcePackage = "com.nequi",
            maskedText = "Transferencia por $50.000, remitente [oculto]",
            text = "Transferencia de Ana Pérez por $ 50.000 recibida",
            rule = rule(),
            receivedAt = receivedAt,
        )

        assertNotNull(first)
        assertNull(retry)
        assertEquals("VERIFIED_AUTO", database.orderDao().observeMerchantOrders("merchant-1").first().single().paymentStatus)
        assertEquals(1, database.notificationDao().observeAll().first().size)
        assertEquals(2, database.pendingSyncDao().nextBatch().size)
    }

    @Test
    fun distinctNearbySameAmountTransferIsRetainedForManualReview() = runBlocking {
        val receivedAt = 1_800_000_000_000L
        database.orderDao().upsert(order("order-1", "Ana Pérez", receivedAt - 5_000L))

        val matched = processor.process(
            merchantId = "merchant-1",
            sourcePackage = "com.nequi",
            maskedText = "Transferencia por $50.000, remitente [oculto]",
            text = "Transferencia de Ana Pérez por $ 50.000 recibida",
            rule = rule(),
            receivedAt = receivedAt,
        )
        val second = processor.process(
            merchantId = "merchant-1",
            sourcePackage = "com.nequi",
            maskedText = "Transferencia por $50.000, remitente [oculto]",
            text = "Transferencia de Ana Pérez por $ 50.000 recibida",
            rule = rule(),
            receivedAt = receivedAt + 1_000L,
        )

        assertNotNull(matched)
        assertNull(second)
        assertEquals(2, database.notificationDao().observeAll().first().size)
        assertEquals(listOf("UNMATCHED", "MATCHED"), database.notificationDao().observeAll().first().map { it.status })
        assertEquals(3, database.pendingSyncDao().nextBatch().size)
    }

    @Test
    fun deliveredProductIsStoredLocallyAndQueuedWithoutCompletingOrder() = runBlocking {
        val receivedAt = 1_800_000_000_000L
        database.orderDao().upsert(
            order("order-delivery", "Ana Pérez", receivedAt).copy(
                itemsJson = """[{"name":"Café","qty":1},{"name":"Pan","qty":2}]""",
                paymentStatus = "VERIFIED_AUTO",
            ),
        )

        processor.markItemDelivered("order-delivery", 0)

        val saved = database.orderDao().observeMerchantOrders("merchant-1").first().single()
        assertEquals("[0]", saved.deliveredItemIndexesJson)
        assertEquals("PENDING_DELIVERY", saved.deliveryStatus)
        val queued = database.pendingSyncDao().nextBatch().single()
        assertEquals("orders", queued.entityName)
        assertEquals("order-delivery", queued.entityId)
        assertEquals("[0]", org.json.JSONObject(queued.payloadJson).getJSONArray("deliveredItemIndexes").toString())
    }

    @Test
    fun orderCannotBeClosedUntilAllProductsAreDelivered() = runBlocking {
        val receivedAt = 1_800_000_000_000L
        database.orderDao().upsert(
            order("order-completion", "Ana Pérez", receivedAt).copy(
                itemsJson = """[{"name":"Café","qty":1},{"name":"Pan","qty":2}]""",
                deliveredItemIndexesJson = "[0]",
                paymentStatus = "VERIFIED_AUTO",
            ),
        )

        val earlyCompletion = runCatching { processor.markDelivered("order-completion") }
        assertEquals(true, earlyCompletion.isFailure)
        assertEquals("PENDING_DELIVERY", database.orderDao().observeMerchantOrders("merchant-1").first().single().deliveryStatus)

        database.orderDao().updateDeliveredItemIndexes("order-completion", "[0,1]", receivedAt + 1)
        processor.markDelivered("order-completion")

        assertEquals("DELIVERED", database.orderDao().observeMerchantOrders("merchant-1").first().single().deliveryStatus)
    }

    private fun rule() = BankRuleEntity(
        ruleId = "nequi",
        walletName = "Nequi",
        androidPackage = "com.nequi",
        amountRegex = """\$\s*([\d.,]+)""",
        senderRegex = """(?i)de\s+([\p{L} ]+?)\s+por""",
        isActive = true,
        updatedAt = 0L,
    )

    private fun order(id: String, customer: String, createdAt: Long) = OrderEntity(
        orderId = id,
        orderCode = id,
        merchantId = "merchant-1",
        customerUid = null,
        customerName = customer,
        customerPhone = "",
        itemsJson = "[]",
        totalAmount = 50_000L,
        checkoutChannel = "WEB_IN_SITU_QR",
        paymentStatus = "PENDING",
        deliveryStatus = "PENDING_DELIVERY",
        matchedNotificationId = null,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
