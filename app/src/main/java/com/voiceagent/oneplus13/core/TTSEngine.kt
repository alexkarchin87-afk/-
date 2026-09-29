package com.voiceagent.oneplus13.core

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Thin wrapper around Android's built-in TextToSpeech, exposed as a suspend function. */
class TTSEngine(context: Context) {

    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }

    init {
        tts.language = Locale("ru", "RU")
    }

    suspend fun speak(text: String) = suspendCoroutine<Unit> { cont ->
        if (!ready) {
            cont.resume(Unit)
            return@suspendCoroutine
        }
        val id = UUID.randomUUID().toString()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == id) cont.resume(Unit)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == id) cont.resume(Unit)
            }
        })
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
