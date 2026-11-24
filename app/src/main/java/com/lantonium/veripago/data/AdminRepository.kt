package com.lantonium.veripago.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import java.util.UUID

data class AdminOrderItem(
    val id: String,
    val orderCode: String,
    val customerName: String,
    val totalAmount: Long,
    val paymentStatus: String,
    val deliveryStatus: String,
)

data class AdminOverview(
    val orders: List<AdminOrderItem>,
    val verifiedVolume: Long,
    val reconciledPayments: Int,
    val merchantsRegistered: Int,
)

data class AdminRule(
    val id: String,
    val walletName: String,
    val androidPackage: String,
    val amountRegex: String,
    val senderRegex: String,
    val isActive: Boolean,
)

data class PlatformRelease(
    val url: String,
    val version: String,
    val notes: String,
)

class AdminRepository {
    private val firestore get() = FirebaseClients.firestore ?: error("Firestore no está disponible.")

    suspend fun loadOverview(): AdminOverview = coroutineScope {
        val (orders, users) = coroutineScope {
            val ordersRequest = async {
                firestore.collection("orders")
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .get().await().documents.map { document ->
                        AdminOrderItem(
                            id = document.id,
                            orderCode = document.getString("orderCode").orEmpty(),
                            customerName = document.getString("customerName").orEmpty(),
                            totalAmount = (document.get("totalAmount") as? Number)?.toLong() ?: 0L,
                            paymentStatus = document.getString("paymentStatus").orEmpty(),
                            deliveryStatus = document.getString("deliveryStatus").orEmpty(),
                        )
                    }
            }
            val usersRequest = async { firestore.collection("users").get().await().documents }
            ordersRequest.await() to usersRequest.await()
        }
        val verified = orders.filter { it.paymentStatus.startsWith("VERIFIED") }
        AdminOverview(
            orders = orders.take(20),
            verifiedVolume = verified.sumOf { it.totalAmount },
            reconciledPayments = verified.size,
            merchantsRegistered = users.count { it.getString("role") == "COMERCIANTE" },
        )
    }

    suspend fun loadRules(): List<AdminRule> = firestore.collection("bank_parser_rules").get().await().documents.map { document ->
        AdminRule(
            id = document.id,
            walletName = document.getString("walletName").orEmpty(),
            androidPackage = document.getString("androidPackage").orEmpty(),
            amountRegex = document.getString("amountRegex").orEmpty(),
            senderRegex = document.getString("senderRegex").orEmpty(),
            isActive = document.getBoolean("isActive") ?: false,
        )
    }

    suspend fun saveDisabledRule(amountRegex: String, senderRegex: String) {
        val id = "rule-${UUID.randomUUID()}"
        firestore.collection("bank_parser_rules").document(id).set(
            mapOf(
                "walletName" to "Personalizada",
                "androidPackage" to "",
                "amountRegex" to amountRegex,
                "senderRegex" to senderRegex,
                "isActive" to false,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    suspend fun loadUsers(): List<UserProfile> = firestore.collection("users").get().await().documents.mapNotNull { document ->
        val uid = document.getString("uid") ?: document.id
        UserProfile(
            uid = uid,
            email = document.getString("email").orEmpty(),
            displayName = document.getString("displayName").orEmpty(),
            photoUrl = document.getString("photoUrl").orEmpty(),
            role = document.getString("role").orEmpty(),
            isActive = document.getBoolean("isActive") ?: false,
            loyaltyPoints = document.getLong("loyaltyPoints") ?: 0L,
        )
    }.sortedBy { it.displayName.lowercase() }

    suspend fun updateRole(uid: String, role: String) {
        require(role == "COMERCIANTE" || role == "ADMINISTRADOR") { "El rol seleccionado no es válido." }
        firestore.collection("users").document(uid).update("role", role).await()
    }

    suspend fun loadPlatformRelease(): PlatformRelease? {
        val snapshot = firestore.collection("platform_settings").document("global").get().await()
        if (!snapshot.exists()) return null
        return PlatformRelease(
            url = snapshot.getString("latestApkUrl").orEmpty(),
            version = snapshot.getString("latestApkVersion").orEmpty(),
            notes = snapshot.getString("apkReleaseNotes").orEmpty(),
        )
    }

    suspend fun publishApk(context: Context, uri: Uri, version: String): PlatformRelease {
        require(version.isNotBlank()) { "Escribe el número de versión." }
        val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }.orEmpty()
        require(displayName.endsWith(".apk", ignoreCase = true)) { "Selecciona un archivo .apk." }
        val storage = FirebaseClients.storage ?: error("Firebase Storage no está disponible.")
        val target = storage.reference.child("releases/veripago-latest.apk")
        val metadata = StorageMetadata.Builder()
            .setContentType("application/vnd.android.package-archive")
            .build()
        target.putFile(uri, metadata).await()
        val url = target.downloadUrl.await().toString()
        val release = PlatformRelease(url, version.trim(), "Versión alfa VeriPago")
        firestore.collection("platform_settings").document("global").set(
            mapOf(
                "latestApkUrl" to release.url,
                "latestApkVersion" to release.version,
                "apkReleaseNotes" to release.notes,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        ).await()
        return release
    }
}
