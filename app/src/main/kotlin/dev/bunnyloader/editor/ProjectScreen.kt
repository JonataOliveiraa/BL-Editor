package dev.bunnyloader.editor

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.bunnyloader.editor.csharp.ProjectIndex
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface ProjectDialog {
    data class NewFile(val parent: Uri, val parentPath: String) : ProjectDialog
    data class NewFolder(val parent: Uri, val parentPath: String) : ProjectDialog
    data class Rename(val entry: Workspace.Entry) : ProjectDialog
    data class Delete(val entry: Workspace.Entry) : ProjectDialog
    class Unsaved(val onSave: () -> Unit, val onDiscard: () -> Unit) : ProjectDialog
    data class Error(val title: String, val message: String) : ProjectDialog
}

/** How the copy installed in Bunny Loader stands against what was saved. */
private sealed interface Sync {
    data object Idle : Sync
    data object Running : Sync
    data object Done : Sync
    data class Failed(val message: String) : Sync
}

/** From this width on, the tree stays fixed on the side, like in VS Code. */
private val SidebarBreakpoint = 840.dp
private const val AUTOSAVE_DELAY_MS = 1500L

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun ProjectScreen(workspace: Workspace, project: Workspace.Project, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notice by rememberNotice()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val tree = remember { FileTree(workspace, project.folder.uri) }
    val files = remember { mutableStateListOf<OpenFile>() }
    var current by remember { mutableStateOf<OpenFile?>(null) }
    var editor by remember { mutableStateOf<CodeEditor?>(null) }
    var dialog by remember { mutableStateOf<ProjectDialog?>(null) }
    var selectedPath by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var sidebarOpen by remember { mutableStateOf(true) }
    var autosave by remember { mutableStateOf(EditorPrefs.autosave(context)) }
    var sync by remember { mutableStateOf<Sync>(Sync.Idle) }
    var importTarget by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var wide by remember { mutableStateOf(false) }
    var painting by remember { mutableStateOf<OpenFile?>(null) }
    var wordWrap by remember { mutableStateOf(EditorPrefs.wordWrap(context)) }
    val isMod = project.kind == Workspace.Kind.MOD
    val symbols = remember { ProjectIndex() }
    val modId = project.id.ifEmpty { project.folder.name }

    val notText = stringResource(R.string.NotText)
    val tooLarge = stringResource(R.string.TooLarge)
    val noGame = stringResource(R.string.TestNoGame)
    val testFailed = stringResource(R.string.TestFailed)
    val syncFailed = stringResource(R.string.SyncFailed)
    val emptyKept = stringResource(R.string.EmptyNotAutosaved)

    suspend fun report(e: Exception) {
        if (e is CancellationException) throw e
        notice = Notice(e.message ?: e.javaClass.simpleName)
    }

    // ------------------------- install in Bunny Loader --------------------------
    // Each save asks for an install; requests arriving during one become a single one at the end.
    var installJob by remember { mutableStateOf<Job?>(null) }
    var installPending by remember { mutableStateOf(false) }
    fun requestInstall() {
        // Bunny Loader's bridge installs mods; packs have their own Install and Export on their screen.
        if (!isMod) return
        installPending = true
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            while (installPending) {
                installPending = false
                sync = Sync.Running
                val error = withContext(Dispatchers.IO) { BunnyLoader.install(context, project.folder.name) }
                sync = if (error == null) Sync.Done else Sync.Failed(error)
            }
        }
    }

    /** A file operation; the tree is reread at the end and the mod reinstalled, whether it worked or not. */
    fun operation(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                requestInstall()
            } catch (e: Exception) {
                report(e)
            }
            runCatching { tree.reload() }
        }
    }

    fun closeDrawer() {
        if (!wide) scope.launch { drawer.close() }
    }

    fun open(entry: Workspace.Entry) {
        files.firstOrNull { it.path == entry.path }?.let {
            current = it
            closeDrawer()
            return
        }
        if (isImageFile(entry.name)) {
            val file = OpenFile(entry.path, entry.uri, Content(), image = true)
            files.add(file)
            current = file
            closeDrawer()
            return
        }
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { workspace.readText(entry.uri) }
                val file = OpenFile(entry.path, entry.uri, Content(text))
                files.add(file)
                current = file
                closeDrawer()
            } catch (_: Workspace.Binary) {
                notice = Notice(notText.format(entry.name))
            } catch (_: Workspace.TooLarge) {
                notice = Notice(tooLarge.format(entry.name))
            } catch (e: Exception) {
                report(e)
            }
        }
    }

    /**
     * Writes the tab. Autosave ([manual] false) refuses to replace a file that has
     * text with an empty one: only the Save button does that.
     */
    suspend fun save(file: OpenFile, manual: Boolean = true): Boolean {
        if (file.image) return true
        val revision = file.revision
        val text = file.content.toString()
        if (!manual && text.isEmpty() && file.savedLength > 0) {
            notice = Notice(emptyKept.format(file.name))
            return false
        }
        return try {
            withContext(Dispatchers.IO) { workspace.writeText(file.uri, text) }
            Log.i(MainActivity.TAG, "Saved ${file.path}: ${file.savedLength} -> ${text.length} chars" +
                if (manual) "" else " (autosave)")
            file.savedLength = text.length
            if (file.name.endsWith(".cs", true)) withContext(Dispatchers.Default) { symbols.update(file.path, text) }
            if (file.revision == revision) file.dirty = false
            true
        } catch (e: Exception) {
            report(e)
            false
        }
    }

    /** Saves what changed and, if anything was saved, updates the mod in Bunny Loader. */
    suspend fun saveAll(install: Boolean = true, manual: Boolean = true): Boolean {
        val dirty = files.filter { it.dirty }
        val ok = dirty.all { save(it, manual) }
        if (ok && install && dirty.isNotEmpty()) requestInstall()
        return ok
    }

    val projectExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { target ->
        if (target != null) scope.launch {
            try {
                if (!saveAll(install = false)) return@launch
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(target, "wt")?.use {
                        workspace.exportProject(project.folder.uri, it)
                    } ?: error(context.getString(R.string.ErrWriteFile))
                }
                notice = Notice(context.getString(R.string.DesktopProjectExported), error = false)
            } catch (e: Exception) {
                report(e)
            }
        }
    }

    fun closeTab(file: OpenFile) {
        val index = files.indexOf(file)
        files.remove(file)
        if (current === file) current = files.getOrNull(index.coerceAtMost(files.lastIndex))
    }

    fun requestCloseTab(file: OpenFile) {
        if (!file.dirty) return closeTab(file)
        if (autosave) {
            scope.launch { if (save(file)) { requestInstall(); closeTab(file) } }
            return
        }
        dialog = ProjectDialog.Unsaved(
            onSave = { scope.launch { if (save(file)) { requestInstall(); closeTab(file) } } },
            onDiscard = { closeTab(file) },
        )
    }

    fun leave() {
        if (files.none { it.dirty }) return onClose()
        if (autosave) {
            scope.launch { if (saveAll()) onClose() }
            return
        }
        dialog = ProjectDialog.Unsaved(onSave = { scope.launch { if (saveAll()) onClose() } }, onDiscard = onClose)
    }

    val tester = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        testing = false
        val failure = BunnyLoader.testFailure(result.resultCode, result.data)
        when {
            failure == null -> sync = Sync.Done
            failure.code == "cancelled" -> Unit
            failure.code == "noGame" -> {
                sync = Sync.Done
                dialog = ProjectDialog.Error(testFailed, noGame)
            }
            else -> {
                sync = Sync.Failed(failure.message)
                dialog = ProjectDialog.Error(testFailed, failure.message)
            }
        }
    }

    /** The bunny: saves everything, and Bunny Loader packages, installs and opens the game. */
    fun test() {
        if (testing) return
        scope.launch {
            if (!saveAll(install = false)) return@launch
            testing = true
            sync = Sync.Running
            try {
                tester.launch(BunnyLoader.testIntent(project.folder.name))
            } catch (e: Exception) {
                testing = false
                sync = Sync.Failed(e.message ?: e.javaClass.simpleName)
                dialog = ProjectDialog.Error(testFailed, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val (target, path) = importTarget ?: return@rememberLauncherForActivityResult
        importTarget = null
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        operation {
            withContext(Dispatchers.IO) { uris.forEach { workspace.importFile(target, it) } }
            if (path.isNotEmpty() && path !in tree.expanded) tree.expanded.add(path)
        }
    }

    val actions = remember {
        object : TreeActions {
            override fun open(entry: Workspace.Entry) = open(entry)
            override fun toggle(entry: Workspace.Entry) {
                if (entry.path in tree.expanded) tree.collapse(entry.path)
                else scope.launch { runCatching { tree.expand(entry) }.onFailure { notice = Notice(it.message ?: "") } }
            }
            override fun newFile(parent: Uri, parentPath: String) { dialog = ProjectDialog.NewFile(parent, parentPath) }
            override fun newFolder(parent: Uri, parentPath: String) { dialog = ProjectDialog.NewFolder(parent, parentPath) }
            override fun import(parent: Uri, parentPath: String) {
                importTarget = parent to parentPath
                importer.launch(arrayOf("*/*"))
            }
            override fun rename(entry: Workspace.Entry) { dialog = ProjectDialog.Rename(entry) }
            override fun delete(entry: Workspace.Entry) { dialog = ProjectDialog.Delete(entry) }
            override fun refresh() { scope.launch { runCatching { tree.reload() } } }
        }
    }

    // Everything the project declares, for completion across files.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching {
                workspace.allFiles(project.folder.uri).filter { it.name.endsWith(".cs", true) }.take(MAX_INDEXED_FILES).forEach { entry ->
                    symbols.update(entry.path, workspace.readText(entry.uri))
                }
            }
        }
    }

    // Opens the tree with Content/ open and the mod's main class already in a tab.
    LaunchedEffect(Unit) {
        try {
            tree.reload()
            tree.children[""]?.firstOrNull { it.directory && it.name == "Content" }?.let { tree.expand(it) }
            val entry = if (!isMod) null else withContext(Dispatchers.IO) { workspace.resolve(project.folder.uri, "$modId.cs") }
            if (entry != null) {
                selectedPath = entry.path
                open(entry)
            } else if (!wide) drawer.open()
        } catch (e: Exception) {
            report(e)
        }
    }

    // Autosave: some time after the last keystroke.
    LaunchedEffect(autosave) {
        if (!autosave) return@LaunchedEffect
        snapshotFlow { files.sumOf { it.revision } }.collectLatest {
            delay(AUTOSAVE_DELAY_MS)
            if (files.any { it.dirty }) saveAll(manual = false)
        }
    }

    // Leaving the app with autosave on, nothing stays behind.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && autosave && files.any { it.dirty }) scope.launch { saveAll(manual = false) }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    BackHandler {
        when {
            painting != null -> painting = null
            searching -> searching = false
            !wide && drawer.isOpen && current != null -> scope.launch { drawer.close() }
            else -> leave()
        }
    }

    val panel: @Composable (Modifier) -> Unit = { modifier ->
        FilesPanel(project.name, tree, selectedPath, current?.path, { selectedPath = it }, actions, modifier)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        wide = maxWidth >= SidebarBreakpoint
        val main: @Composable () -> Unit = {
            Column(Modifier.fillMaxSize()) {
                ProjectHeader(
                    title = project.name,
                    subtitle = current?.path,
                    onMenu = { if (wide) sidebarOpen = !sidebarOpen else scope.launch { drawer.open() } },
                    sync = sync,
                    onSyncClick = { (sync as? Sync.Failed)?.let { dialog = ProjectDialog.Error(syncFailed, it.message) } },
                    canSave = current?.dirty == true,
                    onSave = { scope.launch { saveAll() } },
                    autosave = autosave,
                    onAutosave = {
                        autosave = it
                        EditorPrefs.setAutosave(context, it)
                    },
                    testing = testing,
                    onTest = if (isMod) ::test else null,
                    menuOpen = menu,
                    onMenu2 = { menu = it },
                    menuItems = listOf(
                        MenuItem(stringResource(R.string.Find), enabled = editor != null) { searching = true },
                        MenuItem(stringResource(R.string.FormatDocument), enabled = current?.name?.endsWith(".cs", true) == true && editor != null) {
                            (editor as? BlCodeEditor)?.formatDocument()
                        },
                        MenuItem(stringResource(R.string.ToggleComment), enabled = current?.name?.endsWith(".cs", true) == true && editor != null) {
                            (editor as? BlCodeEditor)?.toggleLineComment()
                        },
                        MenuItem(stringResource(R.string.DuplicateLine), enabled = editor != null) { editor?.duplicateLine() },
                        MenuItem(stringResource(if (wordWrap) R.string.WordWrapOff else R.string.WordWrapOn)) {
                            wordWrap = !wordWrap
                            EditorPrefs.setWordWrap(context, wordWrap)
                            editor?.setWordwrap(wordWrap)
                        },
                        MenuItem(stringResource(R.string.SaveAll), enabled = files.any { it.dirty }) {
                            scope.launch { saveAll() }
                        },
                        MenuItem(stringResource(R.string.CloseTab), enabled = current != null) {
                            current?.let(::requestCloseTab)
                        },
                        MenuItem(stringResource(R.string.ExportDesktopProject), enabled = isMod) {
                            projectExporter.launch("${modId}-sources.zip")
                        },
                        MenuItem(stringResource(R.string.OpenBunnyLoader)) { BunnyLoader.openLauncher(context) },
                        MenuItem(stringResource(R.string.Projects)) { leave() },
                    ),
                )
                if (files.isNotEmpty()) TabStrip(files, current, { current = it }, ::requestCloseTab)
                val code = editor
                if (searching && code != null && current != null) SearchBar(code) { searching = false }
                val file = current
                if (file != null && file.image) {
                    ImageViewer(workspace, file, Modifier.weight(1f).fillMaxWidth()) { painting = file }
                } else if (file != null) {
                    CodePane(file, symbols, Modifier.weight(1f).fillMaxWidth()) { editor = it }
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth().background(Bl.Code), contentAlignment = Alignment.Center) {
                        PixelText(stringResource(R.string.NoFileOpen), Ts.Item, Bl.TextMuted)
                    }
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                if (sidebarOpen) {
                    panel(Modifier.width(280.dp).fillMaxHeight())
                    Box(Modifier.width(2.dp).fillMaxHeight().background(Bl.Outline))
                }
                Box(Modifier.weight(1f)) { main() }
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawer,
                gesturesEnabled = drawer.isOpen,
                drawerContent = {
                    Box(Modifier.width(300.dp).fillMaxHeight().pixelPanel(fill = Bl.Panel, corner = PixelCorner.NONE)
                        .padding(2.dp)) { panel(Modifier) }
                },
                scrimColor = Bl.Night.copy(alpha = 0.6f),
            ) { main() }
        }
        NoticeBar(notice, { notice = null }, Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp))
    }

    if (testing) {
        Box(
            Modifier.fillMaxSize().background(Bl.Night.copy(alpha = 0.7f))
                .clickable(remember { MutableInteractionSource() }, null) {},
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.pixelPanel(fill = Bl.Panel, corner = PixelCorner.LARGE).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Sunflower(52.dp)
                Spacer(Modifier.height(14.dp))
                PixelText(stringResource(R.string.Testing), Ts.Item)
            }
        }
    }

    painting?.let { file ->
        PixelEditorScreen(
            file.name,
            load = { withContext(Dispatchers.IO) { workspace.readBytes(file.uri) } },
            onSave = { bytes ->
                withContext(Dispatchers.IO) { workspace.writeBytes(file.uri, bytes) }
                file.revision++
                requestInstall()
            },
            onClose = { painting = null },
        )
    }

    when (val shown = dialog) {
        null -> Unit
        is ProjectDialog.NewFile -> NewFileDialog(isMod, { dialog = null }) { name, template ->
            dialog = null
            operation {
                val uri = withContext(Dispatchers.IO) {
                    val created = workspace.createFile(shown.parent, name)
                    val stem = Templates.identifier(name.substringBeforeLast('.'))
                    val namespace = Templates.namespaceFor(modId, shown.parentPath)
                    if (template != null) {
                        val output = template.build(namespace, stem, Templates.words(stem))
                        workspace.writeText(created, output.code)
                        symbols.update(if (shown.parentPath.isEmpty()) name else "${shown.parentPath}/$name", output.code)
                        for (art in output.art) {
                            val png = PlaceholderArt.png(art.width, art.height, art.argb)
                            val picture = workspace.createFile(shown.parent, "$stem${art.suffix}.png")
                            workspace.writeBytes(picture, png)
                        }
                        if (output.entries.isNotEmpty()) {
                            val file = workspace.fileAt(project.folder.uri, "Localization/en-US_Mods.$modId.hjson")
                            workspace.writeText(file, Templates.addLocalization(workspace.readText(file), output.entries))
                        }
                    } else if (name.endsWith(".cs", true) && isMod) {
                        workspace.writeText(created, Templates.defaultClass(namespace, stem))
                    }
                    created
                }
                if (shown.parentPath.isNotEmpty() && shown.parentPath !in tree.expanded) tree.expanded.add(shown.parentPath)
                val path = if (shown.parentPath.isEmpty()) name else "${shown.parentPath}/$name"
                selectedPath = path
                open(Workspace.Entry(uri, name, false, 0, path))
            }
        }
        is ProjectDialog.NewFolder -> NameDialog(stringResource(R.string.NewFolder), "", stringResource(R.string.Create),
            { dialog = null }) { name ->
            dialog = null
            operation {
                withContext(Dispatchers.IO) { workspace.createFolder(shown.parent, name) }
                if (shown.parentPath.isNotEmpty() && shown.parentPath !in tree.expanded) tree.expanded.add(shown.parentPath)
                val path = if (shown.parentPath.isEmpty()) name else "${shown.parentPath}/$name"
                tree.expanded.add(path)
                selectedPath = path
            }
        }
        is ProjectDialog.Rename -> NameDialog(stringResource(R.string.Rename), shown.entry.name,
            stringResource(R.string.Rename), { dialog = null }) { name ->
            dialog = null
            val entry = shown.entry
            operation {
                val renamed = withContext(Dispatchers.IO) { workspace.rename(entry.uri, name) }
                val parent = entry.path.substringBeforeLast('/', "")
                val target = if (parent.isEmpty()) name else "$parent/$name"
                tree.moved(entry.path, target)
                if (selectedPath == entry.path) selectedPath = target
                for (file in files.filter { it.path == entry.path || it.path.startsWith("${entry.path}/") }) {
                    file.path = target + file.path.removePrefix(entry.path)
                    file.uri = if (!entry.directory) renamed
                    else withContext(Dispatchers.IO) { workspace.resolve(project.folder.uri, file.path)?.uri } ?: file.uri
                }
            }
        }
        is ProjectDialog.Delete -> ConfirmDialog(stringResource(R.string.Delete),
            stringResource(R.string.DeleteConfirm, shown.entry.path), stringResource(R.string.Delete),
            { dialog = null }) {
            dialog = null
            val entry = shown.entry
            operation {
                withContext(Dispatchers.IO) { workspace.delete(entry.uri) }
                files.filter { it.path == entry.path || it.path.startsWith("${entry.path}/") }.forEach(::closeTab)
                if (entry.directory) tree.collapse(entry.path)
                if (selectedPath == entry.path) selectedPath = null
            }
        }
        is ProjectDialog.Unsaved -> UnsavedDialog({ dialog = null },
            onDiscard = { dialog = null; shown.onDiscard() }, onSave = { dialog = null; shown.onSave() })
        is ProjectDialog.Error -> ErrorDialog(shown.title, shown.message) { dialog = null }
    }
}

private const val MAX_INDEXED_FILES = 600

@Composable
private fun ProjectHeader(
    title: String,
    subtitle: String?,
    onMenu: () -> Unit,
    sync: Sync,
    onSyncClick: () -> Unit,
    canSave: Boolean,
    onSave: () -> Unit,
    autosave: Boolean,
    onAutosave: (Boolean) -> Unit,
    testing: Boolean,
    onTest: (() -> Unit)?,
    menuOpen: Boolean,
    onMenu2: (Boolean) -> Unit,
    menuItems: List<MenuItem>,
) {
    PixelHeader(title, subtitle = subtitle, navigation = {
        IconAction(stringResource(R.string.Files), onMenu) { GlyphIcon(Glyph.MENU, 22.dp) }
    }) {
        // The state of the copy in Bunny Loader: sunflower installing, check installed, ! failed.
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            when (sync) {
                Sync.Idle -> Unit
                Sync.Running -> Sunflower(22.dp)
                Sync.Done -> GlyphIcon(Glyph.CHECK, 18.dp, color = Bl.GoodText)
                is Sync.Failed -> IconAction(stringResource(R.string.SyncFailed), onSyncClick) {
                    GlyphIcon(Glyph.WARNING, 18.dp, color = Bl.Bad)
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PixelSwitch(autosave, onAutosave, stringResource(R.string.Autosave))
        }
        IconAction(stringResource(R.string.Save), onSave, enabled = canSave) {
            GlyphIcon(Glyph.SAVE, 22.dp, color = if (canSave) Bl.Text else Bl.TextMuted,
                accent = if (canSave) Bl.PressedText else Bl.TextMuted)
        }
        // The bunny: Test, as a highlighted button, like the launcher's Play.
        if (onTest != null) Box(
            Modifier.padding(horizontal = 4.dp)
                .pixelPanel(fill = if (testing) Bl.ButtonPressed else Bl.TitleFill)
                .pixelClickable(enabled = !testing, onClick = onTest)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelIcon(R.drawable.ic_bunny_menu, 26.dp)
                PixelText(stringResource(R.string.Test), Ts.Body, modifier = Modifier.padding(start = 4.dp))
            }
        }
        Box {
            IconAction(stringResource(R.string.More), { onMenu2(true) }) { GlyphIcon(Glyph.MORE, 22.dp) }
            PixelMenu(menuOpen, { onMenu2(false) }, menuItems)
        }
    }
}

/** The tabs of the open files, attached to the code: the current one has the editor background and the yellow strip. */
@Composable
private fun TabStrip(files: List<OpenFile>, current: OpenFile?, onSelect: (OpenFile) -> Unit, onClose: (OpenFile) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Bl.Panel.mix(Bl.Night, 0.35f)).horizontalScroll(rememberScrollState()),
    ) {
        files.forEach { file ->
            val selected = file === current
            Row(
                Modifier
                    .background(if (selected) Bl.Code else Color.Transparent)
                    .drawBehind {
                        if (selected) drawRect(Bl.PressedText, Offset.Zero, Size(size.width, 2.dp.toPx()))
                        drawRect(Bl.Outline, Offset(size.width - 1.dp.toPx(), 0f), Size(1.dp.toPx(), size.height))
                    }
                    .pixelClickable(PixelCorner.NONE) { onSelect(file) }
                    .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    file.name, color = if (selected) Bl.Text else Bl.TextFaint, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    Modifier.padding(start = 6.dp).size(18.dp).pixelClickable(PixelCorner.NONE) { onClose(file) },
                    contentAlignment = Alignment.Center,
                ) {
                    // The "unsaved" dot takes the place of the X, like in VS Code.
                    if (file.dirty) Box(Modifier.size(8.dp).background(Bl.PressedText))
                    else GlyphIcon(Glyph.CLOSE, 12.dp, color = Bl.TextMuted)
                }
            }
        }
    }
}

@Composable
private fun SearchBar(editor: CodeEditor, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    fun search(text: String) {
        runCatching {
            if (text.isEmpty()) editor.searcher.stopSearch()
            else editor.searcher.search(text, EditorSearcher.SearchOptions(EditorSearcher.SearchOptions.TYPE_NORMAL, true))
        }
    }
    DisposableEffect(editor) { onDispose { runCatching { editor.searcher.stopSearch() } } }
    Column(
        Modifier.fillMaxWidth().pixelPanel(fill = Bl.Panel, corner = PixelCorner.NONE).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SearchField(query, { query = it; search(it) }, stringResource(R.string.Find), Modifier.weight(1f))
            IconAction(stringResource(R.string.Previous), { runCatching { editor.searcher.gotoPrevious() } }) {
                GlyphIcon(Glyph.CHEVRON_DOWN, 14.dp, Modifier.rotate(180f))
            }
            IconAction(stringResource(R.string.Next), { runCatching { editor.searcher.gotoNext() } }) {
                GlyphIcon(Glyph.CHEVRON_DOWN, 14.dp)
            }
            IconAction(stringResource(R.string.Close), onClose) { GlyphIcon(Glyph.CLOSE, 16.dp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SearchField(replacement, { replacement = it }, stringResource(R.string.ReplaceWith), Modifier.weight(1f))
            PixelButton(stringResource(R.string.Replace), {
                runCatching {
                    val searcher = editor.searcher
                    if (!searcher.hasQuery()) return@runCatching
                    if (searcher.isMatchedPositionSelected) searcher.replaceCurrentMatch(replacement) else searcher.gotoNext()
                }
            }, fontSize = Ts.Small)
            PixelButton(stringResource(R.string.ReplaceAll), {
                runCatching { if (editor.searcher.hasQuery()) editor.searcher.replaceAll(replacement) }
            }, fontSize = Ts.Small)
        }
    }
}

/** The search field: the code font, because what is searched for is code. */
@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier) {
    Box(modifier.pixelPanel(fill = Bl.Select, raised = false).padding(horizontal = 10.dp, vertical = 8.dp)) {
        if (value.isEmpty()) Text(placeholder, color = Bl.TextMuted, fontSize = 14.sp)
        androidx.compose.foundation.text.BasicTextField(
            value, onChange, Modifier.fillMaxWidth(), singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Bl.Text, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Bl.PressedText),
        )
    }
}
