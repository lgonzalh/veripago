package com.lantonium.veripago.data

import com.lantonium.veripago.AppGraph
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import androidx.room.withTransaction
import org.json.JSONObject

data class MerchantQrDraft(
    val provider: String,
    val contentType: String,
    val imageBase64: String,
    val paymentKeyLabel: String,
    val beneficiaryName: String,
)

class MerchantQrRepository(
    private val database: VeriPagoDatabase = AppGraph.database,
    private val firestore: FirebaseFirestore? = FirebaseClients.firestore,
) {
    fun observe(merchantId: String): Flow<List<MerchantQrEntity>> = database.merchantQrDao().observe(merchantId)

    suspend fun loadAll(merchantId: String): List<MerchantQrEntity> {
        val db = firestore
        if (db != null) {
            runCatching { db.collection("users").document(merchantId).collection("wallet_qrs").get().await() }
                .onSuccess { snapshot ->
                    snapshot.documents.forEach { remote ->
                        val provider = remote.getString("provider") ?: return@forEach
                        if (database.pendingSyncDao().hasPending("merchant_qrs", "$merchantId|$provider") == 0) {
                            remote.toMerchantQrEntity(merchantId)?.let { database.merchantQrDao().upsert(it) }
                        }
                    }
                }.onFailure { if (database.merchantQrDao().getAll(merchantId).isEmpty()) throw it }
        }
        return database.merchantQrDao().getAll(merchantId)
    }

    suspend fun save(merchantId: String, qr: MerchantQrDraft): Boolean {
        require(qr.provider in setOf("NEQUI", "DAVIPLATA", "BRE_B")) { "Selecciona un medio de pago válido." }
        require(qr.contentType == "image/png") { "El código QR debe estar preparado como imagen PNG." }
        require(qr.imageBase64.isNotBlank() && qr.imageBase64.length <= 750_000) {
            "La imagen del QR es demasiado grande. Elige una imagen más pequeña e inténtalo de nuevo."
        }
        val entity = MerchantQrEntity(
            merchantId = merchantId,
            provider = qr.provider,
            contentType = qr.contentType,
            imageBase64 = qr.imageBase64,
            paymentKeyLabel = qr.paymentKeyLabel.take(100),
            beneficiaryName = qr.beneficiaryName.take(100),
            updatedAt = System.currentTimeMillis(),
        )
        val data = JSONObject()
            .put("provider", entity.provider)
            .put("contentType", entity.contentType)
            .put("imageBase64", entity.imageBase64)
            .put("paymentKeyLabel", entity.paymentKeyLabel)
            .put("beneficiaryName", entity.beneficiaryName)
        database.withTransaction {
            database.merchantQrDao().upsert(entity)
            database.pendingSyncDao().enqueue(PendingSyncEntity(
                operationId = "merchant-qr:$merchantId:${qr.provider}",
                entityName = "merchant_qrs",
                entityId = "$merchantId|${qr.provider}",
                payloadJson = JSONObject().put("merchantId", merchantId).put("provider", qr.provider).put("data", data).toString(),
                createdAt = System.currentTimeMillis(),
            ))
        }
        val manager = FirestoreSyncManager(database)
        return try {
            manager.uploadPendingBatch()
            database.pendingSyncDao().hasPending("merchant_qrs", "$merchantId|${qr.provider}") == 0
        } finally {
            manager.stop()
        }
    }

    suspend fun hasPendingSync(merchantId: String, provider: String): Boolean =
        database.pendingSyncDao().hasPending("merchant_qrs", "$merchantId|$provider") > 0
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
