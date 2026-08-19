package com.a11ylab.prototype.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.setPadding
import com.a11ylab.prototype.capture.CaptureBus
import com.a11ylab.prototype.capture.CaptureEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Draws a small floating panel (TYPE_APPLICATION_OVERLAY) over whatever app is in the
 * foreground and streams [CaptureBus] events into it live. Plain Views on purpose —
 * hosting Compose outside an Activity needs manual Lifecycle/SavedState plumbing that
 * would distract from the point of this prototype: the AccessibilityService itself.
 */
class OverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private var rootView: View? = null
    private var logContainer: LinearLayout? = null
    private var pauseButton: Button? = null

    fun show() {
        if (rootView != null) return
        if (!Settings.canDrawOverlays(context)) return

        val container = buildView()
        rootView = container

        val params = WindowManager.LayoutParams(
            dpToPx(280),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(12)
            y = dpToPx(64)
        }

        windowManager.addView(container, params)

        scope.launch {
            CaptureBus.events.collect { events -> renderEvents(events) }
        }
        scope.launch {
            CaptureBus.isPaused.collect { paused ->
                pauseButton?.text = if (paused) "▶" else "⏸"
            }
        }
    }

    fun hide() {
        rootView?.let { windowManager.removeView(it) }
        rootView = null
        logContainer = null
        pauseButton = null
        scope.coroutineContext[Job]?.cancel()
    }

    private fun buildView(): View {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(12))

            val title = TextView(context).apply {
                text = "Accessibility Lab"
                setTextColor(Color.WHITE)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val pause = Button(context).apply {
                text = "⏸"
                setOnClickListener { CaptureBus.togglePaused() }
            }
            val clear = Button(context).apply {
                text = "limpar"
                setOnClickListener { CaptureBus.clear() }
            }
            pauseButton = pause

            addView(title)
            addView(pause)
            addView(clear)
        }

        val log = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12))
        }
        logContainer = log

        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(260),
            )
            addView(log)
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F0101418"))
            addView(header)
            addView(scrollView)
        }
    }

    private fun renderEvents(events: List<CaptureEvent>) {
        val log = logContainer ?: return
        log.removeAllViews()
        events.take(30).forEach { event -> log.addView(buildEventRow(event)) }
    }

    private fun buildEventRow(event: CaptureEvent): TextView = TextView(context).apply {
        setTextColor(Color.parseColor("#CCCCCC"))
        textSize = 11f
        setPadding(0, dpToPx(4), 0, dpToPx(4))
        text = buildString {
            append(formatter.format(event.timestampMillis))
            append(" · ")
            append(event.packageName)
            append('\n')
            append(event.eventType)
            append(" — ")
            append(event.className.substringAfterLast('.'))
            if (event.text.isNotBlank()) {
                append('\n')
                append(event.text)
            }
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()
}
