package com.lantonium.veripago.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

class PendingSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!FirebaseClients.isConfigured || FirebaseClients.auth?.currentUser == null) return Result.retry()
        val manager = FirestoreSyncManager(VeriPagoDatabase.get(applicationContext))
        return try {
            if (manager.uploadPendingBatch()) Result.success() else Result.retry()
        } finally {
            manager.stop()
        }
    }
}

object PendingSyncScheduler {
    fun request(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<PendingSyncWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork("veripago-pending-sync", ExistingWorkPolicy.KEEP, request)
    }
}
