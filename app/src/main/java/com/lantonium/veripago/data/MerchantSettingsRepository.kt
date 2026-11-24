package com.lantonium.veripago.data

import com.lantonium.veripago.AppGraph
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import androidx.room.withTransaction
import org.json.JSONObject

/** Keeps merchant preferences in the local Room mirror and queues their cloud writes. */
class MerchantSettingsRepository(
    private val database: VeriPagoDatabase = AppGraph.database,
    private val firestore: FirebaseFirestore? = FirebaseClients.firestore,
) {
    fun observe(merchantId: String, settingId: String): Flow<MerchantSettingEntity?> =
        database.merchantSettingDao().observe(merchantId, settingId)

    suspend fun load(merchantId: String, settingId: String): Map<String, Any?> {
        val remote = firestore?.let { db ->
            runCatching {
                db.collection("users").document(merchantId).collection("settings").document(settingId).get().await()
            }.getOrNull()
        }
        if (remote?.exists() == true && database.pendingSyncDao().hasPending("merchant_settings", "$merchantId|$settingId") == 0) {
            val values = remote.data.orEmpty().filterKeys { it != "updatedAt" }
            database.merchantSettingDao().upsert(MerchantSettingEntity(
                merchantId = merchantId,
                settingId = settingId,
                payloadJson = JSONObject(values).toString(),
                updatedAt = System.currentTimeMillis(),
            ))
        }
        val cached = database.merchantSettingDao().get(merchantId, settingId)?.payloadJson
            ?: return emptyMap()
        return JSONObject(cached).toMap()
    }

    suspend fun saveContact(merchantId: String, whatsappNumber: String): Boolean = save(
        merchantId,
        "contact",
        mapOf("whatsappNumber" to whatsappNumber),
    )

    suspend fun saveAdjustments(
        merchantId: String,
        perProduct: Map<String, Any>,
        perOrder: Map<String, Any>,
    ): Boolean = save(merchantId, "fees", mapOf("perProduct" to perProduct, "perOrder" to perOrder))

    suspend fun hasPendingSync(merchantId: String, settingId: String): Boolean =
        database.pendingSyncDao().hasPending("merchant_settings", "$merchantId|$settingId") > 0

    private suspend fun save(merchantId: String, settingId: String, values: Map<String, Any>): Boolean {
        val payload = JSONObject(values)
        val operation = JSONObject()
            .put("merchantId", merchantId)
            .put("settingId", settingId)
            .put("data", payload)
        database.withTransaction {
            database.merchantSettingDao().upsert(MerchantSettingEntity(
                merchantId = merchantId,
                settingId = settingId,
                payloadJson = payload.toString(),
                updatedAt = System.currentTimeMillis(),
            ))
            database.pendingSyncDao().enqueue(PendingSyncEntity(
                operationId = "merchant-setting:$merchantId:$settingId",
                entityName = "merchant_settings",
                entityId = "$merchantId|$settingId",
                payloadJson = operation.toString(),
                createdAt = System.currentTimeMillis(),
            ))
        }
        val manager = FirestoreSyncManager(database)
        return try {
            manager.uploadPendingBatch()
            !hasPendingSync(merchantId, settingId)
        } finally {
            manager.stop()
        }
    }
}

private fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { key ->
    when (val value = get(key)) {
        JSONObject.NULL -> null
        is JSONObject -> value.toMap()
        else -> value
    }
}
