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
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import com.a11ylab.prototype.BuildConfig
import com.a11ylab.prototype.capture.CaptureBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Draws a small floating panel (TYPE_APPLICATION_OVERLAY) over whatever app is in the
 * foreground. Plain Views on purpose — hosting Compose outside an Activity needs manual
 * Lifecycle/SavedState plumbing that would distract from the point of this prototype: the
 * AccessibilityService itself.
 */
class OverlayManager(
    private val context: Context,
    private val onReadScreen: () -> Unit,
    private val onStopReading: () -> Unit,
    private val onSkipForward: () -> Unit,
    private val onSkipBack: () -> Unit,
    private val onToggleAutoScroll: () -> Unit,
    private val onDumpTree: () -> Unit,
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(Dispatchers.Main.immediate + Job())

    private var rootView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var autoScrollButton: Button? = null

    /** True once the user taps the close button — [show] won't re-create the panel until reopened. */
    private var dismissed = false

    fun show() {
        if (dismissed) return
        if (rootView != null) return
        if (!Settings.canDrawOverlays(context)) return

        val params = WindowManager.LayoutParams(
            dpToPx(220),
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
        layoutParams = params

        windowManager.addView(container, params)

        scope.launch {
            CaptureBus.isAutoScrollReading.collect { reading ->
                autoScrollButton?.text = if (reading) "🔁 rolando…" else "🔁 rolagem auto"
            }
        }
    }

    /** Re-opens the panel even if the user previously dismissed it — used by the "abrir janela flutuante" action. */
    fun open() {
        dismissed = false
        show()
    }

    fun hide() {
        rootView?.let { windowManager.removeView(it) }
        rootView = null
        layoutParams = null
        autoScrollButton = null
        scope.coroutineContext[Job]?.cancel()
    }

    /**
     * Keeps the display on (but dimmed to near-black) while auto-scroll reading runs — with
     * the screen off, the foreground app's Activity pauses and stops responding to
     * programmatic scroll actions, silently ending the loop. TalkBack sidesteps this with
     * real touch-exploration input; we don't have that, so we just don't let the screen sleep
     * during a reading session. Dimming keeps most of the battery savings a real screen-off
     * would have given. Both the keep-on flag and the brightness override are cleared the
     * moment auto-scroll stops — however it stops — so the screen returns to normal on its own.
     */
    fun setKeepScreenOnDimmed(enabled: Boolean) {
        val view = rootView ?: return
        val params = layoutParams ?: return
        params.flags = if (enabled) {
            params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        params.screenBrightness = if (enabled) {
            0.0f
        } else {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        windowManager.updateViewLayout(view, params)
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
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(12), dpToPx(2), dpToPx(12), dpToPx(2))

            val readRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL

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

            val skipRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL

                val skipBackButton = Button(context).apply {
                    text = "⏮"
                    setOnClickListener { onSkipBack() }
                }
                val skipForwardButton = Button(context).apply {
                    text = "⏭"
                    setOnClickListener { onSkipForward() }
                }

                addView(skipBackButton)
                addView(skipForwardButton)
            }

            val autoScroll = Button(context).apply {
                text = "🔁 rolagem auto"
                setOnClickListener { onToggleAutoScroll() }
            }
            autoScrollButton = autoScroll

            addView(readRow)
            addView(skipRow)
            addView(autoScroll)
        }

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(12), dpToPx(2), dpToPx(12), dpToPx(8))

            // Both share the row evenly: the panel is only 220dp wide, and two
            // default-width buttons side by side would clip.
            fun half() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

            val closeButton = Button(context).apply {
                text = "✕ fechar"
                setOnClickListener { close() }
                layoutParams = half()
            }
            // Writes the raw accessibility tree of every window to a file. Lives on the panel
            // rather than in the app's screen because the tree that matters is the one on the
            // *other* app's screen — opening the settings Activity would replace it.
            val dumpButton = Button(context).apply {
                text = "🧪 dump"
                setOnClickListener { onDumpTree() }
                layoutParams = half()
            }

            addView(closeButton)
            addView(dumpButton)
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F0101418"))
            addView(titleBar)
            addView(readerControls)
            addView(controls)
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()

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
