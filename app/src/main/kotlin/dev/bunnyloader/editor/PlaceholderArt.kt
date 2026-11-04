package dev.bunnyloader.editor

/**
 * The picture a template starts with, so a new class loads with something on screen instead of a
 * missing-texture error: a flat tile with a darker border, ready to be painted over.
 */
object PlaceholderArt {
    fun png(width: Int, height: Int, argb: Int): ByteArray {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val dark = (0xFF shl 24) or ((r * 3 / 5) shl 16) or ((g * 3 / 5) shl 8) or (b * 3 / 5)
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if (x == 0 || y == 0 || x == width - 1 || y == height - 1) dark else argb
        }
        return encodePng(width, height, pixels)
    }
}
