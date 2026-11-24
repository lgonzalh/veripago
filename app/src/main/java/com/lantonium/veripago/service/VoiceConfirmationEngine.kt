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
    private val lifecycleLock = Any()
    @Volatile private var ready = false
    private var testPending = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            if (engine.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) engine.setLanguage(Locale("es"))
            engine.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                .build())
            val shouldPlayTest = synchronized(lifecycleLock) {
                ready = true
                testPending.also { testPending = false }
            }
            if (shouldPlayTest) speakTestAnnouncement()
        }
    }

    fun announce(amount: Long, customerName: String) {
        if (!ready) return
        val recipient = customerName.trim().ifBlank { "pedido pendiente" }
        val text = "VeriPago: recibiste ${numberFormat.format(amount)} pesos, pedido de $recipient"
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, "payment-${System.nanoTime()}")
    }

    fun announceTest() {
        val speakNow = synchronized(lifecycleLock) {
            if (ready) true else {
                testPending = true
                false
            }
        }
        if (speakNow) speakTestAnnouncement()
    }

    private fun speakTestAnnouncement() {
        if (!ready) return
        val text = "VeriPago. Esta es una prueba de voz. El pago de cincuenta mil pesos fue verificado. Esta simulación no corresponde a un pago real."
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice-test-${System.nanoTime()}")
    }

    fun release() {
        synchronized(lifecycleLock) {
            ready = false
            testPending = false
        }
        engine.stop()
        engine.shutdown()
    }
}
