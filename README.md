# BL Editor

A code editor for Android, made for [Bunny Loader](https://github.com/JonataOliveiraa/Bunny-Loader) mods.

- Looks like Bunny Loader: the launcher palette, bevelled pixel panels, the yellow touch border and Terraria's font
  on the frame (titles, buttons, forms). Code, the file tree and the tabs use regular fonts.
- Edits the mod projects in the Bunny Loader **ModSources** folder in place. No root, no `Android/data` access.
- Syntax highlighting for JavaScript, C#, JSON and Markdown, tabs, find and replace, undo and redo, and a symbol bar
  above the keyboard.
- A compact file tree with indent guides (the guide of the open file's folder is brighter). The buttons on top act on
  the selected folder; a long press on a row opens its menu: create file or folder, import from the device, rename,
  delete. On wide screens the tree stays open on the side.
- **New mod**: its own screen with picture (saved as `icon.png`), name, ID (suggested from the name), author,
  version, a generated UID and JavaScript or C#. Description, license and the rest are files the modder creates.
- **Saving updates the mod in Bunny Loader**: every save packages, installs and enables it in the background, so
  switching to Bunny Loader already shows the new version (description, picture...). The check next to the toggle
  says it worked; a red `!` shows the error.
- **Autosave** (the ON/OFF toggle): saves 1.5 s after the last keystroke, and when the app goes to the background.
- **Test** (the bunny): saves everything, and Bunny Loader packages, installs, enables and opens the game. If the game
  is already open, it restarts with the new version.

## How it talks to Bunny Loader

BL Editor is a separate app. Bunny Loader exposes:

- `EditorBridgeActivity`, protected by the signature permission `com.bunnyloader.permission.EDITOR`, so only an
  app signed with the same key can call it:
  - `com.bunnyloader.action.CONNECT_EDITOR` returns a persistable read/write grant on the ModSources tree;
  - `com.bunnyloader.action.TEST_SOURCE` (extra `project` = the project folder) builds, installs, enables and opens
    the game. Errors come back as `RESULT_FIRST_USER` with the `error` and `errorCode` extras.
- `EditorBridgeProvider` (`content://com.bunnyloader.editorbridge`, same permission): `call("install", project)`
  builds, installs and enables without any screen. It returns `ok` and, on failure, `error`.
- The `com.bunnyloader.documents` document provider, which the editor uses for every file operation.

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

## Third-party code

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
