package dev.bunnyloader.editor

import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bunnyloader.editor.csharp.ApiMember
import dev.bunnyloader.editor.csharp.CSharpApi
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Installing and exporting a pack, shared by both pack screens. */
private class PackActions(
    private val context: android.content.Context,
    private val workspace: Workspace,
    private val project: Workspace.Project,
    private val scope: CoroutineScope,
    private val onNotice: (Notice) -> Unit,
    private val onInstallFailed: (String) -> Unit,
) {
    fun install() {
        scope.launch {
            val error = withContext(Dispatchers.IO) { BunnyLoader.install(context, project.folder.name) }
            if (error == null) onNotice(Notice(context.getString(R.string.PackInstalled), error = false))
            else onInstallFailed(error)
        }
    }

    fun export(target: Uri) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(target, "wt")?.use { workspace.exportPackage(project.folder.uri, it) }
                        ?: error("Cannot write the package")
                }
                onNotice(Notice(context.getString(R.string.PackExported), error = false))
            } catch (e: Exception) {
                onNotice(Notice(e.message ?: e.javaClass.simpleName))
            }
        }
    }
}

@Composable
private fun PackHeader(project: Workspace.Project, onBack: () -> Unit, onFiles: () -> Unit, onInstall: () -> Unit, onExport: () -> Unit, busy: Boolean) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    PixelHeader(project.name, subtitle = stringResource(if (project.kind == Workspace.Kind.FONT) R.string.KindFontPack else R.string.KindTexturePack), navigation = {
        IconAction(stringResource(R.string.Back), onBack) { GlyphIcon(Glyph.BACK, 22.dp) }
    }) {
        if (busy) Sunflower(24.dp, Modifier.padding(end = 4.dp))
        IconAction(stringResource(R.string.Files), onFiles) { PixelIcon(R.drawable.ic_folder, 22.dp) }
        Box(
            Modifier.padding(horizontal = 4.dp).pixelPanel(fill = Bl.TitleFill).pixelClickable(onClick = onInstall)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) { PixelText(stringResource(R.string.Install), Ts.Body) }
        Box {
            IconAction(stringResource(R.string.More), { menu = true }) { GlyphIcon(Glyph.MORE, 22.dp) }
            PixelMenu(menu, { menu = false }, listOf(
                MenuItem(stringResource(R.string.ExportPackage), onClick = onExport),
                MenuItem(stringResource(R.string.OpenBunnyLoader)) { BunnyLoader.openLauncher(context) },
            ))
        }
    }
}

@Composable
private fun InstallFailedDialog(message: String, onDismiss: () -> Unit, onExport: () -> Unit) {
    PixelDialog(stringResource(R.string.InstallFailed), onDismiss, buttons = {
        PixelButton(stringResource(R.string.Close), onDismiss, fontSize = Ts.Body)
        PixelButton(stringResource(R.string.ExportPackage), onExport, fill = Bl.TitleFill, fontSize = Ts.Body)
    }) {
        PixelText(stringResource(R.string.InstallFailedHint), Ts.Body, Bl.TextDim)
        Spacer(Modifier.height(10.dp))
        Text(message, color = Bl.TextFaint, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

// ------------------------------ texture packs ------------------------------

/** A sprite being edited: where it goes, and how it starts. */
private class EditingTexture(val path: String, val existing: Uri?, val width: Int, val height: Int, val imported: Uri? = null)

/** The textures of a pack, each named after the sprite of the game it replaces. */
@Composable
fun TexturePackScreen(workspace: Workspace, project: Workspace.Project, onBack: () -> Unit, onFiles: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notice by rememberNotice()
    var images by remember { mutableStateOf<List<Workspace.Entry>?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EditingTexture?>(null) }
    var deleting by remember { mutableStateOf<Workspace.Entry?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        try {
            images = withContext(Dispatchers.IO) {
                val folder = workspace.resolve(project.folder.uri, "Images") ?: return@withContext emptyList()
                workspace.allFiles(folder.uri).filter { it.name.endsWith(".png", true) }.sortedBy { it.path.lowercase() }
            }
            generation++
        } catch (e: Exception) {
            notice = Notice(e.message ?: e.javaClass.simpleName)
        }
    }
    LaunchedEffect(Unit) { reload() }

    val actions = remember {
        PackActions(context, workspace, project, scope, { notice = it }, { failed = it })
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) actions.export(uri)
    }
    fun export() {
        failed = null
        if (images.isNullOrEmpty()) notice = Notice(context.getString(R.string.PackEmptyImages))
        else exporter.launch("${project.folder.name}.bl")
    }

    editing?.let { texture ->
        PixelEditorScreen(
            texture.path.substringAfterLast('/'),
            load = when {
                texture.imported != null -> ({
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(texture.imported)?.use { it.readBytes() } ?: error("Cannot open the file")
                    }
                })
                texture.existing != null -> ({ withContext(Dispatchers.IO) { workspace.readBytes(texture.existing) } })
                else -> null
            },
            blankWidth = texture.width,
            blankHeight = texture.height,
            onSave = { bytes ->
                withContext(Dispatchers.IO) {
                    workspace.writeBytes(workspace.fileAt(project.folder.uri, "Images/${texture.path}"), bytes)
                }
                reload()
            },
            onClose = { editing = null },
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PackHeader(project, onBack, onFiles, actions::install, ::export, images == null)
            val list = images
            if (list != null && list.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        GlyphIcon(Glyph.IMAGE, 64.dp, color = Bl.TextMuted)
                        Spacer(Modifier.height(12.dp))
                        PixelText(stringResource(R.string.NoTextures), Ts.Item, Bl.TextFaint)
                    }
                }
            }
            LazyVerticalGrid(
                GridCells.Adaptive(112.dp), Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list.orEmpty(), key = { it.path }) { image ->
                    TextureCell(workspace, image, generation, {
                        editing = EditingTexture(image.path, image.uri, 0, 0)
                    }, { deleting = image })
                }
            }
        }
        PixelButton(
            stringResource(R.string.AddTexture), { adding = true },
            Modifier.align(Alignment.BottomEnd).padding(18.dp), fill = Bl.TitleFill, glyph = Glyph.PLUS,
        )
        NoticeBar(notice, { notice = null }, Modifier.align(Alignment.BottomCenter))
    }

    if (adding) {
        AddTextureDialog(
            existing = images.orEmpty(),
            onDismiss = { adding = false },
        ) {
            adding = false
            editing = it
        }
    }
    deleting?.let { image ->
        ConfirmDialog(stringResource(R.string.Delete), stringResource(R.string.DeleteConfirm, image.path), stringResource(R.string.Delete), { deleting = null }) {
            deleting = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { workspace.delete(image.uri) }
                } catch (e: Exception) {
                    notice = Notice(e.message ?: e.javaClass.simpleName)
                }
                reload()
            }
        }
    }
    failed?.let { message -> InstallFailedDialog(message, { failed = null }, ::export) }
}

@Composable
private fun TextureCell(workspace: Workspace, image: Workspace.Entry, generation: Int, onOpen: () -> Unit, onDelete: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, image.uri, generation) {
        value = withContext(Dispatchers.IO) { workspace.readBitmap(image.uri, 128) }
    }
    Column(
        Modifier.fillMaxWidth().pixelPanel(fill = Bl.Panel).pixelCombinedClickable(onLongClick = onDelete, onClick = onOpen).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).framePanel().padding(4.dp), contentAlignment = Alignment.Center) {
            bitmap?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
            }
        }
        Text(image.path.removeSuffix(".png"), Modifier.padding(top = 4.dp), color = Bl.TextDim, fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AddTextureDialog(existing: List<Workspace.Entry>, onDismiss: () -> Unit, onStart: (EditingTexture) -> Unit) {
    val context = LocalContext.current
    var category by remember { mutableStateOf(TexturePaths.CATEGORIES[0]) }
    var query by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf("") }
    var width by remember { mutableStateOf(category.defaultWidth.toString()) }
    var height by remember { mutableStateOf(category.defaultHeight.toString()) }
    val constants by produceState<List<ApiMember>>(emptyList(), category) {
        value = emptyList()
        val type = category.idClass ?: return@produceState
        value = withContext(Dispatchers.Default) {
            CSharpApi.tree(context)?.type(type)?.members.orEmpty()
                .filter { it.kind == ApiMember.CONSTANT && (it.value?.toIntOrNull() ?: -1) >= 0 }
                .sortedBy { it.name.lowercase() }
        }
    }
    val path = if (category.id == "custom") TexturePaths.clean(custom)
        else number.takeIf { it.isNotEmpty() }?.let { "${category.prefix}$it.png" }
    val w = width.toIntOrNull()
    val h = height.toIntOrNull()
    val sizeOk = w != null && h != null && w in 1..PixelCanvas.MAX_SIDE && h in 1..PixelCanvas.MAX_SIDE
    val already = existing.firstOrNull { it.path.equals(path, true) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && path != null) onStart(EditingTexture(path, already?.uri, 0, 0, imported = uri))
    }
    PixelDialog(stringResource(R.string.AddTexture), onDismiss, buttons = {
        PixelButton(stringResource(R.string.Cancel), onDismiss, fontSize = Ts.Body)
        PixelButton(stringResource(R.string.ImportImage), { importer.launch(arrayOf("image/*")) }, fontSize = Ts.Body, enabled = path != null)
        PixelButton(stringResource(if (already != null) R.string.EditImage else R.string.DrawTexture), {
            onStart(EditingTexture(path!!, already?.uri, w ?: 32, h ?: 32))
        }, fill = Bl.TitleFill, fontSize = Ts.Body, enabled = path != null && (already != null || sizeOk))
    }) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TexturePaths.CATEGORIES.forEach { item ->
                    ChoiceChip(stringResource(item.title), item === category) {
                        category = item
                        number = ""
                        query = ""
                        width = item.defaultWidth.toString()
                        height = item.defaultHeight.toString()
                    }
                }
            }
            if (category.id == "custom") {
                PixelTextField(custom, { custom = it }, stringResource(R.string.FieldTexturePath), monospace = true)
                PixelText(stringResource(R.string.TexturePathHint), Ts.Tiny, Bl.TextMuted)
            } else {
                PixelTextField(number, { number = it.filter(Char::isDigit).take(6) }, stringResource(R.string.FieldTextureId), monospace = true)
                if (category.idClass != null) {
                    PixelTextField(query, { query = it }, stringResource(R.string.SearchByName), monospace = true)
                    val found = remember(constants, query) {
                        if (query.isBlank()) emptyList()
                        else constants.filter { it.name.contains(query.trim(), ignoreCase = true) }.take(40)
                    }
                    if (found.isNotEmpty()) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 150.dp).framePanel().padding(2.dp)) {
                            items(found, key = { it.name }) { constant ->
                                Row(
                                    Modifier.fillMaxWidth().pixelClickable(PixelCorner.NONE) {
                                        number = constant.value.orEmpty()
                                        query = ""
                                    }.padding(horizontal = 8.dp, vertical = 6.dp),
                                ) {
                                    Text(constant.name, Modifier.weight(1f), color = Bl.TextDim, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                                    Text(constant.value.orEmpty(), color = Bl.TextMuted, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
            if (path != null) Text("Images/$path", color = if (already != null) Bl.Gold else Bl.TextFaint, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            if (already == null) SizeFields(width, { width = it }, height, { height = it })
            PixelText(stringResource(R.string.TextureSizeHint), Ts.Tiny, Bl.TextMuted)
        }
    }
}

@Composable
fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.pixelPanel(fill = if (selected) Bl.TitleFill else Bl.Button, raised = !selected,
            outline = if (selected) Bl.PressedBorder else Bl.Outline)
            .pixelClickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        PixelText(label, Ts.Small, if (selected) Bl.Text else Bl.TextDim, maxLines = 1)
    }
}

// -------------------------------- font packs --------------------------------

/**
 * Turns a .ttf/.otf into the game's five fonts: what tools/mods/fonts.py does on the PC, with the
 * same measurements, so the interface stays in place.
 */
@Composable
fun FontPackScreen(workspace: Workspace, project: Workspace.Project, onBack: () -> Unit, onFiles: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notice by rememberNotice()
    var fontFile by remember { mutableStateOf<File?>(null) }
    var fontName by remember { mutableStateOf<String?>(null) }
    var typeface by remember { mutableStateOf<Typeface?>(null) }
    var preset by remember { mutableStateOf(FontBuilder.Preset.LATIN) }
    val selected = remember { mutableStateListOf<String>().apply { addAll(FontBuilder.TARGETS.map { it.name }) } }
    var sizeFactor by remember { mutableFloatStateOf(1f) }
    var stroke by remember { mutableIntStateOf(0) }
    var sample by remember { mutableStateOf("The quick brown fox 0123456789 — Ação, ñ, é") }
    var working by remember { mutableStateOf<String?>(null) }
    var existing by remember { mutableStateOf<List<String>>(emptyList()) }
    var failed by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        existing = withContext(Dispatchers.IO) {
            runCatching {
                val folder = workspace.resolve(project.folder.uri, "Fonts") ?: return@runCatching emptyList<String>()
                workspace.children(folder.uri).filter { it.name.endsWith(".json", true) }.map { it.name.removeSuffix(".json") }
            }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(Unit) { reload() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val (file, loaded, name) = withContext(Dispatchers.IO) {
                    val cache = File(context.cacheDir, "font-source").apply { mkdirs() }
                    val target = File(cache, "source.ttf")
                    context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                        ?: error("Cannot read the file")
                    FontTables.codePoints(target.readBytes())
                    val display = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                    Triple(target, Typeface.createFromFile(target), display ?: "font")
                }
                fontFile = file
                typeface = loaded
                fontName = name
            } catch (e: Exception) {
                notice = Notice(context.getString(R.string.NotAFont))
            }
        }
    }

    val actions = remember { PackActions(context, workspace, project, scope, { notice = it }, { failed = it }) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) actions.export(uri)
    }
    fun export() {
        failed = null
        if (existing.isEmpty()) notice = Notice(context.getString(R.string.PackEmptyFonts))
        else exporter.launch("${project.folder.name}.bl")
    }

    fun generate() {
        val source = fontFile ?: return
        if (working != null) return
        working = ""
        scope.launch {
            try {
                val targets = FontBuilder.TARGETS.filter { it.name in selected }
                val written = withContext(Dispatchers.Default) {
                    FontBuilder.build(source, FontBuilder.Options(preset, targets, sizeFactor, stroke), progress = { working = it }) { name ->
                        workspace.output(workspace.fileAt(project.folder.uri, "Fonts/$name"))
                    }
                }
                reload()
                notice = Notice(context.getString(R.string.FontsGenerated, written.size, written.maxOf { it.glyphs }), error = false)
            } catch (e: Exception) {
                notice = Notice(e.message ?: e.javaClass.simpleName)
            } finally {
                working = null
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PackHeader(project, onBack, onFiles, actions::install, ::export, working != null)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.fillMaxWidth().pixelPanel(fill = Bl.Panel, corner = PixelCorner.LARGE).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PixelText(stringResource(R.string.FontSource), Ts.Item)
                        PixelButton(fontName ?: stringResource(R.string.ChooseFont), { picker.launch(arrayOf("*/*")) },
                            Modifier.fillMaxWidth(), glyph = Glyph.FONT, fontSize = Ts.Body)
                        PixelText(stringResource(R.string.FontSourceHint), Ts.Tiny, Bl.TextMuted)
                        val family = remember(typeface) { typeface?.let { FontFamily(it) } }
                        if (family != null) {
                            PixelTextField(sample, { sample = it }, stringResource(R.string.Preview))
                            Column(Modifier.fillMaxWidth().framePanel(fill = Bl.Code).padding(10.dp)) {
                                Text(sample, color = Bl.Text, fontFamily = family, fontSize = 18.sp * sizeFactor)
                                Text(sample, color = Bl.Gold, fontFamily = family, fontSize = 30.sp * sizeFactor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth().pixelPanel(fill = Bl.Panel, corner = PixelCorner.LARGE).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PixelText(stringResource(R.string.FontTargets), Ts.Item)
                        FontBuilder.TARGETS.forEach { target ->
                            val checked = target.name in selected
                            Row(
                                Modifier.fillMaxWidth().background(if (checked) Bl.Select else Bl.FrameFill)
                                    .pixelClickable(PixelCorner.NONE) {
                                        if (checked) selected.remove(target.name) else selected.add(target.name)
                                    }.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.padding(end = 10.dp), contentAlignment = Alignment.Center) {
                                    if (checked) GlyphIcon(Glyph.CHECK, 18.dp, color = Bl.GoodText)
                                    else Spacer(Modifier.widthIn(min = 18.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(target.name, color = Bl.Text, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                                    PixelText(stringResource(fontUse(target.name)), Ts.Tiny, Bl.TextFaint)
                                }
                                if (target.name in existing) GlyphIcon(Glyph.FILE, 16.dp, color = Bl.Gold)
                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth().pixelPanel(fill = Bl.Panel, corner = PixelCorner.LARGE).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PixelText(stringResource(R.string.FontOptions), Ts.Item)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ChoiceChip(stringResource(R.string.FontPresetLatin), preset == FontBuilder.Preset.LATIN) { preset = FontBuilder.Preset.LATIN }
                            ChoiceChip(stringResource(R.string.FontPresetEurope), preset == FontBuilder.Preset.EUROPE) { preset = FontBuilder.Preset.EUROPE }
                            ChoiceChip(stringResource(R.string.FontPresetAll), preset == FontBuilder.Preset.ALL) { preset = FontBuilder.Preset.ALL }
                        }
                        PixelText(stringResource(R.string.FontSize, (sizeFactor * 100).toInt()), Ts.Small, Bl.TextFaint)
                        Slider(sizeFactor, { sizeFactor = it }, valueRange = 0.7f..1.3f,
                            colors = SliderDefaults.colors(thumbColor = Bl.PressedText, activeTrackColor = Bl.TitleFill, inactiveTrackColor = Bl.Select))
                        PixelText(stringResource(R.string.FontStroke), Ts.Small, Bl.TextFaint)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (0..3).forEach { value -> ChoiceChip(value.toString(), stroke == value) { stroke = value } }
                        }
                    }
                    PixelButton(
                        working?.let { stringResource(R.string.FontGenerating, it) } ?: stringResource(R.string.FontGenerate),
                        ::generate, Modifier.fillMaxWidth().padding(bottom = 24.dp), fill = Bl.TitleFill,
                        enabled = fontFile != null && selected.isNotEmpty() && working == null,
                    )
                }
            }
        }
        NoticeBar(notice, { notice = null }, Modifier.align(Alignment.BottomCenter))
    }
    failed?.let { message -> InstallFailedDialog(message, { failed = null }, ::export) }
}

private fun fontUse(name: String): Int = when (name) {
    "Mouse_Text" -> R.string.FontUseMouse
    "Item_Stack" -> R.string.FontUseStack
    "Death_Text" -> R.string.FontUseDeath
    "Combat_Text" -> R.string.FontUseCombat
    else -> R.string.FontUseCrit
}
