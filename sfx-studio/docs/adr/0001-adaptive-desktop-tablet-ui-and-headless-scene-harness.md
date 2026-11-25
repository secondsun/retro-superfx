# Adaptive Desktop/Tablet UI and Headless Compose Scene Harness

`sfx-studio-desktop` targets desktop workstations, 2-in-1 convertibles, and touch/stylus tablets (`Expanded >= 1280dp`, `Medium 840–1279dp`, `Compact < 840dp`) inside a single Compose Desktop JVM Maven module (`desktop-jvm` + Skiko), verified deterministically offscreen via `androidx.compose.ui.ImageComposeScene` rather than splitting into separate Android/iOS build targets or locking layouts to fixed desktop split panes.

## Considered Options

- **Fixed-width desktop split panes**: Simple layout code, but clips toolbars and crushes central 2D/3D viewports below `1280dp`, while trapping core actions (`Open`, `New Level`, `Save`, `UI Scale`, camera/cell shortcuts) inside OS menu bars and physical keyboards.
- **Separate Android/iOS Gradle targets**: Native mobile packaging, but breaks `sfx-studio`'s single-reactor Java 26 Maven build (`./mvnw clean verify`) and duplicates file-picker/filesystem workflows designed around local SNES assembly repositories.
- **Adaptive Compose Desktop shell + headless `ImageComposeScene` suite (chosen)**: Preserves the pure Maven reactor and `StudioPlatform` test seam while adapting side docks into collapsible rails/drawers/sheets, surfacing touch-sized (`40–44dp`) command and pan/zoom controls without requiring an OS menu bar or keyboard, and rendering multi-viewport user-guide screenshots headlessly in CI and local builds.
