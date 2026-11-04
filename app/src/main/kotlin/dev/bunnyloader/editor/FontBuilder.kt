package dev.bunnyloader.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Bunny Loader's tools/mods/fonts.py on the phone: draws a .ttf/.otf with more pixels than the game
 * uses and fits it to the measurements of the mobile fonts (capital height, baseline and line
 * spacing), so the interface does not move. Writes `<Font>.json` + `<Font>.<page>.png` (blfont/1).
 */
object FontBuilder {
    class Target(val name: String, val cap: Int, val baseline: Int, val lineSpacing: Int, val scale: Int)

    /** Measured from Terraria Mobile 1.4.5.8.6 (tools/tests/fontprobe in Bunny Loader). */
    val TARGETS = listOf(
        Target("Mouse_Text", 14, 16, 24, 3),
        Target("Item_Stack", 12, 15, 21, 3),
        Target("Death_Text", 34, 41, 59, 2),
        Target("Combat_Text", 15, 21, 26, 3),
        Target("Combat_Crit", 19, 26, 32, 3),
    )

    enum class Preset(val ranges: String) {
        LATIN("0020-007E,00A0-024F,2010-2027,2030-203A,20A0-20BF,2122"),
        EUROPE("0020-007E,00A0-052F,2010-2027,2030-203A,20A0-20BF,2122,2190-21FF"),
        ALL(""),
    }

    private const val FORMAT = "blfont/1"
    private const val FIELDS = "code,page,bx,by,bw,bh,cx,cy,cw,ch,leftBearing,width,rightBearing"
    private const val MAX_PAGE = 2048

    class Options(
        val preset: Preset = Preset.LATIN,
        val targets: List<Target> = TARGETS,
        val sizeFactor: Float = 1f,
        val stroke: Int = 0,
    )

    private class Glyph(
        val code: Int,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val advance: Float,
        val visible: Boolean,
    ) {
        var page = 0
        var x = 0
        var y = 0
    }

    class Written(val name: String, val glyphs: Int, val pages: Int)

    /** [write] opens the file with that name in the pack's Fonts folder. [progress] gets the font being made. */
    fun build(
        context: Context,
        fontFile: File,
        options: Options,
        name: String? = null,
        progress: (String) -> Unit = {},
        write: (String) -> OutputStream,
    ): List<Written> {
        val bytes = fontFile.readBytes()
        val available = try {
            FontTables.codePoints(bytes)
        } catch (_: RuntimeException) {
            throw IllegalArgumentException(context.getString(R.string.NotAFont))
        }
        val codes = available
            .filter { it in 0x20 until 0xFFFF && inRanges(it, parseRanges(options.preset.ranges)) }
            .sorted()
        require(codes.isNotEmpty()) { context.getString(R.string.ErrFontNoChars) }
        val typeface = Typeface.createFromFile(fontFile)
        val reference = Paint().apply { this.typeface = typeface; textSize = 1000f }
        val bounds = Rect()
        reference.getTextBounds("H", 0, 1, bounds)
        val capRatio = (-bounds.top / 1000f).takeIf { it > 0f } ?: 0.7f
        return options.targets.map { target ->
            progress(target.name)
            try {
                buildOne(typeface, codes, capRatio, target, options, name ?: target.name, write)
            } catch (_: GlyphTooBig) {
                throw IllegalArgumentException(context.getString(R.string.ErrFontTooBig))
            }
        }
    }

    private fun buildOne(
        typeface: Typeface,
        codes: List<Int>,
        capRatio: Float,
        target: Target,
        options: Options,
        name: String,
        write: (String) -> OutputStream,
    ): Written {
        val scale = target.scale
        val size = target.cap / capRatio * options.sizeFactor
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.typeface = typeface
            textSize = size * scale
            color = android.graphics.Color.WHITE
            if (options.stroke > 0) {
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = options.stroke * 2f
            }
        }
        val bounds = Rect()
        val glyphs = ArrayList<Glyph>(codes.size)
        for (code in codes) {
            val text = code.toChar().toString()
            paint.getTextBounds(text, 0, text.length, bounds)
            val advance = paint.measureText(text) / scale
            if (bounds.isEmpty) {
                glyphs.add(Glyph(code, 0, 0, 0, 0, advance, false))
                continue
            }
            // A margin for antialiasing; the box lands on whole logical pixels, like the original tool.
            val l = bounds.left - 1 - options.stroke
            val t = bounds.top - 1 - options.stroke
            val r = bounds.right + 1 + options.stroke
            val b = bounds.bottom + 1 + options.stroke
            val left = floor(l.toFloat() / scale).toInt()
            val top = floor(t.toFloat() / scale).toInt()
            val width = ceil(r.toFloat() / scale).toInt() - left
            val height = ceil(b.toFloat() / scale).toInt() - top
            glyphs.add(Glyph(code, left, top, width, height, advance, true))
        }
        val placed = glyphs.filter { it.visible }.sortedWith(compareBy({ -it.height }, { -it.width }))
        val packer = FontPacker(MAX_PAGE, scale, 2 * scale)
        for (glyph in placed) {
            val spot = packer.place(glyph.width * scale, glyph.height * scale)
            glyph.page = spot[0]
            glyph.x = spot[1]
            glyph.y = spot[2]
        }
        val pageNames = ArrayList<String>()
        for (page in 0 until packer.pageCount) {
            val here = placed.filter { it.page == page }
            val used = here.maxOfOrNull { it.y + it.height * scale + packer.padding } ?: scale
            val height = minOf(packer.side, ceil(used.toFloat() / scale).toInt() * scale)
            val fileName = "$name.$page.png"
            drawPage(paint, here, scale, packer.side, height, write(fileName))
            pageNames.add(fileName)
        }
        val json = buildString(glyphs.size * 40) {
            append("{\"format\":\"").append(FORMAT).append("\",\"scale\":").append(scale)
            append(",\"lineSpacing\":").append(target.lineSpacing).append(",\"spacing\":0,\"pages\":[")
            append(pageNames.joinToString(",") { "\"$it\"" })
            append("],\"fields\":\"").append(FIELDS).append("\",\"glyphs\":[")
            glyphs.forEachIndexed { i, g ->
                if (i > 0) append(',')
                if (g.visible) {
                    val right = g.advance - g.left - g.width
                    append('[').append(g.code).append(',').append(g.page).append(',').append(g.x / scale).append(',').append(g.y / scale)
                    append(',').append(g.width).append(',').append(g.height).append(",0,").append(target.baseline + g.top)
                    append(',').append(g.width).append(',').append(target.lineSpacing).append(',')
                    append(g.left).append(".0,").append(g.width).append(".0,").append(round(right)).append(']')
                } else {
                    append('[').append(g.code).append(",0,0,0,0,0,0,0,0,").append(target.lineSpacing)
                    append(",0.0,0.0,").append(round(g.advance)).append(']')
                }
            }
            append("]}")
        }
        write("$name.json").use { it.write(json.toByteArray(Charsets.UTF_8)) }
        return Written(name, glyphs.size, pageNames.size)
    }

    private fun round(value: Float): String = String.format(java.util.Locale.ROOT, "%.3f", value)

    private fun drawPage(paint: Paint, glyphs: List<Glyph>, scale: Int, width: Int, height: Int, out: OutputStream) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        for (g in glyphs) {
            canvas.save()
            canvas.clipRect(g.x, g.y, g.x + g.width * scale, g.y + g.height * scale)
            canvas.drawText(g.code.toChar().toString(), (g.x - g.left * scale).toFloat(), (g.y - g.top * scale).toFloat(), paint)
            canvas.restore()
        }
        // White with the coverage as alpha, transparent included: the game filters and mipmaps these.
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        for (i in pixels.indices) pixels[i] = (pixels[i] and 0xFF000000.toInt()) or 0xFFFFFF
        val page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        page.isPremultiplied = false
        page.setPixels(pixels, 0, width, 0, 0, width, height)
        out.use { page.compress(Bitmap.CompressFormat.PNG, 100, it) }
        page.recycle()
    }

    private fun parseRanges(text: String): List<IntRange> = text.split(',').filter { it.isNotBlank() }.map { part ->
        val low = part.substringBefore('-').trim().toInt(16)
        val high = part.substringAfter('-', part).trim().toInt(16)
        low..high
    }

    private fun inRanges(code: Int, ranges: List<IntRange>) = ranges.isEmpty() || ranges.any { code in it }
}

class GlyphTooBig : IllegalArgumentException("A glyph does not fit a font page")

/** Shelves in a page, with positions on multiples of [step]: a logical pixel must land on whole atlas pixels. */
class FontPacker(maxPage: Int, private val step: Int, val padding: Int) {
    val side = maxPage / step * step
    var pageCount = 1
        private set
    private var x = padding
    private var y = padding
    private var shelf = 0

    private fun align(v: Int) = (v + step - 1) / step * step

    /** Returns page, x, y. */
    fun place(width: Int, height: Int): IntArray {
        if (width + 2 * padding > side || height + 2 * padding > side) throw GlyphTooBig()
        if (x + width + padding > side) {
            x = padding
            y = align(y + shelf + padding)
            shelf = 0
        }
        if (y + height + padding > side) {
            pageCount++
            x = padding
            y = padding
            shelf = 0
        }
        val spot = intArrayOf(pageCount - 1, x, y)
        x = align(x + width + padding)
        shelf = maxOf(shelf, height)
        return spot
    }
}

/** Reads the characters a font file holds from its cmap: asking Android would count fallback fonts too. */
object FontTables {
    fun codePoints(font: ByteArray): Set<Int> {
        val data = ByteBuffer.wrap(font)
        var start = 0
        if (font.size > 12 && String(font, 0, 4, Charsets.ISO_8859_1) == "ttcf") start = data.getInt(12)
        val tables = data.getShort(start + 4).toInt() and 0xFFFF
        var cmap = -1
        for (i in 0 until tables) {
            val record = start + 12 + i * 16
            if (String(font, record, 4, Charsets.ISO_8859_1) == "cmap") cmap = data.getInt(record + 8)
        }
        require(cmap >= 0) { "This file is not a font" }
        val result = HashSet<Int>(1024)
        val subtables = data.getShort(cmap + 2).toInt() and 0xFFFF
        for (i in 0 until subtables) {
            val platform = data.getShort(cmap + 4 + i * 8).toInt() and 0xFFFF
            val encoding = data.getShort(cmap + 6 + i * 8).toInt() and 0xFFFF
            val offset = cmap + data.getInt(cmap + 8 + i * 8)
            val unicode = platform == 0 || (platform == 3 && (encoding == 1 || encoding == 10))
            if (!unicode) continue
            when (data.getShort(offset).toInt() and 0xFFFF) {
                4 -> readFormat4(data, offset, result)
                12 -> readFormat12(data, offset, result)
            }
        }
        return result
    }

    private fun readFormat4(data: ByteBuffer, at: Int, out: MutableSet<Int>) {
        val segments = (data.getShort(at + 6).toInt() and 0xFFFF) / 2
        val ends = at + 14
        val starts = ends + segments * 2 + 2
        val deltas = starts + segments * 2
        val offsets = deltas + segments * 2
        for (s in 0 until segments) {
            val first = data.getShort(starts + s * 2).toInt() and 0xFFFF
            val last = data.getShort(ends + s * 2).toInt() and 0xFFFF
            val delta = data.getShort(deltas + s * 2).toInt() and 0xFFFF
            val rangeOffset = data.getShort(offsets + s * 2).toInt() and 0xFFFF
            for (code in first..last) {
                if (code == 0xFFFF) continue
                val glyph = if (rangeOffset == 0) (code + delta) and 0xFFFF else {
                    val position = offsets + s * 2 + rangeOffset + (code - first) * 2
                    if (position + 2 > data.limit()) 0
                    else (data.getShort(position).toInt() and 0xFFFF).let { if (it == 0) 0 else (it + delta) and 0xFFFF }
                }
                if (glyph != 0) out.add(code)
            }
        }
    }

    private fun readFormat12(data: ByteBuffer, at: Int, out: MutableSet<Int>) {
        val groups = data.getInt(at + 12)
        for (g in 0 until groups) {
            val record = at + 16 + g * 12
            val first = data.getInt(record)
            val last = data.getInt(record + 4)
            if (first < 0 || last < first || last - first > 0x20000) continue
            for (code in first..last) out.add(code)
        }
    }
}
