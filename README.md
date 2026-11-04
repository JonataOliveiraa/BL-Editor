# BL Editor

A code editor for Android, made for [Bunny Loader](https://github.com/JonataOliveiraa/Bunny-Loader) C# mods, texture packs
and font packs. C# is the only language for mods, as in Bunny Loader itself.

- Looks like Bunny Loader: the launcher palette, bevelled pixel panels, the yellow touch border and Terraria's font
  on the frame (titles, buttons, forms). Code, the file tree and the tabs use regular fonts.
- Available in the languages of Bunny Loader: English, Spanish, Brazilian Portuguese, Russian, Thai, Vietnamese and
  Simplified Chinese, following the device or chosen on the home screen.
- Edits the projects in the Bunny Loader **ModSources** folder in place. No root, no `Android/data` access.

## Writing C#

- **IntelliSense over the real API**: the Bunny Loader, Terraria, ReLogic and XNA types and the common .NET namespaces,
  built into the app as a tree of about 21 000 types and 135 000 members.
  - Members after `.`, through chains (`Main.LocalPlayer.GetDamage(DamageClass.Melee).`), indexers, generics and `var`.
  - The list after `override ` writes the whole member, with its signature, `base` call and missing `using` lines.
  - Types from namespaces that are not imported yet bring their `using`. `MP` finds `ModPlayer`.
  - The project's own classes, across files.
  - Parameter info above the keyboard, with the current argument in bold.
- **Writing comfort**: closing pairs and typing over them, Enter that indents and opens up `{}`, `}` that goes back to
  its block, snippets with Tab between their fields (`for`, `foreach`, `prop`, `ctor`, `recipe`, `moditem`...), Format
  Document, comment toggling, duplicate line and word wrap.
- **File templates**: Class, ModItem, sword, ModItem with projectile, ModProjectile, ModNPC, ModBuff, accessory, ModTile,
  ModPlayer, ModSystem, GlobalItem and GlobalNPC. The namespace comes from the folder, and the template also writes a
  placeholder PNG and the name in `Localization/`. A plain `.cs` file starts with its namespace and an empty class.
- Syntax highlighting for C# and for the data files of a mod (JSON, Hjson and Markdown), tabs, find and replace, undo and redo, and a symbol bar above
  the keyboard.
- This is a fast reader, not a compiler: it does not report errors while typing. Compile errors come from Bunny Loader,
  next to the toggle and on Test.

## Mods

- A compact file tree with indent guides. The buttons on top act on the selected folder; a long press on a row opens its
  menu: create file or folder, import from the device, rename, delete. On wide screens the tree stays open on the side.
- **New mod**: picture (saved as `icon.png`), name, lowercase ID (suggested from the name), author, version and a generated UID. It
  writes Bunny Loader's format 3: the Mod class in `<id>.cs`, `Assets/`, `Common/`, `Content/` and `Localization/`.
- **Saving updates the mod in Bunny Loader**: every save packages, installs and enables it in the background. The check
  next to the toggle says it worked; a red `!` shows the error.
- **Autosave** (the ON/OFF toggle): saves 1.5 s after the last keystroke, and when the app goes to the background.
- **Test** (the bunny): saves everything, and Bunny Loader packages, installs, enables and opens the game. If the game is
  already open, it restarts with the new version.
- **Image viewer**: pinch to zoom, drag to move, double tap between fit and zoom, pixels stay sharp. PNGs open in the
  texture editor from there.

## Texture packs

A pack's screen shows its sprites. **Add texture** finds the sprite by its name in the game (`CopperPickaxe` gives
`Item_3509.png`) or number, for items, NPCs, projectiles, tiles, walls, buffs, gore and armor, or by any other path in
`Images/`. Then draw it or import a PNG.

The **texture editor** has pencil, eraser, fill, color picker, line and rectangles; brush sizes, mirror drawing, a grid,
a color panel (HSV, opacity, code), resize, flips, undo and redo, and a preview at actual size. One finger paints, two
fingers zoom and move. Pixels are saved exactly as painted, transparency included.

## Font packs

Pick a `.ttf` or `.otf`, choose which of the game's five fonts to replace and generate them. It does what
`tools/mods/fonts.py` in Bunny Loader does: the letters are fitted to the measurements of the mobile fonts, so the
interface does not move, and drawn with 2 or 3 times the pixels the game uses (`blfont/1`). Options: character set, letter
size and extra thickness. Unlike the PC tool, it does not compose missing accents.

## Installing packs

Bunny Loader's bridge only installs `"type": "mod"` from ModSources for now. On a pack, **Install** tries it, and when
Bunny Loader refuses, **Export package** writes a `.bl` to import in Bunny Loader under *Packages → Import package*. As soon
as the bridge accepts packs, Install works with no change here.

## How it talks to Bunny Loader

BL Editor is a separate app. Bunny Loader exposes:

- `EditorBridgeActivity`, protected by the signature permission `com.bunnyloader.permission.EDITOR`, so only an
  app signed with the same key can call it:
  - `com.bunnyloader.action.CONNECT_EDITOR` returns a persistable read/write grant on the ModSources tree;
  - `com.bunnyloader.action.TEST_SOURCE` (extra `project` = the project folder) builds, installs, enables and opens
    the game. Errors come back as `RESULT_FIRST_USER` with the `error` and `errorCode` extras.
- `EditorBridgeProvider` (`content://<package>.editorbridge`, same permission): `call("install", project)`
  builds, installs and enables without any screen. It returns `ok` and, on failure, `error`.
- The `<package>.documents` document provider, which the editor uses for every file operation.

If the signatures differ, the editor can still open the folder through the system picker
("Choose the folder manually"), but Test will not work.

## Building

Requirements: the Android SDK (`local.properties` with `sdk.dir`) and JDK 17 or newer (the Android Studio JBR works).

```bash
./gradlew assembleDebug
```

The APK must be signed with the same key as the Bunny Loader you are going to use: copy `keystore.properties.example`
to `keystore.properties` and point it to that key. Without it, the build uses the debug key of your machine, which
works with a Bunny Loader built on the same machine.

### Font

The frame uses Terraria's font (Andy Bold, by Monotype), which does not go into Git. The build takes it from the
Bunny Loader checkout: `bl.dir` in `local.properties`, by default `../Bunny Loader`
(`app/src/main/res/font/bunny.ttf`). Without it, the build uses Comic Neue (SIL OFL) from `app/src/fontFallback`.

### The API tree

`app/src/main/assets/csharp` is generated from the assemblies mods compile against. To update it after a Bunny Loader
build (it needs the .NET 10 SDK):

```bash
dotnet run --project tools/apigen -c Release -- "../Bunny Loader/app/build/generated/blAssets/dotnet" app/src/main/assets/csharp
```

A third argument also writes the whole tree as one JSON file. In the app, the tree is split in two levels: an index of
namespaces and types, and gzip blocks of about 256 KB with the members, opened only for the types being looked at.

### Tests

```bash
./gradlew :app:testDebugUnitTest
```

They run the analyzer against the real tree. They also write the templates to `app/build/templates-check`, which this
compiles against the real API:

```bash
dotnet build tools/check-templates -p:BunnyLoaderDll="../Bunny Loader/app/build/generated/blAssets/dotnet/BunnyLoader.dll"
```

## Third-party code

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
