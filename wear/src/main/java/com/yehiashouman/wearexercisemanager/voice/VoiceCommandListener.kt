package com.yehiashouman.wearexercisemanager.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Continuous command recognition: recognition is automatically restarted after every result,
 * end-of-speech or error, so the user never has to touch the watch to reactivate it.
 */
class VoiceCommandListener(
    context: Context,
    private val onCommand: (String) -> Unit,
    private val onEnabledChanged: (Boolean) -> Unit = {}
) : RecognitionListener {
    private val appContext = context.applicationContext
    private val available = SpeechRecognizer.isRecognitionAvailable(appContext)
    private val recognizer = if (available) {
        runCatching {
            SpeechRecognizer.createSpeechRecognizer(appContext).also { it.setRecognitionListener(this) }
        }.getOrNull()
    } else null
    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }
    private val handler = Handler(Looper.getMainLooper())
    private val restart = Runnable { beginListening() }

    /** True while the caller wants commands to be recognised, independent of recognizer restarts. */
    private var enabled = false
    private var listening = false
    private var destroyed = false
    private var consecutiveErrors = 0

    val isAvailable: Boolean get() = recognizer != null

    fun start() {
        if (destroyed || recognizer == null) return
        val wasEnabled = enabled
        enabled = true
        consecutiveErrors = 0
        if (!wasEnabled) onEnabledChanged(true)
        beginListening()
    }

    fun stop() {
        val wasEnabled = enabled
        enabled = false
        listening = false
        if (wasEnabled) onEnabledChanged(false)
        handler.removeCallbacks(restart)
        runCatching { recognizer?.cancel() }
    }

    fun destroy() {
        stop()
        destroyed = true
        runCatching { recognizer?.destroy() }
    }

    private fun beginListening() {
        if (destroyed || !enabled || listening) return
        val result = runCatching { recognizer?.startListening(intent) }
        if (result.isFailure) {
            Log.w(TAG, "Could not start speech recognition", result.exceptionOrNull())
            scheduleRestart(RESTART_BACKOFF_MS)
        } else {
            listening = true
        }
    }

    private fun disable() {
        if (!enabled) return
        enabled = false
        handler.removeCallbacks(restart)
        onEnabledChanged(false)
    }

    private fun scheduleRestart(delayMs: Long) {
        if (destroyed || !enabled) return
        handler.removeCallbacks(restart)
        handler.postDelayed(restart, delayMs)
    }

    override fun onResults(results: Bundle?) {
        listening = false
        consecutiveErrors = 0
        handle(results)
        scheduleRestart(RESTART_DELAY_MS)
    }

    override fun onPartialResults(partialResults: Bundle?) = handle(partialResults)

    private fun handle(bundle: Bundle?) {
        val phrases = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val command = phrases.map { it.lowercase() }.firstNotNullOfOrNull { phrase ->
            when {
                "skip" in phrase -> "skip"
                "next" in phrase -> "next"
                "pause" in phrase -> "pause"
                "resume" in phrase || "continue" in phrase -> "resume"
                "repeat" in phrase || "again" in phrase -> "repeat"
                "stop" in phrase || "finish" in phrase -> "stop"
                else -> null
            }
        }
        if (command != null) onCommand(command)
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit

    override fun onError(error: Int) {
        listening = false
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                consecutiveErrors = 0
                scheduleRestart(RESTART_DELAY_MS)
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                // The workout must keep running without microphone access.
                Log.w(TAG, "Microphone permission missing, voice commands disabled")
                disable()
            }
            else -> {
                consecutiveErrors++
                if (consecutiveErrors > MAX_CONSECUTIVE_ERRORS) {
                    Log.w(TAG, "Speech recognition unavailable (error $error), giving up")
                    disable()
                } else {
                    scheduleRestart(RESTART_BACKOFF_MS * consecutiveErrors)
                }
            }
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private companion object {
        const val TAG = "VoiceCommandListener"
        const val RESTART_DELAY_MS = 400L
        const val RESTART_BACKOFF_MS = 1_500L
        const val MAX_CONSECUTIVE_ERRORS = 5
    }
}

