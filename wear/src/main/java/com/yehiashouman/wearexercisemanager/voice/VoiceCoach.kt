package com.yehiashouman.wearexercisemanager.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import com.yehiashouman.wearexercisemanager.shared.AppSettings
import java.util.Locale

class VoiceCoach(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var settings = AppSettings()

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.ENGLISH
            applySettings(settings)
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
        if (!ready || !settings.voiceAnnouncements) return
        tts.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    fun preview() = speak("Starting workout. Bicep curls. Set one.", true)

    fun shutdown() = tts.shutdown()
}
