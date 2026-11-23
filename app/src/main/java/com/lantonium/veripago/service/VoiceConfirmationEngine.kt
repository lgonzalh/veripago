package com.lantonium.veripago.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.text.NumberFormat
import java.util.Locale

class VoiceConfirmationEngine(context: Context) : TextToSpeech.OnInitListener {
    private val locale = Locale("es", "CO")
    private val numberFormat = NumberFormat.getIntegerInstance(locale)
    private val engine = TextToSpeech(context.applicationContext, this)
    @Volatile private var ready = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            engine.language = locale
            engine.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                .build())
            ready = true
        }
    }

    fun announce(amount: Long, customerName: String) {
        if (!ready) return
        val recipient = customerName.trim().ifBlank { "pedido pendiente" }
        val text = "VeriPago: recibiste ${numberFormat.format(amount)} pesos, pedido de $recipient"
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, "payment-${System.nanoTime()}")
    }

    fun release() {
        ready = false
        engine.stop()
        engine.shutdown()
    }
}
