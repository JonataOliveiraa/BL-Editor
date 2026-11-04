package dev.bunnyloader.editor

import android.content.Context
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.eclipse.tm4e.core.registry.IThemeSource

/** The code colors: TextMate grammars from assets/textmate and the Bunny Dark theme. */
object Highlighting {
    private const val THEME = "textmate/bunny-dark.json"
    private var ready = false

    @Synchronized
    fun init(context: Context) {
        if (ready) return
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(context.applicationContext.assets))
        val themes = ThemeRegistry.getInstance()
        val stream = requireNotNull(FileProviderRegistry.getInstance().tryGetInputStream(THEME))
        themes.loadTheme(ThemeModel(IThemeSource.fromInputStream(stream, THEME, null), "bunny-dark").apply {
            isDark = true
        })
        themes.setTheme("bunny-dark")
        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
        ready = true
    }

    /**
     * All colors come from the theme; TextMateColorScheme reapplies the theme when it
     * attaches to the editor, so a color set here in code gets lost. The brace pair
     * under the cursor is the "highlightedDelimitersForeground" key of bunny-dark.json.
     */
    fun colorScheme(): EditorColorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())

    /** The TextMate scope by file extension; null = plain text. */
    fun scopeFor(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "js", "mjs", "cjs" -> "source.js"
        "cs" -> "source.cs"
        "json", "hjson" -> "source.json"
        "md", "markdown" -> "text.html.markdown"
        else -> null
    }

    fun languageFor(name: String): Language =
        scopeFor(name)?.let { TextMateLanguage.create(it, true) } ?: EmptyLanguage()
}
