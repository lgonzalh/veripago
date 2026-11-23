package com.lantonium.veripago.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray

class FirestoreSyncManager(private val database: VeriPagoDatabase) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = mutableListOf<ListenerRegistration>()

    fun start(merchantId: String) {
        val firestore = FirebaseClients.firestore ?: return
        listeners.forEach(ListenerRegistration::remove)
        listeners.clear()
        val orderListener = firestore.collection("orders")
            .whereEqualTo("merchantId", merchantId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(200)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                scope.launch {
                    snapshot.documents.forEach { remote ->
                        if (database.pendingSyncDao().hasPending("orders", remote.id) == 0) {
                            remote.toOrderEntity()?.let { database.orderDao().upsert(it) }
                        }
                    }
                }
            }
        val ruleListener = firestore.collection("bank_parser_rules")
            .whereEqualTo("isActive", true)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                scope.launch {
                    val rules = snapshot.documents.mapNotNull { doc ->
                        val name = doc.getString("walletName") ?: return@mapNotNull null
                        BankRuleEntity(
                            ruleId = doc.id,
                            walletName = name,
                            androidPackage = doc.getString("androidPackage").orEmpty(),
                            amountRegex = doc.getString("amountRegex").orEmpty(),
                            senderRegex = doc.getString("senderRegex").orEmpty(),
                            isActive = doc.getBoolean("isActive") ?: false,
                            updatedAt = doc.getTimestamp("updatedAt")?.toDate()?.time ?: 0L,
                        )
                    }
                    database.bankRuleDao().upsertAll(rules)
                }
            }
        listeners += orderListener
        listeners += ruleListener
    }

    suspend fun uploadPendingBatch(): Boolean = withContext(Dispatchers.IO) {
        val firestore = FirebaseClients.firestore ?: return@withContext false
        val dao = database.pendingSyncDao()
        val batch = dao.nextBatch()
        for (operation in batch) {
            try {
                val payload = org.json.JSONObject(operation.payloadJson)
                val map = payload.toMap().mapValues { (_, value) ->
                    if (value == "SERVER_TIMESTAMP") com.google.firebase.firestore.FieldValue.serverTimestamp() else value
                }
                when (operation.entityName) {
                    "orders" -> firestore.collection("orders").document(operation.entityId).update(map).await()
                    "payment_notifications" -> firestore.collection("payment_notifications").document(operation.entityId).set(map).await()
                }
                dao.acknowledge(operation.operationId)
            } catch (_: Exception) {
                dao.recordRetry(operation.operationId)
                return@withContext false
            }
        }
        true
    }

    fun stop() {
        listeners.forEach(ListenerRegistration::remove)
        listeners.clear()
        scope.cancel()
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toOrderEntity(): OrderEntity? {
        val merchantId = getString("merchantId") ?: return null
        val itemList = get("items") as? List<*> ?: emptyList<Any>()
        val itemsJson = JSONArray(itemList.mapNotNull { it as? Map<*, *> }).toString()
        val createdAt = (get("createdAt") as? Timestamp)?.toDate()?.time ?: System.currentTimeMillis()
        return OrderEntity(
            orderId = id,
            orderCode = getString("orderCode").orEmpty(),
            merchantId = merchantId,
            customerUid = getString("customerUid"),
            customerName = getString("customerName").orEmpty(),
            customerPhone = getString("customerPhone").orEmpty(),
            itemsJson = itemsJson,
            totalAmount = getLong("totalAmount") ?: 0L,
            checkoutChannel = getString("checkoutChannel").orEmpty(),
            paymentStatus = getString("paymentStatus") ?: "PENDING",
            deliveryStatus = getString("deliveryStatus") ?: "PENDING_DELIVERY",
            matchedNotificationId = getString("matchedNotificationId"),
            createdAt = createdAt,
            updatedAt = (get("serverUpdatedAt") as? Timestamp)?.toDate()?.time ?: createdAt,
        )
    }

    private fun org.json.JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { key ->
        when (val value = get(key)) {
            org.json.JSONObject.NULL -> null
            else -> value
        }
    }
}
