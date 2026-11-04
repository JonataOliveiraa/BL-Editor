package dev.bunnyloader.editor

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What each tree row asks for. */
interface TreeActions {
    fun open(entry: Workspace.Entry)
    fun toggle(entry: Workspace.Entry)
    fun newFile(parent: Uri, parentPath: String)
    fun newFolder(parent: Uri, parentPath: String)
    fun import(parent: Uri, parentPath: String)
    fun rename(entry: Workspace.Entry)
    fun delete(entry: Workspace.Entry)
    fun refresh()
}

private val RowIndent = 14.dp
private val RowStart = 6.dp

/**
 * The file tree, VS Code style: rows touching each other (the space is the row's
 * padding, not a margin between them) and one vertical guide per level, showing
 * what is inside what. The guide of the open file's folder is brighter.
 *
 * The buttons on top act on the selected folder (or the selected file's folder);
 * a long press on a row opens its menu.
 */
@Composable
fun FilesPanel(
    title: String,
    tree: FileTree,
    selectedPath: String?,
    openPath: String?,
    onSelect: (String) -> Unit,
    actions: TreeActions,
    modifier: Modifier = Modifier,
) {
    val rows = tree.rows()
    val selected = rows.firstOrNull { it.first.path == selectedPath }?.first
    val target: Pair<Uri, String> = when {
        selected == null -> tree.root to ""
        selected.directory -> selected.uri to selected.path
        else -> {
            val parent = selected.path.substringBeforeLast('/', "")
            if (parent.isEmpty()) tree.root to ""
            else (rows.firstOrNull { it.first.path == parent }?.first?.uri ?: tree.root) to parent
        }
    }
    val activeFolder = openPath?.substringBeforeLast('/', "")
    Column(modifier.fillMaxSize().background(Bl.Code)) {
        Column(Modifier.fillMaxWidth().pixelPanel(fill = Bl.Panel, corner = PixelCorner.NONE).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            PixelText(title, Ts.Item, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelText(stringResource(R.string.Files).uppercase(), Ts.Tiny, Bl.TextFaint, Modifier.weight(1f))
                IconAction(stringResource(R.string.NewFile), { actions.newFile(target.first, target.second) }) {
                    GlyphIcon(Glyph.NEW_FILE, 20.dp)
                }
                IconAction(stringResource(R.string.NewFolder), { actions.newFolder(target.first, target.second) }) {
                    GlyphIcon(Glyph.NEW_FOLDER, 20.dp)
                }
                IconAction(stringResource(R.string.ImportFile), { actions.import(target.first, target.second) }) {
                    GlyphIcon(Glyph.IMPORT, 20.dp)
                }
                IconAction(stringResource(R.string.Refresh), actions::refresh) { PixelIcon(R.drawable.ic_refresh, 18.dp) }
                IconAction(stringResource(R.string.CollapseAll), { tree.expanded.clear() }) {
                    GlyphIcon(Glyph.COLLAPSE, 20.dp)
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = 4.dp)) {
            items(rows, key = { it.first.path }) { (entry, depth) ->
                TreeRow(
                    entry, depth,
                    expanded = entry.path in tree.expanded,
                    selected = entry.path == selectedPath,
                    open = entry.path == openPath,
                    activeDepth = activeFolder?.takeIf { it.isNotEmpty() && entry.path.startsWith("$it/") }
                        ?.count { it == '/' },
                    onClick = {
                        onSelect(entry.path)
                        if (entry.directory) actions.toggle(entry) else actions.open(entry)
                    },
                    onLongClick = { onSelect(entry.path) },
                    actions = actions,
                )
            }
        }
    }
}

@Composable
private fun TreeRow(
    entry: Workspace.Entry,
    depth: Int,
    expanded: Boolean,
    selected: Boolean,
    open: Boolean,
    activeDepth: Int?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    actions: TreeActions,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .background(if (selected) Bl.Select else Color.Transparent)
                .drawBehind {
                    // One guide per folder level above this row, in the middle of the folder arrow.
                    val line = 1.dp.toPx()
                    for (level in 0 until depth) {
                        val x = (RowStart + RowIndent * level + 5.dp).toPx()
                        val color = if (level == activeDepth) Bl.FrameBorder.mix(Color.White, 0.25f) else Bl.Guide
                        drawRect(color, Offset(x, 0f), Size(line, size.height))
                    }
                }
                .pixelCombinedClickable(onLongClick = { onLongClick(); menu = true }, onClick = onClick)
                .padding(start = RowStart + RowIndent * depth, top = 4.dp, bottom = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp), contentAlignment = Alignment.Center) {
                if (entry.directory) {
                    GlyphIcon(if (expanded) Glyph.CHEVRON_DOWN else Glyph.CHEVRON_RIGHT, 10.dp, color = Bl.TextFaint)
                }
            }
            Spacer(Modifier.width(4.dp))
            FileTypeIcon(entry)
            Spacer(Modifier.width(6.dp))
            Text(
                entry.name, Modifier.weight(1f),
                color = if (open) Bl.PressedText else Bl.TextDim,
                fontSize = 13.5.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        val items = buildList {
            if (entry.directory) {
                add(MenuItem(stringResource(R.string.NewFile)) { actions.newFile(entry.uri, entry.path) })
                add(MenuItem(stringResource(R.string.NewFolder)) { actions.newFolder(entry.uri, entry.path) })
                add(MenuItem(stringResource(R.string.ImportFile)) { actions.import(entry.uri, entry.path) })
            }
            add(MenuItem(stringResource(R.string.Rename)) { actions.rename(entry) })
            add(MenuItem(stringResource(R.string.Delete), danger = true) { actions.delete(entry) })
        }
        PixelMenu(menu, { menu = false }, items, alignEnd = false, offsetY = 26.dp)
    }
}

/** Each type's icon: the launcher art where it exists, a colored glyph elsewhere. */
@Composable
private fun FileTypeIcon(entry: Workspace.Entry) {
    val extension = entry.name.substringAfterLast('.', "").lowercase()
    when {
        entry.directory -> PixelIcon(R.drawable.ic_folder, 16.dp)
        extension in IMAGE_EXTENSIONS -> GlyphIcon(Glyph.IMAGE, 16.dp, color = Color(0xFF74C9A0), accent = Bl.PressedText)
        else -> GlyphIcon(Glyph.FILE, 16.dp, color = when (extension) {
            "cs" -> Color(0xFFB98BFF)
            "json", "hjson" -> Bl.Gold
            "md", "txt" -> Color(0xFF7FC8FF)
            "frag", "glsl", "vert" -> Color(0xFFFF8BB0)
            else -> Bl.TextFaint
        })
    }
}
