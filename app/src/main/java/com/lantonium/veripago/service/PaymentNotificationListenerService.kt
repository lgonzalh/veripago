package com.lantonium.veripago.service

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import com.lantonium.veripago.AppGraph
import com.lantonium.veripago.data.FirebaseClients
import com.lantonium.veripago.data.PendingSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PaymentNotificationListenerService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var voice: VoiceConfirmationEngine? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        voice = VoiceConfirmationEngine(this)
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, PaymentMonitorService::class.java))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val user = FirebaseClients.auth?.currentUser ?: return
        val extras = sbn.notification.extras
        val text = listOfNotNull(
            extras.getCharSequence("android.title")?.toString(),
            extras.getCharSequence("android.text")?.toString(),
            extras.getCharSequence("android.bigText")?.toString(),
            extras.getCharSequence("android.subText")?.toString(),
        ).filter(String::isNotBlank).distinct().joinToString(" · ")
        if (text.isBlank()) return

        serviceScope.launch {
            val rule = AppGraph.database.bankRuleDao().getActive()
                .firstOrNull { it.androidPackage == sbn.packageName } ?: return@launch
            val match = AppGraph.payments.process(
                merchantId = user.uid,
                sourcePackage = sbn.packageName,
                maskedText = maskSensitiveText(text),
                text = text,
                rule = rule,
                receivedAt = sbn.postTime,
            )
            PendingSyncScheduler.request(this@PaymentNotificationListenerService)
            if (match != null) voice?.announce(match.order.totalAmount, match.order.customerName)
        }
    }

    override fun onListenerDisconnected() {
        voice?.release()
        voice = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        voice?.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun maskSensitiveText(value: String): String {
        val labeled = Regex("(?i)(cuenta|celular|tel[eé]fono|llave|terminada en)\\s*[:#-]?\\s*[A-Z0-9*@._-]{4,}")
            .replace(value) { "${it.groupValues[1]} [dato oculto]" }
        return Regex("(?<!\\d)(?:\\+?57[ -]?)?(?:\\d[ -]?){10,}(?!\\d)").replace(labeled, "[dato oculto]").take(500)
    }
}
