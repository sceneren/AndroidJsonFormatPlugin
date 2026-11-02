# Third-party notices

## logcat2kt

- Source: https://github.com/rendonglugit/logcat2kt
- Reference UI: https://rendonglugit.github.io/logcat2kt/
- Revision: e8f33d9730618aba35cac9c27286d2f151126412
- Copyright (c) 2026 rendonglu
- License: MIT; full text in `src/main/resources/web/LICENSE` and bundled in the plugin.

The original page is retained at `src/main/resources/web/index.html`. `core.js` is mechanically extracted with explicit downstream patches in `scripts/core-patches.mjs`: recognize Android Studio table prefixes with hidden date/PID columns, remove numbered transport chunk markers, join consecutive numbered payload slices verbatim, and emit Kotlin property annotations on their own preceding line. `runtime.js` supplies non-browser UTF-8/fixed64 helpers and the conversion entry point. The native Swing UI reproduces the page's visual language, with light/dark theme support, not a pixel-identical browser rendering.

## Mozilla Rhino 1.8.0

- Source: https://github.com/mozilla/rhino/tree/Rhino1_8_0_Release
- Artifact: https://repo.maven.apache.org/maven2/org/mozilla/rhino/1.8.0/rhino-1.8.0.jar
- License: Mozilla Public License 2.0; full license included at `licenses/MPL-2.0.txt` in the plugin JAR.
- No Rhino source files have been modified.

## IntelliJ Platform SDK

Compile-only dependencies from the JetBrains Maven repository, platform 251.25410.129 (2025.1). SDK JARs are not included in the distributed plugin.
