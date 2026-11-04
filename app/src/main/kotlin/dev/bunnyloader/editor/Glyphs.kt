package dev.bunnyloader.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * Icons drawn pixel by pixel, for what the launcher art lacks (save, undo, new
 * file...). `#` is the color, `+` the accent; the black outline around them is
 * computed, like Terraria's text.
 */
enum class Glyph(vararg rows: String) {
    SAVE(
        "#########.",
        "#.#####.##",
        "#.#####..#",
        "#.#####..#",
        "#........#",
        "#.######.#",
        "#.#++++#.#",
        "#.######.#",
        "##########",
    ),
    UNDO(
        "...#......",
        "..##......",
        ".#######..",
        "..##....#.",
        "...#.....#",
        ".........#",
        "........#.",
        "...#####..",
    ),
    REDO(
        "......#...",
        "......##..",
        "..#######.",
        ".#....##..",
        "#.....#...",
        "#.........",
        ".#........",
        "..#####...",
    ),
    MENU(
        "#########",
        ".........",
        ".........",
        "#########",
        ".........",
        ".........",
        "#########",
    ),
    MORE(
        "##",
        "##",
        "..",
        "..",
        "##",
        "##",
        "..",
        "..",
        "##",
        "##",
    ),
    NEW_FILE(
        "######...",
        "#....##..",
        "#....###.",
        "#......#.",
        "#...+..#.",
        "#..+++.#.",
        "#...+..#.",
        "########.",
    ),
    NEW_FOLDER(
        "####......",
        "#..######.",
        "#........#",
        "#...+....#",
        "#..+++...#",
        "#...+....#",
        "##########",
    ),
    IMPORT(
        "....#....",
        "....#....",
        "....#....",
        "..#####..",
        "...###...",
        "....#....",
        "#.......#",
        "#.......#",
        "#########",
    ),
    COLLAPSE(
        "#########",
        "#.......#",
        "#.......#",
        "#.#####.#",
        "#.......#",
        "#.......#",
        "#########",
    ),
    CLOSE(
        "#.....#",
        ".#...#.",
        "..#.#..",
        "...#...",
        "..#.#..",
        ".#...#.",
        "#.....#",
    ),
    CHEVRON_RIGHT(
        "#...",
        "##..",
        "###.",
        "##..",
        "#...",
    ),
    CHEVRON_DOWN(
        "#####",
        ".###.",
        "..#..",
    ),
    BACK(
        "...#....",
        "..##....",
        ".#######",
        "########",
        ".#######",
        "..##....",
        "...#....",
    ),
    FILE(
        "#####..",
        "#...##.",
        "#....##",
        "#.....#",
        "#.....#",
        "#.....#",
        "#######",
    ),
    CHECK(
        ".......#",
        "......##",
        "#....##.",
        "##..##..",
        ".####...",
        "..##....",
    ),
    WARNING(
        "##",
        "##",
        "##",
        "##",
        "..",
        "##",
    ),
    IMAGE(
        "#########",
        "#.......#",
        "#.++....#",
        "#.++..#.#",
        "#....###.",
        "#.#.#####",
        "#########",
    ),
    PENCIL(
        ".......##",
        "......#++",
        ".....####",
        "....####.",
        "...####..",
        "..####...",
        ".####....",
        "#.##.....",
        "##.......",
    ),
    ERASER(
        ".....###.",
        "....#####",
        "...######",
        "..#++###.",
        ".#+++##..",
        "#+++##...",
        ".#+##....",
        "..##.....",
    ),
    FILL(
        "...##....",
        "..#..#...",
        "..#..##..",
        ".#.#.###.",
        "#...#.###",
        "#......#+",
        ".#....#.+",
        "..#..#..+",
        "...##...+",
    ),
    PICKER(
        "......##.",
        ".....####",
        "....#####",
        "...#.###.",
        "..#.#.#..",
        ".#.#.....",
        "#.#......",
        "##.......",
    ),
    LINE(
        ".......##",
        "......##.",
        ".....##..",
        "....##...",
        "...##....",
        "..##.....",
        ".##......",
        "##.......",
    ),
    RECT(
        "#########",
        "#.......#",
        "#.......#",
        "#.......#",
        "#.......#",
        "#########",
    ),
    RECT_FILLED(
        "#########",
        "#+++++++#",
        "#+++++++#",
        "#+++++++#",
        "#+++++++#",
        "#########",
    ),
    GRID(
        "#########",
        "#..#..#.#",
        "#########",
        "#..#..#.#",
        "#########",
        "#..#..#.#",
        "#########",
    ),
    MIRROR(
        "...#.#...",
        ".#.#.#.#.",
        "##.#.#.##",
        "###...###",
        "##.#.#.##",
        ".#.#.#.#.",
        "...#.#...",
    ),
    MOVE(
        "....#....",
        "...###...",
        "....#....",
        ".#..#..#.",
        "#########",
        ".#..#..#.",
        "....#....",
        "...###...",
        "....#....",
    ),
    SEARCH(
        ".####....",
        "#....#...",
        "#....#...",
        "#....#...",
        ".####.#..",
        "......##.",
        ".......##",
    ),
    EXPORT(
        "....#....",
        "...###...",
        "..#####..",
        "....#....",
        "....#....",
        "#.......#",
        "#.......#",
        "#########",
    ),
    GLOBE(
        "..#####..",
        ".#..#..#.",
        "#..#.#..#",
        "#########",
        "#..#.#..#",
        ".#..#..#.",
        "..#####..",
    ),
    FONT(
        "...###...",
        "...###...",
        "..##.##..",
        "..##.##..",
        ".#######.",
        ".##...##.",
        "###...###",
    ),
    PLUS(
        "...#...",
        "...#...",
        "...#...",
        "#######",
        "...#...",
        "...#...",
        "...#...",
    ),
    CODE(
        "..#...#..",
        ".#.....#.",
        "#.......#",
        ".#.....#.",
        "..#...#..",
    );

    internal val pattern: List<String> = rows.toList()
    internal val width = pattern.maxOf { it.length }
    internal val height = pattern.size
}

/**
 * Draws the glyph centered in a [size] square, with whole pixels: the pixel size
 * is rounded down so it does not blur.
 */
@Composable
fun GlyphIcon(glyph: Glyph, size: Dp, modifier: Modifier = Modifier, color: Color = Bl.Text, accent: Color = Bl.PressedText) {
    Canvas(modifier.size(size)) {
        val cells = maxOf(glyph.width, glyph.height) + 2
        val unit = kotlin.math.floor(this.size.width / cells).coerceAtLeast(1f)
        val ox = ((this.size.width - glyph.width * unit) / 2f).let { kotlin.math.floor(it) }
        val oy = ((this.size.height - glyph.height * unit) / 2f).let { kotlin.math.floor(it) }
        fun filled(x: Int, y: Int) = y in 0 until glyph.height && x in 0 until glyph.pattern[y].length &&
            glyph.pattern[y][x] != '.'
        val cell = Size(unit, unit)
        for (y in -1..glyph.height) for (x in -1..glyph.width) {
            if (filled(x, y)) continue
            if (filled(x - 1, y) || filled(x + 1, y) || filled(x, y - 1) || filled(x, y + 1)) {
                drawRect(Bl.Ink, Offset(ox + x * unit, oy + y * unit), cell)
            }
        }
        for (y in 0 until glyph.height) for (x in 0 until glyph.pattern[y].length) {
            val c = glyph.pattern[y][x]
            if (c == '.') continue
            drawRect(if (c == '+') accent else color, Offset(ox + x * unit, oy + y * unit), cell)
        }
    }
}
