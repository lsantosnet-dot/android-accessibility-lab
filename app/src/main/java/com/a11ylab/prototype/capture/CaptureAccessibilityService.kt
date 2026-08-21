package com.a11ylab.prototype.capture

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.a11ylab.prototype.overlay.OverlayManager
import com.a11ylab.prototype.reader.MAX_SPEECH_PARAM
import com.a11ylab.prototype.reader.MIN_SPEECH_PARAM
import com.a11ylab.prototype.reader.ScreenReader
import com.a11ylab.prototype.reader.SpeechPrefs

private const val TAG = "CaptureService"

/** Gives a scrolled screen a moment to finish laying out new content before it's captured. */
private const val SCROLL_SETTLE_DELAY_MS = 500L

class CaptureAccessibilityService : AccessibilityService() {

    private lateinit var overlayManager: OverlayManager
    private lateinit var screenReader: ScreenReader
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastAutoScrollSegments: List<String>? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        screenReader = ScreenReader(this)
        overlayManager = OverlayManager(
            context = this,
            onReadScreen = ::readScreen,
            onStopReading = ::stopReading,
            onSkipForward = { screenReader.skipForward() },
            onSkipBack = { screenReader.skipBack() },
            onToggleAutoScroll = ::toggleAutoScroll,
        )
        overlayManager.show()
        instance = this
    }

    /** Reads everything currently loaded in the foreground app's accessibility tree aloud. */
    private fun readScreen() {
        val segments = captureForegroundSegments()
        if (segments.isNullOrEmpty()) {
            Log.w(TAG, "readScreen: nothing to read")
            return
        }
        screenReader.read(segments)
    }

    /** Starts/stops continuous reading: read the screen, scroll on, read again, until stopped or content stops changing. */
    private fun toggleAutoScroll() {
        val enabling = !CaptureBus.isAutoScrollReading.value
        CaptureBus.setAutoScrollReading(enabling)
        Log.d(TAG, "autoScrollReading=$enabling")
        overlayManager.setKeepScreenOnDimmed(enabling)
        if (enabling) {
            lastAutoScrollSegments = null
            screenReader.onReadingFinished = { mainHandler.post(::onAutoScrollChunkFinished) }
            readAndTrackAutoScroll()
        } else {
            screenReader.onReadingFinished = null
            mainHandler.removeCallbacksAndMessages(null)
            screenReader.stop()
        }
    }

    private fun onAutoScrollChunkFinished() {
        if (!CaptureBus.isAutoScrollReading.value) return
        if (!scrollForward()) {
            Log.d(TAG, "auto-scroll: can't scroll further, stopping")
            stopAutoScroll()
            return
        }
        mainHandler.postDelayed({ readAndTrackAutoScroll() }, SCROLL_SETTLE_DELAY_MS)
    }

    private fun readAndTrackAutoScroll() {
        if (!CaptureBus.isAutoScrollReading.value) return
        val segments = captureForegroundSegments()
        if (segments.isNullOrEmpty() || segments == lastAutoScrollSegments) {
            Log.d(TAG, "auto-scroll: no new content, stopping")
            stopAutoScroll()
            return
        }
        lastAutoScrollSegments = segments
        screenReader.read(segments)
    }

    private fun stopAutoScroll() {
        CaptureBus.setAutoScrollReading(false)
        screenReader.onReadingFinished = null
        overlayManager.setKeepScreenOnDimmed(false)
    }

    /** Captures the foreground app's text, one entry per screen element — the units [ScreenReader] reads and skips between. */
    private fun captureForegroundSegments(): List<String>? {
        val root = findForegroundAppRoot() ?: return null
        val segments = mutableListOf<String>()
        collectText(root, segments)
        root.recycle()
        Log.d(TAG, "captureForegroundSegments: collected ${segments.size} segment(s)")
        return segments
    }

    /** Scrolls the foreground app's nearest scrollable container forward. Returns false at the end of content. */
    private fun scrollForward(): Boolean {
        val root = findForegroundAppRoot() ?: return false
        val scrollable = findScrollableNode(root)
        val performed = scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
        if (scrollable != null && scrollable !== root) scrollable.recycle()
        root.recycle()
        return performed
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child)
            if (found != null) {
                if (found !== child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    /**
     * rootInActiveWindow can resolve to our own overlay right after the user taps its "ler
     * tela" button — that tap is what most recently made a window "active". Look at every
     * window instead and pick the foreground app one explicitly, skipping our own package.
     */
    private fun findForegroundAppRoot(): AccessibilityNodeInfo? {
        val allWindows = windows
        Log.d(
            TAG,
            "findForegroundAppRoot: ${allWindows.size} window(s): " +
                allWindows.joinToString {
                    "type=${it.type} active=${it.isActive} focused=${it.isFocused} title=${it.title}"
                },
        )
        for (window in allWindows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root
            if (root != null && root.packageName?.toString() != packageName) {
                return root
            }
            root?.recycle()
        }
        return null
    }

    private fun collectText(node: AccessibilityNodeInfo, into: MutableList<String>) {
        val text = extractText(node)
        if (text.isNotBlank()) into.add(text)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, into)
            child.recycle()
        }
    }

    private fun stopReading() {
        CaptureBus.setAutoScrollReading(false)
        mainHandler.removeCallbacksAndMessages(null)
        if (::overlayManager.isInitialized) overlayManager.setKeepScreenOnDimmed(false)
        if (::screenReader.isInitialized) {
            screenReader.onReadingFinished = null
            screenReader.stop()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Overlay permission may have been granted after the service connected — retry
        // here so the panel appears without requiring the user to toggle the service.
        overlayManager.show()
    }

    /** Redacts password fields — the same convention screen readers use. */
    private fun extractText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        if (node.isPassword) return "••••••"

        val direct = node.text?.toString()
        if (!direct.isNullOrBlank()) return direct

        return node.contentDescription?.toString().orEmpty()
    }

    override fun onInterrupt() {
        stopReading()
        if (::overlayManager.isInitialized) overlayManager.hide()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopReading()
        if (::overlayManager.isInitialized) overlayManager.hide()
        if (::screenReader.isInitialized) screenReader.shutdown()
        if (instance === this) instance = null
    }

    companion object {
        /** The currently connected service instance, if any — accessibility services are singletons in practice. */
        var instance: CaptureAccessibilityService? = null
            private set

        /** Opens the floating panel from outside the service, e.g. the settings screen's "abrir janela flutuante" action. */
        fun openOverlay(): Boolean {
            val service = instance ?: return false
            service.overlayManager.open()
            return true
        }

        /** Adjusts speech rate by [delta], live if the service is running, or persisted for its next start otherwise. */
        fun adjustRate(context: Context, delta: Float): Float {
            val service = instance
            return if (service != null) {
                service.screenReader.adjustRate(delta)
            } else {
                val newValue = (SpeechPrefs.rate(context) + delta).coerceIn(MIN_SPEECH_PARAM, MAX_SPEECH_PARAM)
                SpeechPrefs.saveRate(context, newValue)
                newValue
            }
        }

        /** Adjusts speech pitch by [delta], live if the service is running, or persisted for its next start otherwise. */
        fun adjustPitch(context: Context, delta: Float): Float {
            val service = instance
            return if (service != null) {
                service.screenReader.adjustPitch(delta)
            } else {
                val newValue = (SpeechPrefs.pitch(context) + delta).coerceIn(MIN_SPEECH_PARAM, MAX_SPEECH_PARAM)
                SpeechPrefs.savePitch(context, newValue)
                newValue
            }
        }
    }
}
