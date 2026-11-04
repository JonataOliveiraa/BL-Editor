package dev.bunnyloader.editor

import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A project's file tree: only open folders are read. Paths are relative to the project root. */
class FileTree(private val workspace: Workspace, val root: Uri) {
    val children = mutableStateMapOf<String, List<Workspace.Entry>>()
    val expanded = mutableStateListOf<String>()

    suspend fun reload() {
        val open = expanded.toList().sortedBy { path -> path.count { it == '/' } }
        val result = withContext(Dispatchers.IO) {
            val map = mutableMapOf("" to workspace.children(root, ""))
            for (path in open) {
                val folder = map[path.substringBeforeLast('/', "")]?.firstOrNull { it.path == path && it.directory }
                    ?: continue
                map[path] = workspace.children(folder.uri, path)
            }
            map
        }
        children.clear()
        children.putAll(result)
        expanded.retainAll(result.keys)
    }

    suspend fun expand(folder: Workspace.Entry) {
        val list = withContext(Dispatchers.IO) { workspace.children(folder.uri, folder.path) }
        children[folder.path] = list
        if (folder.path !in expanded) expanded.add(folder.path)
    }

    fun collapse(path: String) {
        expanded.removeAll { it == path || it.startsWith("$path/") }
    }

    /** The visible rows, in order, each with its depth. */
    fun rows(): List<Pair<Workspace.Entry, Int>> = buildList {
        fun visit(path: String, depth: Int) {
            for (entry in children[path].orEmpty()) {
                add(entry to depth)
                if (entry.directory && entry.path in expanded) visit(entry.path, depth + 1)
            }
        }
        visit("", 0)
    }

    /** After renaming a folder, the open ones inside it follow the new name. */
    fun moved(from: String, to: String) {
        val renamed = expanded.map { if (it == from || it.startsWith("$from/")) to + it.removePrefix(from) else it }
        expanded.clear()
        expanded.addAll(renamed)
    }
}
