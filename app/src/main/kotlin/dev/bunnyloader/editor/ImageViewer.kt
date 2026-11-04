package dev.bunnyloader.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.roundToInt

val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

fun isImageFile(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

/** The two tones of "this is transparent", in cells of the same size on screen at any zoom. */
fun DrawScope.drawCheckers(topLeft: Offset, area: Size, cell: Float) {
    val dark = Color(0xFF20264A)
    val light = Color(0xFF2B335A)
    drawRect(dark, topLeft, area)
    val columns = (area.width / cell).toInt() + 1
    val rows = (area.height / cell).toInt() + 1
    for (row in 0 until rows) for (column in 0 until columns) {
        if ((row + column) % 2 != 0) continue
        val x = topLeft.x + column * cell
        val y = topLeft.y + row * cell
        drawRect(light, Offset(x, y), Size(minOf(cell, topLeft.x + area.width - x), minOf(cell, topLeft.y + area.height - y)))
    }
}

/**
 * Shows an image of the project: pinch to zoom, drag to move, double tap to go between fit and 1:1.
 * Pixels stay sharp from 2x up, because mods' art is pixel art.
 */
@Composable
fun ImageViewer(workspace: Workspace, file: OpenFile, modifier: Modifier = Modifier, onEdit: () -> Unit) {
    val image by produceState<Result<Bitmap>?>(null, file.uri, file.revision) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = workspace.readBytes(file.uri)
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
                    var sample = 1
                    while (outWidth / sample > MAX_VIEW_SIDE || outHeight / sample > MAX_VIEW_SIDE) sample *= 2
                    inSampleSize = sample
                    inJustDecodeBounds = false
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Not an image")
            }
        }
    }
    var bytesSize by remember { mutableStateOf(0L) }
    LaunchedEffect(file.uri, file.revision) {
        bytesSize = withContext(Dispatchers.IO) { runCatching { workspace.readBytes(file.uri).size.toLong() }.getOrDefault(0L) }
    }
    Column(modifier.background(Bl.Code)) {
        val bitmap = image?.getOrNull()
        Row(
            Modifier.fillMaxWidth().pixelPanel(fill = Bl.Panel, corner = PixelCorner.NONE).padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                listOfNotNull(bitmap?.let { "${it.width} × ${it.height}" }, "%.1f KB".format(bytesSize / 1024f).takeIf { bytesSize > 0 }).joinToString("  ·  "),
                Modifier.weight(1f), color = Bl.TextFaint, fontSize = 13.sp, maxLines = 1,
            )
            if (bitmap != null && file.name.endsWith(".png", true)) {
                PixelButton(stringResource(R.string.EditImage), onEdit, glyph = Glyph.PENCIL, fontSize = Ts.Small)
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (image?.isFailure == true) {
                PixelText(stringResource(R.string.NotAnImage), Ts.Item, Bl.TextMuted)
            }
            if (bitmap == null) return@BoxWithConstraints
            val areaWidth = constraints.maxWidth.toFloat()
            val areaHeight = constraints.maxHeight.toFloat()
            val fit = remember(bitmap, areaWidth, areaHeight) {
                val ratio = minOf(areaWidth * 0.9f / bitmap.width, areaHeight * 0.9f / bitmap.height)
                // Whole steps keep every pixel the same size, when there is room for one.
                if (ratio >= 1f) floor(ratio) else ratio
            }
            var zoom by remember(bitmap) { mutableFloatStateOf(fit) }
            var panX by remember(bitmap) { mutableFloatStateOf(0f) }
            var panY by remember(bitmap) { mutableFloatStateOf(0f) }
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(bitmap) {
                        detectTransformGestures { centroid, pan, gesture, _ ->
                            val next = (zoom * gesture).coerceIn(fit.coerceAtMost(1f) / 4f, MAX_ZOOM)
                            val applied = next / zoom
                            val cx = centroid.x - areaWidth / 2
                            val cy = centroid.y - areaHeight / 2
                            panX = cx + (panX - cx) * applied + pan.x
                            panY = cy + (panY - cy) * applied + pan.y
                            zoom = next
                        }
                    }
                    .pointerInput(bitmap) {
                        detectTapGestures(onDoubleTap = {
                            zoom = if (zoom == fit) maxOf(1f, fit * 2) else fit
                            panX = 0f
                            panY = 0f
                        })
                    },
            ) {
                val width = bitmap.width * zoom
                val height = bitmap.height * zoom
                val left = (size.width - width) / 2 + panX
                val top = (size.height - height) / 2 + panY
                drawCheckers(Offset(left, top), Size(width, height), 10.dp.toPx())
                drawImage(
                    bitmap.asImageBitmap(), IntOffset.Zero, IntSize(bitmap.width, bitmap.height),
                    IntOffset(left.roundToInt(), top.roundToInt()), IntSize(width.roundToInt(), height.roundToInt()),
                    filterQuality = if (zoom >= 2f) FilterQuality.None else FilterQuality.Low,
                )
            }
            Spacer(Modifier.width(0.dp))
        }
    }
}

private const val MAX_VIEW_SIDE = 4096
private const val MAX_ZOOM = 64f
