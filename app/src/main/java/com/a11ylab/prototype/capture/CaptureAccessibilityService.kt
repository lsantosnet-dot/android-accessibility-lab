package com.a11ylab.prototype.capture

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.a11ylab.prototype.overlay.OverlayManager
import com.a11ylab.prototype.reader.ScreenReader

private const val TAG = "CaptureService"

class CaptureAccessibilityService : AccessibilityService() {

    private lateinit var overlayManager: OverlayManager
    private lateinit var screenReader: ScreenReader

    override fun onServiceConnected() {
        super.onServiceConnected()
        screenReader = ScreenReader(this)
        overlayManager = OverlayManager(this, onReadScreen = ::readScreen, onStopReading = ::stopReading)
        overlayManager.show()
    }

    /** Reads everything currently loaded in the active window's accessibility tree aloud. */
    private fun readScreen() {
        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "readScreen: rootInActiveWindow is null, nothing to read")
            return
        }
        val text = StringBuilder()
        collectText(root, text)
        root.recycle()
        Log.d(TAG, "readScreen: collected ${text.length} chars")
        screenReader.read(text.toString())
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
        if (::screenReader.isInitialized) screenReader.stop()
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
        if (::overlayManager.isInitialized) overlayManager.hide()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::overlayManager.isInitialized) overlayManager.hide()
        if (::screenReader.isInitialized) screenReader.shutdown()
    }
}
