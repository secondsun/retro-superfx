package dev.secondsun.sfxstudio.desktop.ui.theme

import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil

enum class ThemeModePreference(
    val displayName: String,
) {
    SYSTEM("System"),
    DARK("Dark"),
    LIGHT("Light"),
}

enum class AccentColorPreset(
    val displayName: String,
    val fallbackArgb: Int?,
) {
    SYSTEM("System Accent", null),
    COBALT("Cobalt", 0xFF3B82F6.toInt()),
    VIOLET("Violet", 0xFF8B5CF6.toInt()),
    CYAN("Cyan", 0xFF06B6D4.toInt()),
    EMERALD("Emerald", 0xFF10B981.toInt()),
    AMBER("Amber", 0xFFF59E0B.toInt()),
    ROSE("Rose", 0xFFF43F5E.toInt()),
    CORAL("Coral", 0xFFF97316.toInt()),
}

@Immutable
data class StudioColors(
    val isDark: Boolean = true,
    // Canvas & Structural Surfaces
    val bgCanvas: Color = Color(0xFF0A0C10),
    val bgApp: Color = Color(0xFF0F1218),
    val surface0: Color = Color(0xFF131720),
    val surface1: Color = Color(0xFF181D27),
    val surface2: Color = Color(0xFF212735),
    val surface3: Color = Color(0xFF2B3345),
    val scrim: Color = Color(0xB306080C),
    // Hairlines & Borders
    val borderSubtle: Color = Color(0xFF252C3C),
    val borderStrong: Color = Color(0xFF364056),
    val swatchInnerRim: Color = Color(0x24FFFFFF),
    // Typography Hierarchy
    val textPrimary: Color = Color(0xFFF1F5F9),
    val textSecondary: Color = Color(0xFF94A3B8),
    val textMuted: Color = Color(0xFF64748B),
    // Primary Brand & Selection Accent (System or Preset)
    val accentPrimary: Color = Color(0xFF3B82F6),
    val accentHover: Color = Color(0xFF60A5FA),
    val accentSubtle: Color = Color(0x293B82F6),
    val accentBorder: Color = Color(0x8060A5FA),
    val onAccent: Color = Color.White,
    // Semantic Feedback Tokens
    val semanticSuccess: Color = Color(0xFF10B981),
    val semanticSuccessSubtle: Color = Color(0x2410B981),
    val semanticWarning: Color = Color(0xFFF59E0B),
    val semanticWarningSubtle: Color = Color(0x26F59E0B),
    val semanticWarningText: Color = Color(0xFFFDE68A),
    val semanticError: Color = Color(0xFFEF4444),
    val semanticErrorSubtle: Color = Color(0x29EF4444),
    val semanticErrorText: Color = Color(0xFFFCA5A5),
    val semanticInfo: Color = Color(0xFF38BDF8),
    // Domain-Specific Authoring Accents
    val sliceCyan: Color = Color(0xFF22D3EE),
    val aliasAmber: Color = Color(0xFFFBBF24),
    val playerSpawnCyan: Color = Color(0xFF38BDF8),
    val npcRose: Color = Color(0xFFFB7185),
    val waypointGold: Color = Color(0xFFFACC15),
    val interactAmber: Color = Color(0xFFF59E0B),
    val interactViolet: Color = Color(0xFFA78BFA),
    val interactEmerald: Color = Color(0xFF34D399),
    // Transparency Checkerboard & Canvas Overlays
    val checkerDark: Color = Color(0xFF12151D),
    val checkerLight: Color = Color(0xFF1B202C),
    val pixelGridLine: Color = Color(0x20FFFFFF),
    val canvasTileBorder: Color = Color(0x45FFFFFF),
    val canvasOverlayBg: Color = Color(0xD90A0C10),
    val canvasOverlayText: Color = Color(0xFFF1F5F9),
) {
    companion object {
        const val DEFAULT_ACCENT_ARGB: Int = 0xFF3B82F6.toInt()

        fun fromAppearance(
            isDark: Boolean,
            rawAccentArgb: Int? = null,
        ): StudioColors {
            val baseArgb = rawAccentArgb ?: DEFAULT_ACCENT_ARGB
            val derived = deriveAccentTokens(baseArgb, isDark)
            return if (isDark) {
                StudioColors(
                    isDark = true,
                    accentPrimary = derived.primary,
                    accentHover = derived.hover,
                    accentSubtle = derived.subtle,
                    accentBorder = derived.border,
                    onAccent = derived.onAccent,
                )
            } else {
                StudioColors(
                    isDark = false,
                    bgCanvas = Color(0xFFE3E8F0),
                    bgApp = Color(0xFFECEFF5),
                    surface0 = Color(0xFFF3F6FA),
                    surface1 = Color(0xFFFFFFFF),
                    surface2 = Color(0xFFE8EEF6),
                    surface3 = Color(0xFFD9E2EE),
                    scrim = Color(0x660F172A),
                    borderSubtle = Color(0xFFD2DAE6),
                    borderStrong = Color(0xFFB0BED0),
                    swatchInnerRim = Color(0x290F172A),
                    textPrimary = Color(0xFF0F172A),
                    textSecondary = Color(0xFF475569),
                    textMuted = Color(0xFF64748B),
                    accentPrimary = derived.primary,
                    accentHover = derived.hover,
                    accentSubtle = derived.subtle,
                    accentBorder = derived.border,
                    onAccent = derived.onAccent,
                    semanticSuccess = Color(0xFF059669),
                    semanticSuccessSubtle = Color(0x1F10B981),
                    semanticWarning = Color(0xFFD97706),
                    semanticWarningSubtle = Color(0x24F59E0B),
                    semanticWarningText = Color(0xFF92400E),
                    semanticError = Color(0xFFDC2626),
                    semanticErrorSubtle = Color(0x1FEF4444),
                    semanticErrorText = Color(0xFF991B1B),
                    semanticInfo = Color(0xFF0284C7),
                    sliceCyan = Color(0xFF0891B2),
                    aliasAmber = Color(0xFFB45309),
                    playerSpawnCyan = Color(0xFF0284C7),
                    npcRose = Color(0xFFE11D48),
                    waypointGold = Color(0xFFD97706),
                    interactAmber = Color(0xFFD97706),
                    interactViolet = Color(0xFF7C3AED),
                    interactEmerald = Color(0xFF059669),
                    checkerDark = Color(0xFFD8DEE9),
                    checkerLight = Color(0xFFECEFF4),
                    pixelGridLine = Color(0x240F172A),
                    canvasTileBorder = Color(0x500F172A),
                    canvasOverlayBg = Color(0xDE0F172A),
                    canvasOverlayText = Color(0xFFF8FAFC),
                )
            }
        }

        private data class DerivedAccent(
            val primary: Color,
            val hover: Color,
            val subtle: Color,
            val border: Color,
            val onAccent: Color,
        )

        private fun deriveAccentTokens(
            argb: Int,
            isDark: Boolean,
        ): DerivedAccent {
            val r = ((argb shr 16) and 0xFF) / 255f
            val g = ((argb shr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val (h, s, l) = rgbToHsl(r, g, b)

            val clampedL =
                if (isDark) {
                    l.coerceIn(0.46f, 0.64f)
                } else {
                    l.coerceIn(0.36f, 0.52f)
                }
            val hoverL =
                if (isDark) {
                    (clampedL + 0.09f).coerceAtMost(0.76f)
                } else {
                    (clampedL - 0.07f).coerceAtLeast(0.26f)
                }

            val primary = hslToColor(h, s, clampedL)
            val hover = hslToColor(h, s, hoverL)
            val subtle = primary.copy(alpha = if (isDark) 0.16f else 0.13f)
            val border = (if (isDark) hover else primary).copy(alpha = if (isDark) 0.52f else 0.48f)
            val onAccent = if (primary.luminance() > 0.44f) Color(0xFF0B0F17) else Color.White

            return DerivedAccent(
                primary = primary,
                hover = hover,
                subtle = subtle,
                border = border,
                onAccent = onAccent,
            )
        }

        private fun rgbToHsl(
            r: Float,
            g: Float,
            b: Float,
        ): Triple<Float, Float, Float> {
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val l = (max + min) * 0.5f
            if (max == min) return Triple(0f, 0f, l)
            val d = max - min
            val s = d / (1f - abs(2f * l - 1f)).coerceAtLeast(1e-4f)
            val hRaw =
                when (max) {
                    r -> ((g - b) / d) % 6f
                    g -> ((b - r) / d) + 2f
                    else -> ((r - g) / d) + 4f
                }
            val h = ((hRaw * 60f) + 360f) % 360f
            return Triple(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
        }

        private fun hslToColor(
            h: Float,
            s: Float,
            l: Float,
        ): Color {
            val c = (1f - abs(2f * l - 1f)) * s
            val x = c * (1f - abs(((h / 60f) % 2f) - 1f))
            val m = l - c * 0.5f
            val (r1, g1, b1) =
                when {
                    h < 60f -> Triple(c, x, 0f)
                    h < 120f -> Triple(x, c, 0f)
                    h < 180f -> Triple(0f, c, x)
                    h < 240f -> Triple(0f, x, c)
                    h < 300f -> Triple(x, 0f, c)
                    else -> Triple(c, 0f, x)
                }
            return Color(
                red = (r1 + m).coerceIn(0f, 1f),
                green = (g1 + m).coerceIn(0f, 1f),
                blue = (b1 + m).coerceIn(0f, 1f),
                alpha = 1f,
            )
        }
    }
}

@Immutable
data class StudioTypography(
    val titleHero: TextStyle =
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
        ),
    val titleModal: TextStyle =
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
        ),
    val sectionHeader: TextStyle =
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.04.em,
        ),
    val bodyMedium: TextStyle =
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
        ),
    val bodyRegular: TextStyle =
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Normal,
        ),
    val caption: TextStyle =
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Normal,
        ),
    val microBadge: TextStyle =
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
        ),
    val monoBody: TextStyle =
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Normal,
        ),
    val monoCaption: TextStyle =
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
        ),
)

@Immutable
data class StudioMetrics(
    val touchComfort: Boolean = false,
    val spaceXXS: Dp = 2.dp,
    val spaceXS: Dp = 4.dp,
    val spaceSM: Dp = 8.dp,
    val spaceMD: Dp = 12.dp,
    val spaceLG: Dp = 16.dp,
    val spaceXL: Dp = 24.dp,
    val radiusPixel: Dp = 0.dp,
    val radiusXS: Dp = 4.dp,
    val radiusSM: Dp = 6.dp,
    val radiusMD: Dp = 8.dp,
    val radiusLG: Dp = 12.dp,
    val radiusPill: Dp = 999.dp,
    val tabStripHeight: Dp = if (touchComfort) 44.dp else 36.dp,
    val commandBarHeight: Dp = if (touchComfort) 50.dp else 42.dp,
    val controlHeight: Dp = if (touchComfort) 40.dp else 30.dp,
    val controlHeightSM: Dp = if (touchComfort) 36.dp else 26.dp,
    val iconButtonSize: Dp = if (touchComfort) 42.dp else 30.dp,
    val iconSize: Dp = if (touchComfort) 18.dp else 16.dp,
    val tabCloseHitSize: Dp = if (touchComfort) 34.dp else 24.dp,
    val swatchHeight: Dp = if (touchComfort) 34.dp else 26.dp,
    val listRowMinHeight: Dp = if (touchComfort) 52.dp else 44.dp,
    val statusBarHeight: Dp = if (touchComfort) 30.dp else 26.dp,
) {
    companion object {
        fun forTouchComfort(touchComfort: Boolean): StudioMetrics = StudioMetrics(touchComfort = touchComfort)
    }
}

enum class StudioViewportClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
    ;

    companion object {
        fun fromWidth(widthDp: Dp): StudioViewportClass =
            when {
                widthDp < 840.dp -> COMPACT
                widthDp < 1280.dp -> MEDIUM
                else -> EXPANDED
            }
    }
}

@Immutable
data class StudioViewportInfo(
    val viewportClass: StudioViewportClass = StudioViewportClass.EXPANDED,
    val widthDp: Dp = 1280.dp,
    val heightDp: Dp = 800.dp,
    val touchComfort: Boolean = false,
)

enum class HairlineEdge {
    TOP,
    BOTTOM,
    LEFT,
    RIGHT,
}

val LocalStudioColors = staticCompositionLocalOf { StudioColors() }
val LocalStudioTypography = staticCompositionLocalOf { StudioTypography() }
val LocalStudioMetrics = staticCompositionLocalOf { StudioMetrics() }
val LocalStudioViewport = staticCompositionLocalOf { StudioViewportInfo() }

object StudioTheme {
    val colors: StudioColors
        @Composable
        @ReadOnlyComposable
        get() = LocalStudioColors.current

    val typography: StudioTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalStudioTypography.current

    val metrics: StudioMetrics
        @Composable
        @ReadOnlyComposable
        get() = LocalStudioMetrics.current

    val viewport: StudioViewportInfo
        @Composable
        @ReadOnlyComposable
        get() = LocalStudioViewport.current
}

@Composable
fun ProvideStudioTheme(
    viewport: StudioViewportInfo,
    colors: StudioColors = StudioColors(),
    typography: StudioTypography = StudioTypography(),
    content: @Composable () -> Unit,
) {
    val metrics = StudioMetrics.forTouchComfort(viewport.touchComfort)
    val materialPalette =
        if (colors.isDark) {
            darkColors(
                primary = colors.accentPrimary,
                primaryVariant = colors.accentHover,
                secondary = colors.semanticSuccess,
                background = colors.bgApp,
                surface = colors.surface1,
                error = colors.semanticError,
                onPrimary = colors.onAccent,
                onSecondary = Color.White,
                onBackground = colors.textPrimary,
                onSurface = colors.textPrimary,
                onError = Color.White,
            )
        } else {
            lightColors(
                primary = colors.accentPrimary,
                primaryVariant = colors.accentHover,
                secondary = colors.semanticSuccess,
                background = colors.bgApp,
                surface = colors.surface1,
                error = colors.semanticError,
                onPrimary = colors.onAccent,
                onSecondary = Color.White,
                onBackground = colors.textPrimary,
                onSurface = colors.textPrimary,
                onError = Color.White,
            )
        }

    CompositionLocalProvider(
        LocalStudioColors provides colors,
        LocalStudioTypography provides typography,
        LocalStudioMetrics provides metrics,
        LocalStudioViewport provides viewport,
    ) {
        MaterialTheme(
            colors = materialPalette,
            content = content,
        )
    }
}

fun Modifier.drawEdgeHairline(
    edge: HairlineEdge,
    color: Color,
    strokeDp: Dp = 1.dp,
): Modifier =
    drawWithContent {
        drawContent()
        val px = strokeDp.toPx()
        when (edge) {
            HairlineEdge.TOP ->
                drawLine(
                    color = color,
                    start = Offset(0f, px * 0.5f),
                    end = Offset(size.width, px * 0.5f),
                    strokeWidth = px,
                )
            HairlineEdge.BOTTOM ->
                drawLine(
                    color = color,
                    start = Offset(0f, size.height - px * 0.5f),
                    end = Offset(size.width, size.height - px * 0.5f),
                    strokeWidth = px,
                )
            HairlineEdge.LEFT ->
                drawLine(
                    color = color,
                    start = Offset(px * 0.5f, 0f),
                    end = Offset(px * 0.5f, size.height),
                    strokeWidth = px,
                )
            HairlineEdge.RIGHT ->
                drawLine(
                    color = color,
                    start = Offset(size.width - px * 0.5f, 0f),
                    end = Offset(size.width - px * 0.5f, size.height),
                    strokeWidth = px,
                )
        }
    }

fun Modifier.checkerboardBackground(
    cellSize: Dp = 6.dp,
    darkColor: Color? = null,
    lightColor: Color? = null,
): Modifier =
    composed {
        val resolvedDark = darkColor ?: StudioTheme.colors.checkerDark
        val resolvedLight = lightColor ?: StudioTheme.colors.checkerLight
        Modifier.drawBehind {
            drawRect(color = resolvedDark)
            val step = cellSize.toPx().coerceAtLeast(2f)
            val cols = ceil(size.width / step).toInt()
            val rows = ceil(size.height / step).toInt()
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    if ((r + c) and 1 == 1) {
                        val x = c * step
                        val y = r * step
                        val w = minOf(step, size.width - x)
                        val h = minOf(step, size.height - y)
                        if (w > 0f && h > 0f) {
                            drawRect(
                                color = resolvedLight,
                                topLeft = Offset(x, y),
                                size = Size(w, h),
                            )
                        }
                    }
                }
            }
        }
    }
