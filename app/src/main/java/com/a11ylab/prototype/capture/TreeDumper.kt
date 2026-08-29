package com.a11ylab.prototype.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "TreeDumper"

/** Keeps one pathological screen from producing a hundred-megabyte file. */
private const val MAX_NODES = 20_000

/**
 * Writes the raw accessibility tree of every window on screen to a text file the user can
 * open and share.
 *
 * This exists because the Gmail bug was fixed blind six times. A dump taken on the actual
 * screen that misbehaves answers, with facts, the questions the geometry heuristics were
 * guessing at: is the inbox still in the tree at all, does it report itself visible, where
 * does it claim to be, and — the one that turned out to matter — is it marked *not
 * important for accessibility*, i.e. is the app already telling screen readers to ignore
 * it? Taking one dump in each [CaptureTuning.includeNotImportantViews] mode and diffing
 * them shows exactly which subtrees that flag adds.
 */
object TreeDumper {

    /**
     * Dumps every window's tree and saves it. Returns a human-readable location for a
     * toast, or null if the file could not be written.
     */
    fun dump(
        context: Context,
        windows: List<AccessibilityWindowInfo>,
        header: String,
    ): String? {
        val report = StringBuilder(64 * 1024)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        report.append("Accessibility Lab — dump da árvore de acessibilidade\n")
        report.append("gerado em: ").append(stamp).append('\n')
        report.append("android: ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")")
            .append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        report.append(header).append('\n')
        report.append("janelas: ").append(windows.size).append("\n\n")

        val counter = intArrayOf(0)
        val bounds = Rect()
        for ((index, window) in windows.withIndex()) {
            window.getBoundsInScreen(bounds)
            report.append("================ JANELA #").append(index).append(" ================\n")
            report.append("  id=").append(window.id)
                .append(" type=").append(windowTypeName(window.type))
                .append(" layer=").append(window.layer)
                .append(" focused=").append(window.isFocused)
                .append(" active=").append(window.isActive)
                .append(" bounds=").append(bounds.toShortString())
                .append('\n')
            report.append("  title=").append(window.title ?: "-").append('\n')
            val root = window.root
            if (root == null) {
                report.append("  (sem root — o sistema não deu acesso ao conteúdo desta janela)\n\n")
                continue
            }
            report.append("  package=").append(root.packageName ?: "-").append('\n')
            appendNode(report, root, 1, counter)
            root.recycle()
            report.append('\n')
            if (counter[0] >= MAX_NODES) {
                report.append("… corte em ").append(MAX_NODES).append(" nós.\n")
                break
            }
        }

        report.append("\nLegenda dos campos por nó:\n")
        report.append("  vis   = isVisibleToUser — o sistema considera o nó visível ao usuário\n")
        report.append("  IMP   = isImportantForAccessibility — false significa que o app pediu\n")
        report.append("          para leitores de tela ignorarem este nó (e, no caso de\n")
        report.append("          noHideDescendants, toda a subárvore). É o sinal que o modo\n")
        report.append("          leitor de tela usa para não ler um painel mantido vivo atrás\n")
        report.append("          da tela atual.\n")
        report.append("  srf   = isScreenReaderFocusable (API 28+)\n")
        report.append("  draw  = drawingOrder entre os irmãos\n")
        report.append("  SCROLLABLE/DOWN, /RIGHT, /FWD = ações de rolagem que o nó oferece.\n")
        report.append("          Um pager oferece /FWD e /RIGHT mas não /DOWN — rolá-lo troca\n")
        report.append("          de página (de e-mail), não de posição.\n")
        report.append("  pane  = paneTitle (API 28+) — o app declarou este nó como um painel\n")

        return save(context, report.toString())
    }

    private fun appendNode(
        out: StringBuilder,
        node: AccessibilityNodeInfo,
        depth: Int,
        counter: IntArray,
    ) {
        if (counter[0] >= MAX_NODES) return
        counter[0]++

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val indent = "  ".repeat(depth)

        out.append(indent).append(node.className ?: "?")
        node.viewIdResourceName?.let { out.append(" #").append(it.substringAfterLast('/')) }
        out.append('\n')

        out.append(indent).append("  ")
            .append("vis=").append(node.isVisibleToUser)
            .append(" IMP=").append(node.isImportantForAccessibility)
            .append(" bounds=").append(bounds.toShortString())
            .append(" draw=").append(node.drawingOrder)
            .append(" children=").append(node.childCount)
        if (node.isClickable) out.append(" CLICKABLE")
        if (node.isScrollable) {
            out.append(" SCROLLABLE")
            // Which way it scrolls decides whether auto-scroll may target it: ACTION_SCROLL_FORWARD
            // on a horizontal pager swipes to the next page, which is how reading one Gmail
            // message turned into reading the next one.
            val actions = node.actionList
            if (actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN)) out.append("/DOWN")
            if (actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT)) out.append("/RIGHT")
            if (actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)) out.append("/FWD")
        }
        if (node.isPassword) out.append(" PASSWORD")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            out.append(" srf=").append(node.isScreenReaderFocusable)
            node.paneTitle?.let { out.append(" pane=\"").append(flatten(it.toString())).append('"') }
        }
        out.append('\n')

        node.text?.toString()?.takeIf { it.isNotBlank() }?.let {
            out.append(indent).append("  text=\"").append(flatten(it)).append("\"\n")
        }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
            out.append(indent).append("  desc=\"").append(flatten(it)).append("\"\n")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            appendNode(out, child, depth + 1, counter)
            child.recycle()
        }
    }

    /**
     * Collapses newlines/tabs and clips, so one node stays on one line.
     *
     * The cap is generous on purpose: Chromium gives a container a name computed from its
     * contents, and the whole question when reading an e-mail is whether that name covers the
     * subtree or stops short. Clipping at a few hundred characters made complete names look
     * truncated in the dump and sent an earlier analysis down the wrong path.
     */
    private fun flatten(text: String): String {
        val single = text.replace(Regex("\\s+"), " ").trim()
        return if (single.length <= 2000) single else single.take(2000) + "…"
    }

    private fun windowTypeName(type: Int) = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "INPUT_METHOD"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11Y_OVERLAY"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "SPLIT_DIVIDER"
        else -> "TYPE_$type"
    }

    /**
     * Saves to the shared Downloads folder on Android 10+, where any file manager or chat
     * app can pick it up without extra permissions; below that, to the app's own external
     * files folder, which also needs none.
     */
    private fun save(context: Context, content: String): String? {
        val name = "a11ylab-dump-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return saveToAppFolder(context, name, content)
                resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Downloads/$name"
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore save failed, falling back to app folder", e)
                saveToAppFolder(context, name, content)
            }
        }
        return saveToAppFolder(context, name, content)
    }

    private fun saveToAppFolder(context: Context, name: String, content: String): String? = try {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val file = File(dir, name)
        file.writeText(content)
        file.absolutePath
    } catch (e: Exception) {
        Log.e(TAG, "dump save failed", e)
        null
    }
}
