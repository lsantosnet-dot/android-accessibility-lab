package com.a11ylab.prototype.capture

import android.content.Context

private const val PREFS_NAME = "capture_tuning_prefs"
private const val KEY_INCLUDE_NOT_IMPORTANT = "include_not_important_views"
private const val KEY_PIXEL_OCCLUSION = "pixel_occlusion_filter"
private const val KEY_MAIN_CONTENT_ONLY = "read_main_content_only"

/**
 * The knobs that decide *what* a capture reads, persisted so they survive the service
 * restarting and can be flipped from the app's screen without a rebuild.
 *
 * They are switches rather than constants because the Gmail bug ("it reads the Gmail screen,
 * then the app, then the e-mail again") survived six blind fixes. With these, the same device
 * can produce a dump under each setting and the difference between the files decides the
 * question, instead of another guess.
 */
object CaptureTuning {

    /**
     * Whether to ask the system for views marked *not important for accessibility*
     * (`AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS`).
     *
     * On by default, and it has to be: Chromium reports every node inside a WebView with
     * `isImportantForAccessibility=false`, so with this off an open Gmail message arrives as
     * an empty `WebView` and the body is never read. Turning it off is a diagnostic move —
     * a dump taken in each mode shows exactly which subtrees the flag brings back.
     */
    fun includeNotImportantViews(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INCLUDE_NOT_IMPORTANT, true)

    fun setIncludeNotImportantViews(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_INCLUDE_NOT_IMPORTANT, value).apply()
    }

    /**
     * Whether a capture reads only the screen's main scrollable content, instead of every
     * word in the window.
     *
     * On by default. Reading the whole window is what made an open e-mail sound like the
     * Gmail home screen: of the 29 segments a real message produced, 18 were chrome —
     * "Eduardo Bolsonaro condenado Caixa de entrada" (the subject node ends with the folder
     * name), "Navegar para cima", "Gemini", "Arquivar", "Excluir", "Marcar como não lida",
     * "E-mail, 6 novas notificações", "Reunião" — wrapped around the 11 that were the
     * message. Restricted to the content container, the same screen reads as the e-mail and
     * nothing else. Turn it off to hear the sender/subject header and the buttons too.
     */
    fun readMainContentOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MAIN_CONTENT_ONLY, true)

    fun setReadMainContentOnly(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_MAIN_CONTENT_ONLY, value).apply()
    }

    /**
     * Whether to keep the painted-pixel occlusion pass — the heuristic that drops a node
     * whose bounds are covered by what siblings above it appear to paint.
     *
     * Off by default: it is the filter with a proven false-positive record (it once blanked
     * Gmail's whole message body, mistaking a floating native header for cover), and the
     * cases it was built for are handled by the visibility and off-screen tests, which always
     * run and are not affected by this switch.
     */
    fun pixelOcclusionFilter(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PIXEL_OCCLUSION, false)

    fun setPixelOcclusionFilter(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_PIXEL_OCCLUSION, value).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
