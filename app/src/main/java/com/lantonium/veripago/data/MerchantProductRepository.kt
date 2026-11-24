package com.lantonium.veripago.data

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

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

class MerchantProductRepository(private val firestore: FirebaseFirestore? = FirebaseClients.firestore) {
    private fun products(uid: String) = (firestore ?: error("Firestore no está disponible."))
        .collection("users").document(uid).collection("products")

    suspend fun load(uid: String): List<MerchantProduct> = products(uid).get().await().documents.mapNotNull { doc ->
        val name = doc.getString("name") ?: return@mapNotNull null
        MerchantProduct(
            id = doc.id,
            name = name,
            enteredPrice = doc.getLong("enteredPrice") ?: 0L,
            vatRate = doc.getDouble("vatRate") ?: 0.0,
            priceIncludesVat = doc.getBoolean("priceIncludesVat") ?: false,
            priceWithoutVat = doc.getLong("priceWithoutVat") ?: 0L,
            vatAmount = doc.getLong("vatAmount") ?: 0L,
            priceWithVat = doc.getLong("priceWithVat") ?: 0L,
        )
    }.sortedBy { it.name.lowercase() }

    suspend fun save(uid: String, product: MerchantProduct): MerchantProduct {
        val reference = if (product.id.isBlank()) products(uid).document() else products(uid).document(product.id)
        reference.set(mapOf(
            "name" to product.name,
            "enteredPrice" to product.enteredPrice,
            "vatRate" to product.vatRate,
            "priceIncludesVat" to product.priceIncludesVat,
            "priceWithoutVat" to product.priceWithoutVat,
            "vatAmount" to product.vatAmount,
            "priceWithVat" to product.priceWithVat,
        )).await()
        return product.copy(id = reference.id)
    }

    suspend fun delete(uid: String, productId: String) {
        products(uid).document(productId).delete().await()
    }
}
