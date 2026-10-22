package com.invictus.kodex.feature.editor

import android.content.ClipboardManager
import android.content.Context
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.content.res.ColorStateList
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.invictus.kodex.R
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import java.util.WeakHashMap

/**
 * Our own selection popup (Select all / Cut / Copy / Paste as text buttons, plus an AI spark icon for Explain). Sora's built-in
 * icon popup is switched off and this one is shown instead, so labels and width are fully ours.
 *
 * It is a non-focusable [PopupWindow], so the editor keeps focus, the keyboard and the handles.
 * It only appears once the selection has settled (handle drag / scroll hide it), and never for
 * programmatic selections such as a search-match flash.
 */
internal fun installSelectionPopup(editor: CodeEditor, explainProvider: () -> ((String) -> Unit)? = { null }) {
    runCatching { editor.getComponent(EditorTextActionWindow::class.java).isEnabled = false }
    val popup = SelectionPopup(editor, explainProvider)
    popups[editor] = popup
    editor.subscribeEvent(SelectionChangeEvent::class.java) { _, _ -> popup.onSelectionChanged() }
    editor.subscribeEvent(ScrollEvent::class.java) { _, _ -> popup.onScrolled() }
    editor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) = popup.dismiss()
    })
}

/** Hides the popup and keeps it hidden for the selection change that is about to happen. */
internal fun suppressSelectionPopup(editor: CodeEditor) {
    popups[editor]?.suppress()
}

private val popups = WeakHashMap<CodeEditor, SelectionPopup>()

private const val SETTLE_DELAY_MS = 350L
private const val SUPPRESS_MS = 800L

private class SelectionPopup(
    private val editor: CodeEditor,
    private val explainProvider: () -> ((String) -> Unit)?,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val showRunnable = Runnable { showNow() }
    private var window: PopupWindow? = null
    private var suppressUntil = 0L

    fun onSelectionChanged() {
        handler.removeCallbacks(showRunnable)
        dismiss()
        if (!isSelected()) return
        handler.postDelayed(showRunnable, SETTLE_DELAY_MS)
    }

    fun onScrolled() {
        if (window == null) return
        dismiss()
        handler.removeCallbacks(showRunnable)
        if (isSelected()) handler.postDelayed(showRunnable, SETTLE_DELAY_MS)
    }

    fun suppress() {
        suppressUntil = SystemClock.uptimeMillis() + SUPPRESS_MS
        handler.removeCallbacks(showRunnable)
        dismiss()
    }

    fun dismiss() {
        window?.dismiss()
        window = null
    }

    private fun isSelected(): Boolean = runCatching { editor.cursor.isSelected }.getOrDefault(false)

    private fun showNow() {
        if (SystemClock.uptimeMillis() < suppressUntil) return
        if (!editor.isAttachedToWindow || !isSelected()) return
        dismiss()

        val content = buildContent()
        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val w = content.measuredWidth
        val h = content.measuredHeight
        val density = editor.resources.displayMetrics.density
        val gap = (8 * density).toInt()

        val (anchorX, top, bottom) = anchor()
        val loc = IntArray(2)
        editor.getLocationInWindow(loc)

        var x = (anchorX - w / 2f).toInt().coerceIn(gap, maxOf(gap, editor.width - w - gap))
        var y = top - h - gap
        if (y < 0) y = bottom + gap // no room above the selection: go below it
        y = y.coerceIn(0, maxOf(0, editor.height - h))

        val popup = PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isFocusable = false
            isOutsideTouchable = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            elevation = 8 * density
            setBackgroundDrawable(null)
        }
        runCatching {
            popup.showAtLocation(editor, Gravity.NO_GRAVITY, loc[0] + x, loc[1] + y)
            window = popup
        }
    }

    /** Horizontal centre plus top/bottom (editor coordinates) of the selection, from Sora's handles. */
    private fun anchor(): Triple<Float, Int, Int> {
        val rowH = editor.rowHeight
        val left = handleRect("getLeftHandleDescriptor")
        val right = handleRect("getRightHandleDescriptor")
        if (left != null && right != null && (left.height() > 0f || right.height() > 0f || left.top > 0f)) {
            val sameRow = kotlin.math.abs(left.top - right.top) < rowH / 2f
            val cx = if (sameRow) (left.centerX() + right.centerX()) / 2f else editor.width / 2f
            return Triple(cx, (left.top - rowH).toInt(), right.bottom.toInt())
        }
        // Handle geometry unavailable (Sora internals changed): park it near the top of the editor.
        val fallbackTop = (48 * editor.resources.displayMetrics.density).toInt()
        return Triple(editor.width / 2f, fallbackTop, fallbackTop)
    }

    private fun handleRect(getter: String): RectF? = runCatching {
        val descriptor = editor.javaClass.getMethod(getter).invoke(editor) ?: return@runCatching null
        descriptor.javaClass.getField("position").get(descriptor) as? RectF
    }.getOrNull()

    private fun buildContent(): View {
        val ctx = editor.context
        val density = ctx.resources.displayMetrics.density
        val scheme = editor.colorScheme
        val bg = runCatching { scheme.getColor(EditorColorScheme.COMPLETION_WND_BACKGROUND) }.getOrDefault(0xFF2B2B2B.toInt())
        val fg = runCatching { scheme.getColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY) }.getOrDefault(0xFFEEEEEE.toInt())

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(bg)
                cornerRadius = 12 * density
                setStroke((1 * density).toInt().coerceAtLeast(1), (fg and 0x00FFFFFF) or 0x33000000)
            }
            setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
        }

        fun add(label: Int, action: () -> Unit) {
            row.addView(
                TextView(ctx).apply {
                    text = ctx.getString(label)
                    setTextColor(fg)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
                    minHeight = (44 * density).toInt()
                    val ripple = TypedValue()
                    if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
                        setBackgroundResource(ripple.resourceId)
                    }
                    setOnClickListener {
                        dismiss()
                        action()
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

        /** Icon-only action (the AI spark); [label] stays as the accessibility description. */
        fun addIcon(icon: Int, label: Int, action: () -> Unit) {
            row.addView(
                ImageView(ctx).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(fg)
                    contentDescription = ctx.getString(label)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
                    minimumHeight = (44 * density).toInt()
                    val ripple = TypedValue()
                    if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
                        setBackgroundResource(ripple.resourceId)
                    }
                    setOnClickListener {
                        dismiss()
                        action()
                    }
                },
                LinearLayout.LayoutParams((52 * density).toInt(), (44 * density).toInt()),
            )
        }

        val editable = editor.isEditable
        val hasClip = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.hasPrimaryClip() == true
        add(R.string.qa_select_all) { editor.selectAll() }
        if (editable) add(R.string.qa_cut) { editor.cutText() }
        add(R.string.qa_copy) { editor.copyText() }
        if (editable && hasClip) add(R.string.qa_paste) { editor.pasteText() }
        // Only when AI is on: hands the selected code to the editor's AI panel.
        explainProvider()?.let { explain ->
            addIcon(R.drawable.ic_stat_ai, R.string.qa_explain) {
                val code = runCatching {
                    editor.text.subSequence(editor.cursor.left, editor.cursor.right).toString()
                }.getOrDefault("")
                if (code.isNotBlank()) explain(code)
            }
        }
        return row
    }
}
