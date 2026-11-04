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

    data class Project(
        val folder: Entry,
        val id: String,
        val name: String,
        val version: String,
        val error: String?,
        val icon: Uri? = null,
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
        } ?: error("Cannot read folder")
        return result.sortedWith(compareBy<Entry>({ !it.directory }, { it.name.lowercase() }))
    }

    fun projects(): List<Project> = children(root).filter { it.directory }.map { folder ->
        val files = runCatching { children(folder.uri, "") }.getOrDefault(emptyList())
        val icon = files.firstOrNull { it.name == "icon.png" && !it.directory }?.uri
        try {
            val manifest = files.firstOrNull { it.name == MANIFEST && !it.directory } ?: error("Missing manifest.json")
            val json = JSONObject(readText(manifest.uri))
            Project(folder.copy(path = ""), json.optString("id"), json.optString("name").ifBlank { folder.name },
                json.optString("version"), null, icon)
        } catch (e: Exception) {
            Project(folder.copy(path = ""), "", folder.name, "", e.message ?: e.javaClass.simpleName, icon)
        }
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
        } ?: error("Cannot open file")
        if (bytes.take(8192).any { it == 0.toByte() }) throw Binary()
        return String(bytes, Charsets.UTF_8).removePrefix("﻿")
    }

    fun writeText(uri: Uri, text: String) {
        resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("Cannot write file")
    }

    fun createFile(parent: Uri, name: String): Uri {
        validateName(name)
        check(children(parent).none { it.name.equals(name, true) }) { "\"$name\" already exists" }
        return Docs.createDocument(resolver, parent, "text/plain", name) ?: error("Cannot create \"$name\"")
    }

    fun createFolder(parent: Uri, name: String): Uri {
        validateName(name)
        check(children(parent).none { it.name.equals(name, true) }) { "\"$name\" already exists" }
        return Docs.createDocument(resolver, parent, Docs.Document.MIME_TYPE_DIR, name)
            ?: error("Cannot create \"$name\"")
    }

    fun rename(uri: Uri, name: String): Uri {
        validateName(name)
        return Docs.renameDocument(resolver, uri, name) ?: error("Cannot rename to \"$name\"")
    }

    fun delete(uri: Uri) {
        check(Docs.deleteDocument(resolver, uri)) { "Cannot delete" }
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
            ?: error("Cannot create \"$name\"")
        try {
            resolver.openInputStream(source)?.use { input ->
                resolver.openOutputStream(target, "wt")?.use { input.copyTo(it) } ?: error("Cannot write \"$name\"")
            } ?: error("Cannot read the selected file")
        } catch (e: Exception) {
            runCatching { Docs.deleteDocument(resolver, target) }
            throw e
        }
        return name
    }

    enum class Language(val extension: String) { JAVASCRIPT("js"), CSHARP("cs") }

    /** What the new mod screen fills in. The description, license etc. the modder creates later, as files. */
    data class NewMod(
        val id: String,
        val name: String,
        val author: String,
        val version: String,
        val uid: String,
        val language: Language,
        val icon: Uri?,
    )

    /**
     * A new mod, in the format Bunny Loader's packager accepts: manifest.json,
     * content/<entry> and, with a picture, icon.png. Returns the folder.
     */
    fun createProject(mod: NewMod): String {
        check(ID.matches(mod.id)) { "Invalid ID" }
        check(mod.name.isNotBlank()) { "Name is empty" }
        check(mod.author.isNotBlank()) { "Author is empty" }
        check(UID.matches(mod.uid)) { "Invalid UID" }
        check(mod.version.isNotBlank()) { "Version is empty" }
        check(children(root).none { it.name.equals(mod.id, true) }) { "A project named \"${mod.id}\" already exists" }
        val folder = Docs.createDocument(resolver, root, Docs.Document.MIME_TYPE_DIR, mod.id)
            ?: error("Cannot create the project folder")
        try {
            val className = if (mod.language == Language.JAVASCRIPT && mod.id in JS_RESERVED) "${mod.id}Mod" else mod.id
            val entry = "$className.${mod.language.extension}".takeIf { mod.language == Language.CSHARP } ?: "main.js"
            val manifest = buildString {
                append("{\n")
                append("  \"uid\": ${quote(mod.uid)},\n")
                append("  \"id\": ${quote(mod.id)},\n")
                append("  \"name\": ${quote(mod.name.trim())},\n")
                append("  \"version\": ${quote(mod.version.trim())},\n")
                append("  \"author\": ${quote(mod.author.trim())},\n")
                append("  \"type\": \"mod\",\n")
                append("  \"blVersion\": 2,\n")
                append("  \"entry\": ${quote(entry)}\n")
                append("}\n")
            }
            writeText(createFile(folder, MANIFEST), manifest)
            val content = createFolder(folder, "content")
            val code = when (mod.language) {
                Language.JAVASCRIPT -> "export default class $className extends Mod {\n    Load() {\n    }\n}\n"
                Language.CSHARP -> "using BunnyLoader;\n\npublic class $className : Mod\n{\n" +
                    "    public override void Load()\n    {\n    }\n}\n"
            }
            writeText(createFile(content, entry), code)
            mod.icon?.let { writeIcon(folder, it) }
        } catch (e: Exception) {
            runCatching { Docs.deleteDocument(resolver, folder) }
            throw e
        }
        return mod.id
    }

    /** Copies the picture as icon.png; anything that is not a PNG is converted. */
    private fun writeIcon(folder: Uri, source: Uri) {
        val bytes = resolver.openInputStream(source)?.use { it.readBytes() } ?: error("Cannot read the image")
        val png = bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
        val target = createFile(folder, "icon.png")
        resolver.openOutputStream(target, "wt")?.use { out ->
            if (png) out.write(bytes)
            else {
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: error("The selected file is not an image")
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
        } ?: error("Cannot write icon.png")
    }

    private fun validateName(name: String) {
        check(name.isNotBlank() && name != "." && name != ".." && !name.startsWith('.') &&
            name.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }) { "Invalid name: \"$name\"" }
    }

    class TooLarge : IllegalStateException("File is larger than 2 MB")
    class Binary : IllegalStateException("Not a text file")

    companion object {
        const val MANIFEST = "manifest.json"
        private const val MAX_TEXT_BYTES = 2 * 1024 * 1024
        val ID = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$")
        val UID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        /** "Fire Sword 2" becomes "FireSword2": the ID suggested from the name. */
        fun idFromName(name: String): String {
            val plain = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
            val words = plain.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
            val joined = words.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
            return joined.dropWhile { !it.isLetter() }.take(64)
        }
        private val JS_RESERVED = setOf(
            "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default",
            "delete", "do", "else", "enum", "export", "extends", "false", "finally", "for", "function",
            "if", "implements", "import", "in", "instanceof", "interface", "let", "new", "null",
            "package", "private", "protected", "public", "return", "static", "super", "switch",
            "this", "throw", "true", "try", "typeof", "var", "void", "while", "with", "yield",
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
