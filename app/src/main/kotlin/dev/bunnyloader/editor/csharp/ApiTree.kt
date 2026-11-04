package dev.bunnyloader.editor.csharp

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** A field, property, method, constructor, constant, event or indexer. */
class ApiMember(
    val kind: Char,
    val name: String,
    val flags: String,
    /** The type text: full names, keywords for primitives. Null for constructors. */
    val type: String?,
    val parameters: List<String> = emptyList(),
    val generics: List<String> = emptyList(),
    /** The value of a constant, as C# source. */
    val value: String? = null,
) {
    val isStatic get() = 'S' in flags
    val isProtected get() = 'P' in flags
    val isExtension get() = 'E' in flags
    val isReadOnly get() = 'R' in flags
    val isOverridable get() = 'V' in flags || 'A' in flags || 'O' in flags
    val isCallable get() = kind == METHOD || kind == CONSTRUCTOR

    companion object {
        const val FIELD = 'f'
        const val CONSTANT = 'k'
        const val PROPERTY = 'p'
        const val METHOD = 'm'
        const val CONSTRUCTOR = 'x'
        const val EVENT = 'e'
        const val INDEXER = 'i'
    }
}

/**
 * A type of the tree, or of the project being edited. [members] is the branch that loads on
 * demand: asking for it is the only thing that touches the member blocks.
 */
class ApiType(
    val fullName: String,
    val name: String,
    val namespace: String,
    val kind: String,
    val flags: String,
    val base: String?,
    val interfaces: List<String>,
    val generics: List<String>,
    val outer: ApiType?,
    /** How a project type's texts resolve: its file's usings. Null for tree types, which are fully qualified. */
    val scope: TypeScope? = null,
    private val loader: () -> List<ApiMember>,
) {
    val nested = ArrayList<ApiType>(0)
    val isStatic get() = 'S' in flags
    val isAbstract get() = 'A' in flags
    val isEnum get() = kind == "enum"
    val members: List<ApiMember> get() = loader()
}

/** Where a project type looks its names up. */
class TypeScope(val usings: List<String>, val namespace: String, val aliases: Map<String, String> = emptyMap())

/**
 * The C# API tree: namespaces > types > members. The tree and its types load once; each type's
 * members load the first time they are asked for, from a small gzip block, so a keystroke only
 * pays for the types it looks at. Thread-safe: completion runs off the main thread.
 */
class ApiTree private constructor(private val open: (String) -> InputStream?) {
    private val types = HashMap<String, ApiType>(32768)
    private val namespaces = HashSet<String>(256)
    private val namespaceTypes = HashMap<String, MutableList<ApiType>>(256)
    private val namespaceChildren = HashMap<String, MutableSet<String>>(256)
    private val extensions = HashMap<String, List<String>>()

    /** Every top-level and nested type, by simple name, sorted case-insensitively: the search. */
    lateinit var searchNames: Array<ApiType>
        private set
    private val bySimpleName = HashMap<String, MutableList<ApiType>>(32768)

    private val blocks = object : LinkedHashMap<Int, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>) = size > MAX_BLOCKS
    }
    private val parsed = object : LinkedHashMap<String, List<ApiMember>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ApiMember>>) = size > MAX_PARSED
    }

    fun type(fullName: String): ApiType? = types[fullName]
    fun isNamespace(name: String) = name in namespaces
    fun typesIn(namespace: String): List<ApiType> = namespaceTypes[namespace].orEmpty()
    fun childNamespaces(namespace: String): Set<String> = namespaceChildren[namespace].orEmpty()
    fun bySimpleName(name: String): List<ApiType> = bySimpleName[name].orEmpty()
    fun extensionHolders(target: String): List<String> = extensions[target].orEmpty()
    val rootNamespaces: Set<String> get() = childNamespaces("")
    val typeCount: Int get() = types.size

    private fun block(index: Int): ByteArray = synchronized(blocks) {
        blocks.getOrPut(index) {
            val name = "members/%03d.blapi".format(index)
            val stream = open(name) ?: error("Missing API block $name")
            GZIPInputStream(stream, 16 * 1024).use { it.readBytes() }
        }
    }

    private fun loadMembers(fullName: String, block: Int, offset: Int, length: Int): List<ApiMember> {
        synchronized(parsed) { parsed[fullName]?.let { return it } }
        val text = String(block(block), offset, length, Charsets.UTF_8)
        val result = (Json.parse(text) as List<*>).map { memberFrom(it as List<*>) }
        synchronized(parsed) { parsed[fullName] = result }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun read(root: Map<String, Any?>) {
        val ns = root["ns"] as Map<String, Any?>
        for ((name, node) in ns) visitNamespace(name, "", node as Map<String, Any?>)
        (root["ext"] as? Map<String, Any?>)?.forEach { (target, holders) -> extensions[target] = holders as List<String> }
        val all = ArrayList<ApiType>(types.size)
        all.addAll(types.values)
        all.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        searchNames = all.toTypedArray()
        for (type in all) bySimpleName.getOrPut(type.name) { ArrayList(1) }.add(type)
    }

    @Suppress("UNCHECKED_CAST")
    private fun visitNamespace(name: String, parent: String, node: Map<String, Any?>) {
        val full = if (parent.isEmpty()) name else "$parent.$name"
        namespaces.add(full)
        namespaceChildren.getOrPut(parent) { HashSet() }.add(name)
        (node["t"] as? Map<String, Any?>)?.forEach { (typeName, typeNode) ->
            val type = readType(typeName, full, null, typeNode as Map<String, Any?>)
            namespaceTypes.getOrPut(full) { ArrayList() }.add(type)
        }
        (node["n"] as? Map<String, Any?>)?.forEach { (child, childNode) ->
            visitNamespace(child, full, childNode as Map<String, Any?>)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readType(name: String, namespace: String, outer: ApiType?, node: Map<String, Any?>): ApiType {
        val full = if (outer != null) "${outer.fullName}.$name" else "$namespace.$name"
        val at = node["at"] as? List<Any?>
        val loader = if (at == null) NO_MEMBERS else memberLoader(full, (at[0] as Number).toInt(), (at[1] as Number).toInt(), (at[2] as Number).toInt())
        val type = ApiType(
            full, name, namespace, node["k"] as? String ?: "class", node["f"] as? String ?: "",
            node["b"] as? String, node["i"] as? List<String> ?: emptyList(), node["g"] as? List<String> ?: emptyList(),
            outer, null, loader,
        )
        types[full] = type
        (node["t"] as? Map<String, Any?>)?.forEach { (child, childNode) ->
            type.nested.add(readType(child, namespace, type, childNode as Map<String, Any?>))
        }
        return type
    }

    private fun memberLoader(full: String, block: Int, offset: Int, length: Int): () -> List<ApiMember> =
        { loadMembers(full, block, offset, length) }

    companion object {
        private const val MAX_BLOCKS = 8
        private const val MAX_PARSED = 512
        private val NO_MEMBERS: () -> List<ApiMember> = { emptyList() }

        /** [open] gives the tree's files, by name relative to the tree's folder. */
        fun load(open: (String) -> InputStream?): ApiTree {
            val tree = ApiTree(open)
            val stream = open("index.blapi") ?: error("Missing API index")
            val text = GZIPInputStream(stream, 32 * 1024).use { input ->
                val buffer = ByteArrayOutputStream(4 * 1024 * 1024)
                input.copyTo(buffer, 32 * 1024)
                buffer.toString("UTF-8")
            }
            @Suppress("UNCHECKED_CAST")
            tree.read(Json.parse(text) as Map<String, Any?>)
            return tree
        }

        private fun memberFrom(row: List<*>): ApiMember {
            val kind = (row[0] as String)[0]
            val flags = row.getOrNull(2) as? String ?: ""
            val type = row.getOrNull(3) as? String
            return if (kind == ApiMember.CONSTANT) {
                ApiMember(kind, row[1] as String, flags, type, value = row.getOrNull(4) as? String)
            } else {
                @Suppress("UNCHECKED_CAST")
                ApiMember(kind, row[1] as String, flags, type,
                    row.getOrNull(4) as? List<String> ?: emptyList(),
                    row.getOrNull(5) as? List<String> ?: emptyList())
            }
        }
    }
}

/** A small JSON reader. The tree needs no streaming, no numbers beyond ints and no validation. */
internal object Json {
    fun parse(text: String): Any? = Reader(text).value()

    private class Reader(val s: String) {
        var i = 0

        fun value(): Any? {
            skip()
            return when (s[i]) {
                '{' -> obj()
                '[' -> list()
                '"' -> string()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> number()
            }
        }

        private fun skip() {
            while (i < s.length && s[i] <= ' ') i++
        }

        private fun obj(): Map<String, Any?> {
            val map = HashMap<String, Any?>(8)
            i++
            skip()
            if (s[i] == '}') { i++; return map }
            while (true) {
                skip()
                val key = string()
                skip()
                i++ // ':'
                map[key] = value()
                skip()
                if (s[i++] == '}') return map
            }
        }

        private fun list(): List<Any?> {
            i++
            skip()
            if (s[i] == ']') { i++; return emptyList() }
            val result = ArrayList<Any?>(6)
            while (true) {
                result.add(value())
                skip()
                if (s[i++] == ']') return result
            }
        }

        private fun string(): String {
            val start = ++i
            while (true) {
                val c = s[i]
                if (c == '"') return s.substring(start, i++)
                if (c == '\\') return escaped(start)
                i++
            }
        }

        private fun escaped(start: Int): String {
            val out = StringBuilder(s.length.coerceAtMost(64)).append(s, start, i)
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> { out.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> out.append(e)
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Number {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
            val text = s.substring(start, i)
            return text.toIntOrNull() ?: text.toDouble()
        }
    }
}
