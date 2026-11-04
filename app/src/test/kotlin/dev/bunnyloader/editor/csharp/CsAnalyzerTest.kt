package dev.bunnyloader.editor.csharp

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** Runs against the real API tree of the assets folder. `|` marks the cursor. */
class CsAnalyzerTest {
    companion object {
        private lateinit var tree: ApiTree
        private lateinit var analyzer: CsAnalyzer

        @JvmStatic
        @BeforeClass
        fun load() {
            val folder = File("src/main/assets/csharp")
            tree = ApiTree.load { name -> File(folder, name).takeIf { it.isFile }?.inputStream() }
            analyzer = CsAnalyzer(tree)
        }

        private val HEADER = """
            using Terraria;
            using Terraria.ID;
            using Terraria.ModLoader;
            using Microsoft.Xna.Framework;
            using System.Collections.Generic;

            namespace MyMod.Content.Items;

            public class Sword : ModItem
            {
                public int Charges;
                private Player owner;

                public int Twice(int value) => value * 2;

        """.trimIndent() + "\n"
    }

    private fun complete(code: String): CompletionResult {
        val offset = code.indexOf('|')
        return analyzer.complete(code.removeRange(offset, offset + 1), offset)
    }

    private fun labels(code: String) = complete(code).items.map { it.label }

    private fun inMethod(body: String) = HEADER + "    public override void SetDefaults()\n    {\n        $body\n    }\n}\n"

    @Test fun treeHasTheGameAndTheLoader() {
        assertTrue(tree.typeCount > 15000)
        assertNotNull(tree.type("Terraria.Item"))
        assertNotNull(tree.type("Terraria.ModLoader.ModItem"))
        assertNotNull(tree.type("Terraria.ID.ItemID"))
        assertTrue(tree.type("Terraria.ID.ItemID")!!.members.any { it.name == "CopperPickaxe" && it.value == "3509" || it.name == "CopperPickaxe" })
    }

    @Test fun inheritedPropertyGivesItsMembersAndStatics() {
        val items = labels(inMethod("Item.|"))
        assertTrue("damage" in items)
        assertTrue("width" in items)
        assertTrue("buyPrice" in items) // `Item Item`: the static members count too.
        assertTrue("CommonMaxStack" in items)
    }

    @Test fun typedPrefixFilters() {
        val items = labels(inMethod("Item.dam|"))
        assertEquals("damage", items.first())
        assertFalse("width" in items)
    }

    @Test fun constantsOfAnIdClass() {
        val arrow = complete(inMethod("Item.shoot = ProjectileID.Wood|")).items.first { it.label == "WoodenArrowFriendly" }
        assertTrue(arrow.detail.startsWith("= "))
    }

    @Test fun chainThroughStaticsAndCalls() {
        assertTrue("statLife" in labels(inMethod("Main.LocalPlayer.|")))
        assertTrue("Flat" in labels(inMethod("Main.LocalPlayer.GetDamage(DamageClass.Melee).|")))
    }

    @Test fun indexerOnGameArray() {
        assertTrue("statLife" in labels(inMethod("Main.player[0].|")))
    }

    @Test fun localsAndInference() {
        val body = "var player = Main.LocalPlayer;\n        Vector2 where = player.Center;\n        where.|"
        val items = labels(inMethod(body))
        assertTrue("X" in items)
        assertTrue("Length" in items)
        assertTrue("player" in labels(inMethod("var player = Main.LocalPlayer;\n        pla|")))
    }

    @Test fun genericMethodBindsItsType() {
        assertTrue("Twice" !in labels(inMethod("Main.LocalPlayer.GetModPlayer<ExamplePlayer>().|")))
        val code = HEADER + "    public override void SetDefaults()\n    {\n        var m = Main.LocalPlayer.GetModPlayer<Extra>();\n        m.|\n    }\n}\n" +
            "public class Extra : ModPlayer { public int Level; }\n"
        assertTrue("Level" in labels(code))
        assertTrue("Player" in labels(code))
    }

    @Test fun listAndForEachElements() {
        val body = "var list = new List<Item>();\n        foreach (var one in list)\n        {\n            one.|\n        }"
        assertTrue("damage" in labels(inMethod(body)))
        assertTrue("Add" in labels(inMethod("var list = new List<Item>();\n        list.|")))
    }

    @Test fun ownMembersWithoutQualifier() {
        val items = labels(inMethod("Cha|"))
        assertEquals("Charges", items.first())
        assertTrue("Twice" in labels(inMethod("Twi|")))
    }

    @Test fun parametersAreLocals() {
        val code = HEADER + "    public override void UpdateInventory(Player player)\n    {\n        player.|\n    }\n}\n"
        assertTrue("statLife" in labels(code))
    }

    @Test fun overrideListsVirtualsWithFullSignature() {
        val code = HEADER + "    public override Sho|\n}\n"
        val shoot = complete(code).items.first { it.label.startsWith("Shoot(") }
        assertTrue(shoot.insert, shoot.insert.startsWith("public override bool Shoot(Player player, EntitySource_ItemUse_WithAmmo source"))
        assertTrue(shoot.usings.toString(), "Terraria.DataStructures" in shoot.usings)
        assertTrue(shoot.insert, "return base.Shoot(player, source, position, velocity, type, damage, knockback);" in shoot.insert)
    }

    @Test fun overrideSkipsWhatIsAlreadyOverridden() {
        val code = HEADER + "    public override void SetDefaults() { }\n    public override |\n}\n"
        val items = labels(code)
        assertTrue(items.none { it.startsWith("SetDefaults(") })
        assertTrue(items.any { it.startsWith("AddRecipes(") })
    }

    @Test fun baseListAndNewOfferTypes() {
        assertTrue(labels("using Terraria.ModLoader;\npublic class A : Mod|").first().startsWith("Mod"))
        val made = complete(inMethod("var r = new Rect|")).items.first { it.label == "Rectangle" }
        assertTrue(made.insert, made.insert.endsWith("(\$0)"))
    }

    @Test fun unimportedTypeBringsItsUsing() {
        val code = "using Terraria.ModLoader;\npublic class A : ModItem\n{\n    void F()\n    {\n        UnifiedRan|\n    }\n}\n"
        val item = complete(code).items.first { it.label == "UnifiedRandom" }
        assertEquals(listOf("Terraria.Utilities"), item.usings)
    }

    @Test fun theListShowsNamesWithoutNamespaces() {
        val items = complete(inMethod("BiomeLoa|")).items
        val loader = items.first { it.label == "BiomeLoader" }
        assertEquals("", loader.detail)
        assertEquals(items.size, items.distinctBy { it.label }.size)
        assertEquals("int", complete(inMethod("Item.dam|")).items.first().detail)
    }

    @Test fun typedLettersAreMarked() {
        fun marked(label: String, typed: String) =
            CsAnalyzer.matchedLetters(label, typed).withIndex().filter { it.value }.joinToString("") { label[it.index].toString() }
        assertEquals("Dam", marked("Damage", "dam"))
        assertEquals("MP", marked("ModPlayer", "MP"))
        assertEquals("ModPl", marked("ModPlayer", "ModPl"))
        assertEquals("Loader", marked("BiomeLoader", "Loader"))
        assertEquals("", marked("Damage", "xyz"))
    }

    @Test fun camelHumpsFindTypes() {
        assertTrue("ModPlayer" in labels(inMethod("MP|")))
    }

    @Test fun usingDirectiveListsNamespaces() {
        assertTrue("ModLoader" in labels("using Terraria.|"))
        assertTrue("Terraria" in labels("using Terr|"))
    }

    @Test fun quietInsideCommentsAndStrings() {
        assertTrue(labels(inMethod("// Item.|")).isEmpty())
        assertTrue(labels(inMethod("var s = \"Item.|\";")).isEmpty())
    }

    @Test fun signatureFollowsTheArgument() {
        val code = inMethod("Item.buyPrice(1, |)")
        val offset = code.indexOf('|')
        val info = analyzer.signature(code.removeRange(offset, offset + 1), offset)
        assertNotNull(info)
        assertEquals(1, info!!.argument)
        assertEquals("int gold=0", info.overloads[info.active].parameters[1])
    }

    @Test fun signatureOfConstructor() {
        val code = inMethod("var v = new Vector2(1f, |)")
        val offset = code.indexOf('|')
        val info = analyzer.signature(code.removeRange(offset, offset + 1), offset)
        assertNotNull(info)
        assertTrue(info!!.overloads.any { it.parameters.size == 2 })
    }

    @Test fun projectTypesAcrossFiles() {
        val project = ProjectIndex()
        project.update("Common/Stats.cs", "namespace MyMod.Common;\npublic static class Stats { public static int Total; public static void Reset() { } }\n")
        val withProject = CsAnalyzer(tree, project)
        val code = "using MyMod.Common;\nnamespace MyMod;\npublic class A { void F() { Stats.| } }\n"
        val offset = code.indexOf('|')
        val items = withProject.complete(code.removeRange(offset, offset + 1), offset).items.map { it.label }
        assertTrue("Total" in items)
        assertTrue("Reset" in items)
    }

    @Test fun brokenCodeNeverThrows() {
        for (code in listOf("|", "class |", "class A : |", "public class { void (|", "namespace |", "using |", "class A { void F() { x.| ",
                "class A { int[] b = new int[|", "class A { void F() { foreach (var |", "\"|", "/*|", "class A { void F() { a.b(c.d(|", "class A<T> where T : |")) {
            complete(code)
        }
    }

    @Test fun completionIsFastEnoughForAKeystroke() {
        val code = inMethod("Item.dam|")
        repeat(3) { complete(code) }
        val start = System.nanoTime()
        repeat(20) { complete(inMethod("Unif|")); complete(code) }
        val perCall = (System.nanoTime() - start) / 40 / 1_000_000
        println("completion: $perCall ms per call")
        assertTrue("took $perCall ms", perCall < 100)
    }

    @Test fun reindentFollowsBraces() {
        val out = CsFormatter.reindent("class A\n{\nvoid F()\n{\nif (x)\n{\ny();\n}\n}\n}\n")
        assertEquals("class A\n{\n    void F()\n    {\n        if (x)\n        {\n            y();\n        }\n    }\n}\n", out)
    }

    @Test fun reindentKeepsMultilineStrings() {
        val text = "class A\n{\nstring s = @\"one\n  two\";\n}\n"
        assertEquals("class A\n{\n    string s = @\"one\n  two\";\n}\n", CsFormatter.reindent(text))
    }
}
