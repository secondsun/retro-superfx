# SuperFX Studio (`sfx-studio`) — UX & Visual Design Specification

**Status**: Authoritative Implementation Specification  
**Target Module**: `sfx-studio/sfx-studio-desktop` (Compose Multiplatform Desktop JVM + Skiko, no third-party UI libraries)  
**Companion References**: [USER_GUIDE.md](file:///home/summers/Projects/retro-superfx/sfx-studio/docs/user-guide/USER_GUIDE.md) · [GLOSSARY.md](file:///home/summers/Projects/retro-superfx/sfx-studio/GLOSSARY.md) · [ADR-0001](file:///home/summers/Projects/retro-superfx/sfx-studio/docs/adr/0001-adaptive-desktop-tablet-ui-and-headless-scene-harness.md)

---

## 1. Visual Diagnosis & Anti-Blockiness Principles

### 1.1 Concrete Audit of Baseline (`screenshots/before/`) Captures

Visual inspection across all 25 baseline captures (`1600×960` Expanded Desktop, `1024×768` Medium Tablet Landscape, and `768×1024` Compact Tablet Portrait) reveals systemic visual "programmer-UI blockiness", layout clipping, touch hostility, and missing authoring surfaces:

| Category | Concrete Defect Observed in `before/` PNGs | Root Cause in Current Compose Code |
|---|---|---|
| **Nested Rectangular Borders ("Box Soup")** | Up to **5 concentric `1–2dp` rectangular borders** stacked inside one another: Tab Strip (`1dp`) → Tab (`1dp`) → `LeftPane` (`1dp` + `2dp` divider) → Texture Row (`1.5dp`) → Thumbnail (`1dp`); Level Docks (`1dp`) → `BrushLayerMask` rows (`1dp` full-width blue boxes) → `InspectorTextureSlotRow` (`1dp` blue box). | Every container, toolbar, list item, swatch, and chip applies `.border(1.dp, ...)` on all four sides instead of using tonal surface elevation and single-edge hairlines. |
| **Harsh Competing Accent Colors** | `uc02` renders royal blue (`Auto-Snap`), neon purple (`Global 15-Color Auto-Quantize`), and emerald green (`Apply Mapping`) simultaneously. `LeftPane` renders a solid alarm-red `Unassign` button permanently next to palette sliders. Palette swatches have `1dp` pure white borders and `2–3dp` pure yellow (`Color.Yellow`) selection outlines. | Ad-hoc per-file Material 2 `ButtonDefaults.buttonColors(...)` hex literals with no semantic hierarchy between Primary CTA, Secondary Action, and Destructive Action. |
| **Raw Unicode & Emoji Glyphs** | `uc06..uc08` render a color OS teardrop emoji (`💧`) next to monochrome Unicode `⬚` and `✎` in the Toolbox; `▾` / `▸` / `↻` / `↺` vary in baseline across OS fonts; height buttons read `- [[]` and `+ []]` because `[` and `]` shortcuts were wrapped in `[...]`; Slot `0` renders a gray box with `"T"` instead of a transparency checkerboard. | Raw strings passed to `Text(...)` in `LevelEditorWorkspace.kt` (`GimpToolButton`, `IsoCameraControlAction`, `ToolbarActionButton`) and `LeftPane.kt` instead of vector `ImageVector` / `Canvas` icons. |
| **Catastrophic Toolbar & Tab Wrapping** | **Desktop (`1600×960`, `uc01`/`uc04a`)**: Rightmost button in `LayoutToolbar` wraps vertically to `"Exp\nort"` while duplicating `"Export Assets..."` directly above it. **Landscape (`1024×768`)**: `"Zoom: 2x"` wraps vertically (`Z\no\no\nm\n:\n2\nx`) and clips `8px..4x`. **Portrait (`768×1024`)**: `TabRow` (`Layout Editor`, `Hex Preview`, `Index File Preview (.i)`) collapses into 3 unreadable vertical single-letter columns (`t/E/d`, `P/r/e`, `e/P/r`) while `"Grid Snap"` and `"Zoom"` overwrite each other vertically on the right edge. | Non-wrapping `Row` in `LayoutToolbar` (`LayoutTab.kt`); `Undo`/`Redo`/`Export Assets...` crammed into the same `Row` as M2 `TabRow` (`AtlasEditorWorkspace.kt`) beside a rigid `360.dp` `LeftPane`. |
| **Canvas Overflow & Clipped Text Inputs** | **`uc03` (`TextureEditModal`)**: The `32×32` `stone_floor` pixel canvas overflows outside its bordered frame at top and bottom, painting stone pixels directly behind the `Cancel` and `Apply Changes` buttons. **`uc04d` (`ExportModal`)**: `"/home/summers/..."` and `"PROTAGONIST_ROOM_ATLAS"` have their bottom halves sliced off. **`uc02` (`PaletteRemappingModal`)**: Preview labels (`stained_glass_window (new)`) clip at bottom descenders. **`uc01` Portrait**: Status bar left message collides directly with `"DPI Scale: 100%"` and clips `"Palette: 1/15"` on the right. | `TextureEditModal.kt` hardcodes `pixelSize = minOf(400 / maxOf(w, h), 24)` (`384.dp`) inside a `620.dp` modal without `BoxWithConstraints` or `.clipToBounds()`. `ExportModal.kt` / `TextureEditModal.kt` put `.height(48.dp)` on M2 `OutlinedTextField` (whose internal `16.dp` vertical padding clips glyphs below `56.dp`). |
| **Crushed Portrait Viewports (`140dp` Slivers)** | **`uc06..uc08` Portrait (`768×1024`)**: Permanent `216.dp` left dock + `264.dp` right dock (`480.dp` total) leave only `288.dp` for the entire `Split` center stage (`~140.dp` per viewport!), shrinking the `3D Software Preview` to a tiny postage stamp floating in a `900.dp`-tall black column while `12×12` `2D Top-Down Grid` cells bleed across the divider (`uc07`). | Fixed desktop horizontal 3-column `Row` with zero responsive adaptation for `Medium` (`840–1279dp`) or `Compact` (`< 840dp`) viewports. |
| **Actions Trapped in OS `MenuBar` / Keyboard** | `New Level...`, `Open Document...`, `Save`, `Save As...`, and `UI Scale` exist **only** inside `Window` `MenuBar` or shortcuts (`Ctrl+O`, `Ctrl+Shift+N`, `Ctrl+S`). Touch/tablet users and `ImageComposeScene` captures cannot access them; the tab `+` button silently creates an untitled Atlas. Interactive canvases lack a `Pan / Hand` mode, so 1-finger touch drags move textures or paint cells unintentionally. | `Main.kt` puts document lifecycle and UI scale exclusively in AWT `MenuBar`. Canvases bind `detectDragGestures` unconditionally to `moveTexture` / `paintCell`. |
| **Missing Domain Authoring Surfaces (`UC4a`, `UC7`)** | **`UC4a`**: `AtlasDocument` supports `VirtualSlice`, `DescriptorAlias`, and `descriptorOrder`, yet the GUI has **zero** catalog UI, sub-rect picker, or canvas slice overlay. **`UC7`**: `demo_level.sfxlevel` contains a complete `GameplayLayer` (`playerSpawn`, `patrol_slime` + 4 waypoints, `guardian_knight`, `hidden_chest`, `walkabilityOverrides`), yet `LevelEditorWorkspace` renders **zero** markers, patrol chains, or gameplay inspector controls. | Core capabilities were implemented in `sfx-studio-core` (`AtlasDocument`, `DescriptorTable`, `GameplayLayer`) without corresponding `sfx-studio-desktop` UI views. |

---

### 1.2 Core Anti-Blockiness & Pixel-Crisp Design Principles

1. **Tonal Surface Hierarchy Over Nested Borders**:
   - Establish spatial structure through **6 calibrated dark neutral luminance steps** (`bgCanvas` `#0A0C10` → `surface3` `#2A3142`), inspired by Linear, Figma, and Blender 4.
   - Major workspace panes (`StudioTopBar`, `SideDock`, `BottomSheet`, `StatusBar`) use **single-edge `1dp` hairlines** (`Modifier.drawEdgeHairline(edge, StudioTheme.colors.borderSubtle)`) on their dividing boundary only — **never** 4-sided `.border(1.dp, ...)` around structural panels or toolbars.
   - Interactive list items, tool buttons, and inspector rows sit **borderless at rest** (`Color.Transparent` or `surface1`), reveal `surface2` on hover, and apply a soft translucent tint (`accentSubtle`) plus a crisp `1dp` `accentBorder` (or `3dp` left indicator bar) **only** when active/selected.
2. **Strict Semantic Color Discipline**:
   - **One Primary Accent** (`accentPrimary` `#3B82F6`) per screen or modal for the primary commit CTA (`Export Assets...`, `Save & Pack`, `Apply Changes`, `Apply Mapping`, `Create Level`) and active selection state.
   - **Neutral Tonal Secondary Actions** (`surface2` `#222736` fill, `textPrimary` label) for utility operations (`Import...`, `Quantize 15c`, `Auto-Fit`, `Auto-Pack`, `Auto-Snap to Palette`, `Global 15c Quantize`).
   - **Quiet-Until-Invoked Destructive Controls**: `Delete` icons and `Unassign` buttons render in muted neutral (`textMuted` `#7E8A9F`) at rest, transitioning to `semanticError` (`#F87171` text / `#EF444424` fill) only on hover/focus or inside confirmation modals.
3. **Zero Raw Unicode or Emoji Icons**:
   - Every icon, chevron, tool glyph, shape archetype preview, compass control, and tactical map pin is rendered via `StudioIcons` (`ImageVector.Builder` on a `16×16` or `20×20` viewport with `1.5dp` stroke, `StrokeCap.Round`, `StrokeJoin.Round`) or Crisp `Canvas` vector primitives.
4. **Custom `BasicTextField` Controls (Zero M2 `OutlinedTextField` Clipping)**:
   - All single-line text and numeric inputs use `StudioTextField` built on `androidx.compose.foundation.text.BasicTextField` with explicit `PaddingValues(horizontal = 10.dp, vertical = 0.dp)` and centered alignment inside `32.dp` (Compact) / `42.dp` (Touch) boxes so descenders (`g`, `p`, `y`, `_`) are never clipped at any height or DPI scale.
5. **Razor-Sharp Nearest-Neighbor Art Inside Refined Modern Chrome**:
   - Studio chrome uses smooth `6dp` / `8dp` / `12dp` rounded corners, pill segmented controls, and clean typography.
   - All authored SuperFX pixel surfaces (`AtlasCanvas`, `TextureEditModal` pixel grid, `SourceTexture` / `DescriptorTable` thumbnails, `Iso3DViewport` `256×160` render) use **`0dp` sharp texel corners**, **`FilterQuality.None`**, integer-preferred zoom steps (`1×, 2×, 3×, 4×, 6×, 8×` + `Fit`), and true `8×8px` two-tone dark checkerboards behind transparent Slot `0` (`$0000`).

---

## 2. Design System Tokens (`StudioTheme` & `StudioIcons`)

All theme tokens live in `dev.secondsun.sfxstudio.desktop.ui.theme` and are provided at the root of `StudioRootContent` via `CompositionLocalProvider(LocalStudioColors provides ..., LocalStudioMetrics provides ..., LocalStudioTypography provides ..., LocalStudioViewport provides ...)`.

### 2.1 Color Palette (`StudioColors`)

```kotlin
@Immutable
data class StudioColors(
    // Canvas & Structural Surfaces (Dark-to-Light Luminance Ladder)
    val bgCanvas: Color = Color(0xFF0A0C10),        // Deepest stage backdrop behind 2D/3D/Atlas canvases
    val bgApp: Color = Color(0xFF0F1218),           // Root shell & top tab strip background
    val surface0: Color = Color(0xFF131720),        // Recessed wells: text inputs, hex viewer, swatch tray
    val surface1: Color = Color(0xFF181D27),        // Primary docks, command bar, inspector background
    val surface2: Color = Color(0xFF212735),        // Cards, secondary buttons, hover row fill, pill track
    val surface3: Color = Color(0xFF2B3345),        // Active segmented thumb, pressed button, elevated popover
    val scrim: Color = Color(0xB306080C),           // 70% modal & drawer backdrop scrim

    // Hairlines & Borders (1dp subtle, never stacked)
    val borderSubtle: Color = Color(0xFF252C3C),    // Single-edge dock/toolbar dividers & resting input rim
    val borderStrong: Color = Color(0xFF364056),    // Card emphasis, active input rim, floating HUD border
    val swatchInnerRim: Color = Color(0x24FFFFFF),  // 14% white inner hairline on color swatches

    // Typography Hierarchy
    val textPrimary: Color = Color(0xFFF1F5F9),     // Primary labels, active tab titles, input values
    val textSecondary: Color = Color(0xFF94A3B8),   // Inspector field labels, secondary metadata
    val textMuted: Color = Color(0xFF64748B),       // Disabled controls, hex zero bytes, shortcut hints

    // Primary Brand & Selection Accent (SuperFX Cobalt)
    val accentPrimary: Color = Color(0xFF3B82F6),   // Primary CTA fill, active tab bar indicator
    val accentHover: Color = Color(0xFF60A5FA),     // Primary CTA hover, focused input border, selection ring
    val accentSubtle: Color = Color(0x293B82F6),    // 16% selected list row / active tool / mask chip fill
    val accentBorder: Color = Color(0x8060A5FA),    // 50% selected row / active chip border

    // Semantic Feedback Tokens
    val semanticSuccess: Color = Color(0xFF10B981), // Valid drag placement, clean validation badge
    val semanticSuccessSubtle: Color = Color(0x2410B981),
    val semanticWarning: Color = Color(0xFFF59E0B), // Bank $71 >32KB warning, unflush liquid, dirty dot
    val semanticWarningSubtle: Color = Color(0x26F59E0B),
    val semanticWarningText: Color = Color(0xFFFDE68A),
    val semanticError: Color = Color(0xFFEF4444),   // Collision, missing descriptor, out-of-bounds spawn
    val semanticErrorSubtle: Color = Color(0x29EF4444),
    val semanticErrorText: Color = Color(0xFFFCA5A5),
    val semanticInfo: Color = Color(0xFF38BDF8),    // Selected cell wireframe, DPI/telemetry readout

    // Domain-Specific Authoring Accents
    val sliceCyan: Color = Color(0xFF22D3EE),       // VirtualSlice dashed overlay & SLICE badge
    val aliasAmber: Color = Color(0xFFFBBF24),      // DescriptorAlias badge, hex byte highlight, 2h panel tick
    val playerSpawnCyan: Color = Color(0xFF38BDF8), // PlayerSpawn shield & facing arrow
    val npcRose: Color = Color(0xFFFB7185),         // NpcSpawn tactical token
    val waypointGold: Color = Color(0xFFFACC15),    // Patrol waypoint nodes (1..4) & dashed loop arrows
    val interactAmber: Color = Color(0xFFF59E0B),   // Chest marker
    val interactViolet: Color = Color(0xFFA78BFA),  // Switch marker
    val interactEmerald: Color = Color(0xFF34D399), // Sign marker & Level tab badge

    // Transparency Checkerboard (Slot 0 = $0000)
    val checkerDark: Color = Color(0xFF12151D),
    val checkerLight: Color = Color(0xFF1B202C),
)
```

---

### 2.2 Typography & Numeric Rules (`StudioTypography`)

Use `FontFamily.SansSerif` for UI chrome and `FontFamily.Monospace` (with `fontFeatureSettings = "tnum"` on sans numeric readouts) for all ca65 identifiers, `$BGR555` / `$XX` hex words, coordinates, and assembly previews:

| Token | Family | Size / LineHeight | Weight | Usage |
|---|---|---|---|---|
| `titleModal` | `SansSerif` | `16.sp / 22.sp` | `SemiBold (600)` | Modal dialog headers, Onboarding hero title |
| `sectionHeader` | `SansSerif` | `11.sp / 16.sp` | `SemiBold (600)` | Uppercase-tracked (`0.04.em`) or crisp dock section headers (`SOURCE TEXTURES`, `MAP GRID`) |
| `bodyMedium` | `SansSerif` | `12.sp / 18.sp` | `Medium (500)` | Button labels, active tab titles, segmented control labels |
| `bodyRegular` | `SansSerif` | `12.sp / 18.sp` | `Normal (400)` | Modal descriptions, inspector labels, diagnostic prose |
| `caption` | `SansSerif` | `11.sp / 15.sp` | `Normal (400)` | Subtitles, status bar messages, dimension metadata |
| `microBadge` | `SansSerif` | `10.sp / 12.sp` | `SemiBold (600)` | `SLICE`, `ALIAS`, `TEX`, `2h`, walkability cost badges |
| `monoBody` | `Monospace` | `12.sp / 18.sp` | `Normal (400)` | `StudioTextField` symbol/path values, `HexPreviewTab`, `IndexPreviewTab` |
| `monoCaption` | `Monospace` | `11.sp / 14.sp` | `Medium (500)` | `$7FFF` BGR555 readouts, `$00..$EF` slot IDs, `(col, row)`, keycap hints `[V]` |

---

### 2.3 Spacing, Density & Corner Radius Scale (`StudioMetrics`)

- **Spacing Rhythm (`4dp` base grid)**:
  - `spaceXXS = 2.dp` (inline badge internal padding)
  - `spaceXS = 4.dp` (related chip/swatch gap, tight icon-to-label gap)
  - `spaceSM = 8.dp` (standard control group gap, compact card padding)
  - `spaceMD = 12.dp` (dock outer padding, section vertical rhythm)
  - `spaceLG = 16.dp` (modal body padding, major toolbar group separation)
  - `spaceXL = 24.dp` (onboarding card padding, empty-state breathing room)
- **Corner Radii**:
  - `radiusPixel = 0.dp` — **Mandatory** on `AtlasCanvas`, `TextureEditModal` pixel canvas, `Iso3DViewport` image rect, and `2D Grid` cells so edge texels are never clipped by rounded masks.
  - `radiusXS = 4.dp` — Swatches, keycap badges (`[V]`), kind badges (`SLICE`), texture thumbnails.
  - `radiusSM = 6.dp` — Buttons, `StudioTextField`, dropdown menus, list row selection highlight, inspector cards.
  - `radiusMD = 8.dp` — Floating viewport HUDs, camera control pods, onboarding quick-start cards, inline pickers.
  - `radiusLG = 12.dp` — Modal dialogs (`StudioModalDialog`), portrait bottom inspector sheet top corners.
  - `radiusPill = 999.dp` — `StudioSegmentedControl` outer track & active thumb, status count pills.
- **Interactive Hit-Target Density (`Compact` vs `Touch Comfort`)**:

| Metric Token | Desktop Compact (`touchComfort = false`) | Touch Comfort (`touchComfort = true`) |
|---|---|---|
| `tabStripHeight` | `36.dp` | `44.dp` |
| `commandBarHeight` | `42.dp` | `50.dp` |
| `controlHeight` (Buttons, Inputs, Segmented) | `30.dp` | `40.dp` |
| `controlHeightSM` (Toolbar chips, steppers) | `26.dp` | `36.dp` |
| `iconButtonSize` | `30.dp` (`16.dp` icon) | `42.dp` (`20.dp` icon) |
| `tabCloseHitSize` | `24.dp` (`12.dp` icon) | `34.dp` (`14.dp` icon) |
| `swatchHeight` | `26.dp` | `36.dp` |
| `listRowMinHeight` | `44.dp` | `54.dp` |
| `statusBarHeight` | `26.dp` | `30.dp` |

---

### 2.4 Custom Vector & Canvas Icon Inventory (`StudioIcons`)

Implement `object StudioIcons` in `dev.secondsun.sfxstudio.desktop.ui.icons.StudioIcons` using pure `androidx.compose.ui.graphics.vector.ImageVector.Builder` (`defaultWidth = 16.dp`, `defaultHeight = 16.dp`, `viewportWidth = 16f`, `viewportHeight = 16f`, `strokeLineWidth = 1.5f`). **Zero raw Unicode symbols or emojis may appear in UI strings.**

| Icon Property | Replaces Legacy Hack | Visual Geometry (`16×16` Vector Grid) |
|---|---|---|
| `BrandChip` | *(none)* | Isometric/DIP microchip square `(3,3..13,13)` with 3 pins per side & inner diamond |
| `DocAtlas` | *(plain tab text)* | `2×2` unequal packed rectangles (`8×8`, `4×8`, `12×4`) representing a packed Texture Atlas |
| `DocLevel` | *(plain tab text)* | Isometric 3D cube wireframe (`top diamond + left/right vertical faces`) |
| `Plus` / `Close` | `+` / `×` | Crisp centered cross / diagonal `×` (`1.5f` stroke) |
| `FolderOpen` | *(MenuBar only)* | Clean open folder tab outline |
| `Save` / `SaveAs` | *(MenuBar only)* | Floppy/diskette outline (`SaveAs` adds corner `+` badge) |
| `Undo` / `Redo` | Text `"Undo"`/`"Redo"` | Curved return arrow left (`Undo`) / right (`Redo`) |
| `Import` / `Export` | `"Export\n"` wrap | Tray with inward arrow (`Import`) / outward arrow (`Export` / `Pack`) |
| `TouchMode` | *(missing)* | Stylus/hand tap ripple icon (toggles `touchComfortMode`) |
| `UiScale` | *(MenuBar only)* | Magnifier / `Aa` frame icon |
| `DockLeft` / `DockRight` / `DockBottom` | *(missing)* | Split-window rectangle with shaded left, right, or bottom sidebar rail |
| `ToolSelect` | `"⬚"` | Dashed marquee square (`4` corner L-brackets + subtle cursor arrow) |
| `ToolBrush` / `ToolPencil` | `"✎"` | Angled pixel-art stylus/pencil with crisp nib |
| `ToolFill` | *(text only)* | Tilted paint bucket with single vector droplet |
| `ToolEyedropper` | `"💧"` *(color emoji!)* | Clean `45°` pipette dropper outline (monochrome, tintable!) |
| `ToolPanHand` | *(missing)* | Open palm / 4-way pan arrows icon (`[H]` safe navigation mode) |
| `ZoomIn` / `ZoomOut` / `ZoomFit` | *(text only)* | `+` / `-` / 4-corner frame-fit brackets |
| `PixelGrid` | *(missing)* | `3×3` fine lattice icon |
| `SliceRect` | *(missing)* | Outer square with dashed inner top-left sub-rectangle + scissors/crop corner |
| `AliasLink` | *(missing)* | Curved symbolic forward arrow / chain link (`alias → target`) |
| `PinLock` / `PinUnlock` | *(missing)* | Lock/pin icon for `descriptorOrder` pinning |
| `ArrowUp` / `ArrowDown` / `ArrowLeft` / `ArrowRight` | `"↑"` `"↓"` `"←"` `"→"` | Crisp directional chevrons/arrows for camera D-pad & descriptor reordering |
| `RotateCw` / `RotateCcw` | `"↻"` / `"↺"` | `270°` circular arc with crisp arrowhead |
| `ChevronDown` / `ChevronRight` | `"▾"` / `"▸"` | Smooth `90°` disclosure chevrons (`4,6 → 8,10 → 12,6`) |
| `Edit` / `Trash` / `Copy` | M2 default / text | Clean `16dp` stroke edit pen, trash can, and stacked clipboard sheets |
| `EyeVisible` / `EyeHidden` | *(missing)* | Eye outline / eye with slash (toggles `Gameplay Overlay`) |
| `StatusError` / `StatusWarn` / `StatusOk` | `"•"` | Octagon/circle `!`, triangle `!`, and circle checkmark |
| `EntityPlayer` | *(missing)* | Crowned/chevron tactical shield with directional facing pointer |
| `EntityNpcGuard` / `EntityNpcPatrol` | *(missing)* | Crossed-sword/helmet emblem (`Patrol` adds loop arrow indicator) |
| `EntityWaypoint` | *(missing)* | Diamond flag/pin marker |
| `EntityChest` / `EntitySwitch` / `EntitySign` | *(missing)* | Treasure chest box, angled floor lever, and wooden signpost |
| `WalkableCheck` / `WalkableBlocked` | *(missing)* | Boot/step check vs diagonal slash-circle blocked icon |
| `ArchetypeIcon(ShapeArchetype)` | *(text `"0. Flat..."`)* | Dedicated `20×20dp` `Canvas` micro-diagram rendering isometric wireframe silhouettes for all 12 `ShapeArchetype`s (`Flat`, `Straight Wedge 1h/2h`, `Outer Corner 1h/2h`, `Inner Corner 1h/2h`, `Stairs`, `Shallow/Deep Liquid` wave, `Inset Pillar`, `Foliage Cross`). |

---

### 2.5 Reusable Studio UI Primitives (`ui/components/primitives/`)

Engineers must build and reuse these 10 primitives instead of raw `androidx.compose.material.Button`, `OutlinedTextField`, or ad-hoc `.border(...)` boxes:

1. **`StudioButton`**:
   - Variants: `Primary` (`accentPrimary` bg, white text), `Secondary` (`surface2` bg, `borderSubtle` `1dp` hairline, `textPrimary`), `Ghost` (transparent at rest, `surface2` on hover, `textSecondary`), `DangerGhost` (transparent at rest, `semanticErrorSubtle` bg + `semanticError` text on hover — used for `Unassign` and inline delete), `DangerSolid` (`semanticError` bg, white text — used **only** inside confirmation modals).
   - Height: `StudioTheme.metrics.controlHeight` (`30.dp` / `40.dp`), `RoundedCornerShape(6.dp)`, optional leading `ImageVector` (`16.dp`), disabled opacity `0.45f` without shifting border geometry.
2. **`StudioIconButton`**:
   - Square `iconButtonSize` (`30.dp` / `42.dp`), `RoundedCornerShape(6.dp)`, supports `selected: Boolean = false` (`accentSubtle` fill + `accentHover` icon tint), hover highlight (`surface2`), and integrated `StudioTooltip(label, shortcut)`.
3. **`StudioSegmentedControl<T>`**:
   - Pill-shaped or `6.dp` rounded container (`surface0` fill, `2.dp` internal padding) housing `items: List<SegmentItem<T>>` (optional `14.dp` icon + label + optional count badge). Active segment renders `surface3` (or `accentPrimary` when `prominent = true`) with `textPrimary` `Medium` text; inactive segments have **zero** border and `textSecondary` text.
4. **`StudioTextField` & `StudioNumberStepper`**:
   - `StudioTextField`: Wraps `BasicTextField` inside a `controlHeight` (`30.dp` / `40.dp`) `Box` with `surface0` background, `RoundedCornerShape(6.dp)`, and `1.dp` border (`semanticError` when `isError`, `accentHover` when focused, `borderSubtle` at rest). Supports `placeholder`, `monospace: Boolean = false`, `leadingIcon`, `trailingContent`, and below-field `errorText` (`11.sp`, `semanticErrorText`). **Never clips descenders.**
   - `StudioNumberStepper`: Compact `[ − ]  value  [ + ]` control (`surface0` center readout + `surface2` `-`/`+` buttons, optional `StudioKeycap` hint outside the stepper) supporting click-step and direct numeric entry within `min..max`.
5. **`StudioSectionHeader`**:
   - `28.dp` (`36.dp` touch) row with optional disclosure `StudioIcons.ChevronDown`/`ChevronRight` (`collapsible = true`), `sectionHeader` title, optional `StudioBadge` count, and trailing action slot (e.g., `+ Slice`, `Quantize 15c`). Separated from prior sections by `1.dp` `borderSubtle` hairline + `8.dp` spacing.
6. **`StudioPanelSheet`**:
   - Adaptive dock container: on `Expanded`/`Medium` renders a vertical pane (`surface1`) with a single `1.dp` inner edge hairline (`Right` edge for left docks, `Left` edge for right docks); on `Compact (< 840dp)` renders either a docked bottom sheet (`RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)`, `surface1`, `borderStrong` top hairline + `32×4.dp` centered grab pill) or slide-over modal drawer with `scrim`.
7. **`StudioModalDialog`**:
   - Viewport-aware modal container rendered inside `StudioRootContent`'s overlay layer (or `Dialog` constrained by `LocalStudioViewport`):
     - `width = minOf(preferredWidth, viewport.widthDp * 0.92f)`
     - `heightIn(max = viewport.heightDp * 0.90f)`
     - Surface: `surface1` (`#181D27`), `RoundedCornerShape(12.dp)`, `1.dp` `borderStrong` outer rim, `16.dp` shadow elevation over `scrim`.
     - **3-zone vertical anatomy**:
       1. **Sticky Header**: Title (`titleModal`), optional subtitle (`caption`), top-right `Close` `StudioIconButton`, bottom `1.dp` `borderSubtle` divider.
       2. **Scrollable Body**: `Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp)` — guarantees footer buttons are **never** pushed off-screen or overlapped by canvas content on `768×1024` or `1024×768`.
       3. **Sticky Footer**: Top `1.dp` `borderSubtle` divider, right-aligned `Cancel` (`Ghost`) + Primary CTA (`Primary` or `DangerSolid`).
8. **`StudioBadge` & `StudioKeycap`**:
   - `StudioBadge`: `18.dp` tall `4.dp`-radius pill for `TEX` (`surface3`), `SLICE` (`sliceCyan` `18%` fill + `sliceCyan` text), `ALIAS` (`aliasAmber` `18%` fill + `aliasAmber` text), `$00..$EF` slot hex, and diagnostic counts.
   - `StudioKeycap`: `18.dp` tall `surface0` `4.dp`-radius monospace keycap (`V`, `B`, `I`, `H`, `[`, `]`, `R`) with `1.dp` `borderSubtle` — replaces raw `"[$shortcut]"` and broken `"- [[]]"` strings.
9. **`StudioTooltip`**:
   - Wraps desktop controls with `androidx.compose.foundation.TooltipArea` (`delayMillis = 450`), rendering a `surface3` + `borderStrong` `6.dp` card with label + optional `StudioKeycap` shortcut. Automatically bypassed when `touchComfort = true` so touch taps never leave sticky tooltips.
10. **`TransparencyCheckerboard`**:
    - Reusable `Modifier.checkerboardBackground(cellSize = 6.dp)` drawing alternating `checkerDark` (`#12151D`) and `checkerLight` (`#1B202C`) squares via `drawBehind`. Applied to Slot `0` (`$0000`) palette swatches, all `SourceTexture` / `DescriptorTable` thumbnails, `AtlasCanvas`, and `TextureEditModal`.

---

## 3. Responsive & Tablet/Touch Architecture

### 3.1 Viewport Breakpoints & `LocalStudioViewport`

At the top of `StudioRootContent`, wrap the shell in `BoxWithConstraints` to compute and provide `LocalStudioViewport`:

```kotlin
enum class StudioViewportClass {
    COMPACT,  // maxWidth < 840.dp   (e.g., Tablet Portrait 768×1024, split-screen desktop)
    MEDIUM,   // 840.dp <= maxWidth < 1280.dp (e.g., Tablet Landscape 1024×768, compact laptop)
    EXPANDED, // maxWidth >= 1280.dp (e.g., Workstation 1600×960, 1920×1080+)
}

@Immutable
data class StudioViewportInfo(
    val viewportClass: StudioViewportClass,
    val widthDp: Dp,
    val heightDp: Dp,
    val touchComfort: Boolean,
)
```

Because `BoxWithConstraints` reads exact scene constraints directly, `LocalStudioViewport` resolves identically in live AWT windows **and** offscreen `ImageComposeScene` tests (`UserGuideScreenshotGeneratorTest` at `1600×960`, `1024×768`, and `768×1024`), automatically adapting when `studioState.uiScaleMultiplier` changes effective `Dp` bounds.

| Area | `Expanded (>= 1280dp)` (`1600×960`) | `Medium (840–1279dp)` (`1024×768`) | `Compact (< 840dp)` (`768×1024` Portrait) |
|---|---|---|---|
| **Top Chrome** | 2-tier Header (`36dp` Tab Strip + `42dp` Command Bar) with full icon+text buttons | 2-tier Header; secondary I/O buttons condense to icon+tooltip (`Open`, `Save As`, `Undo`, `Redo`); primary CTAs keep labels | 2-tier Header; horizontally scrollable tabs; icon-first secondary actions; concise mode pills (`Layout / Hex / .i`, `Terrain / Gameplay`) |
| **Atlas Left Catalog & Palette** | Pinned Left Dock (`320dp`, collapsible via toggle), split vertically: Top `Textures \| Descriptors`, Bottom `BGR555 Palette` | Pinned Left Dock (`276dp`, collapsible to `0dp` via Command Bar dock button) | **Docked Bottom Sheet (`360dp` height, full `768dp` width)** with 3 horizontal tabs: `[ Textures ] [ Descriptors ] [ Palette ]` (2-col texture grid / side-by-side swatches + sliders), leaving full `768dp` width for `AtlasCanvas` / `Hex` / `Index`! |
| **Atlas `LayoutToolbar`** | Single row inside `FlowRow` — fits cleanly with `200dp+` spare width (duplicate `Export PNG` removed) | `FlowRow` (`verticalArrangement = spacedBy(6.dp)`) wraps gracefully into 2 clean rows if needed — **zero clipped or vertical text** | `FlowRow` wraps cleanly into 2 balanced rows across full `768dp` width |
| **Level Left Toolbox** | Pinned Left Dock (`204dp`): `2×2` / `2×3` labeled tool grid + 2-column `Brush Layer Mask` chips + `Shared Atlas` card | **Compact Vertical Tool Rail (`52dp` wide)**: crisp tool icon buttons + `Mask` badge button opening a floating popover + `Atlas` link icon | **Compact Vertical Tool Rail (`52dp` wide)** along left of center stage + `Mask` popover button |
| **Level Center Viewports** | `Split` (`Row`: `1.15f` `3D Iso` + `0.85f` `2D Grid` across `~1116dp`), `3D Iso`, or `2D Grid` | `Split` (`Row`: `1.1f` `3D Iso` + `0.9f` `2D Grid` across `~704dp`), `3D Iso`, or `2D Grid` | **`Split` auto-stacks vertically (`Column` across `716dp` width)**: `3D Iso` on top (`~275dp` tall → `440×275dp` crisp render!) + `2D Grid` below (`~275dp` tall), OR single maximized `3D Iso` / `2D Grid`! |
| **Level Right Inspector** | Pinned Right Dock (`280dp`, scrollable collapsible sections) | Pinned Right Dock (`268dp`, collapsible via Command Bar right-dock button) | **Docked Bottom Inspector Sheet (`330dp` height, full `768dp` width)**: 2-column responsive layout with section tabs `[ Terrain ] [ Textures ] [ Gameplay ] [ Diagnostics ]` |
| **Hex Preview Columns** | `16 bytes/row` + ASCII column | `16 bytes/row` (auto-drops ASCII column if pane `< 620dp`) | `16 bytes/row` fits cleanly across full `768dp` width (`8 bytes/row` fallback below `520dp`) |

---

### 3.2 Touch Comfort Mode (`studioState.touchComfortMode`)

Add to `StudioState`:
```kotlin
enum class TouchModePreference { AUTO, COMPACT, TOUCH }

var touchModePreference by mutableStateOf(
    getPreference("touchModePreference")?.let { runCatching { TouchModePreference.valueOf(it) }.getOrNull() }
        ?: TouchModePreference.AUTO
)
```
- Resolution rule:
  - `COMPACT` → `touchComfort = false` (`30.dp` controls)
  - `TOUCH` → `touchComfort = true` (`40–42.dp` controls, minimum `8.dp` tap spacing, larger `18.dp` slider thumbs)
  - `AUTO` → `touchComfort = (viewportClass == StudioViewportClass.COMPACT)` (automatically activates touch-sized `40–42.dp` hit targets on `768×1024` portrait tablets while keeping `1600×960` workstation builds dense and crisp, with 1-tap override in the Top Bar!).

---

### 3.3 Unified Two-Tier Studio Header (Zero Trapped `MenuBar` Actions)

Keep `Window` `MenuBar` in `Main.kt` for OS desktop conventions, **and** surface every single Studio command in the in-window Two-Tier Header (`StudioHeader`):

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ [❖] [PROTAGONIST_ROOM_ATLAS ×] [◆ protagonist_room • ×] [❖ TEXTURES ×] [ + ▾ ]     [✋ Touch] [100% ▾] [?] │ ← Tier 1: Document Tab Strip (36dp / 44dp)
├──────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ [◧] [Open] [Save] [Save As] │ [↶] [↷]        [ Layout  |  Hex  |  Index (.i) ]      [Import...] [Export...] │ ← Tier 2: Context Command Bar (42dp / 50dp)
└──────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

- **Tier 1 — Document Tab Strip (`bgApp` `#0F1218`, bottom `1dp` `borderSubtle`)**:
  - **Brand Mark (`36dp` wide)**: `StudioIcons.BrandChip` in `accentHover`.
  - **Scrollable Document Tabs (`Row` with `horizontalScroll`)**:
    - Each tab (`height = fillMaxHeight()`, horizontal padding `12.dp`, min width `120.dp`, max width `220.dp`):
      - **Document Type Icon**: `StudioIcons.DocAtlas` (tinted `sliceCyan` `#22D3EE`) for `AtlasEditorState`; `StudioIcons.DocLevel` (tinted `interactEmerald` `#34D399`) for `LevelEditorState`.
      - **Title**: `session.displayTitle` (`Ellipsis`, `12.sp`, `textPrimary` when active else `textSecondary`).
      - **Dirty Indicator**: When `session.hasUnsavedChanges == true`, render a crisp `6.dp` filled circle in `semanticWarning` (`#F59E0B`) beside the title (replacing raw `" *"` in tab UI while `Window.title` keeps `" *"`).
      - **Active Indicator**: Active tab uses `surface1` (`#181D27`) fill + `2.dp` top `accentPrimary` bar; inactive tabs sit transparently on `bgApp` (`surface0` on hover) with a `16.dp`-tall vertical hairline between inactive neighbors.
      - **Close Button (`×`)**: `tabCloseHitSize` (`24.dp` compact / `34.dp` touch) hit box around `12.dp` `StudioIcons.Close`.
  - **`[ + ▾ ]` New / Open Quick Menu Button** (immediately right of tabs):
    - Clicking opens a crisp `surface2` dropdown menu with 3 touch-friendly rows:
      1. `DocAtlas` **New Atlas Document** (`Ctrl+N`) → `studioState.newAtlasSession()`
      2. `DocLevel` **New Level Document...** (`Ctrl+Shift+N`) → `studioState.showNewLevelModal = true`
      3. `FolderOpen` **Open Document...** (`Ctrl+O`) → `studioState.promptOpenDocument()`
  - **Right-Pinned Global Controls** (never scrolled off-screen):
    - **Touch Comfort Pill (`StudioIcons.TouchMode`)**: Cycles/toggles `Compact` vs `Touch` (`accentSubtle` highlight when active).
    - **UI Scale Pill (`100% ▾`)**: Displays effective UI scale (`Math.round(studioState.uiScaleMultiplier * 100)%`); clicking opens a compact popover with `[ − ] [ Reset 100% ] [ + ]` and presets `100%`, `125%`, `150%`, `175%`, `200%`.
    - **About Button (`?`)**: Opens `AboutModal(studioState)`.

- **Tier 2 — Unified Context Command Bar (`surface1` `#181D27`, bottom `1dp` `borderSubtle`)**:
  - **Left Cluster (Universal File & History)**:
    - `LeftDockToggle` (`StudioIconButton`, shown on `Medium`/`Compact` or collapsible `Expanded`)
    - `Open` (`StudioButton(Ghost, icon = FolderOpen)`), `Save` (`StudioButton(Secondary, icon = Save)` with subtle amber dot when `activeSession?.hasUnsavedChanges == true` → `studioState.saveActiveSession()`), `Save As` (`StudioIconButton(SaveAs)` → `studioState.saveActiveSessionAs()`)
    - `16.dp` vertical `borderSubtle` divider
    - `Undo` (`StudioIconButton(Undo)`, `enabled = activeSession?.canUndo == true`) & `Redo` (`StudioIconButton(Redo)`, `enabled = activeSession?.canRedo == true`) — **single canonical location**, removing the duplicate `Undo`/`Redo` buttons previously hardcoded inside `AtlasEditorWorkspace` and `LevelTopToolbar`!
  - **Center Cluster (Active Session Mode Switcher)**:
    - **When `AtlasEditorState` is active**:
      - `StudioSegmentedControl` with 3 concise segments: **`Layout`** · **`Hex`** · **`Index (.i)`**.
    - **When `LevelEditorState` is active**:
      - Primary Authoring Mode `StudioSegmentedControl`: **`Terrain`** · **`Gameplay`** (with entity count dot/badge when `document.gameplay != null`).
      - Viewport Split `StudioSegmentedControl`: **`Split`** · **`3D Iso`** · **`2D Grid`**.
      - `Gameplay Overlay` `StudioIconButton(EyeVisible / EyeHidden)` (visible in `Terrain` mode so artists can toggle tactical markers ON/OFF while sculpting terrain).
  - **Right Cluster (Session Primary CTA & Inspector Toggle)**:
    - **When `AtlasEditorState` is active**:
      - `Import...` (`StudioButton(Secondary, icon = Import)` → `state.promptImportFile()`)
      - `Export Assets...` (`StudioButton(Primary, icon = Export)` → `state.showExportModal = true`)
    - **When `LevelEditorState` is active**:
      - `Import .i...` (`StudioButton(Secondary, icon = Import)` → `state.promptImportCa65Map()`)
      - `Save & Pack` (`StudioButton(Primary, icon = Export)`, labeled `"Save & Pack"` on Compact or `"Save & Pack .map"` / `"Save & Pack (.map+.gmap)"` when `gameplay != null` → `state.requestSave()`)
      - `RightDockToggle` (`StudioIconButton(DockRight)`, shown on `Medium`/`Compact`).

---

### 3.4 Universal Canvas Pan / Hand (`[H]`) & Zoom Gesture Specification

Every interactive authoring canvas must obey a unified pointer/touch contract so stylus and finger users never accidentally mutate pixels, move packed textures, or paint map cells when trying to navigate:

| Canvas | Available Tool Modes | Pan Gesture Rules | Zoom Rules |
|---|---|---|---|
| **`AtlasCanvas`** (`LayoutTab`) | `Move [V]` (default) · `Pan [H]` | 1-finger/left-drag pans `canvasPanOffset` when `Pan [H]` is active **or** when dragging empty canvas outside any `SourceTexture`; Middle-mouse drag or `Space + Left-Drag` always pans. | `ZoomOut [-]` / `100%..800%` / `ZoomIn [+]` / `Fit` pill in `LayoutToolbar` + `Ctrl/Cmd + ScrollWheel` + 2-finger pinch. |
| **`TextureEditModal`** (Pixel Editor) | `Pencil [B]` · `Fill [G]` · `Eyedrop [I]` · `Pan [H]` | 1-finger/left-drag pans pixel viewport when `Pan [H]` is active; Middle-mouse or `Space + Drag` always pans. Painting is **strictly suppressed** in `Pan [H]` mode. | `Fit` (auto-computes max integer texel scale fitting `BoxWithConstraints`) + `[-]` / `[+]` (`1×..32×` texel zoom) + `Grid` hairline toggle. |
| **`Iso3DViewport`** (`LevelEditor`) | `Select [V]` · `Brush [B]` · `Pick [I]` · `Pan [H]` (or Gameplay tools) | When `Pan [H]` is active (or Middle-mouse / `Space + Drag`), dragging translates `(cameraPanX, cameraPanY)` scaled by `1f / effectiveScale` with zero cell selection or brush painting. Tactile D-pad overlay remains available for step-pan & `Q/E` orbit. | Wire `LevelEditorState.isoZoom` (`0f = Fit`, or explicit `1f, 2f, 3f, 4f`) in **both** `Iso3DViewport` rendering **and** `mapCanvasToRenderPixel` so hit-picking stays 100% pixel-accurate at every zoom! |
| **`TopDownGridViewport`** (`LevelEditor`) | Shares active `LevelEditor` tool (`[V]`, `[B]`, `[I]`, `[H]`, or Gameplay tools) | When `Pan [H]` is active (or Middle-mouse / `Space + Drag`), dragging pans `gridPanOffset` inside `.clipToBounds()`. | `Fit` (default, scales `cols×rows` + rulers cleanly inside viewport) + `1×..4×` zoom pills in top-right HUD for dense `16×16` maps on tablets. |

---

## 4. Component & Workflow Specifications (Use Cases 1–8)

### 4.1 Studio Shell, Onboarding Cards & Status Bar (`UC1`)

#### Empty Untitled Atlas Onboarding (`UC1`)
- **State Invariant Preserved**: `StudioState()` with `initialFiles = emptyList()` (and `studio.newAtlasSession()` in `UC1`) continues to create 1 untitled `AtlasEditorState` (`openSessions.size == 1`, `hasUnsavedChanges == false`).
- **Visual Behavior**: When `state.atlas.textures.isEmpty() && state.atlas.slices.isEmpty() && state.atlas.aliases.isEmpty()`, `LayoutTab` renders a centered **Studio Onboarding Card Deck** over a subtle blueprint grid backdrop (instead of an empty purple rectangle):
  - **Header**: `StudioIcons.BrandChip` (`28.dp`) + `"SuperFX Asset & Level Studio"` (`18.sp SemiBold`) + subtitle `"Pack 4-bit indexed BGR555 Texture Atlases and author isometric tactical Levels for GSU / FX3."`
  - **3 Actionable Quick-Start Cards** (`Row` on `Expanded`/`Medium`, `Column` or `2+1` grid on `Compact`, `surface2` fill, `1dp` `borderSubtle`, `8.dp` radius, hover `borderStrong` + `accentSubtle` icon badge):
    1. **`Import Source Textures`** (`StudioIcons.Import` in `accentHover`) — *"Import power-of-two `.png` / `.bmp` bitmaps (`16–256px`) with automatic BGR555 palette quantization."* → Button `Import Bitmaps...` (`state.promptImportFile()`).
    2. **`New Isometric Level`** (`StudioIcons.DocLevel` in `interactEmerald`) — *"Create a `4×4` to `16×16` `.sfxlevel` terrain & `.gmap` tactical RPG document."* → Button `Create Level...` (`state.studio?.let { it.showNewLevelModal = true }`, hidden only if `state.studio == null`).
    3. **`Open Existing Project`** (`StudioIcons.FolderOpen` in `sliceCyan`) — *"Load an authored `.sfxproj`, `.sfxatlas`, or `.sfxlevel` file from disk."* → Button `Open Document...` (`state.studio?.promptOpenDocument()`).
  - **Hardware Spec Strip** (muted monospace footer pills below cards): `SNES BGR555 (Slot 0 = $0000 Transparent, $0001 Black)` · `Max 240 Descriptors ($00–$EF)` · `32 KB Bank $71 Staging Budget`.
- **Empty Left Catalog State**: In `SourceTexturesSection`, replace plain floating gray text with a clean dashed-border drop/import callout (`surface0`, `6.dp` radius) with `StudioIcons.Import` and `"No Source Textures yet — Import PNG/BMP tiles to populate this Atlas Document."`

#### Status Bar (`Main.kt`)
- Height: `statusBarHeight` (`26.dp` / `30.dp`), background `bgCanvas` (`#0A0C10`), top single-edge `1.dp` `borderSubtle` hairline (remove 4-sided `.border(1.dp)`).
- Layout fix for `768dp` Portrait:
  - Left `Text(statusMessage)`: **`Modifier.weight(1f).padding(end = 12.dp)`**, `maxLines = 1`, `overflow = TextOverflow.Ellipsis` — **never** collides with right telemetry!
  - Right telemetry cluster (`Row(horizontalArrangement = spacedBy(10.dp))`):
    - Document summary pill (`Atlas: 128×128 (16 KB / 32 KB) · 18 Tex · 15/15 Pal` or `12×12 (1,160 B) · Yaw 45° · 0 Err, 0 Warn`)
    - Subtle `12.dp` vertical divider + `UI: 100%` (`semanticInfo`).

---

### 4.2 Atlas Editor: Source Textures, Descriptors (`VirtualSlice` & `DescriptorAlias`), Palette & Layout (`UC2`, `UC3`, `UC4a`)

#### A. Atlas Catalog Dock (`Textures` + `Descriptors`)
Replace `LeftPane` with `AtlasCatalogDock`:
- On `Expanded` (`320.dp`) and `Medium` (`276.dp`): Vertical split with **Catalog Section** (`weight(0.56f)`) above **BGR555 Palette Section** (`weight(0.44f)`), separated by a `1.dp` `borderSubtle` hairline (replacing the heavy `2.dp` gray bar).
- On `Compact (< 840dp)`: Docked Bottom Sheet (`360.dp` tall) with a 3-segment header pill **`Textures (N)`** · **`Descriptors (M/240)`** · **`Palette (P/15)`**.

1. **`Textures` View (`SourceTexturesSection`)**:
   - Header: `StudioSegmentedControl` switching **`Textures (15)`** | **`Descriptors (17)`** + trailing `StudioButton(Secondary, "Import...", icon = Import)`.
   - Each `SourceTexture` card (`minHeight = 46.dp`, `6.dp` radius, borderless `surface2` at rest, `accentSubtle` + `1.dp` `accentBorder` when selected):
     - `36.dp` `Image(..., filterQuality = FilterQuality.None)` over `Modifier.checkerboardBackground(4.dp)` + `1.dp` `borderSubtle`.
     - Center column: `tex.name` (`monoBody`, `textPrimary`) + `"${tex.width}×${tex.height} @ (${tex.x}, ${tex.y})"` (`caption`, `textSecondary`).
     - Trailing actions: `AddSlice` (`StudioIconButton(SliceRect)`, pre-populates `SliceEditorModal` with `parent = tex.name`), `EditPixels` (`StudioIconButton(Edit)` → `state.editingTextureIndex = index`), `Delete` (`StudioIconButton(Trash)` in `textMuted`, hover `semanticError`).
   - **Inline Dependent Slices & Aliases Tree (Critical for `UC4a` Discoverability)**:
     - Whenever a `SourceTexture` is referenced by one or more `VirtualSlice`s or `DescriptorAlias`es, render compact indented child rows (`padding(start = 20.dp)`, connected by a `1.dp` L-connector line in `borderStrong`) directly beneath the parent `SourceTexture` card:
       - Slice child row: `StudioBadge("SLICE", sliceCyan)` + `roof_top_half` (`monoCaption`) + `16×16 +(0,0)` + `Edit` / `Trash` micro-buttons. Clicking selects and pulses that slice's dashed region on `AtlasCanvas`!
       - Alias child row: `StudioBadge("ALIAS", aliasAmber)` + `hero_trim_alias` (`monoCaption`) + `→ roof_top_half` + `Edit` / `Trash` micro-buttons.
     - **Why this matters**: Both human users **and** headless `UC4a` captures immediately see `roof_top_half` and `hero_trim_alias` bound to `roof` in the left catalog without needing to switch tabs first!

2. **`Descriptors` View (`DescriptorTableSection` — Full `$00..$EF` Authoring & Ordering)**:
   - Action sub-bar:
     - `[ + Slice ]` (`StudioButton(Secondary, icon = SliceRect)`) — opens `SliceEditorModal`.
     - `[ + Alias ]` (`StudioButton(Secondary, icon = AliasLink)`) — opens `AliasEditorModal`.
     - Spacer + **`Order: Auto` / `Order: Pinned`** toggle chip (`StudioIcons.PinLock`):
       - When `atlas.descriptorOrder == null` (`Auto`), clicking calls `document.pinCurrentDescriptorOrder()` so existing `.map` u8 indices are locked against future insertions.
       - When `Pinned`, clicking offers `"Unpin (Reset to Auto Order)"` (`document.withDescriptorOrder(null)`).
   - Budget bar: Compact progress strip showing `${entries.size} / 240 Descriptors ($00..$${String.format("%02X", (entries.size - 1).coerceAtLeast(0))}) · $F0..$FF Solid Colors`.
   - Resolved `DescriptorTable` list (`LazyColumn` over `table.entries.withIndex()`):
     - Each row displays:
       - Slot Hex Pill: `$00`..`$EF` (`StudioBadge` in `monoCaption`).
       - `28.dp` live extracted preview bitmap (`descriptorTable.extractPreviewTexture(desc)` with `FilterQuality.None` + checkerboard).
       - Name + Kind badge (`TEX`, `SLICE`, or `ALIAS`) + resolved byte offset/dimensions (`off $0000 · 16×16`).
       - Reorder & CRUD buttons: `MoveUp` (`↑`) / `MoveDown` (`↓`) calling `state.moveDescriptorInOrder(idx, idx ± 1)`, plus `Edit` / `Delete` for `SLICE` and `ALIAS` entries.
   - **Safe Cascading Delete Flow**:
     - If `removeTexture`, `removeSlice`, or `removeAlias` with `cascadeDependents = false` fails because dependent slices/aliases exist, open a `StudioModalDialog` listing the exact dependent descriptors (e.g., `"Deleting 'roof' will also remove slice 'roof_top_half' and alias 'hero_trim_alias'."`) with `Cancel` and `Delete & Cascade` (`cascadeDependents = true`).

3. **`SliceEditorModal` (Create / Edit `VirtualSlice`)**:
   - Preferred width: `560.dp`.
   - Fields:
     - **Slice Symbol Name** (`StudioTextField`, monospace, live ca65 + case-insensitive collision validation against textures, slices, and aliases).
     - **Parent Source Texture** (Dropdown showing thumbnail + name + `W×H` of each `SourceTexture`).
     - **Power-of-Two Dimensions**: Segmented pills for `Width` and `Height` filtered to valid powers of two `<= parent.width` / `<= parent.height` (`16, 32, 64, 128, 256` — note `VirtualSlice` requires `SourceTexture.VALID_DIMENSIONS` starting at `16`).
     - **Sub-Rectangle Offset (`X`, `Y`)**: `StudioNumberStepper` (`0 .. parent.width - sliceWidth`, step `4` or `1`) **synchronized live with an interactive Parent Texture Sub-Rect Canvas**:
       - Renders the parent `SourceTexture` scaled up (`FilterQuality.None` + checkerboard), dims texels outside the slice with `50%` dark overlay, and draws a draggable `2.dp` dashed `sliceCyan` (`#22D3EE`) selection box so the artist can visually click/drag to position `(x, y)` inside the parent tile!
   - Commit calls `document.addSlice(slice)` or `document.updateSlice(index, slice)` through `AtlasEditorState`.

4. **`AliasEditorModal` (Create / Edit `DescriptorAlias`)**:
   - Preferred width: `460.dp`.
   - Fields:
     - **Alias Symbol Name** (`StudioTextField`, monospace, live ca65 + uniqueness check).
     - **Target Descriptor** (Filterable list/dropdown of all existing `SourceTexture`s, `VirtualSlice`s, and non-cyclic `DescriptorAlias`es with `24.dp` preview thumbnails and `$XX` slot badges).
     - Live validation via `DescriptorTable.fromAtlas` to block cyclic chains (`A → B → A`) before enabling `Save Alias`.

#### B. BGR555 Palette Section (`PaletteSection`)
- Header: `SNES Palette` + `StudioBadge("${palette.assignedCount}/15")` + trailing `StudioButton(Secondary, "Quantize 15c")`.
- **16-Swatch Matrix (`2 rows × 8 cols`, `4.dp` gap, inside `surface0` `6.dp` tray)**:
  - **Slot `0` (`$0000`)**: Renders `TransparencyCheckerboard` (`4.dp` cells) with a subtle diagonal `borderStrong` slash or muted `"0∅"` micro-label — immediately recognizable as hardware transparency.
  - **Slots `1..15`**:
    - Assigned slots fill with `Color(color.argb)` + `1.dp` `swatchInnerRim` (`#24FFFFFF`) — **no harsh white borders**. If `color == SnesColor(1, 0, 0)` (`$0001` opaque black), display a subtle `"$01"` micro-badge in `#94A3B8` so artists distinguish SuperFX opaque black (`$0001`) from unassigned slots.
    - Unassigned slots fill with `surface1` + diagonal hatch line + muted slot index.
    - **Active Swatch Ring**: Selected slot renders a `2.dp` `accentHover` (`#60A5FA`) outer ring + `1.dp` `bgCanvas` inner contrast ring.
- **Selected Slot Inspector (`curSlot in 1..15`)**:
  - Top row: `20.dp` live swatch preview + `"Slot $curSlot"` (`bodyMedium`) + `StudioBadge(color.hexString, monospace)` (e.g., `$20E8`) + `#RRGGBB` 8-bit equivalent in `textMuted` + right-aligned `StudioButton(DangerGhost, "Unassign", icon = Trash)` (quiet gray at rest, red only on hover!).
  - **5-Bit Channel Rows (`R`, `G`, `B`, range `0..31`)**:
    - Each row: Channel pill (`R` in `#F87171`, `G` in `#4ADE80`, `B` in `#60A5FA`, `20.dp` wide) + custom slim `Canvas`/Compose track slider (`6.dp` rounded track tinted toward channel color, `14.dp`/`18.dp` circular thumb, batched undo via `beginEditBatch`/`endEditBatch`) + compact `StudioNumberStepper(value = 0..31)` (`[-]` `27` `[+]`) for exact 1-increment BGR555 tuning without fighting slider mouse precision!

#### C. Responsive `LayoutToolbar` & `AtlasCanvas` (`LayoutTab`)
- **`LayoutToolbar`**:
  - Uses `OptIn(ExperimentalLayoutApi::class) FlowRow` (`modifier = Modifier.fillMaxWidth().background(surface1).drawBottomHairline().padding(horizontal = 12.dp, vertical = 6.dp)`, `horizontalArrangement = Arrangement.spacedBy(12.dp)`, `verticalArrangement = Arrangement.spacedBy(6.dp)`):
    1. **Canvas Tool Pill**: `StudioSegmentedControl` with `[ Move (V) | Pan (H) ]` (icons `ToolSelect` / `ToolPanHand`).
    2. **Sheet Dimensions & Packing**: `W: [64 ▾] × H: [256 ▾]` (`DimensionDropdown` styled as `surface0` `6.dp` select pills with `ChevronDown`) + `Auto-Fit` (`Secondary`) + `Auto-Pack` (`Secondary`) + **Bank `$71` Budget Badge** (`"16 KB / 32 KB"` in `semanticSuccessSubtle` when `<= 32768` bytes; `"64 KB (>32 KB Bank $71!)"` in `semanticWarningSubtle` when `256×256`).
    3. **Grid Snap Pill**: `Snap` label + `StudioSegmentedControl` `[ 4px | 8px | 16px ]`.
    4. **Zoom Cluster**: `StudioSegmentedControl` / pill bar `[ − | 1× | 2× | 3× | 4× | Fit | + ]`.
    - *(Note: Remove the duplicate `"Export PNG..."` green button from `LayoutToolbar` that caused `"Exp\nort"` wrapping at `1600×960` — PNG export is already cleanly handled by `Export Assets...` in the Command Bar and `File` menu!)*
- **`AtlasCanvas`**:
  - Outer stage: `Modifier.fillMaxSize().background(bgCanvas).clipToBounds()` with pan/zoom gesture handling and subtle coordinate ruler ticks (`0, 32, 64, 128...`) along the top and left edges of the sheet.
  - Sheet background: `TransparencyCheckerboard` (`8.dp` cells) bounded by a crisp `1.dp` `borderStrong` frame (replacing the thick `2.dp` electric indigo border) + subtle `gridSnap` lines (`#18FFFFFF`).
  - Placed `SourceTexture`s: Rendered with `FilterQuality.None`. Unselected textures have a subtle `1.dp` `#40FFFFFF` outline; selected texture has a `2.dp` `accentHover` (`#60A5FA`) outline; valid drag shows `semanticSuccess` (`#10B981`), collision/OOB drag shows `semanticError` (`#EF4444`) with translucent red tint.
  - **Texture & Slice Overlays (`UC4a`)**:
    - Texture label tag (`roof`, `grass`): `10.sp` monospace pill (`#CC0A0C10` bg, `3.dp` radius) clipped cleanly inside the texture bounds (single-line `Ellipsis` so `"stone_cracked"` never wraps into `"stone_crack\ned"` or `"foliag\ne"` across the tile!).
    - **`VirtualSlice` Dashed Region Overlay**: For every `slice` in `state.atlas.slices`, resolve its parent `SourceTexture` and draw a crisp `1.5.dp` dashed rectangle (`PathEffect.dashPathEffect(floatArrayOf(5f, 4f))`) in `sliceCyan` (`#22D3EE`, or `aliasAmber` `#FBBF24` `2.dp` when selected) at `(parent.x + slice.x, parent.y + slice.y)` of size `(slice.width, slice.height)`, with a compact corner badge (`SliceRect` + `slice.name`).

---

### 4.3 Atlas Modals & Binary/Index Inspection (`UC2`, `UC3`, `UC4b`, `UC4c`, `UC4d`)

#### A. `PaletteRemappingModal` (`UC2`)
- Container: `StudioModalDialog(preferredWidth = 680.dp, title = "Resolve Palette Conflict", subtitle = "Importing '${pending.suggestedName}' (${pending.width}×${pending.height})")`.
- **Conflict Banner**: Compact `surface0` + `1.dp` `semanticWarning` left-accent callout explaining `"${pending.unmappedColors.size} incoming color(s) exceed open slots in the 15-color opaque SNES palette."`
- **Live Comparison Strip (`height = 104.dp` — fixes `uc02` bottom-clipped filenames!)**:
  - `LazyRow` inside `surface0` (`8.dp` padding, `12.dp` item spacing):
    - Pin **Incoming Preview Card** first (`56.dp` nearest-neighbor preview over checkerboard, `accentHover` `1.5.dp` ring, `"${incomingPreview.name}"` + `StudioBadge("NEW", accentPrimary)` — never clipped!).
    - Vertical `1.dp` `borderSubtle` divider, followed by existing `SourceTexture` cards (`56.dp` image + `maxLines = 1`, `Ellipsis` `11.sp` label with full `20.dp` label line-height).
- **Quick Resolution Action Cards** (2 equal-width `surface2` secondary cards with icon + title + 1-line explanation, replacing the clashing neon blue/purple buttons):
  1. **`Auto-Snap to Existing Palette`** (`StudioButton(Secondary)`) — *"Keep existing palette untouched; map new pixels to nearest BGR555 slot."*
  2. **`Global 15-Color Re-Quantize`** (`StudioButton(Secondary)`) — *"Recompute optimal 15-color Median-Cut across all textures + new image."*
- **Manual Color-to-Slot Mapping List**:
  - Each row (`surface1`, `6.dp` radius, `38.dp` / `44.dp` height) shows:
    - Incoming color swatch (`22.dp`) + `${color.hexString}` (`monoBody`) + `Unmapped` amber dot if `color in pending.unmappedColors`.
    - `→` arrow + **Target Slot Selector Button** showing **both** the target palette slot's live color swatch (`16.dp` / checkerboard for Slot `0`) **and** `"Slot $targetSlot (${state.palette[targetSlot].hexString})"`! Clicking opens a compact **4×4 visual swatch grid popover** (`0..15`) for instant 1-tap visual color picking instead of scrolling a 16-row text menu!
- **Sticky Footer**: `Cancel` (`Ghost`) + `Apply Manual Mapping` (`StudioButton(Primary)`).

#### B. `TextureEditModal` (`UC3` — Fixes Pixel Canvas Overflow Bug)
- Container: `StudioModalDialog(preferredWidth = 680.dp, title = "Edit Source Texture", subtitle = "${originalTex.name} (${originalTex.width}×${originalTex.height})")`.
- **Body Layout** (non-scrolling fixed flex body inside `heightIn(max = viewport.heightDp * 0.90f)` so pixel drag gestures never fight vertical modal scroll):
  1. **Symbol Row**: `"Symbol Name"` label + `StudioTextField(value = textureName, monospace = true, isError = nameError != null, errorText = nameError)` (`32.dp` / `40.dp` tall — crisp, unclipped!).
  2. **Tool & Viewport Bar (`FlowRow`)**:
     - Tool `StudioSegmentedControl`: **`Pencil [B]`** (`ToolPencil`) · **`Fill [G]`** (`ToolFill`) · **`Eyedrop [I]`** (`ToolEyedropper`) · **`Pan [H]`** (`ToolPanHand`).
     - Pixel History: `Undo` / `Redo` `StudioIconButton`s.
     - Canvas Controls: `Grid` toggle (`StudioIconButton(PixelGrid)`, default `ON` when `texelPx >= 8f`) + `[-]` `Fit` `[+]` zoom pills.
     - Right: `1×` actual-size live preview thumbnail (`32.dp` framed box over checkerboard) + Active Slot pill (`swatch` + `Slot 4 · $777B`).
  3. **16-Slot Palette Strip**: Single row of 16 swatches (`Slot 0` checkerboard + `1..15` with adaptive black/white slot number and `2.dp` `accentHover` active ring).
  4. **Clipped & Bounded Pixel Viewport (`Modifier.weight(1f).fillMaxWidth().background(bgCanvas, RoundedCornerShape(6.dp)).border(1.dp, borderSubtle, RoundedCornerShape(6.dp)).clipToBounds()`)**:
     - Inside `BoxWithConstraints`, compute `fitTexelDp = minOf((maxWidth - 24.dp) / w, (maxHeight - 24.dp) / h).coerceAtLeast(1.dp)`.
     - Multiply by `zoomMultiplier` (`1f` at `Fit`) and apply `panOffset` when zoomed past viewport bounds.
     - **Guaranteed invariant**: Because `.clipToBounds()` and `BoxWithConstraints` bound the `Canvas` to the exact remaining flex height (`weight(1f)`), the pixel grid **can never** overflow onto the Symbol bar above or the `Cancel` / `Apply Changes` footer below on `1600×960`, `1024×768`, or `768×1024`!
- **Sticky Footer**: `Cancel` (`Ghost`) + `Apply Changes` (`StudioButton(Primary, enabled = nameError == null)`).

#### C. `HexPreviewTab` (`UC4b`) & `IndexPreviewTab` (`UC4c`)
- **`HexPreviewTab`**:
  - **BGR555 Palette Word Strip**: Wrap in `FlowRow` (8 words × 2 rows on narrow screens, 16 words × 1 row on wide screens) inside `surface0`. Each word (`0000`, `0001`, `082B`...) sits next to a tiny `8.dp` color dot of that palette slot and is clickable to select `selectedPaletteSlot`! Header export action uses `StudioButton(Secondary, "Export .pal...", icon = Export)`.
  - **Graphics Binary Hex Table**: Wrap in `BoxWithConstraints`:
    - `bytesPerRow = if (maxWidth >= 560.dp) 16 else 8`
    - `showAscii = maxWidth >= 680.dp`
    - Header includes byte size, highlighted texture pill (`Highlighting: overlook_carpet (1,024 B)` + `"Scroll to Texture"` button jumping `LazyListState` directly to `tex.y * atlas.width / bytesPerRow`!), and `StudioButton(Secondary, "Export .bin...", icon = Export)`.
    - Highlighted bytes belonging to `selectedTexture` render over a subtle `aliasAmber` `14%` cell background with crisp `#FDE68A` text; `$00` transparent bytes render in `textMuted` (`#475569`); non-zero bytes render in `textPrimary`.
- **`IndexPreviewTab`**:
  - Header: `ca65 Atlas Index (${state.projectName}.i)` + `Copy to Clipboard` (`StudioButton(Secondary, icon = Copy)`) + `Export .i File...` (`StudioButton(Secondary, icon = Export)`).
  - Code Viewer (`surface0`, `6.dp` radius, `1.dp` `borderSubtle`): Render line-by-line with a muted `40.dp` line-number gutter (`1..N`) and lightweight `AnnotatedString` syntax tinting (`;;` comments in `textMuted`, `.struct`/`.endstruct`/`.ifndef`/`.endif`/`.word` directives in `accentHover`, `$XXXX` hex constants in `aliasAmber`, symbols in `textPrimary`), with both vertical and horizontal scroll so long lines never wrap awkwardly on `768dp`.

#### D. `ExportModal` (`UC4d`) & `NewLevelModal` (`UC5`)
- **`ExportModal`**:
  - Container: `StudioModalDialog(preferredWidth = 580.dp, title = "Export Texture Assets", subtitle = "Write runtime binaries, ca65 header, and preview sheet")`.
  - Replace both M2 `OutlinedTextField` instances with `StudioTextField` (`monospace = true`) paired with a same-height (`30.dp`/`40.dp`) `StudioButton(Secondary, "Browse...", icon = FolderOpen)` — **100% eliminates the half-sliced path and `PROTAGONIST_ROOM_ATLAS` text bug seen in `uc04d`!**
  - File checklist (`surface0` container, `1.dp` `borderSubtle` dividers): Each row shows a clean custom checkbox, monospace filename (`sliceCyan`), file role badge (`GSU RAM`, `CGRAM`, `Bank $71`, `ca65`, `Preview`), and exact byte size.
  - Sticky Footer: Left-side total byte summary (`Total runtime payload: 16.2 KB`) + right-side `Cancel` (`Ghost`) & `Export Selected` (`Primary`).
- **`NewLevelModal` (`UC5`)**:
  - Container: `StudioModalDialog(preferredWidth = 520.dp, title = "New Isometric Level", subtitle = "Create a Level Document (.sfxlevel) linked to a Texture Atlas")`.
  - **Level Name**: `StudioTextField(value = levelName, monospace = true)`.
  - **Shared Atlas Picker**:
    - `StudioTextField(value = atlasPath, monospace = true)` + `Browse...` (`Secondary`).
    - **Open-Tab Quick Chips**: If `studio.openSessions` contains any open `AtlasEditorState` tabs, render clickable quick-link pills directly beneath the field (`Link open tab: [❖ DEMO_ATLAS.sfxproj]`) so linking an open atlas takes a single tap without opening an OS file dialog!
  - **Grid Dimensions (`4..16`)**: Side-by-side `Columns` and `Rows` `StudioNumberStepper(value = 4..16)` + quick preset chips (`8×8`, `12×12`, `16×16`) + live `.map` byte readout (`8×8 = 520 bytes`).
  - Sticky Footer: `Cancel` (`Ghost`) + `Create Level` (`Primary`).

---

### 4.4 Level & Gameplay Editor (`UC5`, `UC6`, `UC7`, `UC8`)

#### A. Mode Architecture (`Terrain` vs `Gameplay` + Independent `Gameplay Overlay`)
Add to `LevelEditorState`:
```kotlin
enum class LevelEditMode { TERRAIN, GAMEPLAY }

enum class GameplayTool {
    SELECT_ENTITY,     // [V] Select/drag entities or inspect cell walkability
    PAINT_WALKABILITY, // [B] Paint Walkable / Blocked / Move Cost (0..7)
    PLACE_PLAYER,      // [P] Move PlayerSpawn or cycle N/E/S/W facing
    PLACE_NPC,         // [N] Place new NpcSpawn on walkable cell
    EDIT_WAYPOINTS,    // [M] Click cells to append/remove 1..4 patrol waypoints on selected NPC
    PLACE_INTERACTABLE // [O] Place Chest / Switch / Sign on cell
}

var editMode by mutableStateOf(LevelEditMode.TERRAIN)
var gameplayTool by mutableStateOf(GameplayTool.SELECT_ENTITY)
var showGameplayOverlay by mutableStateOf(initialDocument.gameplay != null)
var paintWalkable by mutableStateOf(true)
var paintMoveCost by mutableStateOf(1)
var paintWalkFlags by mutableStateOf(0)
var pendingInteractableKind by mutableStateOf(InteractableKind.CHEST)
```
- **Crucial Default Invariant (`UC6` vs `UC7`)**:
  - In `openLevel(file)`, initialize `showGameplayOverlay = (loaded.gameplay != null)`.
  - Consequently, `protagonist_room.sfxlevel` (`gameplay == null` in `UC6`/`UC8`) keeps viewports clean and purely terrain-focused, whereas `demo_level.sfxlevel` (`gameplay != null` in `UC7`) **automatically renders all tactical markers, patrol routes, walkability badges, and contextual cell gameplay inspection** on load without requiring extra setup calls!
  - Switching `editMode = LevelEditMode.GAMEPLAY` automatically forces `showGameplayOverlay = true`. In `TERRAIN` mode, the artist can toggle `showGameplayOverlay` freely via the Command Bar eye button.

#### B. Adaptive Center Viewports (`Iso3DViewport` & `TopDownGridViewport`)
- **Responsive `Split` Layout**:
  - `Expanded (>= 1280dp)` & `Medium (840–1279dp)`: Horizontal `Row` (`1.15f` `Iso3DViewport` | `1.dp` `borderSubtle` | `0.85f` `TopDownGridViewport`).
  - `Compact (< 840dp)`: **Vertical `Column`** (`1f` `Iso3DViewport` | `1.dp` `borderSubtle` | `1f` `TopDownGridViewport`) beside the `52.dp` Left Tool Rail and above the `330.dp` Bottom Inspector Sheet. Every viewport gets `> 700dp` width and `~275dp` height — **eliminating the `140dp` portrait sliver permanently!**
- **`Iso3DViewport` Refinements**:
  - **Zoom & Pan Wiring**: Respect `state.isoZoom` (`0f` = `Fit` to viewport with `0.5f` floor; or explicit `1f, 2f, 3f, 4f` integer scale) in **both** `Canvas` drawing (`drawImage(..., filterQuality = FilterQuality.None)`) and `mapCanvasToRenderPixel(...)` hit-testing!
  - **Top-Left Floating HUD Pill** (`#CC0F1218` bg, `1.dp` `borderSubtle`, `6.dp` radius): `3D Iso · 256×160 · Yaw ${state.cameraYaw * 3}° · Pan (${state.cameraPanX}, ${state.cameraPanY})`.
  - **Top-Right Zoom Pill**: Compact `[ Fit | 1× | 2× | 3× ]` + `Reset Camera` (`StudioIconButton`).
  - **Bottom-Right Tactile Camera Cluster (`IsoCameraControlsOverlay`)**:
    - Styled as a unified `surface1` (`85%` alpha) + `borderStrong` `8.dp` pod using crisp `StudioIcons` (`RotateCw` `Q`, `ArrowUp` `W`, `RotateCcw` `E` / `ArrowLeft` `A`, `ArrowDown` `S`, `ArrowRight` `D`), `38×30.dp` (`46×38.dp` in Touch Comfort) buttons with press-and-hold repeat preserved.
  - **3D Gameplay Overlay (when `showGameplayOverlay && document.gameplay != null`)**:
    - Project entities onto cell floor centers (`poly = renderResult.cellFloorPolygons[r * cols + c]`):
      - Draw `patrolWaypoints` loop first as a `1.5.dp` dashed `waypointGold` polyline across 3D cell centers with small numbered `1..4` nodes.
      - Draw `PlayerSpawn` (`playerSpawnCyan` diamond/shield + camera-yaw-transformed facing arrow), `NpcSpawn` (`npcRose` pin + `id` tag on selected cell), and `InteractableSpawn` (`interactAmber`/`Violet`/`Emerald` icon badge).
- **`TopDownGridViewport` Refinements**:
  - **Coordinate Rulers (`N is Up`)**: Render `16.dp` muted monospace column headers (`0 .. cols-1`) along the top edge and row headers (`0 .. rows-1`) along the left edge of the grid so coordinates like `(4, 6)` or `(3, 3)` are readable at a glance.
  - **Cell Rendering**: Sharp `0.dp` cells (`1.dp` dark grid gap), elevation brightness step + optional subtle elevation badge (`1h`, `2h`) in top-left of elevated cells when `cellPix >= 28f`, cyan (`1h`) / amber (`2h`) perimeter `EdgePanels` ticks, crisp vector slope/stair direction arrow (replacing the lollipop stick+dot), diagnostic outlines (`semanticError` / `semanticWarning`), and `accentInfo` (`#38BDF8`) selection frame.
  - **2D Gameplay Overlay (when `showGameplayOverlay && document.gameplay != null`)**:
    1. **Walkability & Cost Layer**:
       - Compute effective walkability via `GameplayBinarySerializer.buildWalkabilityGrid(grid, gameplay)`.
       - Non-walkable cells: subtle `45°` diagonal red/slate hatch (`#33EF4444`) + small corner `×` if explicitly overridden via `WalkabilityOverride`.
       - Walkable cells with `moveCost > 1` or `flags > 0`: bottom-right `microBadge` (`"2"`..`"7"` in `aliasAmber`).
    2. **Patrol Waypoint Loops**:
       - For each `NpcSpawn` with `patrolWaypoints.isNotEmpty()`, draw a dashed `waypointGold` (`#FACC15`) vector path connecting `WP1 → WP2 → ... → WP1` with directional mid-segment arrowheads (`100%` alpha when NPC or its cell is selected, `45%` alpha otherwise) and numbered `14.dp` circular badges (`1`, `2`, `3`, `4`).
    3. **Entity Tokens**:
       - **Player Spawn**: Centered `playerSpawnCyan` (`#38BDF8`) circular shield token (`StudioIcons.EntityPlayer`) with a bold cardinal facing triangle pointing `N` (up), `E` (right), `S` (down), or `W` (left).
       - **NPC Spawns**: Centered `npcRose` (`#FB7185`) tactical token (`EntityNpcPatrol` / `EntityNpcGuard`), pulsing white outer halo when selected.
       - **Interactables**: Centered rounded-square badge (`EntityChest` in `interactAmber`, `EntitySwitch` in `interactViolet`, `EntitySign` in `interactEmerald`).

#### C. Left Toolbox & `Brush Layer Mask` (`LevelLeftDock` / `LevelToolRail`)
- **In `Terrain` Mode**:
  - **4 Tools** (`2×2` grid on `Expanded` `204.dp` dock; vertical `4-icon` stack on `Medium`/`Compact` `52.dp` rail):
    - **`Select`** (`ToolSelect`, `V`) · **`Brush`** (`ToolBrush`, `B`) · **`Pick`** (`ToolEyedropper`, `I`) · **`Pan`** (`ToolPanHand`, `H`).
  - **`Brush Layer Mask` (Compact 2-Column Pill Grid — replaces 5 giant full-width boxes!)**:
    - Header: `BRUSH MASK` + tiny `All` / `None` ghost links.
    - Compact toggle chips (`26.dp` / `34.dp` tall, `6.dp` radius, `surface2` when OFF, `accentSubtle` + `1.dp` `accentBorder` + check dot when ON — **never** full-width M2 form boxes):
      - `[ ✓ Height ]` · `[ ✓ Shape & Rot ]`
      - `[ ✓ Top Tex ]` · `[ ✓ Skirt Tex ]`
      - `[   Panels & Flags (full span) ]`
    - On `Medium`/`Compact` (`52.dp` rail), a `Mask (4/5)` icon button on the rail opens this exact chip card in a clean floating popover!
  - **Shared Atlas Card**: Shows `DocAtlas` icon, linked filename (`PROTAGONIST_ROOM_ATLAS.sfxproj` or warning `"Unlinked"` in `semanticErrorText`), live status dot (`Live Tab` in `semanticSuccess` when resolved from an open `AtlasEditorState` tab!), and `Link...` (`Secondary`) button.
- **In `Gameplay` Mode**:
  - **6 Tactical Tools** (`2×3` grid on `Expanded`; vertical icon stack on `Medium`/`Compact` rail):
    - **`Select`** (`ToolSelect`, `V`) · **`Walk/Cost`** (`WalkableCheck`, `B`)
    - **`Player`** (`EntityPlayer`, `P`) · **`Add NPC`** (`EntityNpcPatrol`, `N`)
    - **`Waypoints`** (`EntityWaypoint`, `M`, enabled when an NPC is selected) · **`Object`** (`EntityChest`, `O`).
  - Contextual Brush Options below toolbox when `Walk/Cost` is active (`[ Walkable | Blocked ]` toggle + `Move Cost: 1..7` stepper + `Flags: 0..15` stepper + `Clear Override` button) or when `Object` is active (`[ Chest | Switch | Sign ]` selector).

#### D. Right Inspector Dock / Portrait Bottom Sheet (`LevelInspector`)
Organize into clean collapsible sections (`StudioSectionHeader`) with `1.dp` `borderSubtle` dividers:

1. **Selected Cell Gameplay Summary / Full Gameplay Inspector (`UC7`)**:
   - **When `editMode == GAMEPLAY`**:
     - If `document.gameplay == null`: Render an onboarding card `"No Gameplay Layer (.gmap)"` with description *"Add S-CPU tactical walkability, Player Spawn, Guard/Patrol NPCs, and Interactables"* and primary button **`Enable Gameplay Layer`** (initializes `GameplayLayer` with `PlayerSpawn` on first walkable cell).
     - If `document.gameplay != null`:
       - **Entity Quick-Jump Roster**: Compact collapsible list showing:
         - `Player Spawn` `@ (2, 9) · N`
         - `NPCs (2)`: `patrol_slime @ (4, 6) [PATROL · 4 WP]`, `guardian_knight @ (8, 2) [GUARD]` + `[+ Add]`
         - `Objects (1)`: `hidden_chest @ (2, 3) [CHEST · ATK +3]` + `[+ Add]`
         - Clicking any row selects its `(col, row)`, centers the camera, and opens its editor below!
       - **Cell `(c, r)` Walkability Card**: Shows effective state (`Walkable · Cost 1 (Archetype Default)` vs `Override`), 3-state segmented pill `[ Default | Walkable | Blocked ]`, `Move Cost (0..7)` `StudioNumberStepper`, and `Hazard Flags (0..15)` `StudioNumberStepper`.
       - **Contextual Entity Editor** (for entity at selected `(c, r)`):
         - **Player Spawn**: Position `(col, row)` steppers + `Facing` `[ N | E | S | W ]` segmented control.
         - **NPC Spawn (`NpcSpawn`)**: `ID` (`StudioTextField`, monospace), `AI` `[ Guard | Patrol ]`, `Sprite ID (0..255)` & `Aggro (0..255)` steppers, `2×3` compact stat grid (`HP 1..255`, `ATK`, `DEF`, `SPD`, `MOV`), **Patrol Waypoints (`0..4`)** list showing `WP1 (4,6) → WP2 (6,6) → WP3 (6,7) → WP4 (4,7)` with `[ Pick on Grid (M) ]` toggle button, per-waypoint `×` delete, and `Delete NPC` (`DangerGhost`).
         - **Interactable (`InteractableSpawn`)**: `ID` (`StudioTextField`), `Kind` `[ Chest | Switch | Sign ]`, `Closed / Open Sprite ID` steppers, `Reward` dropdown (`None`, `ATK Buff`, `HP Heal`) + `Value (0..255)` + `Message ID (0..255)`, and `Delete Object` (`DangerGhost`).
       - Footer action: `Remove Gameplay Layer...` (`DangerGhost`).
   - **When `editMode == TERRAIN` and `document.gameplay != null` (`UC7` Out-of-the-Box Inspection!)**:
     - Whenever the selected cell `(c, r)` hosts a `PlayerSpawn`, `NpcSpawn` (such as `patrol_slime` at `(4, 6)` in `UC7`!), `InteractableSpawn`, or `WalkabilityOverride`, display a compact **`Tactical Entity @ (4, 6)`** summary card right at the top of the inspector (`NPC: patrol_slime · PATROL · HP 12 ATK 5 DEF 1 SPD 4 · 4 Waypoints: (4,6)→(6,6)→(6,7)→(4,7)`) with a 1-click **`Edit Gameplay →`** button that switches `editMode = LevelEditMode.GAMEPLAY`!

2. **Cell Elevation, Shape & Rotation (`Cell (c, r)` / `N Cells Selected`)**:
   - **Base Height**: Label `"Elevation"` + readout `"0h (0wu)"` (`monoCaption`) + `StudioNumberStepper` (`−` / `+` buttons) accompanied by separate muted `StudioKeycap("[")` and `StudioKeycap("]")` badges — **never** `"- [[]]"` or `"+ []]"`!
   - **Shape Archetype**: Trigger button (`surface0`, `6.dp` radius) displaying **`StudioIcons.ArchetypeIcon(inspectedCell.archetype)`** (`20.dp` isometric wireframe icon) + `"${archetype.id}. ${archetype.displayName}"` + `StudioIcons.ChevronDown`. Dropdown renders all 12 archetypes with their wireframe icons so artists visually distinguish `Outer Corner` vs `Inner Corner` vs `Straight Wedge` instantly.
   - **Rotation**: Header with `"Rotation"` + `StudioButton(Secondary, "90°", icon = RotateCw)` + `StudioKeycap("R")`, above a full-width 4-segment `StudioSegmentedControl` `[ 0° | 90° | 180° | 270° ]` (disabled with subtle `"Symmetric"` note when `inspectedCell.archetype.isRotationallySymmetric`).

3. **Cell Texture Slots (`Top`, `Skirt`, `Panel`)**:
   - Each slot row (`surface2` at rest, `surface3` when expanded — **not** permanent bright blue borders!):
     - Left: `ChevronRight`/`ChevronDown` + slot label (`Top`, `Skirt`, `Panel`).
     - Right: **`20.dp` live nearest-neighbor preview thumbnail** (extracted via `descriptorTable.extractPreviewTexture` or solid color swatch, or a red `!` broken-link badge if `DescriptorLookup.Unknown`!) + monospace descriptor name (`overlook_carpet`, `(inherit top)` for null skirt, `(none)` for null panel).
     - **Broken Reference Styling (`UC8`)**: When `descriptorTable?.lookup(texName) is DescriptorLookup.Unknown` (e.g., `MISSING_TEX_SYMBOL` in `UC8`), tint the row background `semanticErrorSubtle`, border `1.dp` `semanticError`, and symbol text `semanticErrorText` so the invalid slot jumps out immediately!
   - Expanding a slot reveals `InlineTextureSlotPicker` (`surface0` recessed card): `Clear` button (for `Skirt`/`Panel`), `2×8` `$F0..$FF` Solid Colors strip, search filter `StudioTextField` (when `> 8` descriptors), and scrollable descriptor list with `28.dp` checkerboard-backed thumbnails + `$XX` hex indices.
   - Below slots: `StudioButton(Secondary, "Stamp Brush onto Selection", fullWidth = true)` when cells are selected.

4. **Perimeter Edge Panels (Interactive Compass Widget)**:
   - Replace the plain linear `[N] [E] [S] [W]` button row with a compact **Spatial Compass Cell Widget** (`76×76.dp` centered visual cell diagram with clickable `N` top bar, `S` bottom bar, `W` left bar, `E` right bar around a center tile square, flanked by quick `[N] [E] [S] [W]` pills and the `Tall 2h Wall Panel (32px)` toggle switch). Clicking any edge bar toggles that cardinal panel visually matching the `2D Top-Down Grid`!

---

### 4.5 Live Validation Diagnostics & Error Recovery (`UC8`)

Upgrade both `AtlasDiagnosticsBanner` and `LevelEditorWorkspace`'s `DiagnosticsBanner`:
- **Visual Styling**:
  - Height: `32.dp` (`40.dp` Touch Comfort), background `semanticErrorSubtle` (`#29EF4444` over `surface1`) when `errors.isNotEmpty()` else `semanticWarningSubtle` (`#26F59E0B`), with a **`3.dp` solid left accent stripe** + `1.dp` bottom hairline (`semanticError` / `semanticWarning`) — replacing the harsh full-bleed `1.dp` 4-sided neon box.
- **Interactive Diagnostic Stepper & Drawer**:
  - Track `activeDiagnosticIndex` (`0 until allDiagnostics.size`) in editor state.
  - Left: `StatusError` / `StatusWarn` vector icon + `StudioBadge(diag.code, monospace)` + `diag.message` (`weight(1f)`, `maxLines = 1`, `Ellipsis`). Clicking jumps selection and 3D camera (`selectSingleCell(c, r)`) directly to `(diag.col, diag.row)`!
  - Right:
    - Stepper pills **`[ ‹ ]  1 / 10  [ › ]`** cycling through every error and warning in `validation.allDiagnostics` and auto-focusing each offending cell!
    - Summary badge (`1 Error · 9 Warnings`) that toggles an expandable **All Diagnostics Popover/List** (`maxHeight = 220.dp`) so multiple diagnostics are never hidden behind the first item.
- **Right Inspector `Cell Diagnostics` Section (`UC8`)**:
  - Whenever the selected cell `(c, r)` has diagnostics, render structured diagnostic cards (`semanticErrorSubtle` / `semanticWarningSubtle`, `6.dp` radius) showing `[CODE]`, message, and a **1-click Quick Fix action** where applicable:
    - `UNKNOWN_TOP_TEXTURE` / `MISSING_TOP_TEXTURE` → `[ Pick Top Texture ]` (auto-expands `Top` inline texture picker and scrolls it into view).
    - `MISSING_PANEL_TEXTURE` → `[ Pick Panel Texture ]` or `[ Clear Edge Panels ]`.

---

## 5. Implementation Mapping & Verification Checklist

To implement this specification cleanly in `sfx-studio-desktop` without breaking existing tests:

| Package / File | Concrete Implementation Responsibilities |
|---|---|
| `desktop/ui/theme/StudioTheme.kt` | Define `StudioColors`, `StudioTypography`, `StudioMetrics`, `StudioViewportClass`, `StudioViewportInfo`, `LocalStudio*` composition locals, and single-edge hairline `Modifier.drawEdgeHairline(...)`. |
| `desktop/ui/icons/StudioIcons.kt` | Implement all crisp `16dp`/`20dp` `ImageVector` icons + `ArchetypeIcon(ShapeArchetype)` `Canvas` renderer. Verify `grep` finds zero `💧`, `⬚`, `✎`, `▾`, `▸`, `↻`, `↺`, `- [[]`, `+ []]` in `sfx-studio-desktop`. |
| `desktop/ui/components/primitives/*` | Implement `StudioButton`, `StudioIconButton`, `StudioSegmentedControl`, `StudioTextField` (`BasicTextField`), `StudioNumberStepper`, `StudioSectionHeader`, `StudioPanelSheet`, `StudioModalDialog`, `StudioBadge`, `StudioKeycap`, `StudioTooltip`, `TransparencyCheckerboard`. |
| `desktop/shell/StudioState.kt` & `Main.kt` | Add `touchModePreference` (`AUTO`/`COMPACT`/`TOUCH`); wrap `StudioRootContent` in `BoxWithConstraints` providing `LocalStudioViewport`; build Two-Tier `StudioHeader` (Tab Strip with type icons, dirty dots, `[+ ▾]` menu, Touch & UI Scale pills + Context Command Bar); fix Status Bar `weight(1f)` ellipsis. Preserve 1-default-session `init` invariant. |
| `desktop/atlas/AtlasEditorState.kt` | Expose `addSlice`, `updateSlice`, `removeSlice`, `addAlias`, `updateAlias`, `removeAlias`, `pinDescriptorOrder`, `unpinDescriptorOrder`, `moveDescriptorInOrder` delegating to `AtlasDocument` with undo/redo tracking (`commitDocumentChange`), plus `canvasTool` (`MOVE` / `PAN`), `canvasPanOffset`, and modal states (`editingSliceIndex` / `showNewSliceModal`, `editingAliasIndex` / `showNewAliasModal`, `pendingCascadeDelete`). |
| `desktop/atlas/AtlasEditorWorkspace.kt`, `LeftPane.kt`, `LayoutTab.kt` | Render Empty Atlas Onboarding Card Deck when `atlas.textures.isEmpty()`; build `AtlasCatalogDock` (`Textures` with inline nested `SLICE`/`ALIAS` rows + `Descriptors` `$00..$EF` tab + `BGR555 Palette` with checkerboard Slot `0`, `$0001` badge, `DangerGhost` Unassign, channel steppers) adapting to a 3-tab Bottom Sheet on `Compact (< 840dp)`; convert `LayoutToolbar` to `FlowRow` with Bank `$71` budget pill; add `AtlasCanvas` checkerboard, pan/zoom, and dashed `sliceCyan` `VirtualSlice` overlays. |
| `desktop/ui/components/*Modal.kt`, `HexPreviewTab.kt`, `IndexPreviewTab.kt` | Migrate all modals to `StudioModalDialog` + `StudioTextField`; fix `TextureEditModal` overflow via `BoxWithConstraints` + `.clipToBounds()` + `Pan [H]` + `Fit/Zoom`; add `SliceEditorModal` & `AliasEditorModal`; make `HexPreviewTab` byte columns responsive (`16` vs `8`); add line numbers & syntax tinting to `IndexPreviewTab`. |
| `desktop/level/LevelEditorState.kt` & `LevelEditorWorkspace.kt` | Add `LevelTool.PAN`, `LevelEditMode` (`TERRAIN` / `GAMEPLAY`), `GameplayTool`, `showGameplayOverlay` (default `true` when `document.gameplay != null`), and undo-tracked `GameplayLayer` mutators (`enableGameplayLayer`, `removeGameplayLayer`, `setCellWalkability`, `clearCellWalkabilityOverride`, `updatePlayerSpawn`, `addNpc`, `updateNpc`, `removeNpc`, `toggleNpcWaypoint`, `addInteractable`, `updateInteractable`, `removeInteractable`). Adapt `Medium`/`Compact` left dock to `52dp` `LevelToolRail` and `Compact` right dock to `330dp` Bottom Sheet + vertical `Split` stack; wire `isoZoom` in `Iso3DViewport` + `mapCanvasToRenderPixel`; render 2D/3D gameplay markers, patrol loops, coordinate rulers, compass `EdgePanels` widget, and interactive diagnostic stepper. |

### Visual Acceptance Criteria (`UserGuideScreenshotGeneratorTest`)
1. Regenerating screenshots with `SFX_GUIDE_PHASE=after` passes across all 25 scenes (`Desktop 1600×960`, `Tablet Landscape 1024×768`, `Tablet Portrait 768×1024`) with **zero** clipped text inputs, **zero** vertical single-letter wrapped tabs/toolbars, **zero** overflowing pixel canvases, and **zero** raw Unicode/emoji glyphs.
2. On `768×1024` Tablet Portrait, `AtlasCanvas`, `Iso3DViewport`, and `TopDownGridViewport` each receive `>= 700dp` horizontal stage width, and every modal leaves visible scrim margins on all 4 sides.
3. `UC4a` visibly displays `roof_top_half` (`VirtualSlice`) on both `AtlasCanvas` (dashed cyan sub-rect) and the catalog, alongside `hero_trim_alias` (`DescriptorAlias`).
4. `UC7` visibly displays `demo_level.sfxlevel`'s `PlayerSpawn` `(2, 9)`, `patrol_slime` `(4, 6)` with its 4-waypoint loop `(4,6)→(6,6)→(6,7)→(4,7)`, `guardian_knight` `(8, 2)`, `hidden_chest` `(2, 3)`, and tactical inspector details across all three viewports.
