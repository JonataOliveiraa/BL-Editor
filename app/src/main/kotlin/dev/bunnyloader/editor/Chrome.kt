package dev.bunnyloader.editor

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The top strip of every screen: the game panel, edge to edge, without steps. */
@Composable
fun PixelHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().height(56.dp)
            .pixelPanel(fill = Bl.GamePanel, corner = PixelCorner.NONE)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (navigation != null) navigation() else Spacer(Modifier.width(10.dp))
        androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            PixelText(title, Ts.Head, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                androidx.compose.material3.Text(
                    subtitle, color = Bl.TextFaint, fontSize = androidx.compose.ui.unit.TextUnit(12f,
                        androidx.compose.ui.unit.TextUnitType.Sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

/** A short notice at the bottom of the screen that goes away by itself; errors in red. */
class Notice(val text: String, val error: Boolean = true, val id: Long = System.nanoTime())

@Composable
fun NoticeBar(notice: Notice?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    if (notice == null) return
    LaunchedEffect(notice.id) {
        delay(if (notice.error) 5000 else 2500)
        onDone()
    }
    Box(modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.widthIn(max = 560.dp)
                .pixelPanel(fill = if (notice.error) Bl.Bad.mix(Bl.Night, 0.45f) else Bl.Panel)
                .pixelClickable(onClick = onDone)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            PixelText(notice.text, Ts.Body)
        }
    }
}

/**
 * The mod picture in the launcher frame, unfiltered (pixel art stays sharp).
 * Without a picture, the bunny takes its place.
 */
@Composable
fun ModPicture(workspace: Workspace?, uri: Uri?, size: Dp, modifier: Modifier = Modifier, bitmap: Bitmap? = null) {
    val loaded by produceState<Bitmap?>(bitmap, uri, bitmap) {
        value = bitmap ?: if (uri == null || workspace == null) null
        else withContext(Dispatchers.IO) { workspace.readBitmap(uri) }
    }
    Box(modifier.size(size).framePanel().padding(4.dp), contentAlignment = Alignment.Center) {
        val image = loaded
        if (image != null) {
            Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                filterQuality = if (image.width <= 128) FilterQuality.None else FilterQuality.Medium)
        } else {
            PixelIcon(R.drawable.ic_bunny_menu, size * 0.6f, alpha = 0.55f)
        }
    }
}

/** Keeps values in the editor's SharedPreferences. */
object EditorPrefs {
    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences("editor", android.content.Context.MODE_PRIVATE)

    fun author(context: android.content.Context): String = prefs(context).getString("author", "").orEmpty()
    fun setAuthor(context: android.content.Context, value: String) = prefs(context).edit().putString("author", value).apply()
    fun autosave(context: android.content.Context): Boolean = prefs(context).getBoolean("autosave", false)
    fun setAutosave(context: android.content.Context, value: Boolean) = prefs(context).edit().putBoolean("autosave", value).apply()
    fun wordWrap(context: android.content.Context): Boolean = prefs(context).getBoolean("wordWrap", false)
    fun setWordWrap(context: android.content.Context, value: Boolean) = prefs(context).edit().putBoolean("wordWrap", value).apply()
    fun language(context: android.content.Context): String = prefs(context).getString("language", "").orEmpty()
    fun setLanguage(context: android.content.Context, value: String) = prefs(context).edit().putString("language", value).commit()

    /** When each project was last opened, so the home screen can put recent work first. */
    fun lastOpened(context: android.content.Context, folder: String): Long = prefs(context).getLong("opened:$folder", 0L)
    fun markOpened(context: android.content.Context, folder: String) =
        prefs(context).edit().putLong("opened:$folder", System.currentTimeMillis()).apply()
}

@Composable
fun rememberNotice() = remember { mutableStateOf<Notice?>(null) }
