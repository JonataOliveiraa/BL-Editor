package dev.bunnyloader.editor.csharp

/** A C# token. Comments and whitespace do not make tokens. */
class Token(val kind: Kind, val start: Int, val end: Int, val text: String) {
    enum class Kind { IDENT, NUMBER, STRING, CHAR, SYMBOL }

    fun isSymbol(s: String) = kind == Kind.SYMBOL && text == s
    fun isWord(s: String) = kind == Kind.IDENT && text == s
    val isIdent get() = kind == Kind.IDENT
}

/** What the lexer found, and whether a position falls where completion makes no sense. */
class LexResult(val tokens: List<Token>, private val quiet: IntArray) {
    /** True inside a comment or a string literal. */
    fun isQuiet(offset: Int): Boolean {
        var low = 0
        var high = quiet.size / 2 - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val start = quiet[mid * 2]
            val end = quiet[mid * 2 + 1]
            when {
                offset <= start -> high = mid - 1
                offset >= end -> low = mid + 1
                else -> return true
            }
        }
        return false
    }
}

object CsLexer {
    private val TWO = setOf("=>", "==", "!=", "<=", ">=", "&&", "||", "++", "--", "+=", "-=", "*=", "/=", "%=", "::", "??", "?.", "<<", "->", "|=", "&=", "^=")

    fun lex(s: CharSequence, limit: Int = s.length): LexResult {
        val tokens = ArrayList<Token>(limit / 5 + 8)
        var quiet = IntArray(32)
        var quietCount = 0
        fun addQuiet(start: Int, end: Int) {
            if (quietCount + 2 > quiet.size) quiet = quiet.copyOf(quiet.size * 2)
            quiet[quietCount++] = start
            quiet[quietCount++] = end
        }
        var i = 0
        while (i < limit) {
            val c = s[i]
            when {
                c <= ' ' -> i++
                c == '/' && i + 1 < limit && s[i + 1] == '/' -> {
                    val start = i
                    while (i < limit && s[i] != '\n') i++
                    addQuiet(start, if (i >= limit) Int.MAX_VALUE else i + 1)
                }
                c == '/' && i + 1 < limit && s[i + 1] == '*' -> {
                    val start = i
                    i += 2
                    while (i < limit && !(s[i] == '*' && i + 1 < limit && s[i + 1] == '/')) i++
                    if (i >= limit) addQuiet(start, Int.MAX_VALUE) else { i += 2; addQuiet(start, i) }
                }
                c == '#' && lineStartsHere(s, i) -> {
                    // A preprocessor line: nothing on it is code.
                    val start = i
                    while (i < limit && s[i] != '\n') i++
                    addQuiet(start, if (i >= limit) Int.MAX_VALUE else i + 1)
                }
                c == '"' || ((c == '$' || c == '@') && stringAhead(s, i, limit)) -> {
                    val start = i
                    i = skipString(s, i, limit)
                    val closed = i <= limit && i > start + 1 && s[i - 1] == '"'
                    tokens.add(Token(Token.Kind.STRING, start, i, ""))
                    addQuiet(start, if (closed) i else Int.MAX_VALUE)
                }
                c == '\'' -> {
                    val start = i
                    i++
                    while (i < limit && s[i] != '\'' && s[i] != '\n') {
                        if (s[i] == '\\') i++
                        i++
                    }
                    val closed = i < limit && s[i] == '\''
                    if (closed) i++
                    i = i.coerceAtMost(limit)
                    tokens.add(Token(Token.Kind.CHAR, start, i, ""))
                    addQuiet(start, if (closed) i else if (i >= limit) Int.MAX_VALUE else i + 1)
                }
                c == '_' || c.isLetter() || (c == '@' && i + 1 < limit && s[i + 1].isLetter()) -> {
                    val start = i
                    i++
                    while (i < limit && (s[i] == '_' || s[i].isLetterOrDigit())) i++
                    tokens.add(Token(Token.Kind.IDENT, start, i, s.subSequence(start, i).toString().removePrefix("@")))
                }
                c.isDigit() -> {
                    val start = i
                    while (i < limit && (s[i].isLetterOrDigit() || s[i] == '_' ||
                            (s[i] == '.' && i + 1 < limit && s[i + 1].isDigit()))) i++
                    tokens.add(Token(Token.Kind.NUMBER, start, i, s.subSequence(start, i).toString()))
                }
                else -> {
                    val two = if (i + 1 < limit) s.subSequence(i, i + 2).toString() else ""
                    if (two in TWO) {
                        tokens.add(Token(Token.Kind.SYMBOL, i, i + 2, two))
                        i += 2
                    } else {
                        tokens.add(Token(Token.Kind.SYMBOL, i, i + 1, c.toString()))
                        i++
                    }
                }
            }
        }
        return LexResult(tokens, quiet.copyOf(quietCount))
    }

    private fun lineStartsHere(s: CharSequence, at: Int): Boolean {
        var i = at - 1
        while (i >= 0 && s[i] != '\n') {
            if (s[i] != ' ' && s[i] != '\t') return false
            i--
        }
        return true
    }

    private fun stringAhead(s: CharSequence, at: Int, limit: Int): Boolean {
        var i = at + 1
        if (i < limit && (s[i] == '$' || s[i] == '@')) i++
        return i < limit && s[i] == '"'
    }

    private fun skipString(s: CharSequence, at: Int, limit: Int): Int {
        var i = at
        var verbatim = false
        var interpolated = false
        while (s[i] != '"') {
            if (s[i] == '@') verbatim = true
            if (s[i] == '$') interpolated = true
            i++
        }
        if (i + 2 < limit && s[i + 1] == '"' && s[i + 2] == '"') {
            // A raw string literal.
            i += 3
            while (i + 2 < limit && !(s[i] == '"' && s[i + 1] == '"' && s[i + 2] == '"')) i++
            return (i + 3).coerceAtMost(limit)
        }
        i++
        var holes = 0
        while (i < limit) {
            val c = s[i]
            when {
                holes > 0 -> {
                    if (c == '{') holes++
                    if (c == '}') holes--
                    i++
                }
                interpolated && c == '{' -> {
                    if (i + 1 < limit && s[i + 1] == '{') i += 2 else { holes = 1; i++ }
                }
                verbatim && c == '"' -> {
                    if (i + 1 < limit && s[i + 1] == '"') i += 2 else return i + 1
                }
                !verbatim && c == '\\' -> i += 2
                !verbatim && c == '\n' -> return i
                c == '"' -> return i + 1
                else -> i++
            }
        }
        return limit
    }
}

/** A member the file itself declares. */
class CsMember(
    val kind: Char,
    val name: String,
    val type: String?,
    val parameters: List<String>,
    val generics: List<String>,
    val modifiers: Set<String>,
    /** The token range of the body, if it has one. */
    val bodyStart: Int = -1,
    val bodyEnd: Int = -1,
    /** The token of a parameter list's "(": the parameters are locals in the body. */
    val parameterTokens: IntRange? = null,
)

/** A type the file itself declares. */
class CsTypeDecl(
    val kind: String,
    val name: String,
    val generics: List<String>,
    val bases: List<String>,
    val modifiers: Set<String>,
    val namespace: String,
    val outer: CsTypeDecl?,
    val bodyStart: Int,
    var bodyEnd: Int,
) {
    val members = ArrayList<CsMember>()
    val nested = ArrayList<CsTypeDecl>()
    val fullName: String get() = when {
        outer != null -> "${outer.fullName}.$name"
        namespace.isEmpty() -> name
        else -> "$namespace.$name"
    }
}

/**
 * A shallow read of a C# file: usings, namespace, types and member headers. Method bodies are only
 * skipped here; they are read for locals when the cursor is in one. It never fails: code being
 * typed is usually broken, and whatever could not be read is simply not there.
 */
class CsFile(val lex: LexResult) {
    val tokens get() = lex.tokens
    val usings = ArrayList<String>()
    val usingStatics = ArrayList<String>()
    val aliases = HashMap<String, String>()
    var namespace = ""
    val types = ArrayList<CsTypeDecl>()
    /** Offset where a new `using` line goes. */
    var usingInsertOffset = 0
    var hasUsings = false

    fun allTypes(): List<CsTypeDecl> = buildList {
        fun visit(t: CsTypeDecl) {
            add(t)
            t.nested.forEach(::visit)
        }
        types.forEach(::visit)
    }

    /** The innermost type whose body holds token [index]. */
    fun typeAt(index: Int): CsTypeDecl? {
        var best: CsTypeDecl? = null
        for (type in allTypes()) if (index in type.bodyStart..type.bodyEnd) best = type
        return best
    }

    fun memberAt(type: CsTypeDecl, index: Int): CsMember? =
        type.members.firstOrNull { it.bodyStart in 0..index && index <= it.bodyEnd }

    companion object {
        private val MODIFIERS = setOf("public", "private", "protected", "internal", "static", "abstract", "virtual", "override",
            "sealed", "readonly", "const", "unsafe", "partial", "new", "extern", "async", "volatile", "ref", "required", "file")
        private val TYPE_KEYWORDS = setOf("class", "struct", "interface", "enum", "record")

        fun parse(text: CharSequence, limit: Int = text.length): CsFile {
            val file = CsFile(CsLexer.lex(text, limit))
            Parser(file).run()
            return file
        }

        /** Reads a type name from [start]; returns the token index after it, or -1 if there is none. */
        fun skipType(tokens: List<Token>, start: Int): Int {
            var i = start
            if (i >= tokens.size) return -1
            if (tokens[i].isSymbol("(")) {
                // A tuple type.
                val close = matching(tokens, i, "(", ")")
                if (close < 0) return -1
                i = close + 1
            } else {
                if (!tokens[i].isIdent) return -1
                i++
                while (true) {
                    if (i < tokens.size && tokens[i].isSymbol("<")) {
                        val close = closeAngle(tokens, i)
                        if (close < 0) return -1
                        i = close + 1
                    }
                    if (i + 1 < tokens.size && (tokens[i].isSymbol(".") || tokens[i].isSymbol("::")) && tokens[i + 1].isIdent) i += 2
                    else break
                }
            }
            while (i < tokens.size) {
                if (tokens[i].isSymbol("?") || tokens[i].isSymbol("*")) { i++; continue }
                if (tokens[i].isSymbol("[")) {
                    var j = i + 1
                    while (j < tokens.size && tokens[j].isSymbol(",")) j++
                    if (j < tokens.size && tokens[j].isSymbol("]")) { i = j + 1; continue }
                }
                break
            }
            return i
        }

        /** The `>` that closes the `<` at [open], if what is in between can be type arguments. */
        fun closeAngle(tokens: List<Token>, open: Int): Int {
            var depth = 0
            var i = open
            while (i < tokens.size && i - open < 64) {
                val t = tokens[i]
                when {
                    t.isSymbol("<") -> depth++
                    t.isSymbol(">") -> { depth--; if (depth == 0) return i }
                    t.kind == Token.Kind.SYMBOL && t.text !in ANGLE_OK -> return -1
                    t.kind == Token.Kind.NUMBER || t.kind == Token.Kind.STRING -> return -1
                }
                i++
            }
            return -1
        }

        private val ANGLE_OK = setOf(",", ".", "?", "[", "]", "(", ")", "::", "*")

        fun matching(tokens: List<Token>, open: Int, a: String, b: String): Int {
            var depth = 0
            var i = open
            while (i < tokens.size) {
                val t = tokens[i]
                if (t.kind == Token.Kind.SYMBOL) {
                    if (t.text == a) depth++
                    else if (t.text == b) { depth--; if (depth == 0) return i }
                }
                i++
            }
            return -1
        }

        fun joinTokens(tokens: List<Token>, from: Int, until: Int): String = buildString {
            for (k in from until until) {
                val t = tokens[k]
                if (t.isSymbol("?") || t.isSymbol("*") || t.kind == Token.Kind.SYMBOL) append(t.text) else {
                    if (isNotEmpty() && last().let { it.isLetterOrDigit() || it == '_' }) append(' ')
                    append(t.text)
                }
            }
        }
    }

    private class Parser(val file: CsFile) {
        val t = file.tokens
        var i = 0

        fun run() {
            header()
            scope(null)
        }

        private fun header() {
            var after = 0
            while (i < t.size) {
                val token = t[i]
                if (token.isWord("global") && i + 1 < t.size && t[i + 1].isWord("using")) { i++; continue }
                if (token.isWord("using")) {
                    val end = statementEnd(i)
                    readUsing(i + 1, end)
                    i = (end + 1).coerceAtMost(t.size)
                    after = t.getOrNull(end)?.end ?: token.end
                    file.hasUsings = true
                    continue
                }
                break
            }
            file.usingInsertOffset = after
        }

        private fun statementEnd(from: Int): Int {
            var j = from
            while (j < t.size && !t[j].isSymbol(";")) {
                if (j > from && (t[j].isWord("using") || t[j].isWord("namespace") || t[j].isWord("public"))) return j - 1
                j++
            }
            return j.coerceAtMost(t.size - 1)
        }

        private fun readUsing(from: Int, end: Int) {
            var j = from
            val static = j <= end && t[j].isWord("static")
            if (static) j++
            if (j + 1 <= end && t[j].isIdent && t[j + 1].isSymbol("=")) {
                val name = t[j].text
                file.aliases[name] = joinTokens(t, j + 2, end.let { if (t[it].isSymbol(";")) it else it + 1 })
                return
            }
            val stop = if (end < t.size && t[end].isSymbol(";")) end else end + 1
            val name = joinTokens(t, j, stop.coerceAtMost(t.size))
            if (name.isEmpty()) return
            if (static) file.usingStatics.add(name) else file.usings.add(name)
        }

        /** Reads declarations until the `}` of the current namespace or type, or the end. */
        private fun scope(outer: CsTypeDecl?, namespace: String = file.namespace) {
            var currentNamespace = namespace
            val pending = LinkedHashSet<String>()
            while (i < t.size) {
                val token = t[i]
                when {
                    token.isSymbol("}") -> return
                    token.isSymbol("[") -> {
                        val close = matching(t, i, "[", "]")
                        i = if (close < 0) t.size else close + 1
                    }
                    token.isWord("namespace") -> {
                        val j = (i + 1).let { skipType(t, it) }
                        val name = if (j > i + 1) joinTokens(t, i + 1, j) else ""
                        if (j >= 0 && j < t.size && t[j].isSymbol(";")) {
                            currentNamespace = name
                            file.namespace = name
                            i = j + 1
                        } else if (j >= 0 && j < t.size && t[j].isSymbol("{")) {
                            file.namespace = name
                            i = j + 1
                            scope(null, name)
                            if (i < t.size) i++
                        } else i = (if (j > 0) j else i + 1)
                    }
                    token.isIdent && token.text in MODIFIERS && !(token.text == "ref" && outer == null) -> {
                        pending.add(token.text)
                        i++
                    }
                    token.isIdent && token.text in TYPE_KEYWORDS -> {
                        readType(outer, currentNamespace, pending.toSet())
                        pending.clear()
                    }
                    token.isWord("delegate") -> {
                        pending.clear()
                        skipToEnd()
                    }
                    outer != null -> {
                        readMember(outer, pending.toSet())
                        pending.clear()
                    }
                    else -> {
                        pending.clear()
                        i++
                    }
                }
            }
        }

        private fun readType(outer: CsTypeDecl?, namespace: String, modifiers: Set<String>) {
            var kind = t[i].text
            i++
            // `record class` and `record struct` are records.
            if (kind == "record" && i < t.size && (t[i].isWord("class") || t[i].isWord("struct"))) i++
            if (kind == "record") kind = "class"
            if (i >= t.size || !t[i].isIdent) return
            val name = t[i++].text
            val generics = ArrayList<String>()
            if (i < t.size && t[i].isSymbol("<")) {
                val close = closeAngle(t, i)
                if (close > 0) {
                    for (k in i + 1 until close) if (t[k].isIdent && t[k].text != "in" && t[k].text != "out") generics.add(t[k].text)
                    i = close + 1
                }
            }
            if (i < t.size && t[i].isSymbol("(")) {
                val close = matching(t, i, "(", ")")
                i = if (close < 0) t.size else close + 1
            }
            val bases = ArrayList<String>()
            if (i < t.size && t[i].isSymbol(":")) {
                i++
                while (i < t.size) {
                    val end = skipType(t, i)
                    if (end < 0) break
                    bases.add(joinTokens(t, i, end))
                    i = end
                    if (i < t.size && t[i].isSymbol("(")) {
                        val close = matching(t, i, "(", ")")
                        i = if (close < 0) t.size else close + 1
                    }
                    if (i < t.size && t[i].isSymbol(",")) i++ else break
                }
            }
            while (i < t.size && !t[i].isSymbol("{") && !t[i].isSymbol(";")) i++
            if (i >= t.size || t[i].isSymbol(";")) {
                i = (i + 1).coerceAtMost(t.size)
                val decl = CsTypeDecl(kind, name, generics, bases, modifiers, namespace, outer, t.size, t.size)
                if (outer != null) outer.nested.add(decl) else file.types.add(decl)
                return
            }
            val decl = CsTypeDecl(kind, name, generics, bases, modifiers, namespace, outer, i, t.size)
            if (outer != null) outer.nested.add(decl) else file.types.add(decl)
            i++
            if (kind == "enum") {
                readEnum(decl)
            } else {
                scope(decl, namespace)
            }
            decl.bodyEnd = i.coerceAtMost(t.size)
            if (i < t.size) i++
        }

        private fun readEnum(decl: CsTypeDecl) {
            var expectName = true
            while (i < t.size && !t[i].isSymbol("}")) {
                if (expectName && t[i].isIdent) {
                    decl.members.add(CsMember(ApiMember.CONSTANT, t[i].text, decl.name, emptyList(), emptyList(), setOf("public", "static")))
                    expectName = false
                } else if (t[i].isSymbol(",")) expectName = true
                i++
            }
        }

        private fun skipToEnd() {
            while (i < t.size) {
                if (t[i].isSymbol(";")) { i++; return }
                if (t[i].isSymbol("{")) { skipBlock(); return }
                if (t[i].isSymbol("}")) return
                i++
            }
        }

        private fun skipBlock(): Int {
            val close = matching(t, i, "{", "}")
            val end = if (close < 0) t.size else close
            i = if (close < 0) t.size else close + 1
            return end
        }

        private fun readMember(type: CsTypeDecl, modifiers: Set<String>) {
            val start = i
            // Constructor: Name(...)
            if (t[i].isIdent && t[i].text == type.name && i + 1 < t.size && t[i + 1].isSymbol("(")) {
                i++
                readCallable(type, ApiMember.CONSTRUCTOR, type.name, null, emptyList(), modifiers)
                return
            }
            if (t[i].isSymbol("~")) { i++; skipToEnd(); return }
            val typeEnd = skipType(t, i)
            if (typeEnd < 0) {
                i++
                return
            }
            val typeText = joinTokens(t, start, typeEnd)
            if (typeText == "implicit" || typeText == "explicit" || (typeEnd < t.size && t[typeEnd].isWord("operator"))) {
                skipToEnd()
                return
            }
            i = typeEnd
            if (i >= t.size) return
            if (t[i].isWord("this")) {
                val close = t.getOrNull(i + 1)?.takeIf { it.isSymbol("[") }?.let { matching(t, i + 1, "[", "]") } ?: -1
                val parameters = if (close > 0) readParameters(i + 2, close) else emptyList()
                type.members.add(CsMember(ApiMember.INDEXER, "this", typeText, parameters, emptyList(), modifiers))
                i = if (close > 0) close + 1 else i + 1
                skipToEnd()
                return
            }
            if (!t[i].isIdent) {
                // Not a member after all: something still being typed.
                if (t[i].isSymbol(";") || t[i].isSymbol("{")) skipToEnd() else i++
                return
            }
            // Explicit interface members keep only the last name.
            var nameEnd = i
            while (nameEnd + 2 < t.size && t[nameEnd + 1].isSymbol(".") && t[nameEnd + 2].isIdent) nameEnd += 2
            val name = t[nameEnd].text
            i = nameEnd + 1
            val generics = ArrayList<String>()
            if (i < t.size && t[i].isSymbol("<")) {
                val close = closeAngle(t, i)
                if (close > 0) {
                    for (k in i + 1 until close) if (t[k].isIdent) generics.add(t[k].text)
                    i = close + 1
                }
            }
            if (i >= t.size) {
                type.members.add(CsMember(ApiMember.FIELD, name, typeText, emptyList(), emptyList(), modifiers))
                return
            }
            when {
                t[i].isSymbol("(") -> readCallable(type, ApiMember.METHOD, name, typeText, generics, modifiers)
                t[i].isSymbol("{") || t[i].isSymbol("=>") -> {
                    val bodyStart = i
                    if (t[i].isSymbol("{")) {
                        val end = skipBlock()
                        // An initializer after the accessors.
                        if (i < t.size && t[i].isSymbol("=")) skipToEnd()
                        type.members.add(CsMember(ApiMember.PROPERTY, name, typeText, emptyList(), emptyList(), modifiers, bodyStart, end))
                    } else {
                        val end = expressionEnd(i)
                        type.members.add(CsMember(ApiMember.PROPERTY, name, typeText, emptyList(), emptyList(), modifiers, bodyStart, end))
                        i = (end + 1).coerceAtMost(t.size)
                    }
                }
                else -> {
                    // field[, field2] [= value];
                    var declared = name
                    while (true) {
                        val kind = if ("const" in modifiers) ApiMember.CONSTANT else ApiMember.FIELD
                        val valueStart = i
                        while (i < t.size && !t[i].isSymbol(",") && !t[i].isSymbol(";") && !t[i].isSymbol("}")) {
                            if (t[i].isSymbol("{")) { skipBlock(); continue }
                            if (t[i].isSymbol("(")) {
                                val close = matching(t, i, "(", ")")
                                i = if (close < 0) t.size else close + 1
                                continue
                            }
                            i++
                        }
                        type.members.add(CsMember(kind, declared, typeText, emptyList(), emptyList(), modifiers, valueStart, i))
                        if (i + 1 < t.size && t[i].isSymbol(",") && t[i + 1].isIdent) {
                            declared = t[i + 1].text
                            i += 2
                            continue
                        }
                        break
                    }
                    if (i < t.size && !t[i].isSymbol("}")) i++
                }
            }
        }

        private fun expressionEnd(from: Int): Int {
            var j = from
            var depth = 0
            while (j < t.size) {
                val x = t[j]
                if (x.kind == Token.Kind.SYMBOL) {
                    when (x.text) {
                        "(", "{", "[" -> depth++
                        ")", "]" -> depth--
                        "}" -> { if (depth == 0) return j; depth-- }
                        ";" -> if (depth <= 0) return j
                    }
                }
                j++
            }
            return t.size
        }

        private fun readCallable(type: CsTypeDecl, kind: Char, name: String, returns: String?, generics: List<String>, modifiers: Set<String>) {
            val open = i
            val close = matching(t, i, "(", ")")
            if (close < 0) {
                // Parameters still being typed: they run up to the next `{` or to the end.
                val stop = (open + 1 until t.size).firstOrNull { t[it].isSymbol("{") || t[it].isSymbol(";") || t[it].isSymbol("}") } ?: t.size
                type.members.add(CsMember(kind, name, returns, readParameters(open + 1, stop), generics, modifiers))
                i = stop
                return
            }
            val parameters = readParameters(open + 1, close)
            i = close + 1
            // Base call and generic constraints.
            while (i < t.size && !t[i].isSymbol("{") && !t[i].isSymbol(";") && !t[i].isSymbol("=>") && !t[i].isSymbol("}")) i++
            var bodyStart = -1
            var bodyEnd = -1
            if (i < t.size && t[i].isSymbol("{")) {
                bodyStart = i
                bodyEnd = skipBlock()
            } else if (i < t.size && t[i].isSymbol("=>")) {
                bodyStart = i
                bodyEnd = expressionEnd(i)
                i = (bodyEnd + 1).coerceAtMost(t.size)
            } else if (i < t.size && t[i].isSymbol(";")) i++
            type.members.add(CsMember(kind, name, returns, parameters, generics, modifiers, bodyStart, bodyEnd, open..close))
        }

        /** Parameters as "Type name" texts, the shape the tree uses. */
        private fun readParameters(from: Int, until: Int): List<String> {
            val result = ArrayList<String>()
            var j = from
            while (j < until) {
                val partStart = j
                var depth = 0
                while (j < until) {
                    val x = t[j]
                    if (x.kind == Token.Kind.SYMBOL) {
                        when (x.text) {
                            "(", "[", "<", "{" -> depth++
                            ")", "]", ">", "}" -> depth--
                            "," -> if (depth == 0) break
                        }
                    }
                    j++
                }
                var k = partStart
                if (k < j && t[k].isSymbol("[")) {
                    val close = matching(t, k, "[", "]")
                    if (close in k until j) k = close + 1
                }
                val prefixes = StringBuilder()
                while (k < j && t[k].isIdent && t[k].text in PARAMETER_MODIFIERS) {
                    prefixes.append(t[k].text).append(' ')
                    k++
                }
                val typeEnd = skipType(t, k)
                if (typeEnd in (k + 1) until j + 1 && typeEnd < j && t[typeEnd].isIdent) {
                    val defaultAt = (typeEnd + 1 until j).firstOrNull { t[it].isSymbol("=") }
                    val default = if (defaultAt != null) "=" + joinTokens(t, defaultAt + 1, j) else ""
                    result.add("$prefixes${joinTokens(t, k, typeEnd)} ${t[typeEnd].text}$default")
                }
                j++
            }
            return result
        }

        private val PARAMETER_MODIFIERS = setOf("ref", "out", "in", "params", "this", "scoped")
    }
}
