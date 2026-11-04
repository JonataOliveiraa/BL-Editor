# Third-party notices

BL Editor includes the following third-party components.

## sora-editor

- Source: https://github.com/Rosemoe/sora-editor (modules `editor` and `language-textmate`, version 0.23.6)
- License: GNU Lesser General Public License v2.1 or later
- Used unmodified, as libraries pulled from Maven Central. Its source is available at the link above, and the
  libraries can be replaced by rebuilding this project with a different version in `app/build.gradle.kts`.

## TextMate grammars from Visual Studio Code

- Files: `app/src/main/assets/textmate/javascript`, `csharp`, `json` and `markdown`
- Source: https://github.com/microsoft/vscode (tag 1.95.0, `extensions/*/syntaxes`)
- License: MIT, Copyright (c) 2015 - present Microsoft Corporation. Full text in `VSCODE-LICENSE.txt`.
- The grammars were themselves converted from upstream projects under MIT licenses
  (microsoft/TypeScript-TmLanguage, dotnet/csharp-tmLanguage, microsoft/vscode-markdown-tm-grammar).

## Pixel art from Bunny Loader

- Files: `app/src/main/res/drawable-nodpi/*.png`
- Copied from the Bunny Loader launcher (`app/src/main/res/drawable-nodpi`). Some of them are Terraria art; see the
  Bunny Loader notices.

## Comic Neue (fallback font)

- File: `app/src/fontFallback/res/font/bunny.ttf`
- Source: https://github.com/crozynski/comicneue
- License: SIL Open Font License 1.1, full text in `app/src/fontFallback/OFL.txt`.
- The official build uses Terraria's font from the Bunny Loader checkout instead; it is not part of this repository.

## AndroidX and Jetpack Compose

- Source: https://developer.android.com/jetpack/androidx
- License: Apache License 2.0
