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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import dev.bunnyloader.editor.csharp.CSharpApi
import dev.bunnyloader.editor.csharp.CsAnalyzer
import dev.bunnyloader.editor.csharp.ProjectIndex
import dev.bunnyloader.editor.csharp.SignatureInfo
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.widget.SelectionMovement
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.SymbolInputView

/** A file open in a tab. [content] holds the text, the cursor and the undo history. */
class OpenFile(path: String, uri: Uri, val content: Content, val image: Boolean = false) {
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
private const val SIGNATURE_DELAY_MS = 150L
private const val COMPLETION_MAX_HEIGHT_DP = 212

/**
 * Parameter info, above the keyboard: the call around the cursor with the current argument in
 * bold. With several overloads, a tap goes to the next one.
 */
@Composable
private fun SignatureBar(info: SignatureInfo, chosen: Int, onChoose: (Int) -> Unit) {
    val index = if (chosen in info.overloads.indices) chosen else info.active
    val signature = info.overloads[index]
    val count = info.overloads.size
    Row(
        Modifier.fillMaxWidth().background(Bl.FrameFill)
            .pixelClickable(PixelCorner.NONE, enabled = count > 1) { onChoose((index + 1) % count) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (count > 1) Text("${index + 1}/$count", Modifier.padding(end = 8.dp), color = Bl.PressedText, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        val text = buildAnnotatedString {
            append(signature.head)
            signature.parameters.forEachIndexed { i, parameter ->
                if (i > 0) append(", ")
                val current = i == info.argument || (i == signature.parameters.lastIndex && info.argument > i && parameter.startsWith("params "))
                if (current) withStyle(SpanStyle(color = Bl.PressedText, fontWeight = FontWeight.Bold)) { append(parameter) }
                else append(parameter)
            }
            append(signature.tail)
        }
        Text(text, Modifier.horizontalScroll(rememberScrollState()), color = Bl.TextDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false)
    }
}

private val INSERTS = arrayOf("\t", "{}", "}", "()", ")", "[]", "]", ";", ".", ",", "=", "\"", "'", "`",
    "<", ">", "+", "-", "*", "/", "!", "&", "|", ":", "?", "_", "$", "#", "\\")

/**
 * The editor (sora-editor) and, below it, the bar above the keyboard: undo, redo
 * and the symbols. A single CodeEditor: switching tabs swaps the [Content], which
 * carries the cursor and the undo history.
 */
@Composable
fun CodePane(file: OpenFile, project: ProjectIndex?, modifier: Modifier = Modifier, onEditor: (CodeEditor?) -> Unit) {
    val context = LocalContext.current
    var cursorMoves by remember { mutableIntStateOf(0) }
    var signature by remember { mutableStateOf<SignatureInfo?>(null) }
    var overload by remember { mutableIntStateOf(-1) }
    val editor = remember {
        BlCodeEditor(context).apply {
            props.overScrollEnabled = true
            getComponent(EditorAutoCompletion::class.java).apply {
                setLayout(CompletionWindowLayout())
                setAdapter(CompletionRowAdapter())
                setMaxHeight((COMPLETION_MAX_HEIGHT_DP * resources.displayMetrics.density).toInt())
            }
            typefaceText = Typeface.MONOSPACE
            typefaceLineNumber = Typeface.MONOSPACE
            runCatching { colorScheme = Highlighting.colorScheme() }
            setTextSize(14f)
            setScalable(true)
            setLineNumberEnabled(true)
            setPinLineNumber(true)
            setLineNumberMarginLeft(8 * resources.displayMetrics.density)
            setHighlightCurrentLine(true)
            setWordwrap(EditorPrefs.wordWrap(context))
            setTabWidth(4)
            props.useICULibToSelectWords = true
            props.symbolPairAutoCompletion = true
            subscribeEvent(SelectionChangeEvent::class.java) { _, _ -> cursorMoves++ }
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
    // Parameter info: a moment after the cursor settles, off the main thread.
    val isCSharp = file.name.endsWith(".cs", true)
    LaunchedEffect(file) {
        signature = null
        if (!isCSharp) return@LaunchedEffect
        snapshotFlow { cursorMoves }.collectLatest {
            delay(SIGNATURE_DELAY_MS)
            val text = editor.text.toString()
            val offset = editor.cursor.left
            val info = withContext(Dispatchers.Default) {
                runCatching {
                    CSharpApi.tree(context)?.let { CsAnalyzer(it, project).signature(text, offset) }
                }.getOrNull()
            }
            if (info?.overloads?.firstOrNull()?.head != signature?.overloads?.firstOrNull()?.head) overload = -1
            signature = info
        }
    }
    Column(modifier) {
        AndroidView(
            factory = { editor },
            update = {
                if (it.tag !== file) {
                    it.tag = file
                    it.setText(file.content, true, null)
                    it.csharp = file.name.endsWith(".cs", true)
                    it.setEditorLanguage(Highlighting.languageFor(file.name, context, project))
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        signature?.let { info -> SignatureBar(info, overload) { overload = it } }
        Row(
            Modifier.fillMaxWidth().height(44.dp).pixelPanel(fill = Bl.Panel, corner = PixelCorner.NONE)
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(stringResource(R.string.Undo), editor::undo) { GlyphIcon(Glyph.UNDO, 20.dp) }
            IconAction(stringResource(R.string.Redo), editor::redo) { GlyphIcon(Glyph.REDO, 20.dp) }
            IconAction(stringResource(R.string.CursorLeft), { editor.moveSelection(SelectionMovement.LEFT) }) {
                GlyphIcon(Glyph.CHEVRON_RIGHT, 14.dp, Modifier.rotate(180f))
            }
            IconAction(stringResource(R.string.CursorRight), { editor.moveSelection(SelectionMovement.RIGHT) }) {
                GlyphIcon(Glyph.CHEVRON_RIGHT, 14.dp)
            }
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
