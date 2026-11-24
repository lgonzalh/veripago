package com.lantonium.veripago.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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

        val productsListener = firestore.collection("users").document(merchantId).collection("products")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                scope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val productId = change.document.id
                        val pendingId = "$merchantId|$productId"
                        if (database.pendingSyncDao().hasPending("merchant_products", pendingId) > 0) return@forEach
                        if (change.type.name == "REMOVED") {
                            database.merchantProductDao().deleteRemote(merchantId, productId)
                        } else {
                            val doc = change.document
                            val name = doc.getString("name") ?: return@forEach
                            database.merchantProductDao().upsert(MerchantProductEntity(
                                merchantId = merchantId,
                                productId = productId,
                                name = name,
                                enteredPrice = doc.getLong("enteredPrice") ?: 0L,
                                vatRate = (doc.get("vatRate") as? Number)?.toDouble() ?: 0.0,
                                priceIncludesVat = doc.getBoolean("priceIncludesVat") ?: false,
                                priceWithoutVat = doc.getLong("priceWithoutVat") ?: 0L,
                                vatAmount = doc.getLong("vatAmount") ?: 0L,
                                priceWithVat = doc.getLong("priceWithVat") ?: 0L,
                                updatedAt = System.currentTimeMillis(),
                            ))
                        }
                    }
                }
            }
        val settingsListener = firestore.collection("users").document(merchantId).collection("settings")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                scope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val settingId = change.document.id
                        if (settingId !in setOf("fees", "contact")) return@forEach
                        if (database.pendingSyncDao().hasPending("merchant_settings", "$merchantId|$settingId") > 0) return@forEach
                        if (change.type.name == "REMOVED") {
                            database.merchantSettingDao().delete(merchantId, settingId)
                        } else {
                            val values = change.document.data.orEmpty().filterKeys { it != "updatedAt" }
                            database.merchantSettingDao().upsert(MerchantSettingEntity(
                                merchantId = merchantId,
                                settingId = settingId,
                                payloadJson = JSONObject(values).toString(),
                                updatedAt = System.currentTimeMillis(),
                            ))
                        }
                    }
                }
            }
        listeners += productsListener
        listeners += settingsListener

        val qrListener = firestore.collection("users").document(merchantId).collection("wallet_qrs")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                scope.launch {
                    snapshot.documentChanges.forEach { change ->
                        val provider = change.document.id
                        if (database.pendingSyncDao().hasPending("merchant_qrs", "$merchantId|$provider") > 0) return@forEach
                        if (change.type.name == "REMOVED") database.merchantQrDao().delete(merchantId, provider)
                        else change.document.toMerchantQrEntity(merchantId)?.let { database.merchantQrDao().upsert(it) }
                    }
                }
            }
        listeners += qrListener
    }

    suspend fun uploadPendingBatch(): Boolean = withContext(Dispatchers.IO) {
        val firestore = FirebaseClients.firestore ?: return@withContext false
        val dao = database.pendingSyncDao()
        val batch = dao.nextBatch()
        for (operation in batch) {
            try {
                val payload = JSONObject(operation.payloadJson)
                when (operation.entityName) {
                    "orders", "payment_notifications" -> {
                        val map = payload.toMap().mapValues { (_, value) ->
                            if (value == "SERVER_TIMESTAMP") FieldValue.serverTimestamp() else value
                        }
                        if (operation.entityName == "orders") firestore.collection("orders").document(operation.entityId).update(map).await()
                        else firestore.collection("payment_notifications").document(operation.entityId).set(map).await()
                    }
                    "merchant_products" -> {
                        val merchantId = payload.getString("merchantId")
                        val productId = payload.getString("productId")
                        val reference = firestore.collection("users").document(merchantId).collection("products").document(productId)
                        if (payload.optBoolean("deleted")) reference.delete().await()
                        else reference.set(payload.getJSONObject("data").toMap()).await()
                    }
                    "merchant_settings" -> uploadMerchantSetting(firestore, payload)
                    "merchant_qrs" -> uploadMerchantQr(firestore, payload)
                }
                dao.acknowledge(operation.operationId)
            } catch (_: Exception) {
                dao.recordRetry(operation.operationId)
                return@withContext false
            }
        }
        true
    }

    private suspend fun uploadMerchantSetting(firestore: com.google.firebase.firestore.FirebaseFirestore, payload: JSONObject) {
        val merchantId = payload.getString("merchantId")
        val settingId = payload.getString("settingId")
        val data = payload.getJSONObject("data").toMap()
        val setting = firestore.collection("users").document(merchantId).collection("settings").document(settingId)
        if (settingId != "fees") {
            setting.set(data + ("updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge()).await()
            return
        }

        val perProduct = data["perProduct"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val perOrder = data["perOrder"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val adjustments = mapOf("perProduct" to perProduct, "perOrder" to perOrder)
        val links = firestore.collection("payment_links").whereEqualTo("merchantId", merchantId).get().await()
        val batch = firestore.batch()
        batch.set(setting, data + ("updatedAt" to FieldValue.serverTimestamp()), SetOptions.merge())
        links.documents.forEach { link ->
            val slug = link.getString("slug")?.trim().orEmpty()
            if (slug.isBlank()) error("No se pudo sincronizar un enlace de cobro del negocio.")
            val updatedAt = FieldValue.serverTimestamp()
            batch.update(link.reference, mapOf("adjustments" to adjustments, "updatedAt" to updatedAt))
            batch.update(firestore.collection("public_payment_links").document(slug), mapOf("adjustments" to adjustments, "updatedAt" to updatedAt))
        }
        batch.commit().await()
    }

    private suspend fun uploadMerchantQr(firestore: com.google.firebase.firestore.FirebaseFirestore, payload: JSONObject) {
        val merchantId = payload.getString("merchantId")
        val provider = payload.getString("provider")
        val data = payload.getJSONObject("data").toMap()
        val keyLabel = data["paymentKeyLabel"] as? String ?: "QR $provider"
        val beneficiary = data["beneficiaryName"] as? String ?: ""
        val qrReference = "veripagoqr://$merchantId/$provider"
        val method = mapOf(
            "provider" to provider,
            "qrImageUrl" to qrReference,
            "paymentKeyLabel" to keyLabel,
            "beneficiaryName" to beneficiary,
        )
        val links = firestore.collection("payment_links")
            .whereEqualTo("merchantId", merchantId)
            .whereEqualTo("defaultMode", "IN_SITU_QR")
            .whereEqualTo("isActive", true)
            .get().await()
        val batch = firestore.batch()
        val qrData = data + ("updatedAt" to FieldValue.serverTimestamp())
        batch.set(
            firestore.collection("users").document(merchantId).collection("wallet_qrs").document(provider),
            qrData,
        )
        batch.set(
            firestore.collection("public_merchant_qrs").document(merchantId).collection("wallets").document(provider),
            qrData,
        )
        links.documents.forEach { link ->
            val priorMethods = (link.get("paymentMethods") as? List<*>)?.mapNotNull { it as? Map<*, *> }?.map { prior ->
                mapOf(
                    "provider" to (prior["provider"] as? String ?: ""),
                    "qrImageUrl" to (prior["qrImageUrl"] as? String ?: ""),
                    "paymentKeyLabel" to (prior["paymentKeyLabel"] as? String ?: ""),
                    "beneficiaryName" to (prior["beneficiaryName"] as? String ?: ""),
                )
            }.orEmpty()
            val methods = priorMethods.filterNot { it["provider"] == provider } + method
            require(methods.size <= 3) { "Ya configuraste tres billeteras. Cambia uno de esos QR para continuar." }
            val update = mapOf(
                "paymentMethods" to methods,
                "paymentProvider" to provider,
                "qrImageUrl" to qrReference,
                "paymentKeyLabel" to keyLabel,
                "beneficiaryName" to beneficiary,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            batch.update(link.reference, update)
            val slug = link.getString("slug")?.takeIf { it.isNotBlank() }
                ?: error("No se pudo actualizar la vista de cobro del comercio.")
            batch.update(firestore.collection("public_payment_links").document(slug), update)
        }
        batch.commit().await()
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
        val deliveredItemIndexesJson = JSONArray(
            ((get("deliveredItemIndexes") as? List<*>) ?: emptyList<Any>()).mapNotNull { (it as? Number)?.toInt() },
        ).toString()
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
            deliveredItemIndexesJson = deliveredItemIndexesJson,
        )
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toMerchantQrEntity(merchantId: String): MerchantQrEntity? {
        val provider = getString("provider") ?: return null
        val imageBase64 = getString("imageBase64") ?: return null
        return MerchantQrEntity(
            merchantId = merchantId,
            provider = provider,
            contentType = getString("contentType") ?: "image/png",
            imageBase64 = imageBase64,
            paymentKeyLabel = getString("paymentKeyLabel").orEmpty(),
            beneficiaryName = getString("beneficiaryName").orEmpty(),
            updatedAt = getTimestamp("updatedAt")?.toDate()?.time ?: System.currentTimeMillis(),
        )
    }

    private fun org.json.JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { key ->
        when (val value = get(key)) {
            org.json.JSONObject.NULL -> null
            is org.json.JSONObject -> value.toMap()
            is JSONArray -> (0 until value.length()).map { index -> value.get(index) }
            else -> value
        }
    }
}


