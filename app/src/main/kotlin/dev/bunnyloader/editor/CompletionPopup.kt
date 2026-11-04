package dev.bunnyloader.editor

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import dev.bunnyloader.editor.csharp.CsAnalyzer
import dev.bunnyloader.editor.csharp.SuggestionItem
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.widget.component.CompletionLayout
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.component.EditorCompletionAdapter
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/** The completion window, VS Code style: a thin border, compact rows and nothing but a list. */
class CompletionWindowLayout : CompletionLayout {
    private lateinit var list: ListView
    private var completion: EditorAutoCompletion? = null

    override fun setEditorCompletion(completion: EditorAutoCompletion) {
        this.completion = completion
    }

    override fun inflate(context: Context): View {
        val density = context.resources.displayMetrics.density
        list = ListView(context).apply {
            divider = null
            dividerHeight = 0
            selector = ColorDrawable(AndroidColor.TRANSPARENT)
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
            onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ -> completion?.select(position) }
        }
        return FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Bl.FrameFill.toArgb())
                setStroke((density).toInt().coerceAtLeast(1), Bl.FrameBorder.toArgb())
                cornerRadius = 4 * density
            }
            clipToOutline = true
            elevation = 8 * density
            val inset = density.toInt().coerceAtLeast(1)
            setPadding(inset, inset, inset, inset)
            addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    // The colors are the app's, not the code theme's; loading is too quick to deserve a bar.
    override fun onApplyColorScheme(colorScheme: EditorColorScheme) = Unit
    override fun setLoading(loading: Boolean) = Unit
    override fun getCompletionList(): AdapterView<*> = list

    override fun ensureListPositionVisible(position: Int, increment: Int) {
        list.post { list.smoothScrollToPosition(position) }
    }
}

/** One compact line per suggestion: the kind's icon, the name with what was typed in color, the type on the right. */
class CompletionRowAdapter : EditorCompletionAdapter() {
    private val density get() = context.resources.displayMetrics.density

    override fun getItemHeight(): Int = (ROW_HEIGHT_DP * density).toInt()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup, isCurrentCursorPosition: Boolean): View {
        val row = convertView as? Row ?: Row(context, itemHeight)
        val item = getItem(position)
        val label = item.label?.toString().orEmpty()
        row.bind(
            label = highlighted(label, (item as? SuggestionItem)?.typed.orEmpty()),
            detail = item.desc?.toString().orEmpty(),
            kind = item.kind,
            current = isCurrentCursorPosition,
        )
        return row
    }

    private fun highlighted(label: String, typed: String): CharSequence {
        if (typed.isEmpty()) return label
        val mask = CsAnalyzer.matchedLetters(label, typed)
        val text = SpannableString(label)
        for (i in label.indices) {
            if (!mask[i]) continue
            text.setSpan(ForegroundColorSpan(Bl.PressedText.toArgb()), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(StyleSpan(Typeface.BOLD), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }

    private class Row(context: Context, height: Int) : LinearLayout(context) {
        private val density = context.resources.displayMetrics.density
        private val icon = TextView(context).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 11f
            includeFontPadding = false
        }
        private val iconShape = GradientDrawable().apply { cornerRadius = 3 * density }
        private val name = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(Bl.Text.toArgb())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        private val type = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Bl.TextMuted.toArgb())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            gravity = Gravity.END
        }

        init {
            layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((6 * density).toInt(), 0, (8 * density).toInt(), 0)
            icon.background = iconShape
            val side = (18 * density).toInt()
            addView(icon, LayoutParams(side, side).apply { marginEnd = (8 * density).toInt() })
            addView(name, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(type, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (10 * density).toInt()
            })
            type.maxWidth = (120 * density).toInt()
        }

        fun bind(label: CharSequence, detail: String, kind: CompletionItemKind?, current: Boolean) {
            val (letter, color) = KINDS[kind] ?: DEFAULT_KIND
            icon.text = letter
            icon.setTextColor(color)
            iconShape.setColor((color and 0x00FFFFFF) or 0x30000000)
            name.text = label
            type.text = detail
            type.visibility = if (detail.isEmpty()) GONE else VISIBLE
            setBackgroundColor(if (current) Bl.Select.toArgb() else AndroidColor.TRANSPARENT)
        }
    }

    private companion object {
        const val ROW_HEIGHT_DP = 30f

        // The symbol colors of VS Code's dark themes.
        const val PURPLE = 0xFFB180D7.toInt()
        const val BLUE = 0xFF75BEFF.toInt()
        const val ORANGE = 0xFFEE9D28.toInt()
        const val GRAY = 0xFFC5CBE8.toInt()
        val DEFAULT_KIND = "·" to GRAY
        val KINDS = mapOf(
            CompletionItemKind.Method to ("m" to PURPLE),
            CompletionItemKind.Function to ("m" to PURPLE),
            CompletionItemKind.Constructor to ("m" to PURPLE),
            CompletionItemKind.Variable to ("x" to BLUE),
            CompletionItemKind.Field to ("f" to BLUE),
            CompletionItemKind.Property to ("p" to GRAY),
            CompletionItemKind.Event to ("e" to ORANGE),
            CompletionItemKind.Constant to ("c" to BLUE),
            CompletionItemKind.EnumMember to ("v" to BLUE),
            CompletionItemKind.Class to ("C" to ORANGE),
            CompletionItemKind.Struct to ("S" to ORANGE),
            CompletionItemKind.Interface to ("I" to BLUE),
            CompletionItemKind.Enum to ("E" to ORANGE),
            CompletionItemKind.Reference to ("D" to PURPLE),
            CompletionItemKind.Module to ("N" to GRAY),
            CompletionItemKind.Keyword to ("k" to GRAY),
            CompletionItemKind.Snippet to ("{}" to GRAY),
            CompletionItemKind.Identifier to ("a" to GRAY),
            CompletionItemKind.Text to ("a" to GRAY),
        )
    }
}
