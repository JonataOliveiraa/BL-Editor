package dev.bunnyloader.editor.csharp

import dev.bunnyloader.editor.csharp.Semantics.Companion.shortText

/** One line of the completion list. */
class Suggestion(
    val label: String,
    val kind: Kind,
    val detail: String,
    /** The text that goes in; with [snippet], in snippet syntax ($1, $0). */
    val insert: String,
    val score: Int,
    val snippet: Boolean = false,
    /** Namespaces to add `using` lines for, if the file does not have them. */
    val usings: List<String> = emptyList(),
    /** When it replaces more than the word being typed (`public override Fo`): the offset it starts at. */
    val replaceFrom: Int = -1,
) {
    enum class Kind { LOCAL, FIELD, PROPERTY, METHOD, EVENT, CONSTANT, ENUM_MEMBER, CLASS, STRUCT, INTERFACE, ENUM, DELEGATE, NAMESPACE, KEYWORD, SNIPPET }
}

class CompletionResult(val prefixStart: Int, val items: List<Suggestion>, val usingOffset: Int, val hasUsings: Boolean)

/** Parameter info: the overloads of the call around the cursor. */
class SignatureInfo(val overloads: List<Signature>, val active: Int, val argument: Int) {
    class Signature(val head: String, val parameters: List<String>, val tail: String)
}

/**
 * Reads the code around the cursor and answers what can go there. It works on tokens and on the
 * API tree, so it is a shallow reader, not a compiler: when it cannot tell a type, it offers less,
 * never something wrong on purpose.
 */
class CsAnalyzer(private val tree: ApiTree, private val project: ProjectIndex? = null) {

    /** [cancelled] is polled in the long loops; it throws to stop. */
    fun complete(text: CharSequence, offset: Int, cancelled: () -> Unit = {}): CompletionResult =
        Session(text, offset, cancelled).complete()

    fun signature(text: CharSequence, offset: Int): SignatureInfo? = Session(text, offset) {}.signature()

    private sealed interface Eval {
        class Value(val type: TypeValue, val alsoStatic: Boolean = false) : Eval
        class TypeName(val type: TypeValue) : Eval
        class Namespace(val name: String) : Eval
        class Group(val receiver: TypeValue?, val isStaticAccess: Boolean, val members: List<FoundMember>) : Eval
    }

    private class Local(
        val name: String,
        val type: String?,
        val from: Int = -1,
        val until: Int = -1,
        val forEach: Boolean = false,
        val depth: Int = 0,
    )

    private inner class Session(val text: CharSequence, val offset: Int, val cancelled: () -> Unit) {
        val file = CsFile.parse(text)
        val tokens = file.tokens
        val semantics = Semantics(tree, project)
        val scope = TypeScope(file.usings, file.namespace, file.aliases)
        val prefixStart: Int
        val prefix: String
        /** Index of the first token at or after the word being typed: code before the cursor ends here. */
        val here: Int
        val localTypes: List<ApiType> = ProjectIndex.typesOf(file)
        val imports: Set<String>
        val enclosing: CsTypeDecl?
        val thisType: ApiType?
        val member: CsMember?
        private var localsRead: List<Local>? = null
        private val evaluating = HashSet<String>()

        init {
            semantics.localTypes = localTypes.associateBy { it.fullName }
            semantics.localBySimpleName = localTypes.groupBy { it.name }
            var start = offset.coerceAtMost(text.length)
            while (start > 0 && (text[start - 1] == '_' || text[start - 1].isLetterOrDigit())) start--
            prefixStart = start
            prefix = text.subSequence(start, offset.coerceAtMost(text.length)).toString()
            here = firstTokenAtOrAfter(start)
            imports = (Semantics.namespacesOf(file.namespace) + file.usings).toHashSet()
            enclosing = file.typeAt(here - 1)
            thisType = enclosing?.let { semantics.localTypes[it.fullName] }
            member = enclosing?.let { file.memberAt(it, here - 1) }
        }

        private fun firstTokenAtOrAfter(position: Int): Int {
            var low = 0
            var high = tokens.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (tokens[mid].start < position) low = mid + 1 else high = mid
            }
            return low
        }

        // ---------------------------- completion ----------------------------

        fun complete(): CompletionResult {
            if (file.lex.isQuiet(offset)) return result(emptyList())
            val before = tokens.getOrNull(here - 1)
            val items: List<Suggestion> = when {
                isUsingDirective() -> usingItems()
                before != null && (before.isSymbol(".") || before.isSymbol("?.") || before.isSymbol("::")) -> memberAccess()
                isOverride() -> overrideItems()
                isInBaseList() -> typeItems(filter = { it.kind == "class" || it.kind == "interface" })
                before != null && before.isWord("new") -> typeItems({ it.kind != "interface" && it.kind != "enum" && !it.isStatic }, construct = true)
                isDeclaringName() -> emptyList()
                isAttribute() -> attributeItems()
                member != null -> statementItems()
                enclosing != null -> memberLevelItems()
                else -> namespaceLevelItems()
            }
            return result(items)
        }

        private fun result(items: List<Suggestion>): CompletionResult {
            val sorted = items.sortedWith(compareByDescending<Suggestion> { it.score }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            return CompletionResult(prefixStart, if (sorted.size > MAX_ITEMS) sorted.subList(0, MAX_ITEMS) else sorted,
                file.usingInsertOffset, file.hasUsings)
        }

        private fun isUsingDirective(): Boolean {
            var i = here - 1
            while (i >= 0 && (tokens[i].isIdent || tokens[i].isSymbol(".")) && !tokens[i].isWord("using")) i--
            if (i < 0 || !tokens[i].isWord("using")) return false
            val previous = tokens.getOrNull(i - 1)
            return (previous == null || previous.isSymbol(";") || previous.isWord("global")) && i <= here - 1 && enclosing == null
        }

        private fun usingItems(): List<Suggestion> {
            var i = here - 1
            val parts = ArrayList<String>()
            if (i >= 0 && tokens[i].isSymbol(".")) {
                i--
                while (i >= 0 && tokens[i].isIdent && !tokens[i].isWord("using") && !tokens[i].isWord("static")) {
                    parts.add(0, tokens[i].text)
                    if (i > 0 && tokens[i - 1].isSymbol(".")) i -= 2 else break
                }
            }
            val parent = parts.joinToString(".")
            val result = ArrayList<Suggestion>()
            for (child in tree.childNamespaces(parent)) {
                val score = match(child, prefix) ?: continue
                result.add(Suggestion(child, Suggestion.Kind.NAMESPACE, "", child, score))
            }
            if (tokens.getOrNull(i - 1)?.isWord("static") == true || tokens.getOrNull(i)?.isWord("static") == true) for (type in tree.typesIn(parent)) {
                val score = match(type.name, prefix) ?: continue
                result.add(typeSuggestion(type, score - 10))
            }
            return result
        }

        private fun isOverride(): Boolean = headerWords().contains("override")

        /** The modifier words of the member header the cursor is in. */
        private fun headerWords(): List<String> {
            if (enclosing == null || member != null) return emptyList()
            val words = ArrayList<String>()
            var i = here - 1
            while (i >= 0 && tokens[i].isIdent) {
                words.add(tokens[i].text)
                i--
            }
            return if (i < 0 || tokens[i].isSymbol(";") || tokens[i].isSymbol("{") || tokens[i].isSymbol("}") || tokens[i].isSymbol("]")) words else emptyList()
        }

        private fun isInBaseList(): Boolean {
            var i = here - 1
            while (i >= 0 && (tokens[i].isIdent || tokens[i].isSymbol(",") || tokens[i].isSymbol("."))) i--
            if (i < 1 || !tokens[i].isSymbol(":")) return false
            var j = i - 1
            if (j >= 0 && tokens[j].isSymbol(">")) {
                while (j >= 0 && !tokens[j].isSymbol("<")) j--
                j--
            }
            return j >= 1 && tokens[j].isIdent && tokens[j - 1].isIdent && tokens[j - 1].text in setOf("class", "struct", "interface", "record")
        }

        /** Right after a type name in a declaration there is a new name: nothing to offer. */
        private fun isDeclaringName(): Boolean {
            val before = tokens.getOrNull(here - 1) ?: return false
            if (before.isIdent && before.text in setOf("class", "struct", "interface", "enum", "namespace", "record")) return true
            if (member == null && enclosing != null && before.isIdent && before.text !in MEMBER_MODIFIERS) return true
            if (before.isIdent && before.text !in EXPRESSION_WORDS && member != null) {
                val previous = tokens.getOrNull(here - 2)
                return previous == null || previous.isSymbol(";") || previous.isSymbol("{") || previous.isSymbol("}") || previous.isSymbol(")")
            }
            return false
        }

        private fun isAttribute(): Boolean {
            val before = tokens.getOrNull(here - 1) ?: return false
            if (!before.isSymbol("[")) return false
            val previous = tokens.getOrNull(here - 2)
            return previous == null || previous.isSymbol(";") || previous.isSymbol("{") || previous.isSymbol("}") || previous.isSymbol("]")
        }

        private fun attributeItems(): List<Suggestion> = typeItems(filter = { it.name.endsWith("Attribute") })
            .map { s ->
                val short = s.label.removeSuffix("Attribute")
                Suggestion(short, s.kind, s.detail, short, s.score, usings = s.usings)
            }

        // ---------------------------- member access -------------------------

        private fun memberAccess(): List<Suggestion> {
            val eval = evalChain(here - 1) ?: return emptyList()
            return when (eval) {
                is Eval.Namespace -> {
                    val result = ArrayList<Suggestion>()
                    for (child in tree.childNamespaces(eval.name)) {
                        val score = match(child, prefix) ?: continue
                        result.add(Suggestion(child, Suggestion.Kind.NAMESPACE, "", child, score))
                    }
                    for (type in tree.typesIn(eval.name)) {
                        val score = match(type.name, prefix) ?: continue
                        result.add(typeSuggestion(type, score))
                    }
                    result
                }
                is Eval.TypeName -> memberItems(eval.type, staticOnly = true, instance = false)
                is Eval.Value -> memberItems(eval.type, staticOnly = false, instance = !eval.alsoStatic, both = eval.alsoStatic)
                is Eval.Group -> emptyList()
            }
        }

        private fun memberItems(type: TypeValue, staticOnly: Boolean, instance: Boolean, both: Boolean = false): List<Suggestion> {
            val found = semantics.members(type, includeExtensions = !staticOnly, imports)
            val insideType = thisType != null && isRelated(thisType, type)
            val groups = LinkedHashMap<String, MutableList<FoundMember>>()
            for (f in found) {
                val m = f.member
                if (m.kind == ApiMember.INDEXER) continue
                if (m.isProtected && !insideType) continue
                if (ProjectIndex.HIDDEN in m.flags && !insideType) continue
                if (staticOnly && !(m.isStatic || m.kind == ApiMember.CONSTANT)) continue
                if (instance && !f.extension && (m.isStatic || m.kind == ApiMember.CONSTANT)) continue
                if (!both && !staticOnly && !instance) continue
                if (match(m.name, prefix) == null) continue
                groups.getOrPut(m.name) { ArrayList(1) }.add(f)
            }
            val result = ArrayList<Suggestion>(groups.size + 8)
            for ((name, list) in groups) result.add(memberSuggestion(name, list, match(name, prefix)!!))
            if (staticOnly || both) {
                val def = type.def
                if (def != null) for (child in def.nested) {
                    val score = match(child.name, prefix) ?: continue
                    result.add(typeSuggestion(child, score))
                }
            }
            return result
        }

        private fun isRelated(from: ApiType, to: TypeValue): Boolean {
            val target = to.def ?: return false
            var current: ApiType? = from
            while (current != null) {
                var walk: ApiType? = current
                var guard = 0
                while (walk != null && guard++ < 32) {
                    if (walk.fullName == target.fullName) return true
                    walk = walk.base?.let { semantics.find(it.substringBefore('<'), walk?.scope ?: scope, walk) }
                }
                current = current.outer
            }
            return false
        }

        private fun memberSuggestion(name: String, list: List<FoundMember>, score: Int, bonus: Int = 0): Suggestion {
            val first = list[0]
            val m = first.member
            return when (m.kind) {
                ApiMember.METHOD -> {
                    val paramless = list.all { it.parameters.isEmpty() }
                    val needsGeneric = list.all { it.member.generics.isNotEmpty() && it.parameters.none { p -> it.member.generics.any { g -> p.contains(g) } } }
                    val insert = escape(name) + (if (needsGeneric) "<\$1>" else "") + (if (paramless) "()" + (if (needsGeneric) "\$0" else "") else if (needsGeneric) "(\$2)\$0" else "(\$0)")
                    val overloads = if (list.size > 1) " +${list.size - 1}" else ""
                    Suggestion(name, Suggestion.Kind.METHOD, shortText(stripRef(m.type)) + overloads, insert, score + bonus, snippet = true)
                }
                ApiMember.CONSTANT -> Suggestion(name,
                    if (first.owner.isEnum) Suggestion.Kind.ENUM_MEMBER else Suggestion.Kind.CONSTANT,
                    m.value?.let { "= $it" } ?: shortText(m.type), name, score + bonus)
                ApiMember.PROPERTY -> Suggestion(name, Suggestion.Kind.PROPERTY, shortText(stripRef(m.type)), name, score + bonus)
                ApiMember.EVENT -> Suggestion(name, Suggestion.Kind.EVENT, "event ${shortText(m.type)}", name, score + bonus)
                else -> Suggestion(name, Suggestion.Kind.FIELD, shortText(stripRef(m.type)), name, score + bonus)
            }
        }

        private fun stripRef(text: String?) = text?.removePrefix("ref readonly ")?.removePrefix("ref ")

        // ---------------------------- statements and declarations ------------

        private fun statementItems(): List<Suggestion> {
            val result = ArrayList<Suggestion>(64)
            val seen = HashSet<String>()
            for (local in locals().asReversed()) {
                if (!seen.add(local.name)) continue
                val score = match(local.name, prefix) ?: continue
                val type = localType(local)
                result.add(Suggestion(local.name, Suggestion.Kind.LOCAL, type?.let { shortText(displayOf(it)) } ?: "", local.name, score + BONUS_LOCAL))
            }
            result.addAll(scopeMembers(seen))
            if (prefix.isNotEmpty()) {
                result.addAll(typeItems(boost = hintedTypes()))
                val expansions = if (isStatementStart()) snippets(CsSnippets.STATEMENT) else emptyList()
                result.addAll(expansions)
                for (keyword in STATEMENT_KEYWORDS) {
                    // `if` as a snippet already writes the keyword.
                    if (expansions.any { it.label == keyword }) continue
                    val score = match(keyword, prefix) ?: continue
                    result.add(Suggestion(keyword, Suggestion.Kind.KEYWORD, "", keyword, score + BONUS_KEYWORD))
                }
                result.addAll(namespaceRoots())
            }
            return result
        }

        private fun namespaceRoots(): List<Suggestion> = tree.rootNamespaces.mapNotNull { name ->
            match(name, prefix)?.let { Suggestion(name, Suggestion.Kind.NAMESPACE, "", name, it - 200) }
        }

        private fun isStatementStart(): Boolean {
            val before = tokens.getOrNull(here - 1) ?: return true
            return before.isSymbol(";") || before.isSymbol("{") || before.isSymbol("}") || before.isSymbol(")") || before.isWord("else")
        }

        private fun displayOf(type: TypeValue): String =
            (type.def?.fullName ?: type.text) +
                (if (type.args.isEmpty()) "" else type.args.joinToString(",", "<", ">") { displayOf(it) }) +
                "[]".repeat(type.rank)

        /** What the current type and the types around it reach without a qualifier. */
        private fun scopeMembers(seen: MutableSet<String>): List<Suggestion> {
            val result = ArrayList<Suggestion>(32)
            var depth = 0
            var current = thisType
            while (current != null) {
                val value = TypeValue(current)
                val groups = LinkedHashMap<String, MutableList<FoundMember>>()
                for (f in semantics.members(value, includeExtensions = false, imports)) {
                    if (f.member.kind == ApiMember.INDEXER) continue
                    if (depth > 0 && !f.member.isStatic && f.member.kind != ApiMember.CONSTANT) continue
                    if (f.member.name in seen || match(f.member.name, prefix) == null) continue
                    groups.getOrPut(f.member.name) { ArrayList(1) }.add(f)
                }
                for ((name, list) in groups) {
                    if (!seen.add(name)) continue
                    result.add(memberSuggestion(name, list, match(name, prefix)!!, BONUS_MEMBER))
                }
                for (child in current.nested) {
                    val score = match(child.name, prefix) ?: continue
                    if (seen.add(child.name)) result.add(typeSuggestion(child, score + BONUS_MEMBER))
                }
                current = current.outer
                depth++
            }
            for (holder in file.usingStatics) {
                val def = semantics.find(holder, scope) ?: continue
                for (m in def.members) {
                    if (!(m.isStatic || m.kind == ApiMember.CONSTANT) || m.name in seen) continue
                    val score = match(m.name, prefix) ?: continue
                    seen.add(m.name)
                    result.add(memberSuggestion(m.name, listOf(FoundMember(m, def, emptyMap())), score, BONUS_MEMBER))
                }
            }
            return result
        }

        private fun memberLevelItems(): List<Suggestion> {
            val result = ArrayList<Suggestion>()
            for (keyword in MEMBER_KEYWORDS) {
                val score = match(keyword, prefix) ?: continue
                result.add(Suggestion(keyword, Suggestion.Kind.KEYWORD, "", keyword, score + BONUS_KEYWORD))
            }
            if (prefix.isNotEmpty()) {
                result.addAll(typeItems())
                val words = headerWords()
                if (words.isEmpty() || words.all { it in MEMBER_MODIFIERS }) result.addAll(snippets(CsSnippets.MEMBER))
            }
            return result
        }

        private fun namespaceLevelItems(): List<Suggestion> {
            val result = ArrayList<Suggestion>()
            for (keyword in NAMESPACE_KEYWORDS) {
                val score = match(keyword, prefix) ?: continue
                result.add(Suggestion(keyword, Suggestion.Kind.KEYWORD, "", keyword, score + BONUS_KEYWORD))
            }
            if (prefix.isNotEmpty()) result.addAll(snippets(CsSnippets.FILE))
            return result
        }

        private fun snippets(list: List<CsSnippets.Snippet>): List<Suggestion> = list.mapNotNull { snippet ->
            val score = match(snippet.trigger, prefix) ?: return@mapNotNull null
            Suggestion(snippet.trigger, Suggestion.Kind.SNIPPET, snippet.title, snippet.body, score + BONUS_SNIPPET, snippet = true, usings = snippet.usings)
        }

        // ---------------------------- types ----------------------------------

        private fun typeSuggestion(type: ApiType, score: Int, usings: List<String> = emptyList(), construct: Boolean = false): Suggestion {
            val kind = when (type.kind) {
                "interface" -> Suggestion.Kind.INTERFACE
                "struct" -> Suggestion.Kind.STRUCT
                "enum" -> Suggestion.Kind.ENUM
                "delegate" -> Suggestion.Kind.DELEGATE
                else -> Suggestion.Kind.CLASS
            }
            val generic = if (type.generics.isEmpty()) "" else "<\$1>"
            val call = if (!construct) "" else if (generic.isEmpty()) "(\$0)" else "(\$2)\$0"
            val insert = escape(type.name) + generic + call
            return Suggestion(type.name + if (type.generics.isEmpty()) "" else type.generics.joinToString(",", "<", ">"),
                kind, "", insert, score, snippet = generic.isNotEmpty() || construct, usings = usings)
        }

        /** Types by name: imported ones first; the others come with their `using`. */
        private fun typeItems(filter: (ApiType) -> Boolean = { true }, construct: Boolean = false, boost: Set<String> = emptySet()): List<Suggestion> {
            if (prefix.isEmpty()) {
                // Nothing typed: what the imported namespaces hold, the file's own first.
                val result = ArrayList<Suggestion>()
                for (type in localTypes + project?.all().orEmpty()) {
                    if (filter(type) && type.outer == null) result.add(typeSuggestion(type, 900, construct = construct))
                }
                for (namespace in imports) for (type in tree.typesIn(namespace)) {
                    if (filter(type)) result.add(typeSuggestion(type, if (namespace.startsWith("System")) 300 else 400, construct = construct))
                }
                return result.distinctBy { it.label }
            }
            val result = ArrayList<Suggestion>()
            val seen = HashSet<String>()
            for (type in localTypes + project?.all().orEmpty()) {
                if (type.outer != null || !filter(type) || !seen.add(type.fullName)) continue
                val score = match(type.name, prefix) ?: continue
                val usable = type.namespace in imports
                result.add(typeSuggestion(type, score + (if (usable) BONUS_TYPE + 40 else 0), if (usable) emptyList() else listOf(type.namespace), construct))
            }
            val names = tree.searchNames
            var count = 0
            val lower = prefix.lowercase()
            val first = lower[0]
            for (type in names) {
                if ((++count and 0x3FF) == 0) cancelled()
                val initial = type.name[0]
                // Most names fail here: neither the same initial nor a chance of a substring match.
                if (prefix.length == 1 && initial.lowercaseChar() != first) continue
                if (type.outer != null) continue
                val score = match(type.name, prefix) ?: continue
                if (type.fullName in seen || !filter(type)) continue
                val imported = type.namespace in imports
                if (!imported && (prefix.length < 2 || score < SCORE_HUMP)) continue
                var total = if (imported) score + BONUS_TYPE else score - 300
                if (type.name in boost) total += BONUS_HINT
                if (type.namespace.startsWith("BunnyLoader.Interop") || type.name.startsWith("On_")) total -= 200
                result.add(typeSuggestion(type, total, if (imported) emptyList() else listOf(type.namespace), construct))
            }
            // The list shows names only: of two types with one name, the one most likely wanted stays.
            return result.sortedByDescending { it.score }.distinctBy { it.label }
        }

        /** After `Item.rare = `, the ID class that holds the values floats to the top. */
        private fun hintedTypes(): Set<String> {
            val before = tokens.getOrNull(here - 1) ?: return emptySet()
            if (before.isSymbol("=")) {
                val name = tokens.getOrNull(here - 2)?.takeIf { it.isIdent }?.text ?: return emptySet()
                return ASSIGN_HINTS[name].orEmpty()
            }
            if (before.isSymbol("(") || before.isSymbol(",")) {
                var depth = 0
                var i = here - 1
                while (i >= 0) {
                    val t = tokens[i]
                    if (t.isSymbol(")")) depth++
                    if (t.isSymbol("(")) {
                        if (depth == 0) break
                        depth--
                    }
                    i--
                }
                val name = tokens.getOrNull(i - 1)?.takeIf { it.isIdent }?.text ?: return emptySet()
                return CALL_HINTS[name].orEmpty()
            }
            return emptySet()
        }

        // ---------------------------- overrides ------------------------------

        private fun overrideItems(): List<Suggestion> {
            val decl = enclosing ?: return emptyList()
            val type = thisType ?: return emptyList()
            val parent = semantics.resolve(type.base, scope, emptyMap(), type) ?: return emptyList()
            val existing = decl.members.filter { "override" in it.modifiers }.map { "${it.name}/${it.parameters.size}" }.toHashSet()
            val result = ArrayList<Suggestion>()
            val seen = HashSet<String>()
            // Where the override keyword and its modifiers start: the whole header gets rewritten.
            var headerStart = here
            while (headerStart > 0 && tokens[headerStart - 1].isIdent && tokens[headerStart - 1].text in MEMBER_MODIFIERS) headerStart--
            val replaceFrom = tokens[headerStart].start.coerceAtMost(prefixStart)
            for (f in semantics.members(parent, includeExtensions = false, imports)) {
                val m = f.member
                if (!m.isOverridable || m.isStatic || m.kind == ApiMember.EVENT || m.kind == ApiMember.INDEXER) continue
                if (f.owner.fullName == "System.Object" && m.name in OBJECT_NOISE) continue
                val key = "${m.name}/${m.parameters.size}"
                if (key in existing || !seen.add(key)) continue
                val score = match(m.name, prefix) ?: continue
                val usings = LinkedHashSet<String>()
                val returns = code(m.type, f, usings)
                val access = if (m.isProtected) "protected" else "public"
                val (head, body) = if (m.kind == ApiMember.METHOD) {
                    val generics = if (m.generics.isEmpty()) "" else m.generics.joinToString(", ", "<", ">")
                    val parameters = m.parameters.map { codeParameter(it, f, usings) }
                    val call = m.parameters.joinToString(", ") { argumentFor(it) }
                    val base = "base.${m.name}$generics($call);"
                    "${m.name}$generics(${parameters.joinToString(", ")})" to when {
                        'A' in m.flags -> "\n{\n    \$0\n}"
                        m.type == "void" -> "\n{\n    \$0\n}"
                        else -> "\n{\n    \$0return $base\n}"
                    }
                } else m.name to " => \$0;"
                val label = if (m.kind == ApiMember.METHOD) "${m.name}(${m.parameters.joinToString(", ") { shortText(it.substringBeforeLast(' ')) }})" else m.name
                result.add(Suggestion(label, if (m.kind == ApiMember.METHOD) Suggestion.Kind.METHOD else Suggestion.Kind.PROPERTY,
                    returns, escapeKeepingMarks("$access override $returns $head") + body, score,
                    snippet = true, usings = usings.toList(), replaceFrom = replaceFrom))
            }
            return result
        }

        private fun argumentFor(parameter: String): String {
            val text = parameter.substringBefore('=')
            val name = text.substringAfterLast(' ')
            return when {
                text.startsWith("ref ") -> "ref $name"
                text.startsWith("out ") -> "out $name"
                else -> name
            }
        }

        /** A type text as source code: short names where a using is in place (or gets added). */
        private fun code(text: String?, f: FoundMember, usings: MutableSet<String>): String {
            val s = text ?: return "void"
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '_' || c.isLetter()) {
                    val start = i
                    while (i < s.length && (s[i] == '_' || s[i].isLetterOrDigit() || s[i] == '.')) i++
                    val word = s.substring(start, i)
                    val binding = f.bindings[word]
                    val type = binding?.def ?: if ('.' in word) tree.type(word) else null
                    if (type != null) {
                        var top: ApiType = type
                        val path = ArrayList<String>()
                        while (true) {
                            path.add(0, top.name)
                            top = top.outer ?: break
                        }
                        if (top.namespace !in imports && top.namespace.isNotEmpty()) usings.add(top.namespace)
                        out.append(path.joinToString("."))
                    } else out.append(word)
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }

        private fun codeParameter(parameter: String, f: FoundMember, usings: MutableSet<String>): String {
            val cut = parameter.indexOf('=')
            val text = if (cut < 0) parameter else parameter.substring(0, cut)
            val name = text.substringAfterLast(' ')
            val type = text.substringBeforeLast(' ')
            val default = if (cut < 0) "" else " = " + parameter.substring(cut + 1)
            return "${code(type, f, usings)} $name$default"
        }

        // ---------------------------- parameter info -------------------------

        fun signature(): SignatureInfo? {
            if (file.lex.isQuiet(offset)) return null
            val cursor = firstTokenAtOrAfter(offset)
            var depth = 0
            var argument = 0
            var i = cursor - 1
            var open = -1
            var steps = 0
            while (i >= 0 && steps++ < 512) {
                val t = tokens[i]
                if (t.kind == Token.Kind.SYMBOL) when (t.text) {
                    ")", "]", "}" -> depth++
                    "(", "[", "{" -> {
                        if (depth == 0) {
                            if (t.text == "(") open = i
                            break
                        }
                        depth--
                    }
                    "," -> if (depth == 0) argument++
                    ";" -> if (depth == 0) return null
                }
                i--
            }
            if (open <= 0) return null
            val calleeEnd = open
            var nameIndex = open - 1
            var explicit: List<String>? = null
            if (tokens[nameIndex].isSymbol(">")) {
                var d = 0
                var j = nameIndex
                while (j >= 0) {
                    if (tokens[j].isSymbol(">")) d++
                    if (tokens[j].isSymbol("<")) {
                        d--
                        if (d == 0) break
                    }
                    j--
                }
                if (j <= 0) return null
                explicit = Semantics.splitTop(CsFile.joinTokens(tokens, j + 1, nameIndex))
                nameIndex = j - 1
            }
            if (!tokens[nameIndex].isIdent) return null
            val found: List<FoundMember> = when {
                tokens.getOrNull(nameIndex - 1)?.isWord("new") == true || tokens[nameIndex].isWord("base") && member == null -> {
                    val def = semantics.find(tokens[nameIndex].text, scope, thisType) ?: return null
                    def.members.filter { it.kind == ApiMember.CONSTRUCTOR }.map { FoundMember(it, def, emptyMap()) }
                }
                else -> {
                    val eval = evalRange(chainStart(calleeEnd), calleeEnd, callee = true) as? Eval.Group ?: return null
                    eval.members
                }
            }
            if (found.isEmpty()) return null
            val overloads = found.map { f ->
                val returns = f.member.type?.let { shortText(substituted(it, f, explicit)) + " " } ?: ""
                SignatureInfo.Signature("$returns${f.member.name}(", f.parameters.map { shortText(it) }, ")")
            }
            val best = overloads.indexOfFirst { it.parameters.size > argument }.let { if (it < 0) 0 else it }
            return SignatureInfo(overloads, best, argument)
        }

        private fun substituted(text: String, f: FoundMember, explicit: List<String>?): String {
            if (explicit != null && f.member.generics.size == explicit.size) {
                f.member.generics.forEachIndexed { index, g -> if (text == g) return explicit[index].trim() }
            }
            f.bindings[text]?.let { return displayOf(it) }
            return text
        }

        // ---------------------------- evaluation -----------------------------

        /** Walks back from just before token [end] to where its member chain starts. */
        private fun chainStart(end: Int): Int {
            var j = end - 1
            while (j >= 0) {
                val t = tokens[j]
                when {
                    t.isSymbol("!") -> { j--; continue }
                    t.isSymbol(")") -> {
                        val open = openOf(j, "(", ")")
                        if (open < 0) return j
                        val previous = tokens.getOrNull(open - 1)
                        if (previous == null || !(previous.isIdent || previous.isSymbol(">")) || previous.text in EXPRESSION_WORDS) {
                            return open
                        }
                        j = open - 1
                        continue
                    }
                    t.isSymbol("]") -> {
                        val open = openOf(j, "[", "]")
                        if (open < 0) return j
                        j = open - 1
                        continue
                    }
                    t.isSymbol(">") -> {
                        var d = 0
                        var k = j
                        while (k >= 0) {
                            if (tokens[k].isSymbol(">")) d++
                            if (tokens[k].isSymbol("<")) {
                                d--
                                if (d == 0) break
                            }
                            if (tokens[k].isSymbol(";")) return j + 1
                            k--
                        }
                        if (k < 0) return j + 1
                        j = k - 1
                        continue
                    }
                    t.isIdent -> {
                        val previous = tokens.getOrNull(j - 1)
                        if (previous != null && (previous.isSymbol(".") || previous.isSymbol("?.") || previous.isSymbol("::"))) {
                            j -= 2
                            continue
                        }
                        if (previous != null && previous.isWord("new")) return j - 1
                        return j
                    }
                    t.kind == Token.Kind.STRING || t.kind == Token.Kind.NUMBER || t.kind == Token.Kind.CHAR -> return j
                    else -> return j + 1
                }
            }
            return 0
        }

        private fun openOf(close: Int, a: String, b: String): Int {
            var depth = 0
            var i = close
            while (i >= 0) {
                if (tokens[i].isSymbol(b)) depth++
                if (tokens[i].isSymbol(a)) {
                    depth--
                    if (depth == 0) return i
                }
                i--
            }
            return -1
        }

        /** The value of the chain that ends right before token [end] (the dot). */
        private fun evalChain(end: Int): Eval? = evalRange(chainStart(end), end)

        private fun evalRange(from: Int, until: Int, callee: Boolean = false): Eval? {
            if (from >= until) return null
            var i = from
            var current: Eval?
            val t = tokens[i]
            when {
                t.isWord("new") -> {
                    i++
                    val end = CsFile.skipType(tokens, i)
                    if (end < 0) return null
                    val type = semantics.resolve(CsFile.joinTokens(tokens, i, end), scope, emptyMap(), thisType) ?: return null
                    i = end
                    var rank = 0
                    while (i < until && tokens[i].isSymbol("[")) {
                        val close = CsFile.matching(tokens, i, "[", "]")
                        if (close < 0) return null
                        rank++
                        i = close + 1
                    }
                    if (i < until && tokens[i].isSymbol("(")) {
                        val close = CsFile.matching(tokens, i, "(", ")")
                        if (close < 0) return null
                        i = close + 1
                    }
                    if (i < until && tokens[i].isSymbol("{")) {
                        val close = CsFile.matching(tokens, i, "{", "}")
                        if (close < 0) return null
                        i = close + 1
                    }
                    current = Eval.Value(if (rank == 0) type else TypeValue(type.def, type.args, type.rank + rank, type.text))
                }
                t.isSymbol("(") -> {
                    val close = CsFile.matching(tokens, i, "(", ")")
                    if (close < 0 || close >= until) return null
                    val typeEnd = CsFile.skipType(tokens, i + 1)
                    val isCast = typeEnd == close && close + 1 < until && !tokens[close + 1].isSymbol(".") && !tokens[close + 1].isSymbol("?.")
                    if (isCast) {
                        val type = semantics.resolve(CsFile.joinTokens(tokens, i + 1, close), scope, emptyMap(), thisType) ?: return null
                        return Eval.Value(type)
                    }
                    current = evalRange(i + 1, close)
                    i = close + 1
                }
                t.kind == Token.Kind.STRING -> { current = Eval.Value(TypeValue(tree.type("System.String"))); i++ }
                t.kind == Token.Kind.CHAR -> { current = Eval.Value(TypeValue(tree.type("System.Char"))); i++ }
                t.kind == Token.Kind.NUMBER -> {
                    val suffix = t.text.last().lowercaseChar()
                    val name = when {
                        suffix == 'f' -> "System.Single"
                        suffix == 'd' || '.' in t.text -> "System.Double"
                        suffix == 'l' -> "System.Int64"
                        else -> "System.Int32"
                    }
                    current = Eval.Value(TypeValue(tree.type(name)))
                    i++
                }
                t.isWord("this") -> { current = thisType?.let { Eval.Value(TypeValue(it)) }; i++ }
                t.isWord("base") -> {
                    current = thisType?.let { semantics.resolve(it.base, scope, emptyMap(), it) }?.let { Eval.Value(it) }
                    i++
                }
                t.isWord("true") || t.isWord("false") -> { current = Eval.Value(TypeValue(tree.type("System.Boolean"))); i++ }
                t.isWord("typeof") -> {
                    val close = CsFile.matching(tokens, i + 1, "(", ")")
                    if (close < 0) return null
                    current = Eval.Value(TypeValue(tree.type("System.Type")))
                    i = close + 1
                }
                t.isWord("nameof") -> {
                    val close = CsFile.matching(tokens, i + 1, "(", ")")
                    if (close < 0) return null
                    current = Eval.Value(TypeValue(tree.type("System.String")))
                    i = close + 1
                }
                t.isIdent -> {
                    i++
                    val generics = if (i < until && tokens[i].isSymbol("<")) readGenerics(i).also { if (it != null) i = it.second + 1 } else null
                    val call = i < until && tokens[i].isSymbol("(")
                    current = lookupName(t.text, generics?.first, call)
                    if (call) {
                        if (callee && i == until) return current
                        val close = CsFile.matching(tokens, i, "(", ")")
                        if (close < 0 || close >= until) return null
                        current = resultOf(current as? Eval.Group, generics?.first, argumentCount(i, close))
                        i = close + 1
                    }
                    if (callee && i == until) return current
                }
                else -> return null
            }
            while (i < until && current != null) {
                val s = tokens[i]
                when {
                    s.isSymbol("!") -> i++
                    s.isSymbol(".") || s.isSymbol("?.") || s.isSymbol("::") -> {
                        i++
                        if (i >= until || !tokens[i].isIdent) return null
                        val name = tokens[i].text
                        i++
                        val generics = if (i < until && tokens[i].isSymbol("<")) readGenerics(i).also { if (it != null) i = it.second + 1 } else null
                        val call = i < until && tokens[i].isSymbol("(")
                        current = memberOf(current, name, call || (callee && i == until))
                        if (callee && i == until) return current
                        if (call) {
                            val close = CsFile.matching(tokens, i, "(", ")")
                            if (close < 0 || close >= until) return null
                            current = resultOf(current as? Eval.Group, generics?.first, argumentCount(i, close))
                            i = close + 1
                        }
                    }
                    s.isSymbol("[") -> {
                        val close = CsFile.matching(tokens, i, "[", "]")
                        if (close < 0 || close >= until) return null
                        current = indexed(current)
                        i = close + 1
                    }
                    else -> return null
                }
            }
            return current
        }

        private fun readGenerics(open: Int): Pair<List<String>, Int>? {
            val close = CsFile.closeAngle(tokens, open)
            if (close < 0) return null
            return Semantics.splitTop(CsFile.joinTokens(tokens, open + 1, close)) to close
        }

        private fun argumentCount(open: Int, close: Int): Int {
            if (close == open + 1) return 0
            var depth = 0
            var count = 1
            for (k in open + 1 until close) {
                val t = tokens[k]
                if (t.kind != Token.Kind.SYMBOL) continue
                when (t.text) {
                    "(", "[", "{" -> depth++
                    ")", "]", "}" -> depth--
                    "," -> if (depth == 0) count++
                }
            }
            return count
        }

        private fun indexed(value: Eval): Eval? {
            val v = (value as? Eval.Value)?.type ?: return null
            if (v.rank > 0) return Eval.Value(v.element())
            for (f in semantics.members(v, includeExtensions = false, imports)) {
                if (f.member.kind == ApiMember.INDEXER) {
                    val type = semantics.resolve(f.member.type, f.owner.scope ?: scope, f.bindings, f.owner) ?: return null
                    return Eval.Value(type)
                }
            }
            return null
        }

        private fun resultOf(group: Eval.Group?, generics: List<String>?, arguments: Int): Eval? {
            val members = group?.members ?: return null
            val best = members.firstOrNull { it.parameters.size == arguments }
                ?: members.firstOrNull { it.parameters.count { p -> '=' !in p } <= arguments && it.parameters.size >= arguments }
                ?: members.firstOrNull() ?: return null
            val m = best.member
            val bindings = HashMap(best.bindings)
            if (generics != null) m.generics.zip(generics).forEach { (g, text) ->
                semantics.resolve(text, scope, emptyMap(), thisType)?.let { bindings[g] = it }
            }
            // A generic the call cannot pin down yields an unknown type, not some class named T.
            val text = m.type ?: return null
            if (text.substringBefore('[').removePrefix("ref ") in m.generics && text.substringBefore('[').removePrefix("ref ") !in bindings) return null
            return semantics.resolve(text, best.owner.scope ?: scope, bindings, best.owner)?.let { Eval.Value(it) }
        }

        private fun memberOf(value: Eval, name: String, call: Boolean): Eval? {
            when (value) {
                is Eval.Namespace -> {
                    val full = "${value.name}.$name"
                    if (tree.isNamespace(full)) return Eval.Namespace(full)
                    return semantics.byFullName(full)?.let { Eval.TypeName(TypeValue(it)) }
                }
                is Eval.TypeName -> {
                    if (!call) value.type.def?.nested?.firstOrNull { it.name == name }?.let { return Eval.TypeName(TypeValue(it)) }
                    return findMember(value.type, name, call, staticAccess = true)
                }
                is Eval.Value -> return findMember(value.type, name, call, staticAccess = false)
                is Eval.Group -> return null
            }
        }

        private fun findMember(type: TypeValue, name: String, call: Boolean, staticAccess: Boolean): Eval? {
            val found = semantics.members(type, includeExtensions = !staticAccess, imports).filter { it.member.name == name }
            if (call) {
                val methods = found.filter { it.member.isCallable }
                if (methods.isNotEmpty()) return Eval.Group(type, staticAccess, methods)
            }
            val first = found.firstOrNull { !it.member.isCallable } ?: return found.takeIf { it.isNotEmpty() }?.let { Eval.Group(type, staticAccess, it) }
            if (first.owner.isEnum && first.member.kind == ApiMember.CONSTANT) return Eval.Value(TypeValue(first.owner))
            val value = semantics.resolve(first.member.type, first.owner.scope ?: scope, first.bindings, first.owner) ?: return null
            // `Item Item`: the name is both the member and its type.
            return Eval.Value(value, value.def?.name == name)
        }

        private fun lookupName(name: String, generics: List<String>?, call: Boolean): Eval? {
            if (!call) {
                locals().lastOrNull { it.name == name }?.let { local ->
                    return localType(local)?.let { Eval.Value(it) }
                }
                if (member != null) parameterOf(member, name)?.let { return it }
            }
            var current = thisType
            while (current != null) {
                memberOfScope(current, name, call)?.let { return it }
                current = current.outer
            }
            for (holder in file.usingStatics) {
                val def = semantics.find(holder, scope) ?: continue
                findMember(TypeValue(def), name, call, staticAccess = true)?.let { return it }
            }
            if (!call || generics == null) {
                val def = semantics.find(name, scope, thisType)
                if (def != null) {
                    val args = generics?.map { semantics.resolve(it, scope, emptyMap(), thisType) ?: TypeValue(null) }.orEmpty()
                    return Eval.TypeName(TypeValue(def, args))
                }
                if (tree.isNamespace(name)) return Eval.Namespace(name)
                for (using in imports) if (using.isNotEmpty() && tree.isNamespace("$using.$name")) return Eval.Namespace("$using.$name")
            }
            return null
        }

        private fun parameterOf(owner: CsMember, name: String): Eval? {
            for (parameter in owner.parameters) {
                val text = parameter.substringBefore('=')
                if (text.substringAfterLast(' ') == name) {
                    return semantics.resolve(text.substringBeforeLast(' '), scope, emptyMap(), thisType)?.let { Eval.Value(it) }
                }
            }
            return null
        }

        private fun memberOfScope(type: ApiType, name: String, call: Boolean): Eval? {
            val value = TypeValue(type)
            val found = semantics.members(value, includeExtensions = false, imports).filter { it.member.name == name }
            if (found.isEmpty()) return null
            return findMember(value, name, call, staticAccess = false)
        }

        private fun localType(local: Local): TypeValue? {
            if (local.type != null && local.type != "var") return semantics.resolve(local.type, scope, emptyMap(), thisType)
            if (local.from < 0 || !evaluating.add(local.name)) return null
            try {
                val eval = evalRange(local.from, local.until) as? Eval.Value ?: return null
                return if (local.forEach) elementOf(eval.type) else eval.type
            } finally {
                evaluating.remove(local.name)
            }
        }

        private fun elementOf(type: TypeValue): TypeValue? {
            if (type.rank > 0) return type.element()
            if (type.args.size == 1) return type.args[0]
            val def = type.def ?: return null
            for (found in semantics.members(type, false, imports)) {
                if (found.member.kind == ApiMember.INDEXER) {
                    return semantics.resolve(found.member.type, found.owner.scope ?: scope, found.bindings, found.owner)
                }
            }
            return def.interfaces.firstOrNull { it.contains("IEnumerable<") }
                ?.let { semantics.resolve(it, def.scope ?: scope, emptyMap(), def) }?.args?.firstOrNull()
        }

        // ---------------------------- locals ---------------------------------

        private fun locals(): List<Local> {
            localsRead?.let { return it }
            val owner = member
            val result = ArrayList<Local>()
            if (owner != null && owner.bodyStart >= 0) readLocals(owner.bodyStart, here, result)
            if (owner != null) {
                val parameters = owner.parameters.map { p ->
                    val text = p.substringBefore('=')
                    Local(text.substringAfterLast(' '), text.substringBeforeLast(' '))
                }
                result.addAll(0, parameters)
            }
            localsRead = result
            return result
        }

        private fun readLocals(from: Int, until: Int, out: MutableList<Local>) {
            var depth = 0
            var i = from
            while (i < until) {
                val t = tokens[i]
                val previous = tokens.getOrNull(i - 1)
                if (t.isSymbol("{")) depth++
                else if (t.isSymbol("}")) {
                    depth--
                    out.removeAll { it.depth > depth }
                } else if (t.isWord("foreach") && i + 2 < until && tokens[i + 1].isSymbol("(")) {
                    val typeEnd = CsFile.skipType(tokens, i + 2)
                    val close = CsFile.matching(tokens, i + 1, "(", ")")
                    if (typeEnd > 0 && close > 0 && tokens.getOrNull(typeEnd)?.isIdent == true && tokens.getOrNull(typeEnd + 1)?.isWord("in") == true) {
                        val type = CsFile.joinTokens(tokens, i + 2, typeEnd)
                        out.add(Local(tokens[typeEnd].text, type, typeEnd + 2, close, forEach = type == "var", depth = depth + 1))
                        i = typeEnd + 1
                        continue
                    }
                } else if ((t.isWord("out") || t.isWord("is")) && i + 1 < until) {
                    val typeEnd = CsFile.skipType(tokens, i + 1)
                    if (typeEnd > 0 && tokens.getOrNull(typeEnd)?.isIdent == true && tokens[typeEnd].text !in setOf("and", "or", "when")) {
                        out.add(Local(tokens[typeEnd].text, CsFile.joinTokens(tokens, i + 1, typeEnd), depth = depth))
                        i = typeEnd + 1
                        continue
                    }
                } else if (t.isSymbol("=>")) {
                    if (previous?.isIdent == true) out.add(Local(previous.text, null, depth = depth + 1))
                    else if (previous?.isSymbol(")") == true) {
                        val open = openOf(i - 1, "(", ")")
                        var k = open + 1
                        while (k in 1 until i - 1) {
                            val end = CsFile.skipType(tokens, k)
                            if (end > k && tokens.getOrNull(end)?.isIdent == true) {
                                out.add(Local(tokens[end].text, CsFile.joinTokens(tokens, k, end), depth = depth + 1))
                                k = end + 1
                            } else if (tokens[k].isIdent) {
                                out.add(Local(tokens[k].text, null, depth = depth + 1))
                                k++
                            } else k++
                        }
                    }
                } else if (t.isIdent && t.text !in NOT_TYPES && previous != null && !previous.isSymbol(".") &&
                    (previous.isSymbol(";") || previous.isSymbol("{") || previous.isSymbol("}") || previous.isSymbol(")") || previous.isSymbol("(") ||
                        previous.isSymbol("=>") || previous.isWord("else") || previous.isWord("using") || previous.isWord("const"))) {
                    val typeEnd = CsFile.skipType(tokens, i)
                    if (typeEnd > i && typeEnd < until && tokens[typeEnd].isIdent) {
                        val after = tokens.getOrNull(typeEnd + 1)
                        if (after != null && (after.isSymbol("=") || after.isSymbol(";") || after.isSymbol(",") || after.isWord("in"))) {
                            val type = CsFile.joinTokens(tokens, i, typeEnd)
                            i = readDeclarators(type, typeEnd, until, depth, out)
                            continue
                        }
                    }
                }
                i++
            }
        }

        /** `int a = 1, b;` from the first name on. Returns where to carry on. */
        private fun readDeclarators(type: String, first: Int, until: Int, depth: Int, out: MutableList<Local>): Int {
            var i = first
            while (i < until && tokens[i].isIdent) {
                val name = tokens[i].text
                i++
                if (i < until && tokens[i].isSymbol("=")) {
                    val start = i + 1
                    var d = 0
                    i++
                    while (i < until) {
                        val t = tokens[i]
                        if (t.kind == Token.Kind.SYMBOL) {
                            if (t.text == "(" || t.text == "[" || t.text == "{") d++
                            else if (t.text == ")" || t.text == "]" || t.text == "}") {
                                if (d == 0) break
                                d--
                            } else if ((t.text == "," || t.text == ";") && d == 0) break
                        }
                        i++
                    }
                    out.add(Local(name, type, start, i, depth = depth))
                } else out.add(Local(name, type, depth = depth))
                if (i < until && tokens[i].isSymbol(",")) i++ else break
            }
            return i
        }
    }

    companion object {
        const val MAX_ITEMS = 8000
        private const val SCORE_HUMP = 700
        private const val BONUS_LOCAL = 260
        private const val BONUS_MEMBER = 200
        private const val BONUS_TYPE = 120
        private const val BONUS_KEYWORD = 130
        private const val BONUS_SNIPPET = 150
        private const val BONUS_HINT = 2000

        private val OBJECT_NOISE = setOf("Finalize", "MemberwiseClone", "GetType")
        private val MEMBER_MODIFIERS = setOf("public", "private", "protected", "internal", "static", "abstract", "virtual", "override", "sealed", "readonly", "const", "unsafe", "partial", "new", "extern", "async", "volatile")
        /** Words that start or continue an expression, so what follows them is not a new name. */
        private val EXPRESSION_WORDS = setOf("return", "throw", "await", "new", "in", "is", "as", "case", "goto", "yield", "else", "do", "using", "out", "ref", "if", "while", "for", "foreach", "switch", "typeof", "default", "nameof", "sizeof", "when", "not", "and", "or", "lock", "catch", "fixed", "checked", "unchecked")
        private val NOT_TYPES = setOf("return", "throw", "await", "new", "if", "else", "for", "foreach", "while", "do", "switch", "case", "default", "break", "continue", "goto", "yield", "try", "catch", "finally", "lock", "using", "base", "this", "true", "false", "null", "typeof", "nameof", "sizeof", "is", "as", "in", "out", "ref", "checked", "unchecked", "fixed", "unsafe")
        private val STATEMENT_KEYWORDS = listOf("if", "else", "for", "foreach", "while", "do", "switch", "case", "default", "break", "continue", "return", "new", "var", "this", "base", "true", "false", "null", "try", "catch", "finally", "throw", "using", "lock", "await", "is", "as", "typeof", "nameof", "sizeof", "const", "ref", "out", "in", "int", "float", "double", "bool", "string", "byte", "short", "long", "uint", "ushort", "ulong", "char", "object", "void", "yield", "checked", "unchecked")
        private val MEMBER_KEYWORDS = listOf("public", "private", "protected", "internal", "static", "readonly", "const", "override", "virtual", "abstract", "sealed", "partial", "unsafe", "new", "async", "class", "struct", "enum", "interface", "record", "delegate", "event", "void", "int", "float", "double", "bool", "string", "byte", "short", "long", "uint", "ushort", "ulong", "char", "object", "ref", "operator", "implicit", "explicit")
        private val NAMESPACE_KEYWORDS = listOf("using", "namespace", "public", "internal", "static", "abstract", "sealed", "partial", "class", "struct", "enum", "interface", "record", "delegate", "file")

        private val ASSIGN_HINTS = mapOf(
            "useStyle" to setOf("ItemUseStyleID"), "rare" to setOf("ItemRarityID"), "UseSound" to setOf("SoundID"), "shoot" to setOf("ProjectileID"),
            "useAmmo" to setOf("AmmoID"), "ammo" to setOf("AmmoID"), "createTile" to setOf("TileID"), "createWall" to setOf("WallID"),
            "buffType" to setOf("BuffID"), "aiStyle" to setOf("ProjAIStyleID", "NPCAIStyleID"), "HitSound" to setOf("SoundID"), "DeathSound" to setOf("SoundID"),
            "DamageType" to setOf("DamageClass"), "type" to setOf("ItemID", "NPCID", "ProjectileID"),
        )
        private val CALL_HINTS = mapOf(
            "AddIngredient" to setOf("ItemID"), "AddTile" to setOf("TileID"), "CreateRecipe" to setOf("ItemID"), "AddBuff" to setOf("BuffID"), "HasBuff" to setOf("BuffID"),
            "NewProjectile" to setOf("ProjectileID"), "NewNPC" to setOf("NPCID"), "NewDust" to setOf("DustID"), "PlaySound" to setOf("SoundID"), "SetDefaults" to setOf("ItemID"),
        )

        /** How well [label] answers what was typed; null is no match. Higher is better. */
        fun match(label: String, typed: String): Int? {
            // Backing fields and the like go last while nothing was typed.
            if (typed.isEmpty()) return if (label.startsWith("_")) 0 else 1
            if (typed.length > label.length) return null
            if (label.startsWith(typed)) return 1000 - label.length.coerceAtMost(99)
            if (label.startsWith(typed, ignoreCase = true)) return 900 - label.length.coerceAtMost(99)
            if (typed.length >= 2) {
                if (matchesHumps(label, typed)) return SCORE_HUMP
                if (typed.length >= 3 && label.contains(typed, ignoreCase = true)) return 500 - label.length.coerceAtMost(99)
            }
            return null
        }

        /** Which letters of [label] answer what was typed, for the list to color them in. */
        fun matchedLetters(label: String, typed: String): BooleanArray {
            val mask = BooleanArray(label.length)
            if (typed.isEmpty() || typed.length > label.length) return mask
            val at = label.indexOf(typed, ignoreCase = true)
            if (at == 0) {
                mask.fill(true, 0, typed.length)
                return mask
            }
            if (matchesHumps(label, typed)) {
                var t = 0
                for (i in label.indices) {
                    if (t == typed.length) break
                    val startsWord = i == 0 || label[i].isUpperCase() || label[i - 1] == '_'
                    if (label[i].equals(typed[t], ignoreCase = true) && (startsWord || (t > 0 && mask[i - 1]))) {
                        mask[i] = true
                        t++
                    }
                }
                if (t == typed.length) return mask
                mask.fill(false)
            }
            if (at > 0) mask.fill(true, at, at + typed.length)
            return mask
        }

        /** "MP" finds ModPlayer, "ModPl" too: each typed letter starts a word or continues the last. */
        private fun matchesHumps(label: String, typed: String): Boolean {
            var t = 0
            var i = 0
            while (i < label.length && t < typed.length) {
                val c = label[i]
                val startsWord = i == 0 || c.isUpperCase() || label[i - 1] == '_'
                if (c.equals(typed[t], ignoreCase = true) && (startsWord || (t > 0 && label[i - 1].equals(typed[t - 1], ignoreCase = true)))) t++
                else if (startsWord && t > 0 && typed[t].isLowerCase() && !c.equals(typed[t], ignoreCase = true)) return false
                i++
            }
            return t == typed.length
        }

        /** Snippet syntax treats `$` as special. */
        private fun escape(text: String) = text.replace("\\", "\\\\").replace("$", "\\$")

        private fun escapeKeepingMarks(text: String) = escape(text)
    }
}

/** Code that expands from a short word, like Visual Studio's `prop` and `for`. */
object CsSnippets {
    class Snippet(val trigger: String, val title: String, val body: String, val usings: List<String> = emptyList())

    val STATEMENT = listOf(
        Snippet("if", "if statement", "if (\$1)\n{\n    \$0\n}"),
        Snippet("else", "else block", "else\n{\n    \$0\n}"),
        Snippet("for", "for loop", "for (int \${1:i} = 0; \$1 < \${2:length}; \$1++)\n{\n    \$0\n}"),
        Snippet("forr", "reverse for loop", "for (int \${1:i} = \${2:length} - 1; \$1 >= 0; \$1--)\n{\n    \$0\n}"),
        Snippet("foreach", "foreach loop", "foreach (var \${1:item} in \${2:collection})\n{\n    \$0\n}"),
        Snippet("while", "while loop", "while (\$1)\n{\n    \$0\n}"),
        Snippet("do", "do-while loop", "do\n{\n    \$0\n} while (\$1);"),
        Snippet("switch", "switch statement", "switch (\$1)\n{\n    case \$2:\n        \$0\n        break;\n    default:\n        break;\n}"),
        Snippet("try", "try-catch", "try\n{\n    \$0\n}\ncatch (Exception e)\n{\n    Mod.Logger.Error(e);\n}", listOf("System")),
        Snippet("text", "Main.NewText", "Main.NewText(\$0);", listOf("Terraria")),
        Snippet("recipe", "Add a recipe", "CreateRecipe()\n    .AddIngredient(ItemID.\${1:DirtBlock}, \${2:10})\n    .AddTile(TileID.\${3:WorkBenches})\n    .Register();\$0", listOf("Terraria.ID")),
        Snippet("dust", "Dust.NewDust", "Dust.NewDust(\${1:position}, \${2:width}, \${3:height}, DustID.\${4:Torch});\$0", listOf("Terraria", "Terraria.ID")),
        Snippet("proj", "Projectile.NewProjectile", "Projectile.NewProjectile(\${1:source}, \${2:position}, \${3:velocity}, \${4:type}, \${5:damage}, \${6:knockback}, Main.myPlayer);\$0", listOf("Terraria")),
    )

    val MEMBER = listOf(
        Snippet("prop", "Auto property", "public \${1:int} \${2:Name} { get; set; }\$0"),
        Snippet("propg", "Read-only property", "public \${1:int} \${2:Name} { get; private set; }\$0"),
        Snippet("ctor", "Constructor", "public \${1:Name}()\n{\n    \$0\n}"),
        Snippet("method", "Method", "public \${1:void} \${2:Name}(\$3)\n{\n    \$0\n}"),
        Snippet("field", "Private field", "private \${1:int} \${2:name};\$0"),
    )

    val FILE = listOf(
        Snippet("class", "Class", "public class \${1:Name}\n{\n    \$0\n}"),
        Snippet("moditem", "ModItem", "public class \${1:MyItem} : ModItem\n{\n    public override void SetDefaults()\n    {\n        Item.width = 20;\n        Item.height = 20;\n        Item.maxStack = Item.CommonMaxStack;\n        Item.value = Item.buyPrice(silver: 1);\n        \$0\n    }\n}", listOf("Terraria", "Terraria.ModLoader")),
        Snippet("modprojectile", "ModProjectile", "public class \${1:MyProjectile} : ModProjectile\n{\n    public override void SetDefaults()\n    {\n        Projectile.width = 16;\n        Projectile.height = 16;\n        Projectile.friendly = true;\n        Projectile.timeLeft = 300;\n        \$0\n    }\n}", listOf("Terraria", "Terraria.ModLoader")),
        Snippet("modnpc", "ModNPC", "public class \${1:MyNPC} : ModNPC\n{\n    public override void SetDefaults()\n    {\n        NPC.width = 32;\n        NPC.height = 32;\n        NPC.lifeMax = 100;\n        NPC.damage = 10;\n        NPC.defense = 2;\n        \$0\n    }\n}", listOf("Terraria", "Terraria.ModLoader")),
        Snippet("modbuff", "ModBuff", "public class \${1:MyBuff} : ModBuff\n{\n    public override void Update(Player player, ref int buffIndex)\n    {\n        \$0\n    }\n}", listOf("Terraria", "Terraria.ModLoader")),
        Snippet("modplayer", "ModPlayer", "public class \${1:MyPlayer} : ModPlayer\n{\n    public override void ResetEffects()\n    {\n        \$0\n    }\n}", listOf("Terraria.ModLoader")),
        Snippet("modsystem", "ModSystem", "public class \${1:MySystem} : ModSystem\n{\n    public override void PostSetupContent()\n    {\n        \$0\n    }\n}", listOf("Terraria.ModLoader")),
    )
}

/** Fixes indentation from the braces, the way Visual Studio's Format Document does. */
object CsFormatter {
    /** Returns [text] with each line indented by its depth; text inside strings and comments stays put. */
    fun reindent(text: String, indent: String = "    "): String {
        val lex = CsLexer.lex(text)
        val lines = text.split('\n')
        val out = StringBuilder(text.length + 64)
        var tokenIndex = 0
        var depth = 0
        var parens = 0
        var offset = 0
        val tokens = lex.tokens
        for ((number, raw) in lines.withIndex()) {
            val lineEnd = offset + raw.length
            val carriage = raw.endsWith("\r")
            val line = raw.trimEnd('\r')
            val trimmed = line.trimStart()
            val insideLiteral = offset > 0 && lex.isQuiet(offset) && !trimmed.startsWith("//") && !trimmed.startsWith("/*") &&
                !trimmed.startsWith("#") && lex.isQuiet(offset - 1)
            val firstTokenClosing = run {
                while (tokenIndex < tokens.size && tokens[tokenIndex].start < offset) tokenIndex++
                val first = tokens.getOrNull(tokenIndex)
                first != null && first.start < lineEnd && (first.isSymbol("}") || first.isSymbol(")") || first.isSymbol("]"))
            }
            var extra = 0
            val first = tokens.getOrNull(tokenIndex)
            val isLabel = first != null && first.start < lineEnd && (first.isWord("case") || first.isWord("default")) &&
                tokens.getOrNull(tokenIndex + 1)?.let { it.isSymbol(":") || first.isWord("case") } == true
            if (first != null && first.start < lineEnd && first.isSymbol(".")) extra = 1
            val level = (depth + parens - (if (firstTokenClosing) 1 else 0) + extra - (if (isLabel) 1 else 0)).coerceAtLeast(0)
            when {
                insideLiteral -> out.append(line)
                trimmed.isEmpty() -> Unit
                trimmed.startsWith("#") -> out.append(trimmed)
                else -> {
                    repeat(level) { out.append(indent) }
                    out.append(trimmed.trimEnd())
                }
            }
            if (carriage) out.append('\r')
            if (number < lines.lastIndex) out.append('\n')
            while (tokenIndex < tokens.size && tokens[tokenIndex].start < lineEnd) {
                val t = tokens[tokenIndex]
                if (t.kind == Token.Kind.SYMBOL) when (t.text) {
                    "{" -> depth++
                    "}" -> depth = (depth - 1).coerceAtLeast(0)
                    "(", "[" -> parens++
                    ")", "]" -> parens = (parens - 1).coerceAtLeast(0)
                }
                tokenIndex++
            }
            offset = lineEnd + 1
        }
        return out.toString()
    }
}
