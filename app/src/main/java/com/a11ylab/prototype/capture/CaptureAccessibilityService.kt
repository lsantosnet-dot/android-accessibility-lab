package com.a11ylab.prototype.capture

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.a11ylab.prototype.overlay.OverlayManager
import com.a11ylab.prototype.reader.MAX_SPEECH_PARAM
import com.a11ylab.prototype.reader.MEDIA_ACTION_PLAY_PAUSE
import com.a11ylab.prototype.reader.MEDIA_ACTION_SKIP_NEXT
import com.a11ylab.prototype.reader.MEDIA_ACTION_SKIP_PREVIOUS
import com.a11ylab.prototype.reader.MEDIA_ACTION_STOP
import com.a11ylab.prototype.reader.MIN_SPEECH_PARAM
import com.a11ylab.prototype.reader.MediaSessionController
import com.a11ylab.prototype.reader.ReaderState
import com.a11ylab.prototype.reader.ScreenReader
import com.a11ylab.prototype.reader.SpeechPrefs

private const val TAG = "CaptureService"

/** Gives a scrolled screen a moment to finish laying out new content before it's captured. */
private const val SCROLL_SETTLE_DELAY_MS = 500L

class CaptureAccessibilityService : AccessibilityService() {

    private lateinit var overlayManager: OverlayManager
    private lateinit var screenReader: ScreenReader
    private lateinit var mediaSessionController: MediaSessionController
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Everything already spoken in the current auto-scroll session.
     *
     * A scroll rarely advances a full screenful, and headers/toolbars don't move at all, so
     * consecutive captures overlap heavily. Reading each capture whole meant speaking the
     * overlap again on every cycle — the "it kept repeating parts" symptom. Only segments not
     * in this set get read, and the session ends when a capture adds nothing new.
     */
    private val autoScrollAlreadyRead = linkedSetOf<String>()

    /** Mirrors [ScreenReader.onStateChanged] so [handleMediaControl]'s play/pause toggle knows what to do. */
    private var readerState = ReaderState.IDLE

    override fun onServiceConnected() {
        super.onServiceConnected()
        screenReader = ScreenReader(this)
        mediaSessionController = MediaSessionController(this)
        screenReader.onStateChanged = { state, current, total ->
            readerState = state
            mediaSessionController.updateState(state, current, total)
        }
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

    /** play/pause on the lock-screen card is a single button — decide which action it means from the current state. */
    private fun togglePlayPauseFromMediaControl() {
        when (readerState) {
            ReaderState.READING -> screenReader.pause()
            ReaderState.PAUSED -> screenReader.resume()
            ReaderState.IDLE -> readScreen()
        }
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
            autoScrollAlreadyRead.clear()
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
        val segments = captureForegroundSegments().orEmpty()
        val fresh = segments.filterNot { it in autoScrollAlreadyRead }
        if (fresh.isEmpty()) {
            Log.d(TAG, "auto-scroll: capture of ${segments.size} segment(s) held nothing new, stopping")
            stopAutoScroll()
            return
        }
        Log.d(TAG, "auto-scroll: reading ${fresh.size} new of ${segments.size} captured segment(s)")
        autoScrollAlreadyRead += fresh
        screenReader.read(fresh)
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
        collectVisibleText(root, segments)
        if (segments.isEmpty()) {
            // Nothing survived the visibility/occlusion filter. Some apps mis-report
            // isVisibleToUser on their container nodes, and reading a possibly-stale screen
            // still beats reading nothing at all — so fall back to the unfiltered tree.
            Log.w(TAG, "captureForegroundSegments: visible pass came back empty, falling back to full tree")
            collectAllText(root, segments)
        }
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

    /** Skips invisible subtrees for the same reason [collectVisibleText] does: a screen kept alive behind
     *  the one on display still exposes its scrollable list, and scrolling that one moves nothing the user sees. */
    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (!node.isVisibleToUser) return null
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
     * Picks the window whose content the user is actually looking at.
     *
     * rootInActiveWindow is no good here: it can resolve to our own overlay right after the
     * user taps its "ler tela" button — that tap is what most recently made a window
     * "active". And [windows] isn't ordered top-to-bottom, so list position tells us nothing
     * either. Apps that keep a previous screen's window alive underneath the new one (Gmail
     * does this when you open a message) show up as two windows with the same package name,
     * and picking the wrong one is what makes the reader announce the message list instead of
     * the open message.
     *
     * Three signals decide it, in order:
     *
     * 1. **Input focus.** Exactly one window holds it, and it is by definition the screen the
     *    user is interacting with. Our panel is `FLAG_NOT_FOCUSABLE`, so tapping "ler tela"
     *    never takes focus away from the app — the flag still points at the open message.
     * 2. **Visible area.** Walking from the highest [AccessibilityWindowInfo.getLayer] down
     *    and subtracting each window's bounds as we go leaves every window with the area it
     *    actually shows. A stale window that a newer screen fully covers is left with none.
     * 3. **Z-order**, as the final tie-break.
     *
     * Layer alone was the previous cut at this and wasn't enough: a window kept alive
     * underneath can still sort above the one on display.
     */
    private fun findForegroundAppRoot(): AccessibilityNodeInfo? {
        val allWindows = windows
        Log.d(
            TAG,
            "findForegroundAppRoot: ${allWindows.size} window(s): " +
                allWindows.joinToString {
                    "type=${it.type} layer=${it.layer} active=${it.isActive} focused=${it.isFocused} title=${it.title}"
                },
        )
        val topmostFirst = allWindows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }

        val coveredFromAbove = Region()
        val bounds = Rect()
        val candidates = mutableListOf<WindowCandidate>()
        for (window in topmostFirst) {
            window.getBoundsInScreen(bounds)
            val visible = Region(bounds)
            val visibleArea = if (!bounds.isEmpty && visible.op(coveredFromAbove, Region.Op.DIFFERENCE)) {
                visible.area()
            } else {
                0L
            }
            if (!bounds.isEmpty) coveredFromAbove.op(bounds, Region.Op.UNION)

            val root = window.root ?: continue
            // Our own settings screen isn't what the user wants read back to them.
            if (root.packageName?.toString() == packageName) {
                root.recycle()
                continue
            }
            candidates += WindowCandidate(root, window.isFocused, window.isActive, visibleArea, window.layer)
        }

        val best = candidates.maxWithOrNull(
            compareBy<WindowCandidate>({ it.focused }, { it.active }, { it.visibleArea }, { it.layer }),
        )
        candidates.forEach { if (it !== best) it.root.recycle() }
        if (best == null) return null
        Log.d(
            TAG,
            "findForegroundAppRoot: chose ${best.root.packageName} " +
                "(focused=${best.focused} active=${best.active} visibleArea=${best.visibleArea} layer=${best.layer})",
        )
        return best.root
    }

    /** One window in the running for [findForegroundAppRoot], with the signals used to rank it. */
    private class WindowCandidate(
        val root: AccessibilityNodeInfo,
        val focused: Boolean,
        val active: Boolean,
        val visibleArea: Long,
        val layer: Int,
    )

    /** Total pixel area this region covers — used to compare how much of each window is left uncovered. */
    private fun Region.area(): Long {
        var total = 0L
        val iterator = RegionIterator(this)
        val rect = Rect()
        while (iterator.next(rect)) {
            total += rect.width().toLong() * rect.height().toLong()
        }
        return total
    }

    /**
     * Collects the text of everything the user can actually see, one segment per element.
     *
     * Picking the right window isn't enough on its own: apps commonly keep a previous screen
     * mounted inside the *same* window, behind the one on display. Gmail is the case that
     * prompted this — the message list stays in the tree under an opened message, so a plain
     * full-tree walk reads the list. Two filters keep that content out:
     *
     * 1. A node reporting `isVisibleToUser == false` is skipped along with its subtree. This
     *    is the same signal TalkBack uses to decide what is speakable, and it covers screens
     *    that were hidden (GONE) rather than removed.
     * 2. Siblings are examined back-to-front — a View draws over its earlier siblings — and a
     *    sibling whose bounds are fully covered by later siblings that did produce text is
     *    skipped. That catches the screen that is still laid out, still reports itself as
     *    visible, and is simply underneath.
     *
     * Only the *decision* is made back-to-front; each child's segments are merged back in
     * normal front-to-back order, so the reading order the user hears is unchanged. A
     * covering sibling only counts once it has yielded text of its own, so an empty
     * full-screen container (a touch interceptor, a transparent scrim) can't silence the
     * content behind it.
     */
    private fun collectVisibleText(node: AccessibilityNodeInfo, into: MutableList<String>) {
        if (!node.isVisibleToUser) return

        val childSegments = arrayOfNulls<List<String>>(node.childCount)
        val coveredByLaterSiblings = Region()
        val childBounds = Rect()
        for (i in node.childCount - 1 downTo 0) {
            val child = node.getChild(i) ?: continue
            child.getBoundsInScreen(childBounds)
            val stillVisible = childBounds.isEmpty ||
                Region(childBounds).op(coveredByLaterSiblings, Region.Op.DIFFERENCE)
            if (stillVisible) {
                val collected = mutableListOf<String>()
                collectVisibleText(child, collected)
                if (collected.isNotEmpty()) {
                    childSegments[i] = collected
                    if (!childBounds.isEmpty) coveredByLaterSiblings.op(childBounds, Region.Op.UNION)
                }
            }
            child.recycle()
        }

        appendSegment(extractText(node), into)
        for (segments in childSegments) {
            segments?.forEach { appendSegment(it, into) }
        }
    }

    /** Unfiltered walk, kept as the safety net for apps whose containers mis-report their visibility. */
    private fun collectAllText(node: AccessibilityNodeInfo, into: MutableList<String>) {
        appendSegment(extractText(node), into)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllText(child, into)
            child.recycle()
        }
    }

    /**
     * WebView-rendered content (e.g. an HTML email opened in Gmail) commonly exposes the same
     * string twice in a row in the accessibility tree — a container's contentDescription mirrors
     * its single text child, or adjacent nodes both describe the same rendered text. Skipping a
     * segment identical to the one immediately before it removes that echo without touching
     * intentional repeats that are further apart (e.g. a recurring section header).
     */
    private fun appendSegment(text: String, into: MutableList<String>) {
        if (text.isNotBlank() && text != into.lastOrNull()) into.add(text)
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
        if (::mediaSessionController.isInitialized) mediaSessionController.release()
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

        /** Routes a tap on the lock-screen/notification media controls to the right reader action. */
        fun handleMediaControl(action: String) {
            val service = instance ?: return
            when (action) {
                MEDIA_ACTION_PLAY_PAUSE -> service.togglePlayPauseFromMediaControl()
                MEDIA_ACTION_SKIP_NEXT -> service.screenReader.skipForward()
                MEDIA_ACTION_SKIP_PREVIOUS -> service.screenReader.skipBack()
                MEDIA_ACTION_STOP -> service.stopReading()
            }
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
