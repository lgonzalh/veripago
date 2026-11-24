package com.lantonium.veripago.data

import com.google.firebase.firestore.FirebaseFirestore
import com.lantonium.veripago.AppGraph
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import androidx.room.withTransaction
import org.json.JSONObject
import java.util.UUID

data class MerchantProduct(
    val id: String = "",
    val name: String,
    val enteredPrice: Long,
    val vatRate: Double,
    val priceIncludesVat: Boolean,
    val priceWithoutVat: Long,
    val vatAmount: Long,
    val priceWithVat: Long,
)

class MerchantProductRepository(
    private val database: VeriPagoDatabase = AppGraph.database,
    private val firestore: FirebaseFirestore? = FirebaseClients.firestore,
) {
    private fun products(uid: String) = (firestore ?: error("No hay conexión con el servicio de VeriPago."))
        .collection("users").document(uid).collection("products")

    fun observe(uid: String): Flow<List<MerchantProduct>> = database.merchantProductDao().observe(uid).map { items ->
        items.map(MerchantProductEntity::toMerchantProduct)
    }

    suspend fun load(uid: String): List<MerchantProduct> {
        val db = firestore
        if (db != null) {
            runCatching {
                val snapshot = products(uid).get().await()
                // Backfill the explicit relationship on legacy product documents.
                snapshot.documents.filter { it.getString("merchantId") != uid }.forEach { doc ->
                    doc.reference.set(mapOf("merchantId" to uid), com.google.firebase.firestore.SetOptions.merge()).await()
                }
                snapshot
            }.onSuccess { snapshot ->
                val remoteIds = snapshot.documents.mapTo(mutableSetOf()) { it.id }
                val pendingIds = database.pendingSyncDao().pendingIds("merchant_products", "$uid|%").mapTo(mutableSetOf()) { it.substringAfter('|') }
                database.withTransaction {
                    database.merchantProductDao().getAll(uid).filter { it.productId !in remoteIds && it.productId !in pendingIds }
                        .forEach { database.merchantProductDao().deleteRemote(uid, it.productId) }
                    snapshot.documents.forEach { doc ->
                        if (database.pendingSyncDao().hasPending("merchant_products", "$uid|${doc.id}") == 0) {
                            doc.toMerchantProductEntity(uid)?.let { database.merchantProductDao().upsert(it) }
                        }
                    }
                }
            }.onFailure { error ->
                if (database.merchantProductDao().getAll(uid).isEmpty()) throw error
            }
        }
        return database.merchantProductDao().getAll(uid).map(MerchantProductEntity::toMerchantProduct)
    }

    suspend fun save(uid: String, product: MerchantProduct): Boolean {
        val saved = product.copy(id = product.id.ifBlank { UUID.randomUUID().toString() })
        val entity = saved.toEntity(uid)
        val data = JSONObject().apply {
            put("merchantId", uid)
            put("name", saved.name)
            put("enteredPrice", saved.enteredPrice)
            put("vatRate", saved.vatRate)
            put("priceIncludesVat", saved.priceIncludesVat)
            put("priceWithoutVat", saved.priceWithoutVat)
            put("vatAmount", saved.vatAmount)
            put("priceWithVat", saved.priceWithVat)
        }
        database.withTransaction {
            database.merchantProductDao().upsert(entity)
            database.pendingSyncDao().enqueue(PendingSyncEntity(
                operationId = "merchant-product:$uid:${saved.id}",
                entityName = "merchant_products",
                entityId = "$uid|${saved.id}",
                payloadJson = JSONObject().put("merchantId", uid).put("productId", saved.id).put("data", data).toString(),
                createdAt = System.currentTimeMillis(),
            ))
        }
        return syncQueuedChanges(uid)
    }

    suspend fun delete(uid: String, productId: String): Boolean {
        database.withTransaction {
            database.merchantProductDao().delete(uid, productId)
            database.pendingSyncDao().enqueue(PendingSyncEntity(
                operationId = "merchant-product:$uid:$productId",
                entityName = "merchant_products",
                entityId = "$uid|$productId",
                payloadJson = JSONObject().put("merchantId", uid).put("productId", productId).put("deleted", true).toString(),
                createdAt = System.currentTimeMillis(),
            ))
        }
        return syncQueuedChanges(uid)
    }

    suspend fun hasPendingSync(uid: String): Boolean = database.pendingSyncDao().pendingIds("merchant_products", "$uid|%").isNotEmpty()

    private suspend fun syncQueuedChanges(uid: String): Boolean {
        val manager = FirestoreSyncManager(database)
        return try {
            manager.uploadPendingBatch()
            !hasPendingSync(uid)
        } finally {
            manager.stop()
        }
    }
}

private fun MerchantProduct.toEntity(uid: String) = MerchantProductEntity(
    merchantId = uid, productId = id, name = name, enteredPrice = enteredPrice, vatRate = vatRate,
    priceIncludesVat = priceIncludesVat, priceWithoutVat = priceWithoutVat, vatAmount = vatAmount,
    priceWithVat = priceWithVat, updatedAt = System.currentTimeMillis(),
)

private fun MerchantProductEntity.toMerchantProduct() = MerchantProduct(
    id = productId, name = name, enteredPrice = enteredPrice, vatRate = vatRate,
    priceIncludesVat = priceIncludesVat, priceWithoutVat = priceWithoutVat, vatAmount = vatAmount,
    priceWithVat = priceWithVat,
)

private fun com.google.firebase.firestore.DocumentSnapshot.toMerchantProductEntity(uid: String): MerchantProductEntity? {
    val productName = getString("name") ?: return null
    return MerchantProductEntity(
        merchantId = uid, productId = id, name = productName, enteredPrice = getLong("enteredPrice") ?: 0L,
        vatRate = (get("vatRate") as? Number)?.toDouble() ?: 0.0,
        priceIncludesVat = getBoolean("priceIncludesVat") ?: false, priceWithoutVat = getLong("priceWithoutVat") ?: 0L,
        vatAmount = getLong("vatAmount") ?: 0L, priceWithVat = getLong("priceWithVat") ?: 0L,
        updatedAt = System.currentTimeMillis(),
    )
}
