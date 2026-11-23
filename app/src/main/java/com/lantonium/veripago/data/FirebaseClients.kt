package com.lantonium.veripago.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import com.lantonium.veripago.BuildConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

private val Context.profileStore by preferencesDataStore(name = "veripago-profile")

data class UserProfile(
    val uid: String,
    val email: String,
    val displayName: String,
    val photoUrl: String,
    val role: String,
    val isActive: Boolean,
    val loyaltyPoints: Long,
)

object FirebaseClients {
    var app: FirebaseApp? = null
        private set
    var auth: FirebaseAuth? = null
        private set
    var firestore: FirebaseFirestore? = null
        private set
    var functions: FirebaseFunctions? = null
        private set
    var storage: FirebaseStorage? = null
        private set

    val isConfigured: Boolean get() = app != null

    fun initialize(context: Context) {
        if (app != null || BuildConfig.FIREBASE_API_KEY.isBlank() || BuildConfig.FIREBASE_PROJECT_ID.isBlank() || BuildConfig.FIREBASE_APP_ID.isBlank()) return
        val options = FirebaseOptions.Builder()
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setStorageBucket(BuildConfig.FIREBASE_STORAGE_BUCKET.takeIf(String::isNotBlank))
            .build()
        app = FirebaseApp.initializeApp(context.applicationContext, options, "VeriPago")
        auth = FirebaseAuth.getInstance(app!!)
        firestore = FirebaseFirestore.getInstance(app!!)
        functions = FirebaseFunctions.getInstance(app!!, "us-central1")
        storage = FirebaseStorage.getInstance(app!!)
    }
}

class ProfileCache(private val context: Context) {
    private val uidKey = stringPreferencesKey("uid")
    private val emailKey = stringPreferencesKey("email")
    private val nameKey = stringPreferencesKey("displayName")
    private val photoKey = stringPreferencesKey("photoUrl")
    private val roleKey = stringPreferencesKey("role")

    suspend fun save(profile: UserProfile) {
        context.profileStore.edit {
            it[uidKey] = profile.uid
            it[emailKey] = profile.email
            it[nameKey] = profile.displayName
            it[photoKey] = profile.photoUrl
            it[roleKey] = profile.role
        }
    }

    suspend fun read(): UserProfile? {
        val preferences = context.profileStore.data.first()
        val uid = preferences[uidKey] ?: return null
        return UserProfile(uid, preferences[emailKey].orEmpty(), preferences[nameKey].orEmpty(),
            preferences[photoKey].orEmpty(), preferences[roleKey] ?: "COMERCIANTE", true, 0)
    }

    suspend fun clear() { context.profileStore.edit { it.clear() } }
}

class GoogleAuthRepository(private val context: Context, private val cache: ProfileCache) {
    suspend fun signIn(idToken: String): UserProfile {
        val auth = FirebaseClients.auth ?: error("Firebase no está configurado en este build.")
        val result = auth.signInWithCredential(com.google.firebase.auth.GoogleAuthProvider.getCredential(idToken, null)).await()
        val user = result.user ?: error("Google no devolvió un usuario.")
        val database = FirebaseClients.firestore ?: error("Firestore no está disponible.")
        val reference = database.collection("users").document(user.uid)
        val snapshot = reference.get().await()
        if (!snapshot.exists()) {
            auth.signOut()
            error("Primero registra tu perfil de comerciante en la web de VeriPago.")
        }
        val role = snapshot.getString("role").orEmpty()
        if (role != "COMERCIANTE" && role != "ADMINISTRADOR") {
            auth.signOut()
            error("Los compradores no inician sesión. Paga con el enlace o el QR del comercio.")
        }
        if (snapshot.getBoolean("isActive") != true) {
            auth.signOut()
            error("Tu perfil VeriPago está desactivado.")
        }
        reference.update("lastLoginAt", com.google.firebase.firestore.FieldValue.serverTimestamp()).await()
        val profile = UserProfile(user.uid, snapshot.getString("email").orEmpty(), snapshot.getString("displayName").orEmpty(),
            snapshot.getString("photoUrl").orEmpty(), role, true, snapshot.getLong("loyaltyPoints") ?: 0L)
        cache.save(profile)
        return profile
    }

    suspend fun updateLastLogin(profile: UserProfile) {
        FirebaseClients.firestore?.collection("users")?.document(profile.uid)
            ?.update("lastLoginAt", com.google.firebase.firestore.FieldValue.serverTimestamp())?.await()
    }
}
