package com.a11ylab.prototype.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import com.google.mlkit.nl.languageid.LanguageIdentification
import java.util.Locale

private const val UTTERANCE_ID = "a11ylab-read-screen"

/**
 * Speaks captured screen text aloud, auto-picking the TTS voice's language via on-device
 * ML Kit language identification. Lives for the lifetime of the owning service — TTS
 * playback isn't tied to any Activity, so it keeps going with the screen off.
 */
class ScreenReader(context: Context) {

    private val languageIdentifier = LanguageIdentification.getClient()
    private var ttsReady = false
    private var pendingText: String? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) pendingText?.let { speakWithDetectedLanguage(it) }
        pendingText = null
    }

    fun read(text: String) {
        if (text.isBlank()) return
        if (!ttsReady) {
            pendingText = text
            return
        }
        speakWithDetectedLanguage(text)
    }

    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        languageIdentifier.close()
    }

    private fun speakWithDetectedLanguage(text: String) {
        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { languageCode -> speak(text, languageCode) }
            .addOnFailureListener { speak(text, languageCode = null) }
    }

    private fun speak(text: String, languageCode: String?) {
        val locale = languageCode
            ?.takeIf { it != "und" }
            ?.let { Locale.forLanguageTag(it) }
            ?: Locale.getDefault()

        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault())
        }

        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }
}
