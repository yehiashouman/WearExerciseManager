package com.yehiashouman.wearexercisemanager.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class VoiceCommandListener(context: Context, private val onCommand: (String) -> Unit) : RecognitionListener {
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).also { it.setRecognitionListener(this) }
    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }
    private var listening = false

    fun start() {
        if (listening) return
        listening = true
        runCatching { recognizer.startListening(intent) }
    }

    fun stop() {
        listening = false
        runCatching { recognizer.stopListening() }
    }

    fun destroy() = recognizer.destroy()

    override fun onResults(results: Bundle?) {
        handle(results)
        listening = false
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
    override fun onError(error: Int) { listening = false }
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}
