package com.lantonium.veripago.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PaymentMatch(val notificationHash: String, val order: OrderEntity)

class PaymentProcessor(private val database: VeriPagoDatabase) {
    fun observeOrders(merchantId: String): Flow<List<OrderEntity>> = database.orderDao().observeMerchantOrders(merchantId)
    fun observeCustomerOrders(uid: String): Flow<List<OrderEntity>> = database.orderDao().observeCustomerOrders(uid)

    suspend fun process(
        merchantId: String,
        sourcePackage: String,
        maskedText: String,
        text: String,
        rule: BankRuleEntity,
        receivedAt: Long,
    ): PaymentMatch? {
        val transfer = BankNotificationParser.parse(text, rule) ?: return null
        val hash = BankNotificationParser.idempotencyHash(sourcePackage, transfer.amount, receivedAt, transfer.sender)
        return database.withTransaction {
            val notificationId = database.notificationDao().insert(CapturedNotificationEntity(
                idempotencyHash = hash,
                merchantId = merchantId,
                sourcePackage = sourcePackage,
                rawTextMasked = maskedText,
                extractedAmount = transfer.amount,
                extractedSender = transfer.sender,
                status = "UNMATCHED",
                receivedAt = receivedAt,
            ))
            if (notificationId == -1L) return@withTransaction null

            val candidates = database.orderDao().pendingByAmount(transfer.amount, receivedAt - ruleWindowMillis)
                .filter { it.merchantId == merchantId }
            val order = OrderReconciler.bestMatch(candidates, transfer.sender)
            var status = "UNMATCHED"
            val match = if (order != null) {
                val updated = database.orderDao().markPaid(order.orderId, "VERIFIED_AUTO", hash, receivedAt)
                if (updated == 1) {
                    status = "MATCHED"
                    database.pendingSyncDao().enqueue(PendingSyncEntity(
                        operationId = "order-${order.orderId}-$receivedAt",
                        entityName = "orders",
                        entityId = order.orderId,
                        payloadJson = JSONObject(mapOf(
                            "paymentStatus" to "VERIFIED_AUTO",
                            "matchedNotificationId" to hash,
                            "localUpdatedAt" to receivedAt,
                            "serverUpdatedAt" to "SERVER_TIMESTAMP",
                        )).toString(),
                        createdAt = receivedAt,
                    ))
                    PaymentMatch(hash, order)
                } else null
            } else null
            database.notificationDao().updateStatus(notificationId, status)
            val notificationPayload = JSONObject(mapOf(
                "merchantId" to merchantId,
                "deviceId" to android.os.Build.ID,
                "idempotencyHash" to hash,
                "sourcePackage" to sourcePackage,
                "rawTextMasked" to maskedText,
                "extractedAmount" to transfer.amount,
                "extractedSender" to transfer.sender,
                "status" to status,
                "receivedAt" to receivedAt,
                "syncedAt" to "SERVER_TIMESTAMP",
            )).toString()
            database.pendingSyncDao().enqueue(PendingSyncEntity(
                operationId = "notification-$hash",
                entityName = "payment_notifications",
                entityId = hash,
                payloadJson = notificationPayload,
                createdAt = receivedAt,
            ))
            match
        }
    }

    suspend fun markDelivered(orderId: String) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            database.orderDao().markDelivered(orderId, now)
            database.pendingSyncDao().enqueue(PendingSyncEntity(
                operationId = "delivery-$orderId-$now",
                entityName = "orders",
                entityId = orderId,
                payloadJson = JSONObject(mapOf(
                    "deliveryStatus" to "DELIVERED",
                    "localUpdatedAt" to now,
                    "serverUpdatedAt" to "SERVER_TIMESTAMP",
                )).toString(),
                createdAt = now,
            ))
        }
    }

    suspend fun markItemDelivered(orderId: String, itemIndex: Int) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val order = database.orderDao().getById(orderId) ?: error("No se encontró el pedido.")
            if (!order.paymentStatus.startsWith("VERIFIED")) error("Solo puedes marcar productos de pedidos con pago verificado.")
            val itemCount = JSONArray(order.itemsJson).length()
            if (itemIndex !in 0 until itemCount) error("No se encontró el producto del pedido.")
            val deliveredIndexes = JSONArray(order.deliveredItemIndexesJson)
                .let { json -> (0 until json.length()).mapNotNull { index -> json.optInt(index, -1).takeIf { it >= 0 } }.toMutableSet() }
            if (itemIndex in deliveredIndexes) return@withTransaction
            deliveredIndexes += itemIndex
            val updatedIndexes = deliveredIndexes.sorted()
            val indexesJson = JSONArray(updatedIndexes).toString()
            database.orderDao().updateDeliveredItemIndexes(orderId, indexesJson, now)
            database.pendingSyncDao().enqueue(PendingSyncEntity(
                operationId = "item-delivery-$orderId-$itemIndex-$now",
                entityName = "orders",
                entityId = orderId,
                payloadJson = JSONObject(mapOf(
                    "deliveredItemIndexes" to updatedIndexes,
                    "localUpdatedAt" to now,
                    "serverUpdatedAt" to "SERVER_TIMESTAMP",
                )).toString(),
                createdAt = now,
            ))
        }
    }

    companion object { private const val ruleWindowMillis = 20 * 60 * 1000L }
}
