package com.a11ylab.prototype.capture

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.a11ylab.prototype.overlay.OverlayManager
import com.a11ylab.prototype.reader.ScreenReader

private const val TAG = "CaptureService"

/** Gives a scrolled screen a moment to finish laying out new content before it's captured. */
private const val SCROLL_SETTLE_DELAY_MS = 500L

class CaptureAccessibilityService : AccessibilityService() {

    private lateinit var overlayManager: OverlayManager
    private lateinit var screenReader: ScreenReader
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastAutoScrollText: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        screenReader = ScreenReader(this)
        overlayManager = OverlayManager(
            context = this,
            onReadScreen = ::readScreen,
            onStopReading = ::stopReading,
            onAdjustRate = { delta -> screenReader.adjustRate(delta) },
            onAdjustPitch = { delta -> screenReader.adjustPitch(delta) },
            onToggleAutoScroll = ::toggleAutoScroll,
        )
        overlayManager.show()
    }

    /** Reads everything currently loaded in the foreground app's accessibility tree aloud. */
    private fun readScreen() {
        val text = captureForegroundText()
        if (text.isNullOrBlank()) {
            Log.w(TAG, "readScreen: nothing to read")
            return
        }
        screenReader.read(text)
    }

    /** Starts/stops continuous reading: read the screen, scroll on, read again, until stopped or content stops changing. */
    private fun toggleAutoScroll() {
        val enabling = !CaptureBus.isAutoScrollReading.value
        CaptureBus.setAutoScrollReading(enabling)
        Log.d(TAG, "autoScrollReading=$enabling")
        overlayManager.setKeepScreenOn(enabling)
        if (enabling) {
            lastAutoScrollText = null
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
        val text = captureForegroundText()
        if (text.isNullOrBlank() || text == lastAutoScrollText) {
            Log.d(TAG, "auto-scroll: no new content, stopping")
            stopAutoScroll()
            return
        }
        lastAutoScrollText = text
        screenReader.read(text)
    }

    private fun stopAutoScroll() {
        CaptureBus.setAutoScrollReading(false)
        screenReader.onReadingFinished = null
        overlayManager.setKeepScreenOn(false)
    }

    private fun captureForegroundText(): String? {
        val root = findForegroundAppRoot() ?: return null
        val text = StringBuilder()
        collectText(root, text)
        root.recycle()
        Log.d(TAG, "captureForegroundText: collected ${text.length} chars")
        return text.toString()
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

    private fun collectText(node: AccessibilityNodeInfo, into: StringBuilder) {
        val text = extractText(node)
        if (text.isNotBlank()) {
            if (into.isNotEmpty()) into.append(". ")
            into.append(text)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, into)
            child.recycle()
        }
    }

    private fun stopReading() {
        CaptureBus.setAutoScrollReading(false)
        mainHandler.removeCallbacksAndMessages(null)
        if (::overlayManager.isInitialized) overlayManager.setKeepScreenOn(false)
        if (::screenReader.isInitialized) {
            screenReader.onReadingFinished = null
            screenReader.stop()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Overlay permission may have been granted after the service connected — retry
        // here so the panel appears without requiring the user to toggle the service.
        overlayManager.show()
        CaptureBus.push(
            CaptureEvent(
                timestampMillis = System.currentTimeMillis(),
                packageName = event.packageName?.toString() ?: "?",
                className = event.className?.toString() ?: "?",
                eventType = AccessibilityEvent.eventTypeToString(event.eventType),
                text = extractText(event.source),
            ),
        )
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
    }
}
