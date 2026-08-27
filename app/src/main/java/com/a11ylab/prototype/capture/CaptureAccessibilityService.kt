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

/**
 * Gives a scrolled screen a moment to finish laying out new content before it's captured.
 * WebViews need the larger share of this: their accessibility nodes still report
 * pre-scroll bounds for a while after the scroll lands, and a capture taken then reads
 * content that is no longer on screen.
 */
private const val SCROLL_SETTLE_DELAY_MS = 1200L

/** Below this normalized length, auto-scroll dedupe requires an exact match — short strings substring-match too easily. */
private const val MIN_FUZZY_DEDUPE_LENGTH = 12

private val WHITESPACE = Regex("\\s+")

/** The form segments are compared in for duplicate detection, so spacing and case differences don't defeat it. */
private fun normalizeForDedupe(segment: String) = segment.trim().replace(WHITESPACE, " ").lowercase()

/**
 * How much of a node may peek out from under the siblings covering it and still count as
 * hidden. A screen sliding out from behind another commonly leaves a sliver uncovered (a
 * strip beside a navigation bar, an off-by-a-few-pixels pane bound), and reading a whole
 * screen for its sliver is exactly the mixed-content bug this filter exists to prevent.
 */
private const val COVERAGE_TOLERANCE = 0.03

class CaptureAccessibilityService : AccessibilityService() {

    private lateinit var overlayManager: OverlayManager
    private lateinit var screenReader: ScreenReader
    private lateinit var mediaSessionController: MediaSessionController
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Everything already spoken in the current auto-scroll session, normalized, in read order.
     *
     * A scroll rarely advances a full screenful, and headers/toolbars don't move at all, so
     * consecutive captures overlap heavily. Reading each capture whole meant speaking the
     * overlap again on every cycle — the "it kept repeating parts" symptom. Only segments
     * that [alreadyReadInAutoScroll] doesn't recognize get read, and the session ends when a
     * capture adds nothing new.
     */
    private val autoScrollAlreadyRead = mutableListOf<String>()

    /**
     * Whether [segment] repeats something this auto-scroll session already spoke.
     *
     * Exact matching isn't enough: a WebView re-chunks its accessibility nodes as content
     * scrolls, so text that was already read can come back merged with a neighbor (the
     * Gmail recording that drove this heard "Folha de S.Paulo Quinta-feira, 27 de agosto…"
     * re-spoken as one segment after its pieces had each been read) and an exact set lets
     * the repeat through. A segment therefore also counts as read when it appears inside
     * something read, or when something read makes up most of it (6/10 of its length).
     */
    private fun alreadyReadInAutoScroll(segment: String): Boolean {
        val normalized = normalizeForDedupe(segment)
        if (normalized.length < MIN_FUZZY_DEDUPE_LENGTH) return autoScrollAlreadyRead.any { it == normalized }
        return autoScrollAlreadyRead.any { read ->
            read.contains(normalized) ||
                (normalized.contains(read) && read.length * 10 >= normalized.length * 6)
        }
    }

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
        val fresh = segments.filterNot(::alreadyReadInAutoScroll)
        if (fresh.isEmpty()) {
            Log.d(TAG, "auto-scroll: capture of ${segments.size} segment(s) held nothing new, stopping")
            stopAutoScroll()
            return
        }
        Log.d(TAG, "auto-scroll: reading ${fresh.size} new of ${segments.size} captured segment(s)")
        fresh.mapTo(autoScrollAlreadyRead, ::normalizeForDedupe)
        screenReader.read(fresh)
    }

    private fun stopAutoScroll() {
        CaptureBus.setAutoScrollReading(false)
        screenReader.onReadingFinished = null
        overlayManager.setKeepScreenOnDimmed(false)
    }

    /** Captures the foreground app's text, one entry per screen element — the units [ScreenReader] reads and skips between. */
    private fun captureForegroundSegments(): List<String>? {
        val app = findForegroundApp() ?: return null
        val content = collectVisibleContent(app.root, app.bounds)
        content.scrollables.forEach { it.recycle() }
        val segments = content.segments
        if (segments.isEmpty()) {
            // Nothing survived the visibility/occlusion filter. Some apps mis-report
            // isVisibleToUser on their container nodes, and reading a possibly-stale screen
            // still beats reading nothing at all — so fall back to the unfiltered tree.
            Log.w(TAG, "captureForegroundSegments: visible pass came back empty, falling back to full tree")
            collectAllText(app.root, segments)
        }
        app.root.recycle()
        // One screen commonly exposes the same string through more than one node, and not
        // always adjacently, so the consecutive-duplicate rule in appendSegment isn't
        // enough. Within a single capture a repeated string is chrome or echo, not content
        // — keep the first occurrence, in reading order.
        val deduped = mutableListOf<String>()
        val seen = hashSetOf<String>()
        for (segment in segments) {
            if (seen.add(normalizeForDedupe(segment))) deduped += segment
        }
        Log.d(TAG, "captureForegroundSegments: collected ${segments.size} segment(s), ${deduped.size} after dedupe")
        return deduped
    }

    /**
     * Scrolls the container the user is actually looking at. Returns false at the end of content.
     *
     * The same walk that filters what gets *read* also decides what gets *scrolled*: the first
     * surviving scrollable (shallowest, in reading order). Picking any visible-flagged scrollable,
     * the previous behavior, could land on the list a screen kept alive behind the one on display —
     * Gmail's inbox behind an open message — and auto-scroll would then page the hidden inbox,
     * feeding its rows into the next capture as "new" content mixed into the message being read.
     */
    private fun scrollForward(): Boolean {
        val app = findForegroundApp() ?: return false
        val content = collectVisibleContent(app.root, app.bounds)
        val fromWalk = content.scrollables.firstOrNull()
        // Same safety net as the text side: apps that mis-report visibility leave the walk empty.
        val legacy = if (fromWalk == null) findAnyScrollableNode(app.root, app.bounds) else null
        val target = fromWalk ?: legacy
        val performed = target?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
        Log.d(TAG, "scrollForward: target=${target?.className} performed=$performed")
        content.scrollables.forEach { it.recycle() }
        if (legacy != null && legacy !== app.root) legacy.recycle()
        app.root.recycle()
        return performed
    }

    /** Unfiltered scrollable lookup, the fallback mirror of [collectAllText]. Still skips subtrees
     *  that say they're invisible or sit fully off-screen — scrolling those moves nothing the user sees. */
    private fun findAnyScrollableNode(node: AccessibilityNodeInfo, clip: Rect): AccessibilityNodeInfo? {
        if (!node.isVisibleToUser) return null
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty && !Rect.intersects(bounds, clip)) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findAnyScrollableNode(child, clip)
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
    private fun findForegroundApp(): ForegroundApp? {
        val allWindows = windows
        Log.d(
            TAG,
            "findForegroundApp: ${allWindows.size} window(s): " +
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
            candidates += WindowCandidate(root, window.isFocused, window.isActive, visibleArea, window.layer, Rect(bounds))
        }

        val best = candidates.maxWithOrNull(
            compareBy<WindowCandidate>({ it.focused }, { it.active }, { it.visibleArea }, { it.layer }),
        )
        candidates.forEach { if (it !== best) it.root.recycle() }
        if (best == null) return null
        Log.d(
            TAG,
            "findForegroundApp: chose ${best.root.packageName} " +
                "(focused=${best.focused} active=${best.active} visibleArea=${best.visibleArea} " +
                "layer=${best.layer} bounds=${best.bounds})",
        )
        return ForegroundApp(best.root, best.bounds)
    }

    /** The window [findForegroundApp] chose, with its on-screen bounds — the clip for everything captured from it. */
    private class ForegroundApp(val root: AccessibilityNodeInfo, val bounds: Rect)

    /** One window in the running for [findForegroundApp], with the signals used to rank it. */
    private class WindowCandidate(
        val root: AccessibilityNodeInfo,
        val focused: Boolean,
        val active: Boolean,
        val visibleArea: Long,
        val layer: Int,
        val bounds: Rect,
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

    /** Everything that survived the visibility walk: the text to read, and the scrollable containers it lives in. */
    private class VisibleContent {
        val segments = mutableListOf<String>()

        /** Owned copies, shallowest-first in reading order — every consumer must recycle them. */
        val scrollables = mutableListOf<AccessibilityNodeInfo>()
    }

    private fun collectVisibleContent(root: AccessibilityNodeInfo, clip: Rect): VisibleContent {
        val content = VisibleContent()
        collectVisibleContentInto(root, clip, Region(), content)
        return content
    }

    /**
     * Collects the text of everything the user can actually see, one segment per element.
     *
     * Picking the right window isn't enough on its own: apps commonly keep a previous screen
     * mounted inside the *same* window as the one on display. Gmail is the case that keeps
     * prompting this — the message list stays in the tree under an opened message, so a plain
     * full-tree walk mixes the inbox into the message. Three filters keep that content out:
     *
     * 1. A node reporting `isVisibleToUser == false` is skipped along with its subtree. This
     *    is the same signal TalkBack uses to decide what is speakable, and it covers screens
     *    that were hidden (GONE) rather than removed.
     * 2. A node whose bounds don't touch [clip] — the chosen window's on-screen area — is
     *    skipped along with its subtree. A pane *slid out of view* (Gmail parks the message
     *    list beside the open message in its two-pane layout, even on phones) still reports
     *    itself visible, and off-screen bounds can never be "covered" by anything on screen,
     *    so the occlusion test below can't catch it — which is how earlier cuts at this
     *    filter still let the inbox through. All geometry below uses bounds clipped to
     *    [clip] for the same reason.
     * 3. Siblings are examined topmost-first — by [AccessibilityNodeInfo.getDrawingOrder]
     *    where the app reports it, falling back to child index (a View draws over its
     *    earlier siblings) — and a node whose clipped bounds are covered by the *pixels
     *    actually painted* above it is skipped. "Covered" tolerates a sliver of
     *    [COVERAGE_TOLERANCE] left showing, so a screen peeking out from under the one on
     *    top isn't read whole on account of the peek.
     *
     * What counts as painted matters as much as the test. Only nodes that visibly draw
     * something enter [paintedAbove]: nodes with text of their own, and childless leaves
     * (images, icons) no bigger than half the window — see [markPainted]. A container's
     * full bounds are NOT its painted area: Gmail's open message is the case that proved
     * this, floating a full-window native overlay (the sender header, the reply bar) above
     * the message WebView, and an earlier revision that counted the overlay's container
     * bounds as cover blanked the entire message body out of the reading while the header
     * around it was spoken.
     *
     * Only the *decision* is made top-down; each child's segments are merged back in child
     * order, so the reading order the user hears is unchanged.
     *
     * Scrollable nodes on surviving paths are collected along the way, so scrolling targets
     * the same content reading does.
     */
    private fun collectVisibleContentInto(
        node: AccessibilityNodeInfo,
        clip: Rect,
        paintedAbove: Region,
        into: VisibleContent,
    ) {
        if (!node.isVisibleToUser) return

        // A node with text of its own speaks for its whole subtree. WebView links and
        // headings carry their text on the node *and* repeat it piece by piece on
        // descendant nodes; reading both levels was the "…recebe essa… recebe essa…"
        // stutter heard in testing. Native text views are leaves, so they lose nothing.
        if (node.isPassword) {
            appendSegment("••••••", into.segments)
            return
        }
        val ownText = node.text?.toString().orEmpty()
        if (ownText.isNotBlank()) {
            appendSegment(ownText, into.segments)
            if (node.isScrollable) into.scrollables += AccessibilityNodeInfo.obtain(node)
            markPainted(node, ownText, clip, paintedAbove)
            return
        }

        val children = (0 until node.childCount).mapNotNull { i -> node.getChild(i)?.let { i to it } }
        val topmostFirst = children.sortedWith(
            compareByDescending<Pair<Int, AccessibilityNodeInfo>> { it.second.drawingOrder }
                .thenByDescending { it.first },
        )

        val childContent = arrayOfNulls<VisibleContent>(node.childCount)
        val childBounds = Rect()
        for ((index, child) in topmostFirst) {
            child.getBoundsInScreen(childBounds)
            val clipped = Rect(childBounds)
            val onScreen = childBounds.isEmpty || clipped.intersect(clip)
            if (!onScreen) {
                Log.d(TAG, "collect: dropping off-screen ${child.className} at $childBounds")
            } else if (!childBounds.isEmpty && isEffectivelyCovered(clipped, paintedAbove)) {
                Log.d(TAG, "collect: dropping covered ${child.className} at $clipped")
            } else {
                val content = VisibleContent()
                collectVisibleContentInto(child, clip, paintedAbove, content)
                childContent[index] = content
            }
            child.recycle()
        }

        val direct = extractText(node)
        appendSegment(direct, into.segments)
        if (node.isScrollable) into.scrollables += AccessibilityNodeInfo.obtain(node)
        for (content in childContent) {
            content ?: continue
            content.segments.forEach { appendSegment(it, into.segments) }
            into.scrollables += content.scrollables
        }

        // A node's own pixels join the painted region only after its subtree ran: a View's
        // background sits *under* its children, and must not count as cover for them —
        // only for the siblings (and their subtrees) below this node in drawing order.
        markPainted(node, direct, clip, paintedAbove)
    }

    /**
     * Adds the pixels [node] itself draws to [painted]: its bounds when it carries text of
     * its own, or when it's a childless leaf (an image, an icon). A childless leaf bigger
     * than half the window doesn't count — that shape is a touch interceptor or scrim, not
     * content, and it must not silence what's underneath.
     */
    private fun markPainted(node: AccessibilityNodeInfo, direct: String, clip: Rect, painted: Region) {
        if (direct.isBlank() && node.childCount != 0) return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty || !bounds.intersect(clip)) return
        if (direct.isBlank()) {
            val leafArea = bounds.width().toLong() * bounds.height()
            val clipArea = clip.width().toLong() * clip.height()
            if (leafArea * 2 > clipArea) return
        }
        painted.op(bounds, Region.Op.UNION)
    }

    /** True when [covered] hides all of [bounds] but at most a [COVERAGE_TOLERANCE]-sized sliver. */
    private fun isEffectivelyCovered(bounds: Rect, covered: Region): Boolean {
        val remainder = Region(bounds)
        if (!remainder.op(covered, Region.Op.DIFFERENCE)) return true
        val boundsArea = bounds.width().toLong() * bounds.height()
        return remainder.area() <= boundsArea * COVERAGE_TOLERANCE
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
