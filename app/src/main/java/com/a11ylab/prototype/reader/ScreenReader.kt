package com.a11ylab.prototype.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.google.mlkit.nl.languageid.LanguageIdentification
import java.util.Locale

private const val TAG = "ScreenReader"
private const val UTTERANCE_ID = "a11ylab-read-screen"
private const val MIN_SPEECH_PARAM = 0.5f
private const val MAX_SPEECH_PARAM = 2.0f

/**
 * Speaks captured screen text aloud, auto-picking the TTS voice's language via on-device
 * ML Kit language identification. Lives for the lifetime of the owning service — TTS
 * playback isn't tied to any Activity, so it keeps going with the screen off.
 */
class ScreenReader(context: Context) {

    private val languageIdentifier = LanguageIdentification.getClient()
    private var ttsReady = false
    private var pendingText: String? = null
    private var rate = 1.0f
    private var pitch = 1.0f

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
        Log.d(TAG, "TTS init finished: status=$status ready=$ttsReady")
        if (ttsReady) pendingText?.let { speakWithDetectedLanguage(it) }
        pendingText = null
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "speak started")
            }

            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "speak finished")
            }

            @Suppress("DEPRECATION")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "speak failed (utteranceId=$utteranceId)")
            }
        })
    }

    fun read(text: String) {
        Log.d(TAG, "read() called with ${text.length} chars, ttsReady=$ttsReady")
        if (text.isBlank()) {
            Log.w(TAG, "read() got no text to speak — nothing was captured from the screen")
            return
        }
        if (!ttsReady) {
            Log.w(TAG, "TTS not ready yet, queuing text for when init finishes")
            pendingText = text
            return
        }
        speakWithDetectedLanguage(text)
    }

    fun stop() {
        tts.stop()
    }

    /** Adjusts speech rate by [delta] (clamped to a sane range) and returns the new value. */
    fun adjustRate(delta: Float): Float {
        rate = (rate + delta).coerceIn(MIN_SPEECH_PARAM, MAX_SPEECH_PARAM)
        tts.setSpeechRate(rate)
        return rate
    }

    /** Adjusts pitch by [delta] (clamped to a sane range) and returns the new value. */
    fun adjustPitch(delta: Float): Float {
        pitch = (pitch + delta).coerceIn(MIN_SPEECH_PARAM, MAX_SPEECH_PARAM)
        tts.setPitch(pitch)
        return pitch
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        languageIdentifier.close()
    }

    private fun speakWithDetectedLanguage(text: String) {
        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { languageCode ->
                Log.d(TAG, "language detected: $languageCode")
                speak(text, languageCode)
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "language detection failed, falling back to device locale", error)
                speak(text, languageCode = null)
            }
    }

    private fun speak(text: String, languageCode: String?) {
        val locale = languageCode
            ?.takeIf { it != "und" }
            ?.let { Locale.forLanguageTag(it) }
            ?: Locale.getDefault()

        val result = tts.setLanguage(locale)
        Log.d(TAG, "setLanguage($locale) result=$result")
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "locale $locale unavailable, falling back to ${Locale.getDefault()}")
            tts.setLanguage(Locale.getDefault())
        }

        // TextToSpeech.speak() rejects the whole call (result=ERROR, nothing spoken) once text
        // exceeds its max input length — long article pages routinely blow past that in one string.
        val maxChunkLength = TextToSpeech.getMaxSpeechInputLength() - 100
        val chunks = chunkText(text, maxChunkLength)
        Log.d(TAG, "speak(): ${text.length} chars split into ${chunks.size} chunk(s), max=$maxChunkLength")

        chunks.forEachIndexed { index, chunk ->
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val speakResult = tts.speak(chunk, queueMode, null, "$UTTERANCE_ID-$index")
            Log.d(TAG, "speak() chunk $index (${chunk.length} chars) result=$speakResult")
        }
    }

    /** Splits [text] into pieces no longer than [maxLength], breaking on spaces to avoid cutting words. */
    private fun chunkText(text: String, maxLength: Int): List<String> {
        if (text.length <= maxLength) return listOf(text)

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + maxLength).coerceAtMost(text.length)
            if (end < text.length) {
                val lastSpace = text.lastIndexOf(' ', end)
                if (lastSpace > start) end = lastSpace
            }
            val chunk = text.substring(start, end).trim()
            if (chunk.isNotEmpty()) chunks.add(chunk)
            start = end
        }
        return chunks
    }
}
