package com.a11ylab.prototype.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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
import android.widget.Toast
import com.a11ylab.prototype.BuildConfig
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

/**
 * How much text a scrollable must hold to be treated as "the screen's content" rather than
 * a chrome strip that happens to scroll (a tab bar, a chip row).
 */
private const val MIN_MAIN_CONTENT_TEXT = 80

/** Below this, a child segment is too short to count as evidence that an ancestor's text repeats it. */
private const val MIN_SUMMARY_PIECE_LENGTH = 8

/**
 * Longer than any word a person says out loud. A "word" this long is a tracking token or an
 * encoded URL — newsletters are full of them, and a screen reader spelling out
 * `?qs=ABB7InYiOjEsImQiOjQ5ODN9AAcAAAAABeLWuyyLzGv…` is noise, not content.
 */
private const val MAX_SPOKEN_WORD_LENGTH = 40

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
        applyTreeMode()
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
            onDumpTree = ::dumpTree,
        )
        overlayManager.show()
        instance = this
    }

    /**
     * Puts [AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS] in the state
     * [CaptureTuning] says it should be in.
     *
     * The manifest config leaves the flag off, which is the fix for the Gmail bug: with it
     * on, the system hands back subtrees an app marked
     * `IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS` — the standard way to say "this
     * pane is still mounted but is behind the screen on display, don't read it" — and the
     * kept-alive inbox arrived in the capture no matter how the geometry was tuned
     * afterwards. Flipping it back on at runtime is for producing a comparison dump, not
     * for normal reading.
     */
    private fun applyTreeMode() {
        val info = serviceInfo ?: return
        val include = CaptureTuning.includeNotImportantViews(this)
        val updated = if (include) {
            info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        } else {
            info.flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.inv()
        }
        if (updated == info.flags) return
        info.flags = updated
        serviceInfo = info
        Log.d(TAG, "applyTreeMode: includeNotImportantViews=$include flags=0x${updated.toString(16)}")
    }

    /** Writes the raw tree of every window to a file, so a misreading screen can be diagnosed from facts. */
    private fun dumpTree() {
        val header = buildString {
            append("build=").append(BuildConfig.VERSION_NAME).append(' ').append(BuildConfig.BUILD_TIMESTAMP)
            append('\n')
            append("modo=")
            append(
                if (CaptureTuning.includeNotImportantViews(this@CaptureAccessibilityService)) {
                    "INSPETOR (inclui views não importantes — o que o uiautomator vê)"
                } else {
                    "LEITOR DE TELA (árvore já podada pelo sistema — o que o TalkBack vê)"
                },
            )
            append('\n')
            append("lerSoConteudoPrincipal=")
            append(CaptureTuning.readMainContentOnly(this@CaptureAccessibilityService))
            append(" · filtroOclusaoPixels=")
            append(CaptureTuning.pixelOcclusionFilter(this@CaptureAccessibilityService))
            append('\n')
            val chosen = findForegroundApp()
            append("janela escolhida por findForegroundApp: ")
            if (chosen == null) {
                append("(nenhuma)")
            } else {
                append(chosen.root.packageName).append(" bounds=").append(chosen.bounds.toShortString())
                chosen.root.recycle()
            }
            append('\n')
        }
        val where = TreeDumper.dump(this, windows, header)
        val message = if (where == null) "Falha ao salvar o dump" else "Dump salvo em $where"
        Log.d(TAG, "dumpTree: $message")
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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
        val main = pickMainContent(content.scrollables)
        val segments = if (CaptureTuning.readMainContentOnly(this) && main != null) {
            Log.d(
                TAG,
                "capture: reading main content ${main.className} depth=${main.depth} " +
                    "chars=${main.textLength} segments=${main.segments.size}",
            )
            main.segments.toMutableList()
        } else {
            content.segments
        }
        content.recycle()
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
        val kept = mutableListOf<String>()
        for (segment in segments) {
            if (isOpaqueToken(segment)) {
                Log.d(TAG, "capture: dropping opaque token segment of ${segment.length} char(s)")
                continue
            }
            val normalized = normalizeForDedupe(segment)
            // Containment, not equality: a wrapper's text and the pieces it is made of both
            // reach this list, and speaking "Facebook Twitter Instagram YouTube" right after a
            // longer line that already contained it is the echo this removes.
            //
            // Only for segments long enough that containment means something, though. A short
            // one matches by accident: the standalone "35" (an upvote count in the Gmail dump)
            // is a substring of any paragraph that mentions a year or a figure, and dropping it
            // would be losing content, not an echo. Short segments need an exact match.
            val duplicate = if (normalized.length >= MIN_FUZZY_DEDUPE_LENGTH) {
                kept.any { it.contains(normalized) }
            } else {
                kept.any { it == normalized }
            }
            if (duplicate) continue
            deduped += segment
            kept += normalized
        }
        Log.d(TAG, "captureForegroundSegments: collected ${segments.size} segment(s), ${deduped.size} after dedupe")
        return deduped
    }

    /**
     * Scrolls the container the user is actually looking at. Returns false at the end of content.
     *
     * Auto-scroll used to take the *shallowest* surviving scrollable. On an open Gmail message
     * that is `androidx.viewpager.widget.ViewPager #item_pager` — the horizontal pager that
     * holds the neighbouring conversations — and `ACTION_SCROLL_FORWARD` on it swipes to the
     * **next e-mail** instead of scrolling the message. A dump of a real Gmail message
     * (`captures/`) showed both scrollables side by side: the pager at depth 9 and the
     * message WebView at depth 17. That swipe is what made auto-scroll read one message, then
     * another, headers and all, and sound like it was looping over the same screen.
     *
     * [pickMainContent] picks the container with the actual content instead, and the scroll
     * uses a *vertical* action where the node offers one, so a pager can't be paged even if it
     * is somehow the only candidate left.
     */
    private fun scrollForward(): Boolean {
        val app = findForegroundApp() ?: return false
        val content = collectVisibleContent(app.root, app.bounds)
        val target = pickMainContent(content.scrollables)
            ?: content.scrollables.firstOrNull { !it.isPagerLike }
        // Same safety net as the text side: apps that mis-report visibility leave the walk empty.
        val legacy = if (target == null) findAnyScrollableNode(app.root, app.bounds) else null
        val performed = when {
            target != null -> target.scroll()
            legacy != null -> legacy.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            else -> false
        }
        Log.d(TAG, "scrollForward: target=${target?.className ?: legacy?.className} performed=$performed")
        content.recycle()
        if (legacy != null && legacy !== app.root) legacy.recycle()
        app.root.recycle()
        return performed
    }

    /**
     * Chooses the scrollable that holds the screen's content — the thing to read, and the thing
     * to scroll.
     *
     * Pagers are excluded outright: scrolling one changes *page*, not position. Among what is
     * left, containers that advertise a vertical scroll win over ones that don't, and then the
     * one holding the most text — the message body beats a chip row that also scrolls. Below
     * [MIN_MAIN_CONTENT_TEXT] characters nothing qualifies, so a screen without a real content
     * container falls back to reading the whole window.
     */
    private fun pickMainContent(candidates: List<ScrollableCandidate>): ScrollableCandidate? {
        val notPagers = candidates.filterNot { it.isPagerLike }
        if (notPagers.isEmpty()) return null
        val preferred = notPagers.filter { it.scrollsDown }.ifEmpty { notPagers }
        val best = preferred.maxWithOrNull(compareBy({ it.textLength }, { it.depth })) ?: return null
        return best.takeIf { it.textLength >= MIN_MAIN_CONTENT_TEXT }
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

        /** Owned copies, shallowest-first in reading order — every consumer must call [recycle]. */
        val scrollables = mutableListOf<ScrollableCandidate>()

        fun recycle() = scrollables.forEach { it.node.recycle() }
    }

    /**
     * A scrollable container met during the walk, with everything needed to judge whether it is
     * *the* content of the screen: how deep it sits, what its subtree yielded, and whether it
     * advertises a vertical scroll.
     */
    private class ScrollableCandidate(
        val node: AccessibilityNodeInfo,
        val className: String,
        val depth: Int,
        val segments: List<String>,
        val scrollsDown: Boolean,
    ) {
        val textLength: Int = segments.sumOf { it.length }

        /** A pager scrolls between *pages*, never within one — never a scroll target for reading. */
        val isPagerLike: Boolean = className.contains("Pager")

        /**
         * Uses the vertical action when the node offers one. `ACTION_SCROLL_FORWARD` means
         * "advance", which on a horizontally paged container is a swipe to the next page —
         * the Gmail bug. `ACTION_SCROLL_DOWN` can only ever mean down.
         */
        fun scroll(): Boolean = if (scrollsDown) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id)
        } else {
            node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
    }

    private fun collectVisibleContent(root: AccessibilityNodeInfo, clip: Rect): VisibleContent =
        // Read the switch once per capture, not once per node — a walk visits thousands.
        collectVisibleContent(root, clip, Region(), CaptureTuning.pixelOcclusionFilter(this), depth = 0)

    /**
     * Collects the text of everything the user can actually see, one segment per element, and
     * returns it for this subtree so a parent can decide what to do with it.
     *
     * Filters, in order:
     *
     * 1. A node reporting `isVisibleToUser == false` is skipped along with its subtree. This is
     *    the same signal TalkBack uses, and it covers screens hidden (GONE) rather than removed.
     * 2. A node whose bounds don't touch [clip] — the chosen window's on-screen area — is
     *    skipped along with its subtree. A pane *slid out of view* still reports itself visible:
     *    Gmail's conversation pager parks the neighbouring messages just off the right edge, at
     *    bounds like `[795,64][1486,1290]` on a 720px-wide screen.
     * 3. Optionally (off by default, see [CaptureTuning.pixelOcclusionFilter]) a node whose
     *    clipped bounds are covered by the pixels painted above it.
     *
     * The subtree is always walked, and only then does each node decide whether *its* text or
     * its children's is the better rendering of the same content. Chromium makes both mistakes
     * possible on the same screen, so neither level can be trusted a priori:
     *
     * - A container's text often repeats its descendants word for word — a Gmail paragraph
     *   arrived as one node reading "Dino e Carmen Lúcia votaram… Ler mais »" above three
     *   TextViews carrying exactly those three pieces. Reading both levels is the
     *   "…recebe essa… recebe essa…" stutter heard in testing, so when the node
     *   [saysEverythingIn] its children, the children are dropped.
     * - But a container's name is *computed from its contents and capped*, so an ancestor of a
     *   long e-mail can carry only part of it: an O Globo newsletter had a wrapper whose entire
     *   text was "Facebook Twitter Instagram YouTube" sitting above the whole letter. When the
     *   node is merely [isEchoedBy] its children, the children win — the old rule of "a node
     *   with text speaks for its subtree" stopped at that wrapper and captured 34 characters of
     *   a 700-character e-mail.
     *
     * Only the *decision* is made top-down; segments are merged back in child order, so the
     * reading order the user hears is unchanged.
     */
    private fun collectVisibleContent(
        node: AccessibilityNodeInfo,
        clip: Rect,
        paintedAbove: Region,
        occludeByPaintedPixels: Boolean,
        depth: Int,
    ): VisibleContent {
        val content = VisibleContent()
        if (!node.isVisibleToUser) return content

        if (node.isPassword) {
            appendSegment("••••••", content.segments)
            return content
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
            } else if (
                occludeByPaintedPixels &&
                !childBounds.isEmpty &&
                isEffectivelyCovered(clipped, paintedAbove)
            ) {
                Log.d(TAG, "collect: dropping covered ${child.className} at $clipped")
            } else {
                childContent[index] =
                    collectVisibleContent(child, clip, paintedAbove, occludeByPaintedPixels, depth + 1)
            }
            child.recycle()
        }

        val fromChildren = mutableListOf<String>()
        for (sub in childContent) {
            sub ?: continue
            sub.segments.forEach { appendSegment(it, fromChildren) }
        }

        val ownText = extractText(node)
        when {
            ownText.isBlank() -> fromChildren.forEach { appendSegment(it, content.segments) }

            // Nothing below to compare against: this node is the text.
            fromChildren.isEmpty() -> appendSegment(ownText, content.segments)

            // The node already says everything its subtree says, so it is the deepest level
            // that still reads as whole phrases. Descending further would only chop a
            // sentence into the inline spans it happens to be built from.
            saysEverythingIn(ownText, fromChildren) -> appendSegment(ownText, content.segments)

            // The subtree says more than this node does. Chromium computes a container's
            // name from its contents and caps it, so an ancestor of a long newsletter can
            // carry a *partial* name — the O Globo dump had a wrapper whose whole text was
            // "Facebook Twitter Instagram YouTube" sitting above the entire letter. Stopping
            // there, which is what the old "own text wins" rule did, threw the e-mail away.
            isEchoedBy(ownText, fromChildren) ->
                fromChildren.forEach { appendSegment(it, content.segments) }

            // Disjoint: both are real content.
            else -> {
                appendSegment(ownText, content.segments)
                fromChildren.forEach { appendSegment(it, content.segments) }
            }
        }

        if (node.isScrollable) content.scrollables += candidateFor(node, depth, content.segments)
        for (sub in childContent) {
            sub ?: continue
            content.scrollables += sub.scrollables
        }

        // A node's own pixels join the painted region only after its subtree ran: a View's
        // background sits *under* its children, and must not count as cover for them —
        // only for the siblings (and their subtrees) below this node in drawing order.
        if (occludeByPaintedPixels) markPainted(node, ownText, clip, paintedAbove)
        return content
    }

    private fun candidateFor(node: AccessibilityNodeInfo, depth: Int, segments: List<String>) =
        ScrollableCandidate(
            node = AccessibilityNodeInfo.obtain(node),
            className = node.className?.toString().orEmpty(),
            depth = depth,
            segments = segments.toList(),
            scrollsDown = node.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN),
        )

    /**
     * Whether [text] already contains every substantial thing [childSegments] say — i.e. this
     * node is a complete rendering of its own subtree, and reading the children too would only
     * repeat it in pieces (the "…recebe essa… recebe essa…" stutter heard in early testing).
     *
     * "Every", not "most": an earlier cut at this accepted 60% coverage and silently dropped
     * the rest of a newsletter that its container's text didn't happen to include.
     */
    private fun saysEverythingIn(text: String, childSegments: List<String>): Boolean {
        val whole = normalizeForDedupe(text)
        return childSegments
            .map(::normalizeForDedupe)
            .filter { it.length >= MIN_SUMMARY_PIECE_LENGTH }
            .all { whole.contains(it) }
    }

    /** Whether [childSegments] between them already say everything [text] says — then they are the fuller source. */
    private fun isEchoedBy(text: String, childSegments: List<String>): Boolean {
        val whole = normalizeForDedupe(text)
        if (whole.length < MIN_FUZZY_DEDUPE_LENGTH) return childSegments.isNotEmpty()
        return childSegments.joinToString(" ") { normalizeForDedupe(it) }.contains(whole)
    }

    /**
     * True for a segment that is *mostly* one unpronounceable blob — a tracking token, an
     * encoded URL. The O Globo newsletter contributed a 139-character `?qs=ABB7InYiOjEs…`
     * that would otherwise be spelled out loud.
     *
     * Deliberately not "contains a long word": a real sentence that happens to quote a long
     * link is still a sentence, and dropping it whole would lose content — the exact failure
     * mode this whole filter chain exists to avoid.
     */
    private fun isOpaqueToken(segment: String): Boolean {
        val words = segment.split(WHITESPACE).filter { it.isNotBlank() }
        if (words.isEmpty()) return false
        val opaque = words.filter { it.length > MAX_SPOKEN_WORD_LENGTH }
        if (opaque.isEmpty()) return false
        val opaqueChars = opaque.sumOf { it.length }
        return opaqueChars * 10 >= segment.length * 6
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

        /**
         * Switches between the screen-reader tree and the inspector tree, live if the service
         * is running. Off (the default) is what makes an open Gmail message read as just the
         * message; on is only for producing a comparison dump.
         */
        fun setIncludeNotImportantViews(context: Context, value: Boolean) {
            CaptureTuning.setIncludeNotImportantViews(context, value)
            instance?.applyTreeMode()
        }

        /** Turns the painted-pixel occlusion pass on or off; takes effect on the next capture. */
        fun setPixelOcclusionFilter(context: Context, value: Boolean) {
            CaptureTuning.setPixelOcclusionFilter(context, value)
        }

        /** Restricts reading to the screen's content container, or opens it back up to the whole window. */
        fun setReadMainContentOnly(context: Context, value: Boolean) {
            CaptureTuning.setReadMainContentOnly(context, value)
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
