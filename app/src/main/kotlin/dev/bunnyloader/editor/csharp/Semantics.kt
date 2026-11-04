package dev.bunnyloader.editor.csharp

/** The types of the project's own files, kept up to date as they are saved. */
class ProjectIndex {
    private val byPath = HashMap<String, List<ApiType>>()
    @Volatile private var bySimpleName: Map<String, List<ApiType>> = emptyMap()
    @Volatile private var byFullName: Map<String, ApiType> = emptyMap()

    @Synchronized
    fun update(path: String, text: CharSequence) = put(path, typesOf(CsFile.parse(text)))

    @Synchronized
    fun remove(path: String) {
        byPath.remove(path)
        rebuild()
    }

    private fun put(path: String, types: List<ApiType>) {
        byPath[path] = types
        rebuild()
    }

    private fun rebuild() {
        val all = byPath.values.flatten()
        bySimpleName = all.groupBy { it.name }
        byFullName = all.associateBy { it.fullName }
    }

    fun type(fullName: String): ApiType? = byFullName[fullName]
    fun simple(name: String): List<ApiType> = bySimpleName[name].orEmpty()
    fun all(): Collection<ApiType> = byFullName.values

    companion object {
        /** The file's types, in the shape of the tree's. Their texts resolve through the file's usings. */
        fun typesOf(file: CsFile): List<ApiType> {
            val scope = TypeScope(file.usings, file.namespace, file.aliases)
            val result = ArrayList<ApiType>()
            fun convert(decl: CsTypeDecl, outer: ApiType?): ApiType {
                val flags = buildString {
                    if ("static" in decl.modifiers) append('S')
                    if ("abstract" in decl.modifiers) append('A')
                }
                val members = lazy(LazyThreadSafetyMode.PUBLICATION) { decl.members.map(::memberOf) }
                val type = ApiType(
                    decl.fullName, decl.name, decl.namespace, decl.kind, flags,
                    decl.bases.firstOrNull(), decl.bases.drop(1), decl.generics, outer, scope,
                ) { members.value }
                result.add(type)
                decl.nested.forEach { type.nested.add(convert(it, type)) }
                return type
            }
            file.types.forEach { convert(it, null) }
            return result
        }

        private fun memberOf(m: CsMember): ApiMember {
            val flags = buildString {
                if ("static" in m.modifiers || "const" in m.modifiers) append('S')
                if ("protected" in m.modifiers) append('P')
                if ("abstract" in m.modifiers) append('A')
                if ("virtual" in m.modifiers) append('V')
                if ("override" in m.modifiers) append('O')
                if ("public" !in m.modifiers && "internal" !in m.modifiers && "protected" !in m.modifiers) append(HIDDEN)
            }
            return ApiMember(m.kind, m.name, flags, m.type, m.parameters, m.generics)
        }

        /** A flag only project members have: private, so it only shows from inside its type. */
        const val HIDDEN = 'H'
    }
}

/** A resolved type: the definition, its generic arguments and how many array levels wrap it. */
class TypeValue(val def: ApiType?, val args: List<TypeValue> = emptyList(), val rank: Int = 0, val text: String = def?.fullName ?: "?") {
    fun element(): TypeValue = TypeValue(def, args, (rank - 1).coerceAtLeast(0), text)
}

/** A member found on a type, with what its owner's generics stand for there. */
class FoundMember(val member: ApiMember, val owner: ApiType, val bindings: Map<String, TypeValue>, val extension: Boolean = false) {
    /** The parameters as a caller sees them: an extension's receiver is not one. */
    val parameters: List<String> get() = if (extension) member.parameters.drop(1) else member.parameters
}

/** Type names and member lookups, shared by completion and parameter info. */
class Semantics(val tree: ApiTree, val project: ProjectIndex?) {
    /** Overrides project types with what is on screen, which may be newer than what was saved. */
    var localTypes: Map<String, ApiType> = emptyMap()
    var localBySimpleName: Map<String, List<ApiType>> = emptyMap()

    fun byFullName(name: String): ApiType? = localTypes[name] ?: project?.type(name) ?: tree.type(name)

    private fun candidates(name: String): List<ApiType> {
        val local = localBySimpleName[name].orEmpty()
        val own = project?.simple(name).orEmpty()
        val api = tree.bySimpleName(name)
        return if (own.isEmpty() && local.isEmpty()) api else local + own + api
    }

    /** Finds a type by the name a piece of code uses for it. */
    fun find(name: String, scope: TypeScope?, within: ApiType? = null): ApiType? {
        val clean = name.removePrefix("global::")
        scope?.aliases?.get(clean)?.let { return find(it, scope, within) }
        PRIMITIVES[clean]?.let { return tree.type(it) }
        if ('.' in clean) {
            byFullName(clean)?.let { return it }
            val first = clean.substringBefore('.')
            val rest = clean.substringAfter('.')
            // A namespace relative to the current one or to a using: `ID.ItemID`.
            if (scope != null) {
                for (base in namespacesOf(scope.namespace) + scope.usings) byFullName("$base.$clean")?.let { return it }
            }
            val root = find(first, scope, within) ?: return null
            return rest.split('.').fold(root as ApiType?) { type, part -> type?.let { nestedType(it, part, scope) } }
        }
        var outer = within
        while (outer != null) {
            nestedType(outer, clean, outer.scope ?: scope)?.let { return it }
            outer = outer.outer
        }
        val all = candidates(clean).filter { it.outer == null }
        if (all.isEmpty()) return null
        if (all.size == 1 && scope == null) return all[0]
        val imports = if (scope == null) emptySet() else (namespacesOf(scope.namespace) + scope.usings).toSet()
        return all.firstOrNull { it.namespace in imports } ?: all.firstOrNull { it.namespace == "" }
            ?: all.firstOrNull { it.namespace.startsWith("Terraria") } ?: all[0]
    }

    private fun nestedType(type: ApiType, name: String, scope: TypeScope?, depth: Int = 0): ApiType? {
        type.nested.firstOrNull { it.name == name }?.let { return it }
        if (depth > 16) return null
        val base = type.base?.substringBefore('<') ?: return null
        val parent = find(base, type.scope ?: scope, null) ?: return null
        return if (parent === type) null else nestedType(parent, name, scope, depth + 1)
    }

    /** A type text (with generics, arrays, `ref`...) with the generics that are known at that spot. */
    fun resolve(text: String?, scope: TypeScope?, bindings: Map<String, TypeValue> = emptyMap(), within: ApiType? = null): TypeValue? {
        if (text == null) return null
        var s = text.trim()
        for (prefix in PREFIXES) if (s.startsWith(prefix)) s = s.substring(prefix.length).trimStart()
        var rank = 0
        while (true) {
            s = when {
                s.endsWith("?") || s.endsWith("*") -> s.dropLast(1)
                s.endsWith("]") -> {
                    val open = s.lastIndexOf('[')
                    if (open < 0) break
                    rank++
                    s.substring(0, open)
                }
                else -> break
            }.trimEnd()
        }
        if (s.startsWith("(")) return TypeValue(tree.type("System.ValueTuple"), emptyList(), rank, "tuple")
        val angle = s.indexOf('<')
        val name = if (angle < 0) s else s.substring(0, angle)
        val args = if (angle < 0 || !s.endsWith(">")) emptyList()
            else splitTop(s.substring(angle + 1, s.length - 1)).map {
                resolve(it, scope, bindings, within) ?: TypeValue(null, text = it.trim())
            }
        bindings[name]?.let { bound -> return if (rank == 0) bound else TypeValue(bound.def, bound.args, bound.rank + rank, bound.text) }
        if (within != null && generalGeneric(name, within)) return TypeValue(null, text = name)
        val def = find(name, scope, within) ?: return TypeValue(null, args, rank, s)
        return TypeValue(def, args, rank)
    }

    private fun generalGeneric(name: String, within: ApiType): Boolean {
        var type: ApiType? = within
        while (type != null) {
            if (name in type.generics) return true
            type = type.outer
        }
        return false
    }

    /** Every member reachable from [value], nearest first; a member hidden by a nearer one with the same shape is left out. */
    fun members(value: TypeValue, includeExtensions: Boolean, imports: Set<String>): List<FoundMember> {
        val result = ArrayList<FoundMember>(64)
        val seen = HashSet<String>(128)
        val visited = HashSet<String>()
        val chain = ArrayList<Pair<ApiType, Map<String, TypeValue>>>()
        val start = if (value.rank > 0) tree.type("System.Array")?.let { TypeValue(it) } ?: value else value
        fun visit(type: TypeValue) {
            val def = type.def ?: return
            if (!visited.add(def.fullName)) return
            val bindings = if (def.generics.isEmpty() || type.args.isEmpty()) emptyMap()
                else def.generics.zip(type.args).toMap()
            chain.add(def to bindings)
            for (member in def.members) {
                if (member.kind == ApiMember.CONSTRUCTOR) continue
                val key = if (member.isCallable) "${member.name}/${member.parameters.joinToString()}" else member.name
                if (seen.add(key)) result.add(FoundMember(member, def, bindings))
            }
            resolve(def.base, def.scope, bindings, def)?.let(::visit)
            for (i in def.interfaces) resolve(i, def.scope, bindings, def)?.let(::visit)
        }
        visit(start)
        tree.type("System.Object")?.let { visit(TypeValue(it)) }
        if (includeExtensions) {
            for ((def, bindings) in chain) {
                for (holder in tree.extensionHolders(def.fullName)) {
                    val holderType = tree.type(holder) ?: continue
                    if (holderType.namespace !in imports) continue
                    for (member in holderType.members) {
                        if (!member.isExtension || member.parameters.isEmpty()) continue
                        val first = member.parameters[0]
                        val target = first.substringBeforeLast(' ').removePrefix("ref ").removePrefix("in ").substringBefore('<').trimEnd('?')
                        if (target != def.fullName) continue
                        result.add(FoundMember(member, holderType, unify(member, first, def, bindings), extension = true))
                    }
                }
            }
        }
        return result
    }

    /** Binds an extension's generics from its receiver: `IEnumerable<TSource>` on `List<Item>` gives TSource = Item. */
    private fun unify(member: ApiMember, first: String, def: ApiType, bindings: Map<String, TypeValue>): Map<String, TypeValue> {
        if (member.generics.isEmpty() || def.generics.isEmpty()) return emptyMap()
        val text = first.substringBeforeLast(' ')
        val angle = text.indexOf('<')
        if (angle < 0 || !text.endsWith(">")) return emptyMap()
        val parts = splitTop(text.substring(angle + 1, text.length - 1))
        val result = HashMap<String, TypeValue>()
        parts.forEachIndexed { i, part ->
            val generic = part.trim()
            if (generic in member.generics) def.generics.getOrNull(i)?.let { bindings[it] }?.let { result[generic] = it }
        }
        return result
    }

    companion object {
        private val PREFIXES = listOf("ref readonly ", "ref ", "out ", "in ", "params ", "this ", "scoped ", "readonly ")

        val PRIMITIVES = mapOf(
            "int" to "System.Int32", "string" to "System.String", "bool" to "System.Boolean", "float" to "System.Single",
            "double" to "System.Double", "object" to "System.Object", "byte" to "System.Byte", "sbyte" to "System.SByte",
            "short" to "System.Int16", "ushort" to "System.UInt16", "uint" to "System.UInt32", "long" to "System.Int64",
            "ulong" to "System.UInt64", "char" to "System.Char", "decimal" to "System.Decimal", "nint" to "System.IntPtr",
            "nuint" to "System.UIntPtr",
        )

        /** `A.B.C` and the namespaces around it: its own code sees A.B and A without a using. */
        fun namespacesOf(namespace: String): List<String> {
            if (namespace.isEmpty()) return listOf("")
            val result = ArrayList<String>()
            var current = namespace
            while (true) {
                result.add(current)
                val dot = current.lastIndexOf('.')
                if (dot < 0) break
                current = current.substring(0, dot)
            }
            result.add("")
            return result
        }

        /** Splits on commas outside `<>`, `()` and `[]`. */
        fun splitTop(text: String): List<String> {
            val result = ArrayList<String>(2)
            var depth = 0
            var start = 0
            for ((i, c) in text.withIndex()) {
                when (c) {
                    '<', '(', '[' -> depth++
                    '>', ')', ']' -> depth--
                    ',' -> if (depth == 0) {
                        result.add(text.substring(start, i))
                        start = i + 1
                    }
                }
            }
            if (start < text.length) result.add(text.substring(start))
            return result
        }

        /** `Terraria.ModLoader.DamageClass[]` shows as `DamageClass[]`. */
        fun shortText(text: String?): String {
            if (text == null) return ""
            if ('.' !in text) return text
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '_' || c.isLetterOrDigit()) {
                    val start = i
                    while (i < text.length && (text[i] == '_' || text[i].isLetterOrDigit() || text[i] == '.')) i++
                    val word = text.substring(start, i)
                    // A number literal in a default keeps its dot.
                    out.append(if (word[0].isDigit()) word else word.substringAfterLast('.'))
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }
    }
}
