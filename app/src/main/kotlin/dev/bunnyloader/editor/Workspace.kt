package dev.bunnyloader.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract as Docs
import android.provider.OpenableColumns
import org.json.JSONObject

/**
 * The projects in Bunny Loader's ModSources folder, through SAF: [tree] is the tree
 * it granted (or the user chose). Everything here blocks; call it off the main thread.
 */
class Workspace(private val context: Context, val tree: Uri) {
    private val resolver get() = context.contentResolver

    data class Entry(val uri: Uri, val name: String, val directory: Boolean, val size: Long, val path: String)

    /** What a project is, by the `type` of its manifest (the same spellings Bunny Loader accepts). */
    enum class Kind {
        MOD, TEXTURE, FONT;

        companion object {
            private val ALIASES = mapOf(
                "texture" to TEXTURE, "textures" to TEXTURE, "textura" to TEXTURE, "texturas" to TEXTURE,
                "resourcepack" to TEXTURE, "resource-pack" to TEXTURE,
                "font" to FONT, "fonts" to FONT, "fonte" to FONT, "fontes" to FONT,
            )

            fun of(type: String): Kind = ALIASES[type.trim().lowercase()] ?: MOD
        }
    }

    data class Project(
        val folder: Entry,
        val id: String,
        val name: String,
        val version: String,
        val error: String?,
        val icon: Uri? = null,
        val kind: Kind = Kind.MOD,
        val author: String = "",
    )

    val root: Uri = Docs.buildDocumentUriUsingTree(tree, Docs.getTreeDocumentId(tree))

    fun children(directory: Uri, parentPath: String = ""): List<Entry> {
        val query = Docs.buildChildDocumentsUriUsingTree(tree, Docs.getDocumentId(directory))
        val columns = arrayOf(Docs.Document.COLUMN_DOCUMENT_ID, Docs.Document.COLUMN_DISPLAY_NAME,
            Docs.Document.COLUMN_MIME_TYPE, Docs.Document.COLUMN_SIZE)
        val result = resolver.query(query, columns, null, null, null)?.use { cursor ->
            buildList<Entry> {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    if (name.startsWith('.')) continue
                    add(Entry(
                        Docs.buildDocumentUriUsingTree(tree, cursor.getString(0)), name,
                        cursor.getString(2) == Docs.Document.MIME_TYPE_DIR,
                        if (cursor.isNull(3)) 0 else cursor.getLong(3),
                        if (parentPath.isEmpty()) name else "$parentPath/$name",
                    ))
                }
            }
        } ?: error(context.getString(R.string.ErrReadFolder))
        return result.sortedWith(compareBy<Entry>({ !it.directory }, { it.name.lowercase() }))
    }

    fun projects(): List<Project> = children(root).filter { it.directory }.map { folder ->
        val files = runCatching { children(folder.uri, "") }.getOrDefault(emptyList())
        val icon = files.firstOrNull { it.name == "icon.png" && !it.directory }?.uri
        try {
            val manifest = files.firstOrNull { it.name == MANIFEST && !it.directory } ?: error(context.getString(R.string.ErrMissingManifest))
            val json = JSONObject(readText(manifest.uri))
            Project(folder.copy(path = ""), json.optString("id"), json.optString("name").ifBlank { folder.name },
                json.optString("version"), null, icon, Kind.of(json.optString("type")), authorOf(json))
        } catch (e: Exception) {
            Project(folder.copy(path = ""), "", folder.name, "", e.message ?: e.javaClass.simpleName, icon)
        }
    }

    private fun authorOf(json: JSONObject): String {
        json.optString("author").takeIf { it.isNotBlank() }?.let { return it }
        val first = json.optJSONArray("authors")?.opt(0) ?: return ""
        return (first as? JSONObject)?.optString("name") ?: first.toString()
    }

    /** A small image (the mod icon), already decoded; null if it cannot be. */
    fun readBitmap(uri: Uri, maxSide: Int = 256): android.graphics.Bitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2
        resolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
            })
        }
    }.getOrNull()

    /** The file at [path] (relative to [base]), or null if it no longer exists. */
    fun resolve(base: Uri, path: String): Entry? {
        var current = base
        var found: Entry? = null
        var prefix = ""
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            found = children(current, prefix).firstOrNull { it.name == part } ?: return null
            current = found.uri
            prefix = found.path
        }
        return found
    }

    fun readText(uri: Uri): String {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                buffer.write(chunk, 0, count)
                if (buffer.size() > MAX_TEXT_BYTES) throw TooLarge()
            }
            buffer.toByteArray()
        } ?: error(context.getString(R.string.ErrOpenFile))
        if (bytes.take(8192).any { it == 0.toByte() }) throw Binary()
        return String(bytes, Charsets.UTF_8).removePrefix("﻿")
    }

    fun writeText(uri: Uri, text: String) {
        resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error(context.getString(R.string.ErrWriteFile))
    }

    fun readBytes(uri: Uri): ByteArray =
        resolver.openInputStream(uri)?.use { it.readBytes() } ?: error(context.getString(R.string.ErrOpenFile))

    fun writeBytes(uri: Uri, bytes: ByteArray) {
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error(context.getString(R.string.ErrWriteFile))
    }

    fun output(uri: Uri): java.io.OutputStream = resolver.openOutputStream(uri, "wt") ?: error(context.getString(R.string.ErrWriteFile))

    fun createFile(parent: Uri, name: String): Uri {
        validateName(name)
        check(children(parent).none { it.name.equals(name, true) }) { context.getString(R.string.ErrExists, name) }
        return Docs.createDocument(resolver, parent, "text/plain", name) ?: error(context.getString(R.string.ErrCreate, name))
    }

    /** The file at [path] under [base], creating what is missing; an existing one is reused. */
    fun fileAt(base: Uri, path: String): Uri {
        var folder = base
        val parts = path.split('/').filter { it.isNotEmpty() }
        for (part in parts.dropLast(1)) {
            val found = children(folder).firstOrNull { it.name.equals(part, true) }
            folder = if (found != null) found.uri else createFolder(folder, part)
        }
        val name = parts.last()
        return children(folder).firstOrNull { it.name.equals(name, true) }?.uri ?: createFile(folder, name)
    }

    /** Every file below [folder], with paths relative to it. */
    fun allFiles(folder: Uri, prefix: String = ""): List<Entry> {
        val result = ArrayList<Entry>()
        for (entry in children(folder, prefix)) {
            if (entry.directory) result.addAll(allFiles(entry.uri, entry.path)) else result.add(entry)
        }
        return result
    }

    /**
     * The project as a package Bunny Loader imports (a zip with the manifest at the root), leaving out
     * what its own packager leaves out.
     */
    fun exportPackage(folder: Uri, out: java.io.OutputStream) {
        java.util.zip.ZipOutputStream(out.buffered()).use { zip ->
            for (file in allFiles(folder)) {
                val parts = file.path.split('/')
                if (parts.any { part -> EXCLUDED_FOLDERS.any { it.equals(part, true) } } || file.name.endsWith(".csproj", true)) continue
                zip.putNextEntry(java.util.zip.ZipEntry(file.path).apply { time = 0L })
                resolver.openInputStream(file.uri)?.use { it.copyTo(zip) } ?: error(context.getString(R.string.ErrReadName, file.path))
                zip.closeEntry()
            }
        }
    }

    fun createFolder(parent: Uri, name: String): Uri {
        validateName(name)
        check(children(parent).none { it.name.equals(name, true) }) { context.getString(R.string.ErrExists, name) }
        return Docs.createDocument(resolver, parent, Docs.Document.MIME_TYPE_DIR, name)
            ?: error(context.getString(R.string.ErrCreate, name))
    }

    fun rename(uri: Uri, name: String): Uri {
        validateName(name)
        return Docs.renameDocument(resolver, uri, name) ?: error(context.getString(R.string.ErrRename, name))
    }

    fun delete(uri: Uri) {
        check(Docs.deleteDocument(resolver, uri)) { context.getString(R.string.ErrDelete) }
    }

    /** Copies an outside file (system picker) to [parent]; a repeated name gets " (2)". */
    fun importFile(parent: Uri, source: Uri): String {
        val original = resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }?.replace('/', '_')?.trimStart('.')?.ifBlank { null } ?: "imported"
        val taken = children(parent).map { it.name.lowercase() }.toSet()
        val stem = original.substringBeforeLast('.', original)
        val extension = original.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) original else "$stem ($it)$extension" }
            .first { it.lowercase() !in taken }
        val target = Docs.createDocument(resolver, parent, "application/octet-stream", name)
            ?: error(context.getString(R.string.ErrCreate, name))
        try {
            resolver.openInputStream(source)?.use { input ->
                resolver.openOutputStream(target, "wt")?.use { input.copyTo(it) } ?: error(context.getString(R.string.ErrWriteFile))
            } ?: error(context.getString(R.string.ErrOpenFile))
        } catch (e: Exception) {
            runCatching { Docs.deleteDocument(resolver, target) }
            throw e
        }
        return name
    }

    /** What the new project screen fills in. The description, license etc. the modder creates later, as files. */
    data class NewMod(
        val id: String,
        val name: String,
        val author: String,
        val version: String,
        val uid: String,
        val kind: Kind,
        val icon: Uri?,
    )

    /**
     * A new project, in Bunny Loader's format 3. A mod: manifest.json, the Mod class in `<id>.cs` at
     * the root, Assets/, Common/, Content/ and Localization/. A pack: manifest.json and Images/ or
     * Fonts/. With a picture, icon.png. Returns the folder.
     */
    fun createProject(mod: NewMod): String {
        check(ID.matches(mod.id)) { context.getString(R.string.InvalidId) }
        check(mod.name.isNotBlank()) { context.getString(R.string.ErrProjectFields) }
        check(mod.author.isNotBlank()) { context.getString(R.string.ErrProjectFields) }
        check(UID.matches(mod.uid)) { context.getString(R.string.ErrProjectFields) }
        check(mod.version.isNotBlank()) { context.getString(R.string.ErrProjectFields) }
        check(children(root).none { it.name.equals(mod.id, true) }) { context.getString(R.string.ErrExists, mod.id) }
        val folder = Docs.createDocument(resolver, root, Docs.Document.MIME_TYPE_DIR, mod.id)
            ?: error(context.getString(R.string.ErrCreate, mod.id))
        try {
            val type = when (mod.kind) {
                Kind.MOD -> "mod"
                Kind.TEXTURE -> "texture"
                Kind.FONT -> "font"
            }
            val manifest = buildString {
                append("{\n")
                append("  \"uid\": ${quote(mod.uid)},\n")
                append("  \"id\": ${quote(mod.id)},\n")
                append("  \"name\": ${quote(mod.name.trim())},\n")
                append("  \"version\": ${quote(mod.version.trim())},\n")
                append("  \"author\": ${quote(mod.author.trim())},\n")
                append("  \"type\": ${quote(type)},\n")
                append("  \"blVersion\": $BL_VERSION\n")
                append("}\n")
            }
            writeText(createFile(folder, MANIFEST), manifest)
            when (mod.kind) {
                Kind.MOD -> {
                    listOf("Assets", "Common", "Content", "Localization").forEach { createFolder(folder, it) }
                    val namespace = namespaceOf(mod.id)
                    val className = if (mod.id in CSHARP_KEYWORDS) "${mod.id}Mod" else mod.id
                    val code = "using Terraria.ModLoader;\n\nnamespace $namespace;\n\npublic class $className : Mod\n{\n}\n"
                    writeText(createFile(folder, "${mod.id}.cs"), code)
                    writeText(createFile(folder, "description.md"), "# ${mod.name.trim()}\n")
                }
                Kind.TEXTURE -> createFolder(folder, "Images")
                Kind.FONT -> createFolder(folder, "Fonts")
            }
            mod.icon?.let { writeIcon(folder, it) }
        } catch (e: Exception) {
            runCatching { Docs.deleteDocument(resolver, folder) }
            throw e
        }
        return mod.id
    }

    /** Copies the picture as icon.png; anything that is not a PNG is converted. */
    private fun writeIcon(folder: Uri, source: Uri) {
        val bytes = resolver.openInputStream(source)?.use { it.readBytes() } ?: error(context.getString(R.string.NotAnImage))
        val png = bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
        val target = createFile(folder, "icon.png")
        resolver.openOutputStream(target, "wt")?.use { out ->
            if (png) out.write(bytes)
            else {
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: error(context.getString(R.string.NotAnImage))
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
        } ?: error(context.getString(R.string.ErrWriteFile))
    }

    private fun validateName(name: String) {
        check(name.isNotBlank() && name != "." && name != ".." && !name.startsWith('.') &&
            name.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }) { context.getString(R.string.ErrInvalidName, name) }
    }

    class TooLarge : IllegalStateException("File is larger than 2 MB")
    class Binary : IllegalStateException("Not a text file")

    companion object {
        const val MANIFEST = "manifest.json"
        const val BL_VERSION = 3
        private val EXCLUDED_FOLDERS = listOf("build", "bin", "obj")
        private const val MAX_TEXT_BYTES = 2 * 1024 * 1024
        /**
         * Lowercase: what the Bunny Loader catalog accepts for publishing. Bunny Loader also needs it
         * to be a C# name, since it names the `<id>.cs` file. Projects with other IDs still open.
         */
        val ID = Regex("^[a-z][a-z0-9_]{0,63}$")
        val UID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        /** "Fire Sword 2" becomes "firesword2": the ID suggested from the name. */
        fun idFromName(name: String): String {
            val plain = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
            val words = plain.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
            return words.joinToString("").lowercase().dropWhile { !it.isLetter() }.take(64)
        }

        /** The namespace for a mod ID: an ID can be a C# keyword (`event`), which `@` makes usable. */
        fun namespaceOf(id: String): String = if (id in CSHARP_KEYWORDS) "@$id" else id
        private val CSHARP_KEYWORDS = setOf(
            "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "checked", "class",
            "const", "continue", "decimal", "default", "delegate", "do", "double", "else", "enum", "event",
            "explicit", "extern", "false", "finally", "fixed", "float", "for", "foreach", "goto", "if",
            "implicit", "in", "int", "interface", "internal", "is", "lock", "long", "namespace", "new", "null",
            "object", "operator", "out", "override", "params", "private", "protected", "public", "readonly",
            "ref", "return", "sbyte", "sealed", "short", "sizeof", "stackalloc", "static", "string", "struct",
            "switch", "this", "throw", "true", "try", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort",
            "using", "virtual", "void", "volatile", "while",
        )

        fun quote(value: String): String = buildString {
            append('"')
            for (c in value) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
            append('"')
        }

        /** The tree granted before, if the permission still holds. */
        fun saved(context: Context): Uri? = context.contentResolver.persistedUriPermissions
            .firstOrNull { it.isReadPermission && it.isWritePermission && Docs.isTreeUri(it.uri) }?.uri

        fun keep(context: Context, tree: Uri) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.persistedUriPermissions.filter { it.uri != tree }.forEach {
                runCatching { context.contentResolver.releasePersistableUriPermission(it.uri, flags) }
            }
            context.contentResolver.takePersistableUriPermission(tree, flags)
        }
    }
}
