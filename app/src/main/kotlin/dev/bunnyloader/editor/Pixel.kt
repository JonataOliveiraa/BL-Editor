package dev.bunnyloader.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

/**
 * Bunny Loader's interface pieces (app/src/main/kotlin/dev/bunnyloader/ui/
 * Palette.kt and Pixel.kt), brought here: the classic palette, the bevelled panel
 * with stepped corners, the black-outlined text, the yellow touch border.
 *
 * Terraria's font goes only on the frame (titles, buttons, forms). Code, the file
 * tree and the tabs use regular fonts: what matters there is reading.
 */
object Bl {
    val Panel = Color(0xFF2B335A)
    val Button = Color(0xFF323471)
    val ButtonPressed = Color(0xFF717BD5)
    val Select = Color(0xFF32407D)
    val TitleFill = Color(0xFF4A5DAA)
    val GamePanel = Color(0xFF3F5297)
    val FrameBorder = Color(0xFF4E5275)
    val FrameFill = Color(0xFF1A1E39)

    val PressedBorder = Color(0xFFEBE27F)
    val PressedText = Color(0xFFF9E141)
    val Outline = Color(0xFF131625)
    val Night = Color(0xFF0D0F1A)
    val Ink = Color(0xFF000000)

    val Text = Color.White
    val TextDim = Color(0xFFD5DAF2)
    val TextFaint = Color(0xFFA6AED6)
    val TextMuted = Color(0xFF7C85B6)
    val Bad = Color(0xFFE0603F)
    val Good = Color(0xFF3F8F4E)
    val GoodText = Color(0xFF74E65B)
    val Gold = Color(0xFFE9B43A)

    /** The code and tree background: the picture frame, deeper than the panel. */
    val Code = Color(0xFF161A33)
    val CodeLine = Color(0xFF20264A)
    val Guide = Color(0xFF343B66)
}

val PixelFont = FontFamily(Font(R.font.bunny))

/** Terraria's font draws small for its declared size; the scale lives in one place. */
object Ts {
    const val Tiny = 12
    const val Small = 14
    const val Body = 16
    const val Item = 18
    const val Head = 22
    const val Big = 26
}

private val ArtPixel = 2.dp

enum class PixelCorner(internal vararg val steps: Int) {
    NONE(), SMALL(2, 1), LARGE(4, 2, 1, 1);
}

internal fun steppedOutline(size: Size, unit: Float, corner: PixelCorner): Path {
    val w = size.width
    val h = size.height
    val path = Path()
    val steps = corner.steps
    if (steps.isEmpty() || w < unit * steps[0] * 2 + unit || h < unit * steps.size * 2 + unit) {
        path.addRect(Rect(0f, 0f, w, h))
        return path
    }
    val n = steps.size
    val corner0 = buildList {
        add(Offset(0f, n * unit))
        for (k in n - 1 downTo 0) {
            add(Offset(steps[k] * unit, (k + 1) * unit))
            add(Offset(steps[k] * unit, k * unit))
        }
    }
    path.moveTo(corner0[0].x, corner0[0].y)
    corner0.forEach { path.lineTo(it.x, it.y) }
    corner0.asReversed().forEach { path.lineTo(w - it.x, it.y) }
    corner0.forEach { path.lineTo(w - it.x, h - it.y) }
    corner0.asReversed().forEach { path.lineTo(it.x, h - it.y) }
    path.close()
    return path
}

class PixelShape(private val corner: PixelCorner) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        if (corner == PixelCorner.NONE) Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        else Outline.Generic(steppedOutline(size, with(density) { ArtPixel.toPx() }, corner))
}

fun Color.mix(other: Color, t: Float) = Color(
    red + (other.red - red) * t,
    green + (other.green - green) * t,
    blue + (other.blue - blue) * t,
    alpha,
)

/** Bevelled panel: dark outline, light on the top left, shadow on the bottom right. */
fun Modifier.pixelPanel(
    fill: Color = Bl.Panel,
    raised: Boolean = true,
    outline: Color = Bl.Outline,
    light: Color = fill.mix(Color.White, 0.14f),
    dark: Color = fill.mix(Bl.Night, 0.45f),
    corner: PixelCorner = PixelCorner.SMALL,
): Modifier = drawWithCache {
    val b = ArtPixel.toPx()
    val shape = if (corner == PixelCorner.NONE) null else steppedOutline(size, b, corner)
    val stroke = Stroke(b * 2)
    onDrawBehind {
        val w = size.width
        val h = size.height
        val body: DrawScope.() -> Unit = {
            if (shape == null) drawRect(outline)
            drawRect(fill, Offset(b, b), Size(w - 2 * b, h - 2 * b))
            val top = if (raised) light else dark
            val bottom = if (raised) dark else light
            drawRect(top, Offset(b, b), Size(w - 2 * b, b))
            drawRect(top, Offset(b, b), Size(b, h - 2 * b))
            drawRect(bottom, Offset(b, h - 2 * b), Size(w - 2 * b, b))
            drawRect(bottom, Offset(w - 2 * b, b), Size(b, h - 2 * b))
            if (shape != null) drawPath(shape, outline, style = stroke)
        }
        if (shape == null) body() else clipPath(shape) { body() }
    }
}

/** Picture frame: flat border and dark background, no bevel. */
fun Modifier.framePanel(border: Color = Bl.FrameBorder, fill: Color = Bl.FrameFill): Modifier = drawBehind {
    val b = 2.dp.toPx()
    drawRect(border)
    drawRect(fill, Offset(b, b), Size(size.width - 2 * b, size.height - 2 * b))
}

fun Modifier.pixelClickable(corner: PixelCorner = PixelCorner.SMALL, enabled: Boolean = true, onClick: () -> Unit): Modifier =
    composed {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        drawWithContent {
            drawContent()
            if (pressed) drawPressBorder(corner)
        }.clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
    }

/** Tap and long press, with the yellow border: a long press opens the context menu. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pixelCombinedClickable(onLongClick: () -> Unit, onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    drawWithContent {
        drawContent()
        if (pressed) drawPressBorder(PixelCorner.NONE)
    }.combinedClickable(interaction, indication = null, onLongClick = onLongClick, onClick = onClick)
}

fun DrawScope.drawPressBorder(corner: PixelCorner = PixelCorner.NONE) {
    val b = ArtPixel.toPx()
    if (corner == PixelCorner.NONE) {
        drawRect(Bl.PressedBorder, Offset(b / 2, b / 2), Size(size.width - b, size.height - b), style = Stroke(b))
        return
    }
    val shape = steppedOutline(size, b, corner)
    clipPath(shape) { drawPath(shape, Bl.PressedBorder, style = Stroke(b * 2)) }
}

// ------------------------------- sprites -------------------------------

@Composable
fun PixelIcon(res: Int, size: Dp, modifier: Modifier = Modifier, alpha: Float = 1f) {
    Image(ImageBitmap.imageResource(res), null, modifier.size(size), alpha = alpha, filterQuality = FilterQuality.None)
}

/** One frame of a sprite sheet, cut out when drawing. */
@Composable
fun SpriteFrame(res: Int, frameW: Int, frameH: Int, index: Int, width: Dp, modifier: Modifier = Modifier) {
    val sheet = ImageBitmap.imageResource(res)
    val cols = (sheet.width / frameW).coerceAtLeast(1)
    Canvas(modifier.size(width, width * frameH / frameW)) {
        drawImage(
            sheet, IntOffset(index % cols * frameW, index / cols * frameH), IntSize(frameW, frameH),
            IntOffset.Zero, IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.None,
        )
    }
}

/** The game's loading sunflower (spr_loading: 19 frames of 52x54). */
@Composable
fun Sunflower(width: Dp, modifier: Modifier = Modifier) {
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60)
            frame = (frame + 1) % 19
        }
    }
    SpriteFrame(R.drawable.spr_loading, 52, 54, frame, width, modifier)
}

// -------------------------------- text ---------------------------------

/** Text with a one pixel black outline on all four sides, the way Terraria draws it. */
@Composable
fun PixelText(
    text: String,
    size: Int = Ts.Body,
    color: Color = Bl.Text,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    align: TextAlign? = null,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text, color = color,
        modifier = modifier.drawBehind {
            val measured = layout ?: return@drawBehind
            for ((dx, dy) in OUTLINE_OFFSETS) drawText(measured, color = Bl.Ink, topLeft = Offset(dx.dp.toPx(), dy.dp.toPx()))
        },
        fontFamily = PixelFont, fontSize = size.sp, maxLines = maxLines, overflow = overflow, textAlign = align,
        onTextLayout = { layout = it },
    )
}

private val OUTLINE_OFFSETS = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)

// ------------------------------- buttons -------------------------------

/** Action button: sinks the bevel, brightens and gets the yellow border while pressed. */
@Composable
fun PixelButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = Bl.Button,
    icon: Int? = null,
    glyph: Glyph? = null,
    enabled: Boolean = true,
    fontSize: Int = Ts.Item,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .pixelPanel(
                fill = when {
                    !enabled -> fill.mix(Bl.Night, 0.35f)
                    pressed -> Bl.ButtonPressed
                    else -> fill
                },
                raised = !pressed,
                outline = if (pressed) Bl.PressedBorder else Bl.Outline,
            )
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) PixelIcon(icon, 20.dp, Modifier.padding(end = 8.dp), alpha = if (enabled) 1f else 0.5f)
            if (glyph != null) GlyphIcon(glyph, 18.dp, Modifier.padding(end = 8.dp),
                color = if (enabled) Bl.Text else Bl.TextMuted)
            PixelText(text, fontSize, if (enabled) Bl.Text else Bl.TextMuted, maxLines = 1)
        }
    }
}

/** A pressable icon without a box: only the yellow border on touch. */
@Composable
fun IconAction(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier.size(40.dp).pixelClickable(PixelCorner.NONE, enabled, onClick)
            .described(description),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

private fun Modifier.described(description: String): Modifier = semantics { contentDescription = description }

// ------------------------------- fields --------------------------------

/** Sunken text field, in the shade of the launcher's selectors. */
@Composable
fun PixelTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    monospace: Boolean = false,
    error: String? = null,
    enabled: Boolean = true,
    capitalize: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier) {
        PixelText(label, Ts.Small, Bl.TextFaint, Modifier.padding(bottom = 4.dp, start = 2.dp))
        Row(
            Modifier.fillMaxWidth()
                .pixelPanel(fill = if (error != null) Bl.Select.mix(Bl.Bad, 0.25f) else Bl.Select, raised = false)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value, onValueChange, Modifier.weight(1f), enabled = enabled, singleLine = singleLine,
                textStyle = TextStyle(
                    color = if (enabled) Bl.Text else Bl.TextFaint,
                    fontSize = if (monospace) 14.sp else 18.sp,
                    fontFamily = if (monospace) FontFamily.Monospace else PixelFont,
                ),
                cursorBrush = SolidColor(Bl.PressedText),
                keyboardOptions = KeyboardOptions(
                    capitalization = if (capitalize) KeyboardCapitalization.Words else KeyboardCapitalization.None,
                    autoCorrect = false,
                ),
            )
            if (trailing != null) trailing()
        }
        if (error != null) PixelText(error, Ts.Tiny, Bl.Bad, Modifier.padding(top = 4.dp, start = 2.dp))
    }
}

// ------------------------------- dialogs -------------------------------

/** A dialog on the launcher panel: large corner, title in the game font, buttons below. */
@Composable
fun PixelDialog(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth()
                .pixelPanel(fill = Bl.Panel, corner = PixelCorner.LARGE)
                .padding(20.dp),
        ) {
            PixelText(title, Ts.Head)
            Spacer(Modifier.height(14.dp))
            content()
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) { buttons() }
        }
    }
}

class MenuItem(val label: String, val danger: Boolean = false, val enabled: Boolean = true, val onClick: () -> Unit)

/** Dropdown on a pixel panel, below whoever opened it. */
@Composable
fun PixelMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<MenuItem>, alignEnd: Boolean = true, offsetY: Dp = 40.dp) {
    if (!expanded) return
    Popup(
        alignment = if (alignEnd) Alignment.TopEnd else Alignment.TopStart,
        offset = IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { offsetY.roundToPx() }),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(Modifier.widthIn(min = 180.dp, max = 280.dp).pixelPanel(fill = Bl.Panel).padding(4.dp)) {
            items.forEach { item ->
                Box(
                    Modifier.fillMaxWidth().pixelClickable(PixelCorner.NONE, item.enabled) {
                        onDismiss()
                        item.onClick()
                    }.padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    PixelText(item.label, Ts.Body, when {
                        !item.enabled -> Bl.TextMuted
                        item.danger -> Bl.Bad
                        else -> Bl.Text
                    }, maxLines = 1)
                }
            }
        }
    }
}

/** The launcher's switch (ic_switch: OFF on top, ON below, 43x20 each). */
@Composable
fun PixelSwitch(on: Boolean, onChange: (Boolean) -> Unit, description: String, modifier: Modifier = Modifier) {
    Box(
        modifier.pixelClickable(PixelCorner.NONE) { onChange(!on) }.described(description).padding(4.dp),
    ) {
        SpriteFrame(R.drawable.ic_switch, 43, 20, if (on) 1 else 0, 43.dp)
    }
}
