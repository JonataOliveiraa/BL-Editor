package dev.bunnyloader.editor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorLogicTest {
    @Test fun lineAndUndo() {
        val canvas = PixelCanvas(8, 8)
        canvas.beginStep()
        canvas.line(0, 0, 7, 7, RED)
        for (i in 0..7) assertEquals(RED, canvas.get(i, i))
        assertEquals(0, canvas.get(1, 0))
        canvas.undo()
        assertEquals(0, canvas.get(3, 3))
        assertTrue(canvas.canRedo)
        canvas.redo()
        assertEquals(RED, canvas.get(3, 3))
    }

    @Test fun mirrorPaintsBothSides() {
        val canvas = PixelCanvas(8, 4)
        canvas.dot(1, 2, RED, mirror = true)
        assertEquals(RED, canvas.get(1, 2))
        assertEquals(RED, canvas.get(6, 2))
    }

    @Test fun fillStopsAtTheBorder() {
        val canvas = PixelCanvas(5, 5)
        canvas.rectangle(1, 1, 3, 3, RED, filled = false)
        canvas.fill(2, 2, BLUE)
        assertEquals(BLUE, canvas.get(2, 2))
        assertEquals(RED, canvas.get(1, 1))
        assertEquals(0, canvas.get(0, 0))
    }

    @Test fun fillOfAWholeBigPictureFitsItsStack() {
        val canvas = PixelCanvas(512, 512)
        canvas.fill(0, 0, RED)
        assertTrue(canvas.pixels.all { it == RED })
    }

    @Test fun resizeKeepsWhatFits() {
        val canvas = PixelCanvas(4, 4)
        canvas.set(1, 1, RED)
        canvas.set(3, 3, BLUE)
        canvas.resize(2, 6)
        assertEquals(RED, canvas.get(1, 1))
        assertEquals(2, canvas.width)
        assertEquals(6, canvas.height)
        canvas.undo() // Nothing began a step: stays as it is.
        assertEquals(2, canvas.width)
    }

    @Test fun packerUsesWholeLogicalPixelsAndOpensPages() {
        val packer = FontPacker(2048, 3, 6)
        assertEquals(2046, packer.side)
        repeat(4000) {
            val spot = packer.place(45, 60)
            assertEquals(0, spot[1] % 3)
            assertEquals(0, spot[2] % 3)
            assertTrue(spot[1] + 45 <= 2046 && spot[2] + 60 <= 2046)
        }
        assertTrue(packer.pageCount > 1)
    }

    @Test fun cmapOfARealFont() {
        val font = File("src/fontFallback/res/font/bunny.ttf").readBytes()
        val codes = FontTables.codePoints(font)
        assertTrue('A'.code in codes)
        assertTrue('é'.code in codes)
        assertFalse(0x4E00 in codes)
    }

    @Test fun namespacesComeFromFolders() {
        assertEquals("MyMod.Content.Items", Templates.namespaceFor("MyMod", "Content/Items"))
        assertEquals("MyMod", Templates.namespaceFor("MyMod", ""))
        assertEquals("MyMod.Weird_Name", Templates.namespaceFor("MyMod", "Weird_Name!"))
        assertEquals("_2Hot", Templates.identifier("2-Hot"))
        assertEquals("My Fire Sword", Templates.words("MyFireSword"))
    }

    @Test fun localizationAddsWithoutTouchingWhatIsThere() {
        val first = Templates.addLocalization("", listOf(Templates.Entry("Items", "Rock", listOf("DisplayName" to "Rock", "Tooltip" to ""))))
        assertEquals("Items: {\n\tRock: {\n\t\tDisplayName: Rock\n\t\tTooltip: \"\"\n\t}\n}\n", first)
        val second = Templates.addLocalization(first, listOf(
            Templates.Entry("Items", "Rock", listOf("DisplayName" to "Other")),
            Templates.Entry("Items", "Gem", listOf("DisplayName" to "Gem")),
            Templates.Entry("Buffs", "Hot", listOf("DisplayName" to "Hot")),
        ))
        assertTrue("Other" !in second)
        assertTrue("\tGem: {" in second)
        assertTrue("Buffs: {\n\tHot: {" in second)
        assertEquals(1, Regex("(?m)^Items:").findAll(second).count())
    }

    @Test fun texturePathsStayInsideImages() {
        assertEquals("Item_1.png", TexturePaths.clean("Item_1"))
        assertEquals("UI/Minimap/Frame.png", TexturePaths.clean("UI\\Minimap\\Frame.PNG".replace(".PNG", ".png")))
        assertNull(TexturePaths.clean("../secret"))
        assertNull(TexturePaths.clean(" "))
    }

    /** Writes every template to a folder; tools/check-templates compiles them against BunnyLoader.dll. */
    @Test fun templatesAreWrittenForTheCompiler() {
        val folder = File("build/templates-check").apply { deleteRecursively(); mkdirs() }
        for (template in Templates.ALL) {
            val name = "Check" + template.id.replaceFirstChar(Char::uppercaseChar)
            val output = template.build("CheckMod.Content.${template.id.replaceFirstChar(Char::uppercaseChar)}s", name, Templates.words(name))
            File(folder, "$name.cs").writeText(output.code)
            assertTrue("class $name" in output.code)
        }
        assertEquals(Templates.ALL.size, folder.listFiles()!!.size)
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
