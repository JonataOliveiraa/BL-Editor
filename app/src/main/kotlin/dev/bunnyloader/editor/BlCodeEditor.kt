package dev.bunnyloader.editor

import android.content.Context
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * CodeEditor with VS Code's "type over": the editor closes `(`, `[`, `{` and
 * quotes by itself, and typing the closer when it is right after the cursor
 * just moves the cursor, instead of leaving `())`.
 *
 * The keyboard comes in through [commitText]; the symbol bar, through [insertText].
 */
class BlCodeEditor(context: Context) : CodeEditor(context) {

    override fun commitText(text: CharSequence, applyAutoIndent: Boolean) {
        if (!stepOverCloser(text)) super.commitText(text, applyAutoIndent)
    }

    override fun commitText(text: CharSequence, applyAutoIndent: Boolean, applySymbolCompletion: Boolean) {
        if (!stepOverCloser(text)) super.commitText(text, applyAutoIndent, applySymbolCompletion)
    }

    override fun insertText(text: String, selectionOffset: Int) {
        if (!stepOverCloser(text)) super.insertText(text, selectionOffset)
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
    }
}
