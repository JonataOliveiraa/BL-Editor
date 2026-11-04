package dev.bunnyloader.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Filter(val label: Int, val kind: Workspace.Kind?) {
    ALL(R.string.FilterAll, null),
    MODS(R.string.FilterMods, Workspace.Kind.MOD),
    TEXTURES(R.string.FilterTextures, Workspace.Kind.TEXTURE),
    FONTS(R.string.FilterFonts, Workspace.Kind.FONT),
}

/** The home screen: what can be started, then the projects, the latest worked on first. */
@Composable
fun ProjectsScreen(
    workspace: Workspace,
    onOpen: (Workspace.Project) -> Unit,
    onNew: (Workspace.Kind) -> Unit,
    onDisconnect: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notice by rememberNotice()
    var projects by remember { mutableStateOf<List<Workspace.Project>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Workspace.Project?>(null) }
    var menu by remember { mutableStateOf(false) }
    var languages by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(Filter.ALL) }

    fun reload() {
        if (loading) return
        loading = true
        scope.launch {
            try {
                projects = withContext(Dispatchers.IO) {
                    workspace.projects().sortedWith(
                        compareByDescending<Workspace.Project> { EditorPrefs.lastOpened(context, it.folder.name) }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                    )
                }
            } catch (e: Exception) {
                notice = Notice(e.message ?: e.javaClass.simpleName)
            } finally {
                loading = false
            }
        }
    }
    OnResume { reload() }

    val all = projects
    val shown = all.orEmpty().filter { project ->
        (filter.kind == null || project.kind == filter.kind) &&
            (query.isBlank() || project.name.contains(query.trim(), true) || project.folder.name.contains(query.trim(), true))
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PixelHeader(stringResource(R.string.AppName), subtitle = stringResource(R.string.AppTagline), navigation = {
                PixelIcon(R.drawable.ic_bunny_head, 34.dp, Modifier.padding(start = 8.dp))
            }) {
                if (loading) Sunflower(26.dp, Modifier.padding(end = 6.dp))
                IconAction(stringResource(R.string.Refresh), { reload() }) { PixelIcon(R.drawable.ic_refresh, 22.dp) }
                IconAction(stringResource(R.string.OpenBunnyLoader), { BunnyLoader.openLauncher(context) }) {
                    PixelIcon(R.drawable.ic_bunny_menu, 26.dp)
                }
                Box {
                    IconAction(stringResource(R.string.More), { menu = true }) { GlyphIcon(Glyph.MORE, 22.dp) }
                    PixelMenu(menu, { menu = false }, listOf(
                        MenuItem(stringResource(R.string.Language)) { languages = true },
                        MenuItem(stringResource(R.string.Disconnect), danger = true, onClick = onDisconnect),
                    ))
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val columns = if (maxWidth >= 720.dp) GridCells.Adaptive(340.dp) else GridCells.Fixed(1)
                LazyVerticalGrid(
                    columns, Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        val spread = this@BoxWithConstraints.maxWidth >= 720.dp
                        Row(
                            if (spread) Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                            else Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            val card = if (spread) Modifier.weight(1f).fillMaxHeight() else Modifier.width(196.dp)
                            StartCard(stringResource(R.string.NewMod), stringResource(R.string.NewModHint), Glyph.CODE, Color(0xFFB98BFF), card) { onNew(Workspace.Kind.MOD) }
                            StartCard(stringResource(R.string.NewTexturePack), stringResource(R.string.NewTexturePackHint), Glyph.PENCIL, Color(0xFF74C9A0), card) { onNew(Workspace.Kind.TEXTURE) }
                            StartCard(stringResource(R.string.NewFontPack), stringResource(R.string.NewFontPackHint), Glyph.FONT, Bl.Gold, card) { onNew(Workspace.Kind.FONT) }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PixelText(stringResource(R.string.Projects), Ts.Head)
                                Spacer(Modifier.width(8.dp))
                                PixelText(all?.size?.toString().orEmpty(), Ts.Body, Bl.TextMuted)
                            }
                            SearchBox(query) { query = it }
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Filter.entries.forEach { item ->
                                    ChoiceChip(stringResource(item.label), item == filter) { filter = item }
                                }
                            }
                        }
                    }
                    if (all != null && shown.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                PixelText(stringResource(if (all.isEmpty()) R.string.NoProjects else R.string.NothingFound), Ts.Item, Bl.TextFaint, align = TextAlign.Center)
                            }
                        }
                    }
                    items(shown, key = { it.folder.name }) { project ->
                        ProjectCard(workspace, project, {
                            EditorPrefs.markOpened(context, project.folder.name)
                            onOpen(project)
                        }, { deleting = project })
                    }
                }
            }
        }
        NoticeBar(notice, { notice = null }, Modifier.align(Alignment.BottomCenter))
    }

    if (languages) LanguageDialog { languages = false }
    deleting?.let { project ->
        ConfirmDialog(stringResource(R.string.DeleteProject), stringResource(R.string.DeleteConfirm, project.name),
            stringResource(R.string.Delete), { deleting = null }) {
            deleting = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { workspace.delete(project.folder.uri) }
                } catch (e: Exception) {
                    notice = Notice(e.message ?: e.javaClass.simpleName)
                }
                reload()
            }
        }
    }
}

@Composable
private fun StartCard(title: String, hint: String, glyph: Glyph, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.pixelPanel(fill = Bl.GamePanel.mix(Bl.Panel, 0.5f), corner = PixelCorner.LARGE)
            .pixelClickable(PixelCorner.LARGE, onClick = onClick).padding(12.dp),
    ) {
        Box(Modifier.framePanel().padding(8.dp)) { GlyphIcon(glyph, 26.dp, color = tint, accent = Bl.PressedText) }
        Spacer(Modifier.height(8.dp))
        PixelText(title, Ts.Item, maxLines = 1, overflow = TextOverflow.Ellipsis)
        PixelText(hint, Ts.Tiny, Bl.TextDim, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SearchBox(value: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().widthIn(max = 560.dp).pixelPanel(fill = Bl.Select, raised = false).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.SEARCH, 18.dp, color = Bl.TextFaint)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(stringResource(R.string.SearchProjects), color = Bl.TextMuted, fontSize = 14.sp)
            BasicTextField(value, onChange, Modifier.fillMaxWidth(), singleLine = true,
                textStyle = TextStyle(color = Bl.Text, fontSize = 14.sp), cursorBrush = SolidColor(Bl.PressedText))
        }
    }
}

@Composable
private fun ProjectCard(workspace: Workspace, project: Workspace.Project, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .pixelPanel(fill = Bl.Panel, corner = PixelCorner.LARGE)
            .pixelClickable(PixelCorner.LARGE, onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModPicture(workspace, project.icon, 60.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelText(project.name, Ts.Item, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(6.dp))
                KindBadge(project.kind)
            }
            val details = listOfNotNull(project.author.takeIf { it.isNotEmpty() }, project.version.takeIf { it.isNotEmpty() }?.let { "v$it" })
                .ifEmpty { listOf(project.folder.name) }
            PixelText(details.joinToString(" · "), Ts.Small, Bl.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            project.error?.let { PixelText(it, Ts.Small, Bl.Bad, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        IconAction(stringResource(R.string.DeleteProject), onDelete) { PixelIcon(R.drawable.ic_trash, 24.dp) }
    }
}

@Composable
private fun KindBadge(kind: Workspace.Kind) {
    val (label, tint) = when (kind) {
        Workspace.Kind.MOD -> "C#" to Color(0xFF5B3FA8)
        Workspace.Kind.TEXTURE -> stringResource(R.string.BadgeTexture) to Color(0xFF2F7D5B)
        Workspace.Kind.FONT -> stringResource(R.string.BadgeFont) to Color(0xFF9A6F16)
    }
    Box(Modifier.background(tint).padding(horizontal = 6.dp, vertical = 1.dp)) {
        PixelText(label, Ts.Tiny, maxLines = 1)
    }
}
