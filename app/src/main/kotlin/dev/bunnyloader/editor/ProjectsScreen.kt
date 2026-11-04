package dev.bunnyloader.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProjectsScreen(
    workspace: Workspace,
    onOpen: (Workspace.Project) -> Unit,
    onNewMod: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notice by rememberNotice()
    var projects by remember { mutableStateOf<List<Workspace.Project>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Workspace.Project?>(null) }
    var menu by remember { mutableStateOf(false) }

    fun reload() {
        if (loading) return
        loading = true
        scope.launch {
            try {
                projects = withContext(Dispatchers.IO) { workspace.projects() }
            } catch (e: Exception) {
                notice = Notice(e.message ?: e.javaClass.simpleName)
            } finally {
                loading = false
            }
        }
    }
    OnResume { reload() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PixelHeader(stringResource(R.string.Projects), navigation = {
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
                        MenuItem(stringResource(R.string.Disconnect), danger = true, onClick = onDisconnect),
                    ))
                }
            }
            val list = projects
            if (list != null && list.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    PixelText(stringResource(R.string.NoProjects), Ts.Item, Bl.TextFaint, align = TextAlign.Center)
                }
            }
            LazyVerticalGrid(
                GridCells.Adaptive(320.dp),
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list.orEmpty(), key = { it.folder.name }) { project ->
                    ProjectCard(workspace, project, { onOpen(project) }, { deleting = project })
                }
            }
        }
        PixelButton(
            stringResource(R.string.NewMod), onNewMod,
            Modifier.align(Alignment.BottomEnd).padding(18.dp),
            fill = Bl.TitleFill, glyph = Glyph.NEW_FOLDER,
        )
        NoticeBar(notice, { notice = null }, Modifier.align(Alignment.BottomCenter))
    }

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
            PixelText(project.name, Ts.Item, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(project.folder.name, project.version.takeIf { it.isNotEmpty() }?.let { "v$it" })
            PixelText(details.joinToString(" · "), Ts.Small, Bl.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            project.error?.let { PixelText(it, Ts.Small, Bl.Bad, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        IconAction(stringResource(R.string.DeleteProject), onDelete) { PixelIcon(R.drawable.ic_trash, 24.dp) }
    }
}
