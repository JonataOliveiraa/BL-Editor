package dev.bunnyloader.editor

import android.graphics.Typeface
import android.net.Uri
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.SymbolInputView

/** A file open in a tab. [content] holds the text, the cursor and the undo history. */
class OpenFile(path: String, uri: Uri, val content: Content) {
    var path by mutableStateOf(path)
    var uri by mutableStateOf(uri)
    var dirty by mutableStateOf(false)
    /** Goes up on each edit: saving only clears [dirty] if nothing changed while it wrote. */
    var revision by mutableIntStateOf(0)
    /** The size on disk, from opening or the last save: autosave does not trade text for nothing. */
    var savedLength = content.length
    val name get() = path.substringAfterLast('/')
}

private val SYMBOLS = arrayOf("⇥", "{", "}", "(", ")", "[", "]", ";", ".", ",", "=", "\"", "'", "`",
    "<", ">", "+", "-", "*", "/", "!", "&", "|", ":", "?", "_", "$", "#", "\\")
private val INSERTS = arrayOf("\t", "{}", "}", "()", ")", "[]", "]", ";", ".", ",", "=", "\"", "'", "`",
    "<", ">", "+", "-", "*", "/", "!", "&", "|", ":", "?", "_", "$", "#", "\\")

/**
 * The editor (sora-editor) and, below it, the bar above the keyboard: undo, redo
 * and the symbols. A single CodeEditor: switching tabs swaps the [Content], which
 * carries the cursor and the undo history.
 */
@Composable
fun CodePane(file: OpenFile, modifier: Modifier = Modifier, onEditor: (CodeEditor?) -> Unit) {
    val context = LocalContext.current
    val editor = remember {
        BlCodeEditor(context).apply {
            typefaceText = Typeface.MONOSPACE
            typefaceLineNumber = Typeface.MONOSPACE
            runCatching { colorScheme = Highlighting.colorScheme() }
            setTextSize(14f)
            setScalable(true)
            setLineNumberEnabled(true)
            setPinLineNumber(true)
            setLineNumberMarginLeft(8 * resources.displayMetrics.density)
            setHighlightCurrentLine(true)
            setWordwrap(false)
            setTabWidth(4)
            props.useICULibToSelectWords = true
            props.symbolPairAutoCompletion = true
            subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
                if (event.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) {
                    (tag as? OpenFile)?.let {
                        it.dirty = true
                        it.revision++
                    }
                }
            }
        }
    }
    DisposableEffect(editor) {
        onEditor(editor)
        onDispose {
            onEditor(null)
            editor.release()
        }
    }
    Column(modifier) {
        AndroidView(
            factory = { editor },
            update = {
                if (it.tag !== file) {
                    it.tag = file
                    it.setText(file.content, true, null)
                    it.setEditorLanguage(Highlighting.languageFor(file.name))
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth().height(44.dp).pixelPanel(fill = Bl.Panel, corner = PixelCorner.NONE)
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(stringResource(R.string.Undo), editor::undo) { GlyphIcon(Glyph.UNDO, 20.dp) }
            IconAction(stringResource(R.string.Redo), editor::redo) { GlyphIcon(Glyph.REDO, 20.dp) }
            AndroidView(
                factory = { ctx ->
                    val symbols = SymbolInputView(ctx).apply {
                        textColor = Bl.TextDim.toArgb()
                        setBackgroundColor(Bl.Panel.toArgb())
                        bindEditor(editor)
                        addSymbols(SYMBOLS, INSERTS)
                    }
                    HorizontalScrollView(ctx).apply {
                        isHorizontalScrollBarEnabled = false
                        setBackgroundColor(Bl.Panel.toArgb())
                        addView(symbols, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
                    }
                },
                modifier = Modifier.weight(1f).padding(vertical = 4.dp),
            )
        }
    }
}
