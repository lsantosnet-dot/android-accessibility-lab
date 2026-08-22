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

/** Playback state surfaced to [ScreenReader.onStateChanged] — mirrored onto the lock-screen media card. */
enum class ReaderState { IDLE, READING, PAUSED }

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
 *
 * Text is read as a list of [segments] (one per captured screen element) rather than one
 * big blob, so [skipForward]/[skipBack] have something meaningful to jump between — the
 * old single-string approach only had TTS-input-length chunk boundaries, which cut text at
 * arbitrary points unrelated to the screen's actual structure.
 */
class ScreenReader(private val context: Context) {

    private val languageIdentifier = LanguageIdentification.getClient()
    private var ttsReady = false
    private var rate = SpeechPrefs.rate(context)
    private var pitch = SpeechPrefs.pitch(context)

    private var segments: List<String> = emptyList()
    private var currentSegmentIndex = -1
    private var hasPendingRead = false

    /** True from [read] until playback naturally finishes or [stop] is called — guards stale TTS callbacks. */
    private var isActive = false

    /** True between [pause] and [resume] — distinct from [isActive], which stays true while paused. */
    private var isPaused = false

    /** Bumped on every [speakFrom] call so a callback from an already-superseded utterance is ignored. */
    private var readGeneration = 0
    private var lastUtteranceIdForGeneration: String? = null

    /** Fires once per [read] call (success or failure) after every one of its segments has finished. */
    var onReadingFinished: (() -> Unit)? = null

    /** Fires on every playback transition (segment start, pause, resume, finish) with the segment position. */
    var onStateChanged: ((state: ReaderState, currentSegment: Int, totalSegments: Int) -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, ::onTtsInitFinished)

    private fun onTtsInitFinished(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        Log.d(TAG, "TTS init finished: status=$status ready=$ttsReady")
        if (ttsReady) {
            tts.setSpeechRate(rate)
            tts.setPitch(pitch)
            if (hasPendingRead) {
                hasPendingRead = false
                speakFrom(0)
            }
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(TAG, "speak started")
            }

            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "speak finished")
                advanceIfLastChunkOfSegment(utteranceId)
            }

            @Suppress("DEPRECATION")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "speak failed (utteranceId=$utteranceId)")
                advanceIfLastChunkOfSegment(utteranceId)
            }
        })
    }

    /** Auto-continues to the next segment once the current one finishes — unless a stop/skip already moved on. */
    private fun advanceIfLastChunkOfSegment(utteranceId: String?) {
        if (!isActive) return
        if (utteranceId != null && utteranceId == lastUtteranceIdForGeneration) {
            speakFrom(currentSegmentIndex + 1)
        }
    }

    fun read(segments: List<String>) {
        Log.d(TAG, "read() called with ${segments.size} segment(s), ttsReady=$ttsReady")
        if (segments.isEmpty()) {
            Log.w(TAG, "read() got no segments to speak — nothing was captured from the screen")
            return
        }
        this.segments = segments
        isActive = true
        isPaused = false
        if (!ttsReady) {
            Log.w(TAG, "TTS not ready yet, queuing read for when init finishes")
            hasPendingRead = true
            return
        }
        speakFrom(0)
    }

    /** Jumps to the next segment, if any. No-op if nothing is currently being read or it's already the last one. */
    fun skipForward() {
        if (!isActive || currentSegmentIndex + 1 !in segments.indices) return
        tts.stop()
        speakFrom(currentSegmentIndex + 1)
    }

    /** Jumps to the previous segment, if any. No-op if nothing is currently being read. */
    fun skipBack() {
        if (!isActive || segments.isEmpty()) return
        tts.stop()
        speakFrom((currentSegmentIndex - 1).coerceAtLeast(0))
    }

    private fun speakFrom(index: Int) {
        if (!isActive) return
        if (index !in segments.indices) {
            isActive = false
            isPaused = false
            onStateChanged?.invoke(ReaderState.IDLE, 0, 0)
            onReadingFinished?.invoke()
            return
        }
        currentSegmentIndex = index
        readGeneration++
        onStateChanged?.invoke(ReaderState.READING, index + 1, segments.size)
        speakWithDetectedLanguage(segments[index], readGeneration)
    }

    /** Stops speaking but keeps the current position, so [resume] can continue from where it left off. */
    fun pause() {
        if (!isActive || isPaused) return
        isPaused = true
        tts.stop()
        onStateChanged?.invoke(ReaderState.PAUSED, currentSegmentIndex + 1, segments.size)
    }

    /** Re-speaks the segment that was current when [pause] was called. No-op unless currently paused. */
    fun resume() {
        if (!isActive || !isPaused) return
        isPaused = false
        speakFrom(currentSegmentIndex)
    }

    fun stop() {
        isActive = false
        isPaused = false
        tts.stop()
        onStateChanged?.invoke(ReaderState.IDLE, 0, 0)
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

    /**
     * [generation] is the value of [readGeneration] at the moment this read was kicked off —
     * captured up front so a detection result that resolves after a subsequent skip/stop can
     * be recognized as stale and dropped in [speak], instead of flushing the TTS queue with
     * audio for a segment that's no longer the one being read.
     */
    private fun speakWithDetectedLanguage(text: String, generation: Int) {
        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { languageCode ->
                Log.d(TAG, "language detected: $languageCode")
                speak(text, languageCode, generation)
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "language detection failed, falling back to device locale", error)
                speak(text, languageCode = null, generation)
            }
    }

    private fun speak(text: String, languageCode: String?, generation: Int) {
        if (generation != readGeneration) {
            Log.d(TAG, "speak(): stale generation $generation (current is $readGeneration), dropping")
            return
        }

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
        // exceeds its max input length — a single screen element's text can still blow past that.
        val maxChunkLength = TextToSpeech.getMaxSpeechInputLength() - 100
        val chunks = chunkText(text, maxChunkLength)
        Log.d(TAG, "speak(): ${text.length} chars split into ${chunks.size} chunk(s), max=$maxChunkLength")

        lastUtteranceIdForGeneration = "$UTTERANCE_ID-$generation-${chunks.lastIndex}"
        chunks.forEachIndexed { index, chunk ->
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val speakResult = tts.speak(chunk, queueMode, null, "$UTTERANCE_ID-$generation-$index")
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
