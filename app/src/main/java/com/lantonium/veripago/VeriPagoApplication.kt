package com.lantonium.veripago

import android.app.Application
import com.lantonium.veripago.data.FirebaseClients
import com.lantonium.veripago.data.PaymentProcessor
import com.lantonium.veripago.data.ProfileCache
import com.lantonium.veripago.data.VeriPagoDatabase
import com.lantonium.veripago.data.FirestoreSyncManager

class VeriPagoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseClients.initialize(this)
        AppGraph.initialize(this)
    }
}

object AppGraph {
    lateinit var database: VeriPagoDatabase
        private set
    lateinit var payments: PaymentProcessor
        private set
    lateinit var profileCache: ProfileCache
        private set

    fun initialize(application: Application) {
        database = VeriPagoDatabase.get(application)
        payments = PaymentProcessor(database)
        profileCache = ProfileCache(application)
    }

    fun syncManager() = FirestoreSyncManager(database)
}
