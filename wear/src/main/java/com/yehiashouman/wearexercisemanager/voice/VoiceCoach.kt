package com.yehiashouman.wearexercisemanager.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.yehiashouman.wearexercisemanager.shared.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class VoiceCoach(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var settings = AppSettings()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val utteranceCounter = AtomicLong(0)

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = complete(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = complete(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = complete(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = complete(utteranceId)
        })
    }

    private fun complete(utteranceId: String?) {
        utteranceId?.let { pending.remove(it)?.complete(Unit) }
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.ENGLISH
            applySettings(settings)
        } else {
            Log.w(TAG, "TextToSpeech unavailable, continuing without spoken guidance")
            // No caller should wait forever for an engine that never initialised.
            pending.keys.toList().forEach { complete(it) }
        }
    }

    fun applySettings(newSettings: AppSettings) {
        settings = newSettings
        if (!ready) return
        tts.setSpeechRate(settings.speechRate)
        val requested = settings.selectedVoiceName
        if (requested != null) tts.voices.firstOrNull { it.name == requested }?.let { tts.voice = it }
    }

    fun availableVoices() = if (ready) tts.voices.filter { it.locale.language == "en" }.sortedBy { it.name } else emptyList()

    fun speak(text: String, flush: Boolean = false) {
        speakInternal(text, flush)
    }

    /**
     * Speaks [text] and suspends until the utterance is finished, so callers can safely shut the
     * engine down afterwards. Returns immediately when speech is unavailable or fails.
     */
    suspend fun speakAndAwait(text: String, flush: Boolean = false, timeoutMs: Long = 6_000) {
        val id = speakInternal(text, flush) ?: return
        val deferred = pending[id] ?: return
        try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Timed out waiting for utterance to finish")
        } finally {
            pending.remove(id)
        }
    }

    private fun speakInternal(text: String, flush: Boolean): String? {
        if (!ready || !settings.voiceAnnouncements) return null
        val id = "utt-${utteranceCounter.getAndIncrement()}"
        val deferred = CompletableDeferred<Unit>()
        pending[id] = deferred
        val result = runCatching {
            tts.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }.getOrDefault(TextToSpeech.ERROR)
        if (result != TextToSpeech.SUCCESS) {
            complete(id)
            return null
        }
        return id
    }

    fun preview() = speak("Starting workout. Bicep curls. Set one.", true)

    fun shutdown() {
        pending.keys.toList().forEach { complete(it) }
        runCatching { tts.shutdown() }
    }

    private companion object {
        const val TAG = "VoiceCoach"
    }
}

