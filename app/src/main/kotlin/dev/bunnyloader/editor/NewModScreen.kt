package dev.bunnyloader.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A new mod, on its own screen. Only what the manifest needs to be born:
 * description, license, changelog... the modder creates later as files and sees
 * in Bunny Loader on each save.
 */
@Composable
fun NewModScreen(workspace: Workspace, kind: Workspace.Kind, onBack: () -> Unit, onCreated: (Workspace.Project) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notice by rememberNotice()
    var name by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    var idEdited by remember { mutableStateOf(false) }
    var author by remember { mutableStateOf(EditorPrefs.author(context)) }
    var version by remember { mutableStateOf("1.0.0") }
    var uid by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var icon by remember { mutableStateOf<Uri?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var creating by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) }.getOrNull()
            }
            if (bitmap == null) notice = Notice(context.getString(R.string.NotAnImage))
            else {
                icon = uri
                preview = bitmap
            }
        }
    }

    val effectiveId = if (idEdited) id else Workspace.idFromName(name)
    val idError = if (effectiveId.isNotEmpty() && !Workspace.ID.matches(effectiveId)) stringResource(R.string.InvalidId) else null
    val ready = name.isNotBlank() && author.isNotBlank() && version.isNotBlank() && Workspace.ID.matches(effectiveId)

    fun create() {
        if (!ready || creating) return
        creating = true
        scope.launch {
            try {
                val mod = Workspace.NewMod(effectiveId, name.trim(), author.trim(), version.trim(), uid, kind, icon)
                withContext(Dispatchers.IO) { workspace.createProject(mod) }
                EditorPrefs.setAuthor(context, author.trim())
                val created = withContext(Dispatchers.IO) { workspace.projects() }.firstOrNull { it.folder.name == mod.id }
                if (created != null) onCreated(created) else onBack()
            } catch (e: Exception) {
                notice = Notice(e.message ?: e.javaClass.simpleName)
            } finally {
                creating = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            val title = stringResource(when (kind) {
                Workspace.Kind.MOD -> R.string.NewMod
                Workspace.Kind.TEXTURE -> R.string.NewTexturePack
                Workspace.Kind.FONT -> R.string.NewFontPack
            })
            PixelHeader(title, navigation = {
                IconAction(stringResource(R.string.Back), onBack) { GlyphIcon(Glyph.BACK, 22.dp) }
            })
            BoxWithConstraints(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                val wide = maxWidth >= 720.dp
                val picture: @Composable () -> Unit = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ModPicture(null, null, 148.dp, bitmap = preview)
                        PixelButton(stringResource(R.string.ChooseImage), { picker.launch(arrayOf("image/*")) },
                            glyph = Glyph.IMAGE, fontSize = Ts.Body)
                        if (icon != null) {
                            PixelButton(stringResource(R.string.RemoveImage), { icon = null; preview = null },
                                fontSize = Ts.Body)
                        }
                        PixelText(stringResource(R.string.ImageHint), Ts.Tiny, Bl.TextMuted,
                            modifier = Modifier.widthIn(max = 220.dp))
                    }
                }
                val fields: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        PixelTextField(name, { name = it }, stringResource(R.string.FieldName), capitalize = true)
                        PixelTextField(effectiveId, { id = it.trim(); idEdited = true }, stringResource(R.string.FieldId),
                            error = idError)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PixelTextField(author, { author = it }, stringResource(R.string.FieldAuthor), Modifier.weight(2f),
                                capitalize = true)
                            PixelTextField(version, { version = it.trim() }, stringResource(R.string.FieldVersion),
                                Modifier.weight(1f))
                        }
                        PixelTextField(uid, {}, stringResource(R.string.FieldUid), monospace = true, enabled = false,
                            trailing = {
                                IconAction(stringResource(R.string.NewUid), { uid = UUID.randomUUID().toString() }) {
                                    PixelIcon(R.drawable.ic_refresh, 20.dp)
                                }
                            })
                        PixelText(stringResource(R.string.UidHint), Ts.Tiny, Bl.TextMuted)
                    }
                }
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(
                        Modifier.widthIn(max = 880.dp).fillMaxWidth()
                            .pixelPanel(fill = Bl.Panel, corner = PixelCorner.LARGE).padding(18.dp),
                    ) {
                        if (wide) {
                            Row {
                                picture()
                                Spacer(Modifier.width(24.dp))
                                Box(Modifier.weight(1f)) { fields() }
                            }
                        } else {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { picture() }
                            Spacer(Modifier.height(18.dp))
                            fields()
                        }
                    }
                    Column(Modifier.widthIn(max = 880.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PixelText(stringResource(R.string.Preview), Ts.Small, Bl.TextFaint, Modifier.padding(start = 2.dp))
                        PreviewCard(preview, name, author, version, kind)
                    }
                    Row(Modifier.widthIn(max = 880.dp).fillMaxWidth().padding(bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (creating) Sunflower(30.dp)
                        PixelButton(stringResource(R.string.Cancel), onBack)
                        PixelButton(stringResource(R.string.Create), ::create, fill = Bl.TitleFill,
                            glyph = Glyph.NEW_FOLDER, enabled = ready && !creating)
                    }
                }
            }
        }
        NoticeBar(notice, { notice = null }, Modifier.align(Alignment.BottomCenter))
    }
}

/** How the mod will look in the list: picture, name, author and version. */
@Composable
private fun PreviewCard(picture: Bitmap?, name: String, author: String, version: String, kind: Workspace.Kind) {
    Row(
        Modifier.fillMaxWidth().pixelPanel(fill = Bl.Panel, corner = PixelCorner.LARGE).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModPicture(null, null, 64.dp, bitmap = picture)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            PixelText(name.ifBlank { stringResource(R.string.FieldName) }, Ts.Item,
                if (name.isBlank()) Bl.TextMuted else Bl.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            PixelText(listOf(author, version.takeIf { it.isNotBlank() }?.let { "v$it" }).filterNotNull()
                .filter { it.isNotBlank() }.joinToString(" · "), Ts.Small, Bl.TextFaint, maxLines = 1)
        }
        Box(Modifier.pixelPanel(fill = Bl.TitleFill).padding(horizontal = 8.dp, vertical = 3.dp)) {
            PixelText(when (kind) {
                Workspace.Kind.MOD -> "C#"
                Workspace.Kind.TEXTURE -> stringResource(R.string.BadgeTexture)
                Workspace.Kind.FONT -> stringResource(R.string.BadgeFont)
            }, Ts.Small)
        }
    }
}
