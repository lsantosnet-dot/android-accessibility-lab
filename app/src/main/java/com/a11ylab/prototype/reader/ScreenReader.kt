package com.a11ylab.prototype.reader

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.google.mlkit.nl.languageid.LanguageIdentification
import java.util.Locale

private const val TAG = "ScreenReader"
private const val UTTERANCE_ID = "a11ylab-read-screen"
const val MIN_SPEECH_PARAM = 0.5f
const val MAX_SPEECH_PARAM = 2.0f
const val SPEECH_STEP = 0.25f
private const val PREFS_NAME = "screen_reader_prefs"
private const val KEY_RATE = "rate"
private const val KEY_PITCH = "pitch"

/**
 * Persisted speech rate/pitch. Shared between the live [ScreenReader] (when the
 * accessibility service is running) and the settings screen, which can adjust these
 * even while the service isn't connected — [ScreenReader] picks up the stored value
 * the next time it starts.
 */
object SpeechPrefs {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun rate(context: Context): Float = prefs(context).getFloat(KEY_RATE, 1.0f)
    fun pitch(context: Context): Float = prefs(context).getFloat(KEY_PITCH, 1.0f)

    fun saveRate(context: Context, value: Float) = prefs(context).edit().putFloat(KEY_RATE, value).apply()
    fun savePitch(context: Context, value: Float) = prefs(context).edit().putFloat(KEY_PITCH, value).apply()
}

/**
 * Speaks captured screen text aloud, auto-picking the TTS voice's language via on-device
 * ML Kit language identification. Lives for the lifetime of the owning service — TTS
 * playback isn't tied to any Activity, so it keeps going with the screen off.
 */
class ScreenReader(private val context: Context) {

    private val languageIdentifier = LanguageIdentification.getClient()
    private var ttsReady = false
    private var pendingText: String? = null
    private var rate = SpeechPrefs.rate(context)
    private var pitch = SpeechPrefs.pitch(context)
    private var lastUtteranceId: String? = null

    /** Fires once per [read] call (success or failure) after every one of its chunks has finished. */
    var onReadingFinished: (() -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, ::onTtsInitFinished)

    private fun onTtsInitFinished(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        Log.d(TAG, "TTS init finished: status=$status ready=$ttsReady")
        if (ttsReady) {
            tts.setSpeechRate(rate)
            tts.setPitch(pitch)
            pendingText?.let { speakWithDetectedLanguage(it) }
        }
        pendingText = null
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "speak started")
            }

            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "speak finished")
                notifyIfLastChunk(utteranceId)
            }

            @Suppress("DEPRECATION")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "speak failed (utteranceId=$utteranceId)")
                notifyIfLastChunk(utteranceId)
            }
        })
    }

    private fun notifyIfLastChunk(utteranceId: String?) {
        if (utteranceId != null && utteranceId == lastUtteranceId) onReadingFinished?.invoke()
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

    /** Adjusts speech rate by [delta] (clamped to a sane range), persists it, and returns the new value. */
    fun adjustRate(delta: Float): Float {
        rate = (rate + delta).coerceIn(MIN_SPEECH_PARAM, MAX_SPEECH_PARAM)
        tts.setSpeechRate(rate)
        SpeechPrefs.saveRate(context, rate)
        return rate
    }

    /** Adjusts pitch by [delta] (clamped to a sane range), persists it, and returns the new value. */
    fun adjustPitch(delta: Float): Float {
        pitch = (pitch + delta).coerceIn(MIN_SPEECH_PARAM, MAX_SPEECH_PARAM)
        tts.setPitch(pitch)
        SpeechPrefs.savePitch(context, pitch)
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

        lastUtteranceId = "$UTTERANCE_ID-${chunks.lastIndex}"
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
