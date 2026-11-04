package dev.bunnyloader.editor

import android.content.Context
import dev.bunnyloader.editor.csharp.CsFormatter
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion

/**
 * CodeEditor with VS Code's "type over": the editor closes `(`, `[`, `{` and
 * quotes by itself, and typing the closer when it is right after the cursor
 * just moves the cursor, instead of leaving `())`.
 *
 * The keyboard comes in through [commitText]; the symbol bar, through [insertText].
 *
 * With [csharp] on, it also opens completion after `.`, `new ` and `override `, moves a `}` typed on
 * an empty line back to the indent of its block, and Tab walks through a snippet's fields.
 */
class BlCodeEditor(context: Context) : CodeEditor(context) {

    var csharp = false

    override fun commitText(text: CharSequence, applyAutoIndent: Boolean) {
        if (!stepOverCloser(text)) super.commitText(text, applyAutoIndent)
    }

    override fun commitText(text: CharSequence, applyAutoIndent: Boolean, applySymbolCompletion: Boolean) {
        if (stepOverCloser(text)) return
        dedentCloser(text)
        super.commitText(text, applyAutoIndent, applySymbolCompletion)
        completeAfter(text)
    }

    override fun insertText(text: String, selectionOffset: Int) {
        if (text == "\t") {
            if (snippetController.isInSnippet()) snippetController.shiftToNextTabStop()
            else super.insertText(" ".repeat(tabWidth), tabWidth)
            return
        }
        if (stepOverCloser(text)) return
        dedentCloser(text)
        super.insertText(text, selectionOffset)
        completeAfter(text)
    }

    private fun dedentCloser(text: CharSequence) {
        if (!csharp || text.length != 1 || text[0] != '}' || cursor.isSelected) return
        val line = cursor.leftLine
        val column = cursor.leftColumn
        val before = this.text.getLineString(line).substring(0, column)
        if (before.isEmpty() || before.isNotBlank()) return
        val remove = before.length.coerceAtMost(tabWidth)
        this.text.delete(line, column - remove, line, column)
    }

    private fun completeAfter(text: CharSequence) {
        if (!csharp || text.isEmpty()) return
        val last = text[text.length - 1]
        val wanted = last == '.' || (last == ' ' && wordBeforeCursorIs("new", "override"))
        // Later, not now: the editor closes the list after a character that is not part of a word,
        // and it ignores requests that come right after another one.
        if (wanted) postDelayed({ getComponent(EditorAutoCompletion::class.java).requireCompletion() }, COMPLETE_DELAY_MS)
    }

    private fun wordBeforeCursorIs(vararg words: String): Boolean {
        val line = this.text.getLineString(cursor.leftLine).substring(0, cursor.leftColumn).trimEnd()
        return words.any { line == it || line.endsWith(" $it") || line.endsWith("\t$it") || line.endsWith("($it") }
    }

    /** Comments the selected lines out with `//`, or back in if all of them already are. */
    fun toggleLineComment() {
        val content = this.text
        val first = cursor.leftLine
        val last = cursor.rightLine
        val lines = (first..last).filter { content.getLineString(it).isNotBlank() }
        if (lines.isEmpty()) return
        val commented = lines.all { content.getLineString(it).trimStart().startsWith("//") }
        val indent = lines.minOf { n -> content.getLineString(n).let { it.length - it.trimStart().length } }
        content.beginBatchEdit()
        for (n in lines) {
            val line = content.getLineString(n)
            if (commented) {
                val at = line.indexOf("//")
                val end = if (line.getOrNull(at + 2) == ' ') at + 3 else at + 2
                content.delete(n, at, n, end)
            } else content.insert(n, indent, "// ")
        }
        content.endBatchEdit()
    }

    /** Format Document: fixes the indentation of the whole file, as one undo step. */
    fun formatDocument() {
        val content = this.text
        val before = content.toString()
        val after = CsFormatter.reindent(before, " ".repeat(tabWidth))
        if (after == before) return
        val line = cursor.leftLine
        val column = cursor.leftColumn
        val lastLine = content.lineCount - 1
        content.replace(0, 0, lastLine, content.getColumnCount(lastLine), after)
        val targetLine = line.coerceAtMost(content.lineCount - 1)
        setSelection(targetLine, column.coerceAtMost(content.getColumnCount(targetLine)))
    }

    private fun stepOverCloser(text: CharSequence): Boolean {
        if (text.length != 1 || text[0] !in CLOSERS || cursor.isSelected) return false
        val line = cursor.leftLine
        val column = cursor.leftColumn
        val content = this.text
        if (column >= content.getColumnCount(line) || content.charAt(line, column) != text[0]) return false
        setSelection(line, column + 1)
        return true
    }

    private companion object {
        const val CLOSERS = ")]}\"'`"
        const val COMPLETE_DELAY_MS = 100L
    }
}
