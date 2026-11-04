package dev.bunnyloader.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Tool(val glyph: Glyph, val label: Int) {
    PENCIL(Glyph.PENCIL, R.string.ToolPencil),
    ERASER(Glyph.ERASER, R.string.ToolEraser),
    FILL(Glyph.FILL, R.string.ToolFill),
    PICKER(Glyph.PICKER, R.string.ToolPicker),
    LINE(Glyph.LINE, R.string.ToolLine),
    RECT(Glyph.RECT, R.string.ToolRect),
    RECT_FILLED(Glyph.RECT_FILLED, R.string.ToolRectFilled),
    MOVE(Glyph.MOVE, R.string.ToolMove),
}

/** A start palette in the tones of Terraria sprites; anything else comes from the color panel. */
private val START_PALETTE = listOf(
    0xFF000000, 0xFFFFFFFF, 0xFF7C85B6, 0xFF3C2A21, 0xFF8B6A4A, 0xFFC9A26B, 0xFFE0603F, 0xFFA31621, 0xFFF9E141, 0xFFE9B43A,
    0xFF3F8F4E, 0xFF74E65B, 0xFF2B6CB0, 0xFF7FC8FF, 0xFF5B3FA8, 0xFFB98BFF,
).map { it.toInt() }

/**
 * The texture editor. One finger paints with the current tool, two fingers zoom and move the
 * picture. Pixels are kept as plain ARGB in [PixelCanvas] and saved as they are, so a transparent
 * pixel stays transparent and a color is the color that was picked.
 */
@Composable
fun PixelEditorScreen(
    title: String,
    /** The PNG to open; null starts a blank picture of [blankWidth] x [blankHeight]. */
    load: (suspend () -> ByteArray)?,
    blankWidth: Int = 32,
    blankHeight: Int = 32,
    onSave: suspend (ByteArray) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notice by rememberNotice()
    var canvas by remember { mutableStateOf<PixelCanvas?>(if (load == null) PixelCanvas(blankWidth, blankHeight) else null) }
    var revision by remember { mutableIntStateOf(0) }
    var savedRevision by remember { mutableIntStateOf(if (load == null) -1 else 0) }
    var tool by remember { mutableStateOf(Tool.PENCIL) }
    var color by remember { mutableIntStateOf(START_PALETTE[0]) }
    val palette = remember { mutableStateListOf<Int>().apply { addAll(START_PALETTE) } }
    var brush by remember { mutableIntStateOf(1) }
    var grid by remember { mutableStateOf(true) }
    var mirror by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var colorPanel by remember { mutableStateOf(false) }
    var resizing by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (load != null) {
            try {
                val bytes = load()
                canvas = withContext(Dispatchers.Default) { decodePixels(bytes) } ?: error(context.getString(R.string.NotAnImage))
            } catch (e: Exception) {
                notice = Notice(e.message ?: e.javaClass.simpleName)
                canvas = PixelCanvas(blankWidth, blankHeight)
            }
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val target = canvas
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picture = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let(::decodePixels) }.getOrNull()
            }
            if (picture == null) notice = Notice(context.getString(R.string.NotAnImage))
            else {
                target.beginStep()
                target.replace(picture.width, picture.height, picture.pixels)
                revision++
            }
        }
    }

    val dirty = canvas != null && revision != savedRevision

    fun save(then: () -> Unit = {}) {
        val target = canvas ?: return
        if (saving) return
        saving = true
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) { encodePng(target) }
                onSave(bytes)
                savedRevision = revision
                then()
            } catch (e: Exception) {
                notice = Notice(e.message ?: e.javaClass.simpleName)
            } finally {
                saving = false
            }
        }
    }

    fun leave() {
        if (dirty) leaving = true else onClose()
    }
    BackHandler { leave() }

    Box(Modifier.fillMaxSize().background(Bl.Code)) {
        Column(Modifier.fillMaxSize()) {
            PixelHeader(title, subtitle = canvas?.let { "${it.width} × ${it.height}" }, navigation = {
                IconAction(stringResource(R.string.Back), ::leave) { GlyphIcon(Glyph.BACK, 22.dp) }
            }) {
                if (saving) Sunflower(24.dp)
                val current = canvas
                IconAction(stringResource(R.string.Undo), {
                    current?.undo()
                    revision++
                }, enabled = current?.canUndo == true) { GlyphIcon(Glyph.UNDO, 20.dp, color = if (current?.canUndo == true) Bl.Text else Bl.TextMuted) }
                IconAction(stringResource(R.string.Redo), {
                    current?.redo()
                    revision++
                }, enabled = current?.canRedo == true) { GlyphIcon(Glyph.REDO, 20.dp, color = if (current?.canRedo == true) Bl.Text else Bl.TextMuted) }
                IconAction(stringResource(R.string.Save), { save() }, enabled = dirty) {
                    GlyphIcon(Glyph.SAVE, 22.dp, color = if (dirty) Bl.Text else Bl.TextMuted, accent = if (dirty) Bl.PressedText else Bl.TextMuted)
                }
                Box {
                    IconAction(stringResource(R.string.More), { menu = true }) { GlyphIcon(Glyph.MORE, 22.dp) }
                    PixelMenu(menu, { menu = false }, listOf(
                        MenuItem(stringResource(R.string.ImportImage)) { importer.launch(arrayOf("image/*")) },
                        MenuItem(stringResource(R.string.ResizeCanvas)) { resizing = true },
                        MenuItem(stringResource(R.string.FlipHorizontal)) { current?.run { beginStep(); flipHorizontal() }; revision++ },
                        MenuItem(stringResource(R.string.FlipVertical)) { current?.run { beginStep(); flipVertical() }; revision++ },
                        MenuItem(stringResource(R.string.ClearCanvas), danger = true) { current?.run { beginStep(); clear() }; revision++ },
                    ))
                }
            }
            val current = canvas
            if (current == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Sunflower(52.dp) }
            } else {
                PaintArea(
                    current, revision, tool, color, brush, grid, mirror,
                    onChanged = { revision++ },
                    onPicked = { picked ->
                        color = picked
                        tool = Tool.PENCIL
                        if (picked !in palette && (picked ushr 24) != 0) palette.add(0, picked)
                    },
                    Modifier.weight(1f).fillMaxWidth(),
                )
            }
            ToolBar(tool, { tool = it }, brush, { brush = it }, grid, { grid = it }, mirror, { mirror = it })
            PaletteBar(palette, color, { color = it; if (tool == Tool.ERASER || tool == Tool.PICKER) tool = Tool.PENCIL }) { colorPanel = true }
        }
        NoticeBar(notice, { notice = null }, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
    }

    if (colorPanel) {
        ColorPanel(color, { colorPanel = false }) { picked ->
            colorPanel = false
            color = picked
            if (picked !in palette) palette.add(0, picked)
            if (tool == Tool.ERASER || tool == Tool.PICKER) tool = Tool.PENCIL
        }
    }
    if (resizing) {
        val current = canvas
        if (current != null) ResizeDialog(current.width, current.height, { resizing = false }) { w, h ->
            resizing = false
            current.beginStep()
            current.resize(w, h)
            revision++
        }
    }
    if (leaving) {
        UnsavedDialog({ leaving = false }, onDiscard = { leaving = false; onClose() }, onSave = {
            leaving = false
            save(onClose)
        })
    }
}

fun decodePixels(bytes: ByteArray): PixelCanvas? {
    val options = BitmapFactory.Options().apply {
        inPremultiplied = false
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    require(bitmap.width <= PixelCanvas.MAX_SIDE && bitmap.height <= PixelCanvas.MAX_SIDE) { "Images up to ${PixelCanvas.MAX_SIDE} pixels per side" }
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val canvas = PixelCanvas(bitmap.width, bitmap.height, pixels)
    bitmap.recycle()
    return canvas
}

/** A PNG of exactly these pixels: without premultiplying, a transparent pixel keeps its color. */
fun encodePng(canvas: PixelCanvas): ByteArray = encodePng(canvas.width, canvas.height, canvas.pixels)

fun encodePng(width: Int, height: Int, pixels: IntArray): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.isPremultiplied = false
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    bitmap.recycle()
    return out.toByteArray()
}

@Composable
private fun PaintArea(
    canvas: PixelCanvas,
    revision: Int,
    tool: Tool,
    color: Int,
    brush: Int,
    grid: Boolean,
    mirror: Boolean,
    onChanged: () -> Unit,
    onPicked: (Int) -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.background(Bl.Night)) {
        val areaWidth = constraints.maxWidth.toFloat()
        val areaHeight = constraints.maxHeight.toFloat()
        val fit = remember(canvas.width, canvas.height, areaWidth, areaHeight) {
            max(1f, floor(minOf(areaWidth * 0.94f / canvas.width, areaHeight * 0.94f / canvas.height)))
        }
        var zoom by remember(canvas.width, canvas.height) { mutableFloatStateOf(fit) }
        var panX by remember(canvas.width, canvas.height) { mutableFloatStateOf(0f) }
        var panY by remember(canvas.width, canvas.height) { mutableFloatStateOf(0f) }
        val bitmap = remember(canvas.width, canvas.height) { Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888) }
        val image = remember(bitmap) { bitmap.asImageBitmap() }

        fun texel(position: Offset): Pair<Int, Int> {
            val left = (areaWidth - canvas.width * zoom) / 2 + panX
            val top = (areaHeight - canvas.height * zoom) / 2 + panY
            return floor((position.x - left) / zoom).toInt() to floor((position.y - top) / zoom).toInt()
        }

        // The latest values, for a gesture that outlives recompositions.
        val state = remember { arrayOfNulls<Any>(4) }
        state[0] = tool
        state[1] = color
        state[2] = brush
        state[3] = mirror

        Canvas(
            Modifier.fillMaxSize().pointerInput(canvas) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val currentTool = state[0] as Tool
                    val paintColor = if (currentTool == Tool.ERASER) 0 else state[1] as Int
                    val size = state[2] as Int
                    val mirrored = state[3] as Boolean
                    val (startX, startY) = texel(down.position)
                    var lastX = startX
                    var lastY = startY
                    var base: IntArray? = null
                    var began = false
                    var transforming = false
                    fun begin() {
                        if (began) return
                        canvas.beginStep()
                        began = true
                        base = canvas.pixels.copyOf()
                    }
                    fun paintTo(x: Int, y: Int) {
                        when (currentTool) {
                            Tool.PENCIL, Tool.ERASER -> {
                                begin()
                                canvas.line(lastX, lastY, x, y, paintColor, size, mirrored)
                            }
                            Tool.LINE -> {
                                begin()
                                canvas.replace(canvas.width, canvas.height, base!!)
                                canvas.line(startX, startY, x, y, paintColor, size, mirrored)
                            }
                            Tool.RECT, Tool.RECT_FILLED -> {
                                begin()
                                canvas.replace(canvas.width, canvas.height, base!!)
                                canvas.rectangle(startX, startY, x, y, paintColor, currentTool == Tool.RECT_FILLED, mirrored)
                            }
                            else -> Unit
                        }
                        lastX = x
                        lastY = y
                        onChanged()
                    }
                    if (currentTool == Tool.PENCIL || currentTool == Tool.ERASER) paintTo(startX, startY)
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            if (!transforming) when (currentTool) {
                                Tool.FILL -> {
                                    if (canvas.inside(lastX, lastY)) {
                                        canvas.beginStep()
                                        canvas.fill(lastX, lastY, state[1] as Int)
                                        onChanged()
                                    }
                                }
                                Tool.PICKER -> if (canvas.inside(lastX, lastY)) onPicked(canvas.get(lastX, lastY))
                                else -> Unit
                            }
                            break
                        }
                        if (pressed.size >= 2) {
                            if (!transforming) {
                                transforming = true
                                // The first finger of a pinch may have left a dot.
                                if (began) {
                                    canvas.undo()
                                    onChanged()
                                }
                            }
                            val gesture = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val next = (zoom * gesture).coerceIn(0.5f, MAX_ZOOM)
                            val applied = next / zoom
                            val cx = centroid.x - areaWidth / 2
                            val cy = centroid.y - areaHeight / 2
                            panX = cx + (panX - cx) * applied + pan.x
                            panY = cy + (panY - cy) * applied + pan.y
                            zoom = next
                            event.changes.forEach { it.consume() }
                        } else if (!transforming) {
                            val change = pressed[0]
                            if (change.positionChanged()) {
                                if (currentTool == Tool.MOVE) {
                                    panX += change.position.x - change.previousPosition.x
                                    panY += change.position.y - change.previousPosition.y
                                } else {
                                    val (x, y) = texel(change.position)
                                    if (x != lastX || y != lastY) {
                                        if (currentTool == Tool.FILL || currentTool == Tool.PICKER) {
                                            lastX = x
                                            lastY = y
                                        } else paintTo(x, y)
                                    }
                                }
                                change.consume()
                            }
                        }
                    }
                }
            },
        ) {
            // Reading the revision makes each change redraw.
            revision.hashCode()
            bitmap.setPixels(canvas.pixels, 0, canvas.width, 0, 0, canvas.width, canvas.height)
            val width = canvas.width * zoom
            val height = canvas.height * zoom
            val left = (size.width - width) / 2 + panX
            val top = (size.height - height) / 2 + panY
            drawCheckers(Offset(left, top), Size(width, height), 8.dp.toPx())
            drawImage(
                image, IntOffset.Zero, IntSize(canvas.width, canvas.height),
                IntOffset(left.roundToInt(), top.roundToInt()), IntSize(width.roundToInt(), height.roundToInt()),
                filterQuality = FilterQuality.None,
            )
            if (grid && zoom >= GRID_FROM_ZOOM) {
                val line = Color.White.copy(alpha = 0.12f)
                for (x in 0..canvas.width) {
                    val at = left + x * zoom
                    if (at in 0f..size.width) drawRect(line, Offset(at, top), Size(1f, height))
                }
                for (y in 0..canvas.height) {
                    val at = top + y * zoom
                    if (at in 0f..size.height) drawRect(line, Offset(left, at), Size(width, 1f))
                }
            }
            // Actual size, in the corner: what the sprite looks like in the game.
            val preview = 1.dp.toPx().roundToInt().coerceAtLeast(1)
            val previewWidth = canvas.width * preview
            val previewHeight = canvas.height * preview
            if (previewWidth < size.width / 3 && previewHeight < size.height / 3) {
                val margin = 8.dp.toPx()
                drawRect(Bl.FrameFill, Offset(margin - 4, margin - 4), Size(previewWidth + 8f, previewHeight + 8f))
                drawImage(image, IntOffset.Zero, IntSize(canvas.width, canvas.height),
                    IntOffset(margin.roundToInt(), margin.roundToInt()), IntSize(previewWidth, previewHeight), filterQuality = FilterQuality.None)
            }
        }
    }
}

@Composable
private fun ToolBar(
    tool: Tool,
    onTool: (Tool) -> Unit,
    brush: Int,
    onBrush: (Int) -> Unit,
    grid: Boolean,
    onGrid: (Boolean) -> Unit,
    mirror: Boolean,
    onMirror: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().pixelPanel(fill = Bl.Panel, corner = PixelCorner.NONE)
            .horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tool.entries.forEach { item ->
            ToggleIcon(stringResource(item.label), item == tool, { onTool(item) }) { GlyphIcon(item.glyph, 22.dp) }
        }
        Box(Modifier.padding(horizontal = 4.dp).size(2.dp, 28.dp).background(Bl.Outline))
        ToggleIcon(stringResource(R.string.ToolBrushSize), false, { onBrush(if (brush >= 4) 1 else brush + 1) }) {
            PixelText("${brush}px", Ts.Small)
        }
        ToggleIcon(stringResource(R.string.ToolMirror), mirror, { onMirror(!mirror) }) { GlyphIcon(Glyph.MIRROR, 22.dp) }
        ToggleIcon(stringResource(R.string.ToolGrid), grid, { onGrid(!grid) }) { GlyphIcon(Glyph.GRID, 22.dp) }
    }
}

@Composable
private fun ToggleIcon(description: String, selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    IconAction(description, onClick, Modifier.background(if (selected) Bl.TitleFill else Color.Transparent)) { content() }
}

@Composable
private fun PaletteBar(palette: List<Int>, color: Int, onColor: (Int) -> Unit, onCustom: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Bl.FrameFill).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(34.dp).pixelPanel(fill = Color(color or (0xFF shl 24)), outline = Bl.PressedBorder)
                .pixelClickable(onClick = onCustom),
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(Glyph.PLUS, 16.dp) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            palette.forEach { item ->
                Box(
                    Modifier.size(30.dp).pixelPanel(fill = Color(item), outline = if (item == color) Bl.PressedText else Bl.Outline)
                        .pixelClickable { onColor(item) },
                )
            }
        }
    }
}

/** Hue, saturation, value and alpha, with the color's code for pasting. */
@Composable
private fun ColorPanel(initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val start = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) }
    }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var saturation by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    var alpha by remember { mutableFloatStateOf(((initial ushr 24) and 0xFF) / 255f) }
    val argb = android.graphics.Color.HSVToColor((alpha * 255).roundToInt(), floatArrayOf(hue, saturation, value))
    var hex by remember(argb) { mutableStateOf("%08X".format(argb)) }
    PixelDialog(stringResource(R.string.ColorPanel), onDismiss, buttons = {
        PixelButton(stringResource(R.string.Cancel), onDismiss, fontSize = Ts.Body)
        PixelButton(stringResource(R.string.UseColor), { onPick(argb) }, fill = Bl.TitleFill, fontSize = Ts.Body)
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.fillMaxWidth().height(44.dp).framePanel().padding(3.dp).background(Color(argb)))
            ColorSlider(stringResource(R.string.ColorHue), hue, 0f..360f) { hue = it }
            ColorSlider(stringResource(R.string.ColorSaturation), saturation, 0f..1f) { saturation = it }
            ColorSlider(stringResource(R.string.ColorValue), value, 0f..1f) { value = it }
            ColorSlider(stringResource(R.string.ColorAlpha), alpha, 0f..1f) { alpha = it }
            PixelTextField(hex, { text ->
                hex = text.trim().removePrefix("#").uppercase().take(8)
                val parsed = hex.toLongOrNull(16)
                if (parsed != null && (hex.length == 6 || hex.length == 8)) {
                    val full = (if (hex.length == 6) parsed or 0xFF000000 else parsed).toInt()
                    val hsv = FloatArray(3)
                    android.graphics.Color.colorToHSV(full, hsv)
                    hue = hsv[0]
                    saturation = hsv[1]
                    value = hsv[2]
                    alpha = ((full ushr 24) and 0xFF) / 255f
                }
            }, stringResource(R.string.ColorCode), monospace = true)
        }
    }
}

@Composable
private fun ColorSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PixelText(label, Ts.Small, Bl.TextFaint, Modifier.padding(end = 8.dp).size(84.dp, 20.dp), maxLines = 1)
        Slider(value, onChange, Modifier.weight(1f).height(32.dp), valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Bl.PressedText, activeTrackColor = Bl.TitleFill, inactiveTrackColor = Bl.Select))
    }
}

@Composable
fun SizeFields(width: String, onWidth: (String) -> Unit, height: String, onHeight: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PixelTextField(width, { onWidth(it.filter(Char::isDigit).take(4)) }, stringResource(R.string.FieldWidth), Modifier.weight(1f))
        PixelTextField(height, { onHeight(it.filter(Char::isDigit).take(4)) }, stringResource(R.string.FieldHeight), Modifier.weight(1f))
    }
}

@Composable
private fun ResizeDialog(currentWidth: Int, currentHeight: Int, onDismiss: () -> Unit, onResize: (Int, Int) -> Unit) {
    var width by remember { mutableStateOf(currentWidth.toString()) }
    var height by remember { mutableStateOf(currentHeight.toString()) }
    val w = width.toIntOrNull()
    val h = height.toIntOrNull()
    val valid = w != null && h != null && w in 1..PixelCanvas.MAX_SIDE && h in 1..PixelCanvas.MAX_SIDE
    PixelDialog(stringResource(R.string.ResizeCanvas), onDismiss, buttons = {
        PixelButton(stringResource(R.string.Cancel), onDismiss, fontSize = Ts.Body)
        PixelButton(stringResource(R.string.Apply), { onResize(w!!, h!!) }, fill = Bl.TitleFill, fontSize = Ts.Body, enabled = valid)
    }) {
        SizeFields(width, { width = it }, height, { height = it })
    }
}

private const val MAX_ZOOM = 64f
private const val GRID_FROM_ZOOM = 6f
