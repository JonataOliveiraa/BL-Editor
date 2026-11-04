package dev.bunnyloader.editor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The pixels of the texture editor and everything done to them, with no drawing code: colors are
 * plain unpremultiplied ARGB, so what is saved is exactly what was painted. Undo keeps whole
 * pictures, as many as fit in [MAX_UNDO_PIXELS]: many steps on a sprite, fewer on a big sheet.
 */
class PixelCanvas(width: Int, height: Int, pixels: IntArray? = null) {
    var width = width
        private set
    var height = height
        private set
    var pixels: IntArray = pixels?.copyOf() ?: IntArray(width * height)
        private set
    /** Goes up on every change: the screen redraws from it. */
    var revision = 0
        private set

    private class Step(val width: Int, val height: Int, val pixels: IntArray)

    private val undo = ArrayDeque<Step>()
    private val redo = ArrayDeque<Step>()

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    private fun snapshot() = Step(width, height, pixels.copyOf())

    /** Marks the start of something undoable: one stroke, one fill. */
    fun beginStep() {
        undo.addLast(snapshot())
        var total = undo.sumOf { it.pixels.size.toLong() }
        while (undo.size > 1 && total > MAX_UNDO_PIXELS) total -= undo.removeFirst().pixels.size
        redo.clear()
    }

    fun undo() {
        val step = undo.removeLastOrNull() ?: return
        redo.addLast(snapshot())
        restore(step)
    }

    fun redo() {
        val step = redo.removeLastOrNull() ?: return
        undo.addLast(snapshot())
        restore(step)
    }

    private fun restore(step: Step) {
        width = step.width
        height = step.height
        pixels = step.pixels
        revision++
    }

    fun inside(x: Int, y: Int) = x in 0 until width && y in 0 until height

    fun get(x: Int, y: Int): Int = if (inside(x, y)) pixels[y * width + x] else 0

    fun set(x: Int, y: Int, color: Int) {
        if (!inside(x, y)) return
        pixels[y * width + x] = color
        revision++
    }

    /** A dot of [size] pixels, with its mirror across the vertical middle if [mirror]. */
    fun dot(x: Int, y: Int, color: Int, size: Int = 1, mirror: Boolean = false) {
        val low = (size - 1) / 2
        for (dy in -low until size - low) for (dx in -low until size - low) {
            set(x + dx, y + dy, color)
            if (mirror) set(width - 1 - (x + dx), y + dy, color)
        }
    }

    fun line(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, size: Int = 1, mirror: Boolean = false) {
        var x = x0
        var y = y0
        val dx = abs(x1 - x0)
        val dy = -abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var error = dx + dy
        while (true) {
            dot(x, y, color, size, mirror)
            if (x == x1 && y == y1) break
            val twice = 2 * error
            if (twice >= dy) {
                error += dy
                x += sx
            }
            if (twice <= dx) {
                error += dx
                y += sy
            }
        }
    }

    fun rectangle(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, filled: Boolean, mirror: Boolean = false) {
        val left = min(x0, x1)
        val right = max(x0, x1)
        val top = min(y0, y1)
        val bottom = max(y0, y1)
        for (y in top..bottom) for (x in left..right) {
            if (filled || x == left || x == right || y == top || y == bottom) dot(x, y, color, 1, mirror)
        }
    }

    /** Fills the area of the same color around a pixel. */
    fun fill(x: Int, y: Int, color: Int) {
        if (!inside(x, y)) return
        val target = get(x, y)
        if (target == color) return
        val stack = IntArray(width * height + 1)
        var top = 0
        stack[top++] = y * width + x
        pixels[y * width + x] = color
        while (top > 0) {
            val at = stack[--top]
            val px = at % width
            val py = at / width
            if (px > 0 && pixels[at - 1] == target) { pixels[at - 1] = color; stack[top++] = at - 1 }
            if (px < width - 1 && pixels[at + 1] == target) { pixels[at + 1] = color; stack[top++] = at + 1 }
            if (py > 0 && pixels[at - width] == target) { pixels[at - width] = color; stack[top++] = at - width }
            if (py < height - 1 && pixels[at + width] == target) { pixels[at + width] = color; stack[top++] = at + width }
        }
        revision++
    }

    /** Changes the picture size, keeping what fits, anchored at the top left. */
    fun resize(newWidth: Int, newHeight: Int) {
        require(newWidth in 1..MAX_SIDE && newHeight in 1..MAX_SIDE)
        val out = IntArray(newWidth * newHeight)
        for (y in 0 until min(height, newHeight)) {
            System.arraycopy(pixels, y * width, out, y * newWidth, min(width, newWidth))
        }
        width = newWidth
        height = newHeight
        pixels = out
        revision++
    }

    fun flipHorizontal() {
        for (y in 0 until height) for (x in 0 until width / 2) {
            val a = y * width + x
            val b = y * width + width - 1 - x
            val keep = pixels[a]
            pixels[a] = pixels[b]
            pixels[b] = keep
        }
        revision++
    }

    fun flipVertical() {
        for (y in 0 until height / 2) for (x in 0 until width) {
            val a = y * width + x
            val b = (height - 1 - y) * width + x
            val keep = pixels[a]
            pixels[a] = pixels[b]
            pixels[b] = keep
        }
        revision++
    }

    fun clear() {
        pixels.fill(0)
        revision++
    }

    /** Replaces every pixel with the given ones, as an import does; undoable if a step was begun. */
    fun replace(newWidth: Int, newHeight: Int, newPixels: IntArray) {
        width = newWidth
        height = newHeight
        pixels = newPixels.copyOf()
        revision++
    }

    companion object {
        const val MAX_SIDE = 2048
        private const val MAX_UNDO_PIXELS = 12_000_000L
    }
}

/** Where a texture of a pack goes, by the rules of Images/ in Bunny Loader's docs. */
object TexturePaths {
    class Category(val id: String, val title: Int, val prefix: String, val idClass: String?, val defaultWidth: Int, val defaultHeight: Int)

    val CATEGORIES = listOf(
        Category("item", R.string.CatItem, "Item_", "Terraria.ID.ItemID", 32, 32),
        Category("npc", R.string.CatNpc, "NPC_", "Terraria.ID.NPCID", 32, 32),
        Category("projectile", R.string.CatProjectile, "Projectile_", "Terraria.ID.ProjectileID", 16, 16),
        Category("tile", R.string.CatTile, "Tiles_", "Terraria.ID.TileID", 288, 270),
        Category("wall", R.string.CatWall, "Wall_", "Terraria.ID.WallID", 432, 270),
        Category("buff", R.string.CatBuff, "Buff_", "Terraria.ID.BuffID", 32, 32),
        Category("gore", R.string.CatGore, "Gore_", null, 32, 32),
        Category("head", R.string.CatArmorHead, "Armor_Head_", null, 40, 1120),
        Category("body", R.string.CatArmorBody, "Armor_Body_", null, 40, 1120),
        Category("legs", R.string.CatArmorLegs, "Armor_Legs_", null, 40, 1120),
        Category("custom", R.string.CatCustom, "", null, 32, 32),
    )

    /** `UI/Foo.png` and `Item_1` are both fine; anything that climbs out of Images/ is not. */
    fun clean(path: String): String? {
        val parts = path.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it == "." || it == ".." || it.startsWith('.') }) return null
        if (parts.any { part -> part.any { it == ':' || it.isISOControl() } }) return null
        val joined = parts.joinToString("/")
        return if (joined.lowercase().endsWith(".png")) joined else "$joined.png"
    }
}
