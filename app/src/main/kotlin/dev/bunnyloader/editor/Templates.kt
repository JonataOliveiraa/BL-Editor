package dev.bunnyloader.editor

/**
 * What a new file can start as. Each one writes a class that compiles against Bunny Loader
 * (tools/check-templates runs them through the compiler), names the textures it expects next to
 * the code and the localization entries that give it a name in the game.
 */
object Templates {
    class Art(val suffix: String, val width: Int, val height: Int, val argb: Int)

    class Entry(val section: String, val key: String, val fields: List<Pair<String, String>>)

    class Output(val code: String, val art: List<Art>, val entries: List<Entry>)

    class Template(
        val id: String,
        val title: Int,
        val description: Int,
        val build: (namespace: String, name: String, words: String) -> Output,
    )

    private val ITEM_ART = 0xFF9AA7D9.toInt()
    private val SHOT_ART = 0xFFF9E141.toInt()
    private val NPC_ART = 0xFF74C9A0.toInt()
    private val BUFF_ART = 0xFFB98BFF.toInt()
    private val TILE_ART = 0xFF8B6A4A.toInt()

    private fun itemEntry(name: String, words: String, tooltip: String = "") =
        Entry("Items", name, listOf("DisplayName" to words, "Tooltip" to tooltip))

    val ALL: List<Template> = listOf(
        Template("class", R.string.TplClass, R.string.TplClassHint) { ns, name, _ ->
            Output("""
                |namespace $ns;
                |
                |public class $name
                |{
                |}
                |""".trimMargin(), emptyList(), emptyList())
        },
        Template("item", R.string.TplItem, R.string.TplItemHint) { ns, name, words ->
            Output("""
                |using Terraria;
                |using Terraria.ID;
                |using Terraria.ModLoader;
                |
                |namespace $ns;
                |
                |public class $name : ModItem
                |{
                |    public override void SetDefaults()
                |    {
                |        Item.width = 32;
                |        Item.height = 32;
                |        Item.maxStack = Item.CommonMaxStack;
                |        Item.value = Item.buyPrice(silver: 1);
                |        Item.rare = ItemRarityID.White;
                |    }
                |
                |    public override void AddRecipes()
                |    {
                |        CreateRecipe()
                |            .AddIngredient(ItemID.DirtBlock, 10)
                |            .AddTile(TileID.WorkBenches)
                |            .Register();
                |    }
                |}
                |""".trimMargin(), listOf(Art("", 32, 32, ITEM_ART)), listOf(itemEntry(name, words)))
        },
        Template("sword", R.string.TplSword, R.string.TplSwordHint) { ns, name, words ->
            Output("""
                |using Terraria;
                |using Terraria.ID;
                |using Terraria.ModLoader;
                |
                |namespace $ns;
                |
                |public class $name : ModItem
                |{
                |    public override void SetDefaults()
                |    {
                |        Item.width = 40;
                |        Item.height = 40;
                |
                |        Item.useStyle = ItemUseStyleID.Swing;
                |        Item.useTime = 20;
                |        Item.useAnimation = 20;
                |        Item.autoReuse = true;
                |
                |        Item.DamageType = DamageClass.Melee;
                |        Item.damage = 50;
                |        Item.knockBack = 6;
                |
                |        Item.value = Item.buyPrice(silver: 1);
                |        Item.rare = ItemRarityID.Blue;
                |        Item.UseSound = SoundID.Item1;
                |    }
                |
                |    public override void AddRecipes()
                |    {
                |        CreateRecipe()
                |            .AddIngredient(ItemID.DirtBlock, 10)
                |            .AddTile(TileID.WorkBenches)
                |            .Register();
                |    }
                |}
                |""".trimMargin(), listOf(Art("", 40, 40, ITEM_ART)), listOf(itemEntry(name, words)))
        },
        Template("shooter", R.string.TplShooter, R.string.TplShooterHint) { ns, name, words ->
            Output("""
                |using Microsoft.Xna.Framework;
                |using Terraria;
                |using Terraria.ID;
                |using Terraria.ModLoader;
                |
                |namespace $ns;
                |
                |public class $name : ModItem
                |{
                |    public override void SetDefaults()
                |    {
                |        Item.width = 32;
                |        Item.height = 32;
                |
                |        Item.useStyle = ItemUseStyleID.Shoot;
                |        Item.useTime = 20;
                |        Item.useAnimation = 20;
                |        Item.autoReuse = true;
                |        Item.noMelee = true;
                |
                |        Item.DamageType = DamageClass.Magic;
                |        Item.damage = 30;
                |        Item.knockBack = 4;
                |
                |        Item.shoot = ModContent.ProjectileType<${name}Projectile>();
                |        Item.shootSpeed = 10f;
                |
                |        Item.value = Item.buyPrice(silver: 1);
                |        Item.rare = ItemRarityID.Blue;
                |        Item.UseSound = SoundID.Item20;
                |    }
                |}
                |
                |public class ${name}Projectile : ModProjectile
                |{
                |    public override void SetDefaults()
                |    {
                |        Projectile.width = 16;
                |        Projectile.height = 16;
                |
                |        Projectile.friendly = true;
                |        Projectile.DamageType = DamageClass.Magic;
                |        Projectile.penetrate = 1;
                |        Projectile.timeLeft = 300;
                |    }
                |
                |    public override void AI()
                |    {
                |        Projectile.rotation = Projectile.velocity.ToRotation();
                |        Lighting.AddLight(Projectile.Center, 0.5f, 0.5f, 0.2f);
                |    }
                |}
                |""".trimMargin(),
                listOf(Art("", 32, 32, ITEM_ART), Art("Projectile", 16, 16, SHOT_ART)),
                listOf(itemEntry(name, words), Entry("Projectiles", "${name}Projectile", listOf("DisplayName" to "$words Shot"))))
        },
        Template("projectile", R.string.TplProjectile, R.string.TplProjectileHint) { ns, name, words ->
            Output("""
                |using Terraria;
                |using Terraria.ModLoader;
                |
                |namespace $ns;
                |
                |public class $name : ModProjectile
                |{
                |    public override void SetDefaults()
                |    {
                |        Projectile.width = 16;
                |        Projectile.height = 16;
                |
                |        Projectile.friendly = true;
                |        Projectile.hostile = false;
                |        Projectile.penetrate = 1;
                |        Projectile.timeLeft = 300;
                |    }
                |
                |    public override void AI()
                |    {
                |        Projectile.rotation = Projectile.velocity.ToRotation();
                |    }
                |}
                |""".trimMargin(), listOf(Art("", 16, 16, SHOT_ART)), listOf(Entry("Projectiles", name, listOf("DisplayName" to words))))
        },
        Template("npc", R.string.TplNpc, R.string.TplNpcHint) { ns, name, words ->
            Output("""
                |using Terraria;
                |using Terraria.ID;
                |using Terraria.ModLoader;
                |
                |namespace $ns;
                |
                |public class $name : ModNPC
                |{
                |    public override void SetDefaults()
                |    {
                |        NPC.width = 32;
                |        NPC.height = 32;
                |
                |        NPC.lifeMax = 100;
                |        NPC.damage = 15;
                |        NPC.defense = 4;
                |        NPC.knockBackResist = 0.5f;
                |
                |        NPC.aiStyle = NPCAIStyleID.Slime;
                |        NPC.HitSound = SoundID.NPCHit1;
                |        NPC.DeathSound = SoundID.NPCDeath1;
                |        NPC.value = Item.buyPrice(silver: 1);
                |    }
                |}
                |""".trimMargin(), listOf(Art("", 32, 32, NPC_ART)), listOf(Entry("NPCs", name, listOf("DisplayName" to words))))
        },
        Template("buff", R.string.TplBuff, R.string.TplBuffHint) { ns, name, words ->
            Output("""
                |using Terraria;
                |using Terraria.ModLoader;
                |
                |namespace $ns;
                |
                |public class $name : ModBuff
                |{
                |    public override void Update(Player player, ref int buffIndex)
                |    {
                |        player.statDefense += 4;
                |    }
                |}
                |""".trimMargin(), listOf(Art("", 32, 32, BUFF_ART)), listOf(Entry("Buffs", name, listOf("DisplayName" to words, "Description" to ""))))
        },
        Template("accessory", R.string.TplAccessory, R.string.TplAccessoryHint) { ns, name, words ->
            Output("""
                |using Terraria;
                |using Terraria.ID;
                |using Terraria.ModLoader;
                |
                |namespace $ns;
                |
                |public class $name : ModItem
                |{
                |    public override void SetDefaults()
                |    {
                |        Item.width = 28;
                |        Item.height = 28;
                |        Item.accessory = true;
                |        Item.value = Item.buyPrice(gold: 1);
                |        Item.rare = ItemRarityID.Green;
                |    }
                |
                |    public override void UpdateAccessory(Player player, bool hideVisual)
                |    {
                |        player.GetDamage(DamageClass.Generic) += 0.05f;
                |    }
                |}
                |""".trimMargin(), listOf(Art("", 28, 28, ITEM_ART)), listOf(itemEntry(name, words, "5% increased damage")))
        },
        Template("tile", R.string.TplTile, R.string.TplTileHint) { ns, name, words ->
            Output("""
                |using Terraria.ID;
                |using Terraria.ModLoader;
                |using Terraria;
                |
                |namespace $ns;
                |
                |public class $name : ModTile
                |{
                |    public override void SetStaticDefaults()
                |    {
                |        Main.tileSolid[Type] = true;
                |        Main.tileBlockLight[Type] = true;
                |        DustType = DustID.Stone;
                |        HitSound = SoundID.Tink;
                |    }
                |}
                |
                |public class ${name}Item : ModItem
                |{
                |    public override void SetDefaults()
                |    {
                |        Item.DefaultToPlaceableTile(ModContent.TileType<$name>());
                |        Item.value = Item.buyPrice(copper: 10);
                |    }
                |}
                |""".trimMargin(),
                listOf(Art("", 288, 270, TILE_ART), Art("Item", 16, 16, TILE_ART)),
                listOf(Entry("Tiles", name, listOf("MapEntry" to words)), itemEntry("${name}Item", words)))
        },
        Template("player", R.string.TplPlayer, R.string.TplPlayerHint) { ns, name, _ ->
            Output("""
                |using Terraria.ModLoader;
                |
                |namespace $ns;
                |
                |public class $name : ModPlayer
                |{
                |    public override void ResetEffects()
                |    {
                |    }
                |
                |    public override void PostUpdateEquips()
                |    {
                |    }
                |}
                |""".trimMargin(), emptyList(), emptyList())
        },
        Template("system", R.string.TplSystem, R.string.TplSystemHint) { ns, name, _ ->
            Output("""
                |using Terraria.ModLoader;
                |
                |namespace $ns;
                |
                |public class $name : ModSystem
                |{
                |    public override void PostSetupContent()
                |    {
                |    }
                |
                |    public override void AddRecipes()
                |    {
                |    }
                |}
                |""".trimMargin(), emptyList(), emptyList())
        },
        Template("globalitem", R.string.TplGlobalItem, R.string.TplGlobalItemHint) { ns, name, _ ->
            Output("""
                |using Terraria;
                |using Terraria.ModLoader;
                |
                |namespace $ns;
                |
                |public class $name : GlobalItem
                |{
                |    public override void SetDefaults(Item entity)
                |    {
                |    }
                |}
                |""".trimMargin(), emptyList(), emptyList())
        },
        Template("globalnpc", R.string.TplGlobalNpc, R.string.TplGlobalNpcHint) { ns, name, _ ->
            Output("""
                |using Terraria;
                |using Terraria.ModLoader;
                |
                |namespace $ns;
                |
                |public class $name : GlobalNPC
                |{
                |    public override void SetDefaults(NPC entity)
                |    {
                |    }
                |}
                |""".trimMargin(), emptyList(), emptyList())
        },
    )

    /** Mod id + folders: `MyMod.Content.Items`. Whatever cannot be part of a namespace is dropped. */
    fun namespaceFor(modId: String, folder: String): String {
        val parts = folder.split('/').filter { it.isNotEmpty() }.map(::identifier).filter { it.isNotEmpty() }
        return (listOf(Workspace.namespaceOf(modId)) + parts).joinToString(".")
    }

    fun identifier(text: String): String {
        val clean = text.filter { it == '_' || it.isLetterOrDigit() && it.code < 128 }
        return if (clean.firstOrNull()?.isDigit() == true) "_$clean" else clean
    }

    /** `MyFireSword` reads as `My Fire Sword` in the game until it gets translated. */
    fun words(name: String): String = name.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace('_', ' ')

    /**
     * Adds an entry to a Localization .hjson, under its section. Entries already there stay as
     * they are: a translator's text is never overwritten.
     */
    fun addLocalization(text: String, entries: List<Entry>): String {
        var result = text
        for (entry in entries) {
            val body = entry.fields.joinToString("") { (key, value) -> "\t\t$key: ${hjsonValue(value)}\n" }
            val block = "\t${entry.key}: {\n$body\t}\n"
            val section = Regex("(?m)^${Regex.escape(entry.section)}: \\{[ \\t]*\\r?\\n").find(result)
            if (section == null) {
                if (result.isNotEmpty() && !result.endsWith("\n")) result += "\n"
                if (result.isNotEmpty()) result += "\n"
                result += "${entry.section}: {\n$block}\n"
            } else {
                val end = result.indexOf("\n}", section.range.last).let { if (it < 0) result.length else it }
                val inside = result.substring(section.range.last + 1, end)
                if (Regex("(?m)^\\t${Regex.escape(entry.key)}: \\{").containsMatchIn(inside)) continue
                val separator = if (inside.isBlank()) "" else "\n"
                result = result.substring(0, section.range.last + 1) + block + separator + result.substring(section.range.last + 1)
            }
        }
        return result
    }

    private fun hjsonValue(value: String): String = if (value.isEmpty()) "\"\"" else value

    /** What a plain `.cs` starts with when no template was picked. */
    fun defaultClass(namespace: String, name: String): String =
        ALL.first { it.id == "class" }.build(namespace, name, name).code
}
