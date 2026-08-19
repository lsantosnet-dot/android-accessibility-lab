package com.a11ylab.prototype.capture

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.a11ylab.prototype.overlay.OverlayManager

class CaptureAccessibilityService : AccessibilityService() {

    private lateinit var overlayManager: OverlayManager

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlayManager = OverlayManager(this)
        overlayManager.show()
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
    }
}
