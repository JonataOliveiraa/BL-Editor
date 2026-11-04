package dev.bunnyloader.editor.csharp

import android.content.Context
import android.os.Bundle
import android.util.Log
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.snippet.parser.CodeSnippetParser
import io.github.rosemoe.sora.lang.format.Formatter
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandleResult
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.SymbolPairMatch

/** The API tree, loaded once, off the main thread, the first time something needs it. */
object CSharpApi {
    @Volatile private var loaded: ApiTree? = null

    fun tree(context: Context): ApiTree? {
        loaded?.let { return it }
        return synchronized(this) {
            loaded ?: runCatching {
                val assets = context.applicationContext.assets
                val start = System.currentTimeMillis()
                ApiTree.load { name -> runCatching { assets.open("csharp/$name") }.getOrNull() }.also {
                    Log.i("BLEditor", "API tree: ${it.typeCount} types in ${System.currentTimeMillis() - start} ms")
                }
            }.onFailure { Log.e("BLEditor", "API tree is unavailable", it) }.getOrNull()?.also { loaded = it }
        }
    }
}

/**
 * C# for the editor: the colors and pairs come from the TextMate language it wraps, and completion
 * and Enter from here.
 */
class CSharpLanguage(
    private val base: Language,
    private val context: Context,
    private val project: ProjectIndex?,
) : Language {
    override fun getAnalyzeManager(): AnalyzeManager = base.analyzeManager
    override fun getInterruptionLevel(): Int = Language.INTERRUPTION_LEVEL_STRONG
    override fun getIndentAdvance(content: ContentReference, line: Int, column: Int): Int = base.getIndentAdvance(content, line, column)
    override fun useTab(): Boolean = false
    override fun getFormatter(): Formatter = base.formatter
    override fun getSymbolPairs(): SymbolPairMatch = base.symbolPairs
    override fun getNewlineHandlers(): Array<NewlineHandler> = arrayOf(CSharpNewline)
    override fun destroy() = base.destroy()

    override fun requireAutoComplete(content: ContentReference, position: CharPosition, publisher: CompletionPublisher, extra: Bundle) {
        val tree = CSharpApi.tree(context) ?: return
        val text = content.reference.toString()
        val result = CsAnalyzer(tree, project).complete(text, position.index) { publisher.checkCancelled() }
        if (result.items.isEmpty()) return
        publisher.setComparator { a, b -> (a as SuggestionItem).rank - (b as SuggestionItem).rank }
        publisher.setUpdateThreshold(Int.MAX_VALUE)
        val prefix = position.index - result.prefixStart
        val typed = text.substring(result.prefixStart, position.index)
        val items = result.items.mapIndexed { index, it -> SuggestionItem(it, index, prefix, typed, result) as CompletionItem }
        publisher.addItems(items)
        publisher.updateList(true)
    }
}

class SuggestionItem(
    private val suggestion: Suggestion,
    val rank: Int,
    prefix: Int,
    /** What was typed, for the list to color in the name. */
    val typed: String,
    private val result: CompletionResult,
) : CompletionItem(suggestion.label, suggestion.detail) {
    init {
        prefixLength = prefix
        val shown = when (suggestion.kind) {
            Suggestion.Kind.LOCAL -> CompletionItemKind.Variable
            Suggestion.Kind.FIELD -> CompletionItemKind.Field
            Suggestion.Kind.PROPERTY -> CompletionItemKind.Property
            Suggestion.Kind.METHOD -> CompletionItemKind.Method
            Suggestion.Kind.EVENT -> CompletionItemKind.Event
            Suggestion.Kind.CONSTANT -> CompletionItemKind.Constant
            Suggestion.Kind.ENUM_MEMBER -> CompletionItemKind.EnumMember
            Suggestion.Kind.CLASS -> CompletionItemKind.Class
            Suggestion.Kind.STRUCT -> CompletionItemKind.Struct
            Suggestion.Kind.INTERFACE -> CompletionItemKind.Interface
            Suggestion.Kind.ENUM -> CompletionItemKind.Enum
            Suggestion.Kind.DELEGATE -> CompletionItemKind.Reference
            Suggestion.Kind.NAMESPACE -> CompletionItemKind.Module
            Suggestion.Kind.KEYWORD -> CompletionItemKind.Keyword
            Suggestion.Kind.SNIPPET -> CompletionItemKind.Snippet
        }
        kind(shown)
    }

    override fun performCompletion(editor: CodeEditor, text: Content, position: CharPosition) {
        var end = position.index
        var start = if (suggestion.replaceFrom >= 0) suggestion.replaceFrom else end - prefixLength
        // Usings first: changing the text above an open snippet would close the snippet.
        val added = addUsings(text)
        start += added
        end += added
        val startPosition = text.indexer.getCharPosition(start)
        val endPosition = text.indexer.getCharPosition(end)
        text.delete(startPosition.line, startPosition.column, endPosition.line, endPosition.column)
        if (suggestion.snippet) {
            editor.snippetController.startSnippet(start, CodeSnippetParser.parse(suggestion.insert), "")
        } else {
            val at = text.indexer.getCharPosition(start)
            text.insert(at.line, at.column, suggestion.insert)
        }
    }

    override fun performCompletion(editor: CodeEditor, text: Content, line: Int, column: Int) {
        performCompletion(editor, text, text.indexer.getCharPosition(line, column))
    }

    private fun addUsings(text: Content): Int {
        if (suggestion.usings.isEmpty()) return 0
        val current = CsFile.parse(text.toString())
        val missing = suggestion.usings.filter { it !in current.usings }
        if (missing.isEmpty()) return 0
        val lines = missing.joinToString("") { "using $it;\n" }
        val inserted = if (current.hasUsings) {
            val at = text.indexer.getCharPosition(current.usingInsertOffset)
            val block = "\n" + lines.trimEnd('\n')
            text.insert(at.line, at.column, block)
            block.length
        } else {
            val block = lines + "\n"
            text.insert(0, 0, block)
            block.length
        }
        return inserted
    }
}

/** Enter the way Visual Studio does it: keep the indent, go in after `{`, open up `{}`, continue `///`. */
private object CSharpNewline : NewlineHandler {
    override fun matchesRequirement(text: Content, position: CharPosition, style: Styles?): Boolean = true

    override fun handleNewline(text: Content, position: CharPosition, style: Styles?, tabSize: Int): NewlineHandleResult {
        val line = text.getLineString(position.line)
        val before = line.substring(0, position.column.coerceAtMost(line.length))
        val after = line.substring(position.column.coerceAtMost(line.length))
        val indent = before.takeWhile { it == ' ' || it == '\t' }
        val step = " ".repeat(tabSize)
        val left = before.trimEnd()
        return when {
            left.endsWith("{") && after.trimStart().startsWith("}") -> {
                val closing = "\n$indent"
                NewlineHandleResult("\n$indent$step$closing", closing.length)
            }
            left.endsWith("{") || left.endsWith("(") && after.trimStart().startsWith(")") -> NewlineHandleResult("\n$indent$step", 0)
            left.trimStart().startsWith("///") -> NewlineHandleResult("\n$indent/// ", 0)
            else -> NewlineHandleResult("\n$indent", 0)
        }
    }
}
