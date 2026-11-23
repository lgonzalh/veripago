package com.lantonium.veripago.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lantonium.veripago.R

class PaymentMonitorService : Service() {
    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Escucha de pagos", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mantiene activa la confirmación de transferencias recibidas"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_veripago)
        .setContentTitle("VeriPago está escuchando pagos")
        .setContentText("Te avisaremos cuando llegue una transferencia compatible.")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    companion object {
        const val CHANNEL_ID = "veripago_payment_listener"
        const val NOTIFICATION_ID = 4101
    }
}
