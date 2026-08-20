package com.a11ylab.prototype.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.setPadding
import com.a11ylab.prototype.BuildConfig
import com.a11ylab.prototype.capture.CaptureBus
import com.a11ylab.prototype.capture.CaptureEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

private const val SPEECH_STEP = 0.25f

/**
 * Draws a small floating panel (TYPE_APPLICATION_OVERLAY) over whatever app is in the
 * foreground and streams [CaptureBus] events into it live. Plain Views on purpose —
 * hosting Compose outside an Activity needs manual Lifecycle/SavedState plumbing that
 * would distract from the point of this prototype: the AccessibilityService itself.
 */
class OverlayManager(
    private val context: Context,
    private val onReadScreen: () -> Unit,
    private val onStopReading: () -> Unit,
    private val onAdjustRate: (delta: Float) -> Float,
    private val onAdjustPitch: (delta: Float) -> Float,
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private var rootView: View? = null
    private var logContainer: LinearLayout? = null
    private var pauseButton: Button? = null

    /** True once the user taps the close button — [show] won't re-create the panel until the service reconnects. */
    private var dismissed = false

    /** Whether the log accordion is expanded — persists across close/reopen for this service connection. */
    private var logExpanded = true

    fun show() {
        if (dismissed) return
        if (rootView != null) return
        if (!Settings.canDrawOverlays(context)) return

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
        val container = buildView(params)
        rootView = container

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

    /** User-initiated close: same teardown as [hide], but keeps the panel from popping back up. */
    private fun close() {
        dismissed = true
        hide()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildView(params: WindowManager.LayoutParams): View {
        val titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(2))

            val appName = TextView(context).apply {
                text = "Accessibility Lab"
                setTextColor(Color.WHITE)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val version = TextView(context).apply {
                text = "v${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_TIMESTAMP}"
                setTextColor(Color.parseColor("#888888"))
                textSize = 10f
            }

            addView(appName)
            addView(version)

            // Drag handle lives on the title bar only, so it doesn't fight the buttons below for touches.
            setOnTouchListener(DragHandler(params))
        }

        val readerControls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(12), dpToPx(2), dpToPx(12), dpToPx(2))

            val readButton = Button(context).apply {
                text = "🔊 ler tela"
                setOnClickListener { onReadScreen() }
            }
            val stopButton = Button(context).apply {
                text = "⏹"
                setOnClickListener { onStopReading() }
            }

            addView(readButton)
            addView(stopButton)
        }

        val speechControls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12), dpToPx(2), dpToPx(12), dpToPx(2))

            addView(buildStepperRow("Vel", onAdjustRate))
            addView(buildStepperRow("Tom", onAdjustPitch))
        }

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(12), dpToPx(2), dpToPx(12), dpToPx(8))

            val pause = Button(context).apply {
                text = "⏸"
                setOnClickListener { CaptureBus.togglePaused() }
            }
            val clear = Button(context).apply {
                text = "limpar"
                setOnClickListener { CaptureBus.clear() }
            }
            val closeButton = Button(context).apply {
                text = "✕"
                setOnClickListener { close() }
            }
            pauseButton = pause

            addView(pause)
            addView(clear)
            addView(closeButton)
        }

        val log = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12))
        }
        logContainer = log

        val scrollView = BoundedScrollView(context, maxHeightPx = dpToPx(260)).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            visibility = if (logExpanded) View.VISIBLE else View.GONE
            addView(log)
        }

        val logHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(12), dpToPx(4), dpToPx(12), dpToPx(4))
            isClickable = true
            isFocusable = true

            val label = TextView(context).apply {
                text = logHeaderText()
                setTextColor(Color.parseColor("#CCCCCC"))
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
            }
            addView(label)

            setOnClickListener {
                logExpanded = !logExpanded
                label.text = logHeaderText()
                scrollView.visibility = if (logExpanded) View.VISIBLE else View.GONE
            }
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F0101418"))
            addView(titleBar)
            addView(readerControls)
            addView(speechControls)
            addView(controls)
            addView(logHeader)
            addView(scrollView)
        }
    }

    private fun logHeaderText(): String = if (logExpanded) "▾ Eventos" else "▸ Eventos"

    /** Builds a "− 1.00x +" row that nudges a speech parameter (rate or pitch) via [onAdjust]. */
    private fun buildStepperRow(label: String, onAdjust: (delta: Float) -> Float): LinearLayout {
        lateinit var valueLabel: TextView

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL

            val name = TextView(context).apply {
                text = label
                setTextColor(Color.parseColor("#CCCCCC"))
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER_VERTICAL
            }
            val minus = Button(context).apply {
                text = "−"
                setOnClickListener { valueLabel.text = formatSpeechValue(onAdjust(-SPEECH_STEP)) }
            }
            valueLabel = TextView(context).apply {
                text = formatSpeechValue(1.0f)
                setTextColor(Color.WHITE)
                textSize = 11f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dpToPx(40), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val plus = Button(context).apply {
                text = "+"
                setOnClickListener { valueLabel.text = formatSpeechValue(onAdjust(SPEECH_STEP)) }
            }

            addView(name)
            addView(minus)
            addView(valueLabel)
            addView(plus)
        }
    }

    private fun formatSpeechValue(value: Float): String = String.format(Locale.US, "%.2fx", value)

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

    /** Shrinks to fit its content, up to [maxHeightPx], instead of always claiming a fixed height. */
    private class BoundedScrollView(context: Context, private val maxHeightPx: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val boundedHeightSpec = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, boundedHeightSpec)
        }
    }

    /** Lets the user drag the panel by its header; updates [params] and re-lays out the window as the finger moves. */
    private inner class DragHandler(private val params: WindowManager.LayoutParams) : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    rootView?.let { windowManager.updateViewLayout(it, params) }
                    return true
                }
            }
            return false
        }
    }
}
