package dev.secondsun.sfxstudio.desktop.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.secondsun.sfxstudio.core.level.ShapeArchetype
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioTheme

object StudioIcons {
    private fun icon16(
        name: String,
        defaultDp: Dp = 16.dp,
        build: ImageVector.Builder.() -> Unit,
    ): ImageVector =
        ImageVector
            .Builder(
                name = name,
                defaultWidth = defaultDp,
                defaultHeight = defaultDp,
                viewportWidth = 16f,
                viewportHeight = 16f,
            ).apply(build)
            .build()

    private fun ImageVector.Builder.strokePath(
        strokeWidth: Float = 1.5f,
        block: PathBuilder.() -> Unit,
    ) {
        addPath(
            pathData = PathBuilder().apply(block).nodes,
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }

    private fun ImageVector.Builder.fillPath(block: PathBuilder.() -> Unit) {
        addPath(
            pathData = PathBuilder().apply(block).nodes,
            fill = SolidColor(Color.White),
        )
    }

    val BrandChip: ImageVector by lazy {
        icon16("BrandChip") {
            strokePath {
                moveTo(4f, 4f)
                lineTo(12f, 4f)
                lineTo(12f, 12f)
                lineTo(4f, 12f)
                close()
                // Pins
                moveTo(6f, 1.5f)
                lineTo(6f, 4f)
                moveTo(10f, 1.5f)
                lineTo(10f, 4f)
                moveTo(6f, 12f)
                lineTo(6f, 14.5f)
                moveTo(10f, 12f)
                lineTo(10f, 14.5f)
                moveTo(1.5f, 6f)
                lineTo(4f, 6f)
                moveTo(1.5f, 10f)
                lineTo(4f, 10f)
                moveTo(12f, 6f)
                lineTo(14.5f, 6f)
                moveTo(12f, 10f)
                lineTo(14.5f, 10f)
                // Inner diamond
                moveTo(8f, 6f)
                lineTo(10f, 8f)
                lineTo(8f, 10f)
                lineTo(6f, 8f)
                close()
            }
        }
    }

    val DocAtlas: ImageVector by lazy {
        icon16("DocAtlas") {
            strokePath(1.4f) {
                moveTo(2f, 2f)
                lineTo(14f, 2f)
                lineTo(14f, 14f)
                lineTo(2f, 14f)
                close()
                moveTo(9f, 2f)
                lineTo(9f, 10f)
                moveTo(2f, 10f)
                lineTo(14f, 10f)
                moveTo(9f, 6f)
                lineTo(14f, 6f)
            }
        }
    }

    val DocLevel: ImageVector by lazy {
        icon16("DocLevel") {
            strokePath(1.4f) {
                moveTo(8f, 2f)
                lineTo(13.5f, 5f)
                lineTo(13.5f, 11f)
                lineTo(8f, 14f)
                lineTo(2.5f, 11f)
                lineTo(2.5f, 5f)
                close()
                moveTo(2.5f, 5f)
                lineTo(8f, 8f)
                lineTo(13.5f, 5f)
                moveTo(8f, 8f)
                lineTo(8f, 14f)
            }
        }
    }

    val Plus: ImageVector by lazy {
        icon16("Plus") {
            strokePath {
                moveTo(8f, 3f)
                lineTo(8f, 13f)
                moveTo(3f, 8f)
                lineTo(13f, 8f)
            }
        }
    }

    val Minus: ImageVector by lazy {
        icon16("Minus") {
            strokePath {
                moveTo(3.5f, 8f)
                lineTo(12.5f, 8f)
            }
        }
    }

    val Close: ImageVector by lazy {
        icon16("Close") {
            strokePath {
                moveTo(4f, 4f)
                lineTo(12f, 12f)
                moveTo(12f, 4f)
                lineTo(4f, 12f)
            }
        }
    }

    val Check: ImageVector by lazy {
        icon16("Check") {
            strokePath(1.6f) {
                moveTo(3f, 8.5f)
                lineTo(6.5f, 12f)
                lineTo(13f, 4.5f)
            }
        }
    }

    val InfoQuestion: ImageVector by lazy {
        icon16("InfoQuestion") {
            strokePath(1.4f) {
                moveTo(8f, 2f)
                arcTo(6f, 6f, 0f, true, true, 7.99f, 2f)
                close()
                moveTo(6.3f, 6.2f)
                curveTo(6.3f, 5f, 9.7f, 5f, 9.7f, 6.6f)
                curveTo(9.7f, 7.8f, 8f, 8.1f, 8f, 9.3f)
            }
            fillPath {
                moveTo(7.3f, 11.2f)
                lineTo(8.7f, 11.2f)
                lineTo(8.7f, 12.6f)
                lineTo(7.3f, 12.6f)
                close()
            }
        }
    }

    val FolderOpen: ImageVector by lazy {
        icon16("FolderOpen") {
            strokePath(1.4f) {
                moveTo(2f, 4f)
                lineTo(6f, 4f)
                lineTo(7.5f, 5.8f)
                lineTo(14f, 5.8f)
                lineTo(14f, 13f)
                lineTo(2f, 13f)
                close()
            }
        }
    }

    val Save: ImageVector by lazy {
        icon16("Save") {
            strokePath(1.4f) {
                moveTo(2.5f, 2.5f)
                lineTo(11.5f, 2.5f)
                lineTo(13.5f, 4.5f)
                lineTo(13.5f, 13.5f)
                lineTo(2.5f, 13.5f)
                close()
                moveTo(5f, 2.5f)
                lineTo(5f, 6.2f)
                lineTo(10.5f, 6.2f)
                lineTo(10.5f, 2.5f)
                moveTo(4.8f, 13.5f)
                lineTo(4.8f, 9.2f)
                lineTo(11.2f, 9.2f)
                lineTo(11.2f, 13.5f)
            }
        }
    }

    val SaveAs: ImageVector by lazy {
        icon16("SaveAs") {
            strokePath(1.4f) {
                moveTo(2f, 2.5f)
                lineTo(10.5f, 2.5f)
                lineTo(12.5f, 4.5f)
                lineTo(12.5f, 8.5f)
                moveTo(8f, 13.5f)
                lineTo(2f, 13.5f)
                lineTo(2f, 2.5f)
                moveTo(4.5f, 2.5f)
                lineTo(4.5f, 6f)
                lineTo(9.5f, 6f)
                // Plus corner
                moveTo(12f, 10f)
                lineTo(12f, 14.5f)
                moveTo(9.75f, 12.25f)
                lineTo(14.25f, 12.25f)
            }
        }
    }

    val Undo: ImageVector by lazy {
        icon16("Undo") {
            strokePath {
                moveTo(6f, 4f)
                lineTo(2.5f, 7.2f)
                lineTo(6f, 10.4f)
                moveTo(3f, 7.2f)
                lineTo(9.8f, 7.2f)
                curveTo(12.2f, 7.2f, 13.5f, 8.6f, 13.5f, 10.6f)
                curveTo(13.5f, 12.4f, 12.2f, 13.5f, 9.8f, 13.5f)
                lineTo(7.5f, 13.5f)
            }
        }
    }

    val Redo: ImageVector by lazy {
        icon16("Redo") {
            strokePath {
                moveTo(10f, 4f)
                lineTo(13.5f, 7.2f)
                lineTo(10f, 10.4f)
                moveTo(13f, 7.2f)
                lineTo(6.2f, 7.2f)
                curveTo(3.8f, 7.2f, 2.5f, 8.6f, 2.5f, 10.6f)
                curveTo(2.5f, 12.4f, 3.8f, 13.5f, 6.2f, 13.5f)
                lineTo(8.5f, 13.5f)
            }
        }
    }

    val Import: ImageVector by lazy {
        icon16("Import") {
            strokePath {
                moveTo(8f, 2.2f)
                lineTo(8f, 10.2f)
                moveTo(5.2f, 7.5f)
                lineTo(8f, 10.4f)
                lineTo(10.8f, 7.5f)
                moveTo(2.5f, 10.5f)
                lineTo(2.5f, 13.5f)
                lineTo(13.5f, 13.5f)
                lineTo(13.5f, 10.5f)
            }
        }
    }

    val Export: ImageVector by lazy {
        icon16("Export") {
            strokePath {
                moveTo(8f, 10.2f)
                lineTo(8f, 2.2f)
                moveTo(5.2f, 5f)
                lineTo(8f, 2.1f)
                lineTo(10.8f, 5f)
                moveTo(2.5f, 10.5f)
                lineTo(2.5f, 13.5f)
                lineTo(13.5f, 13.5f)
                lineTo(13.5f, 10.5f)
            }
        }
    }

    val TouchMode: ImageVector by lazy {
        icon16("TouchMode") {
            strokePath(1.4f) {
                // Tap ripple arc
                moveTo(5.2f, 4.8f)
                curveTo(5.2f, 2.8f, 10.8f, 2.8f, 10.8f, 4.8f)
                // Finger / stylus silhouette
                moveTo(8f, 4.5f)
                lineTo(8f, 10f)
                moveTo(6f, 8.5f)
                lineTo(6f, 12.5f)
                lineTo(11f, 12.5f)
                lineTo(11.8f, 9f)
                lineTo(8f, 8.5f)
            }
        }
    }

    val UiScale: ImageVector by lazy {
        icon16("UiScale") {
            strokePath(1.4f) {
                moveTo(7f, 2.5f)
                arcTo(4.5f, 4.5f, 0f, true, true, 6.99f, 2.5f)
                close()
                moveTo(10.4f, 10.4f)
                lineTo(13.8f, 13.8f)
                moveTo(5.2f, 7f)
                lineTo(8.8f, 7f)
                moveTo(7f, 5.2f)
                lineTo(7f, 8.8f)
            }
        }
    }

    val DockLeft: ImageVector by lazy {
        icon16("DockLeft") {
            strokePath(1.4f) {
                moveTo(2f, 3f)
                lineTo(14f, 3f)
                lineTo(14f, 13f)
                lineTo(2f, 13f)
                close()
                moveTo(6.2f, 3f)
                lineTo(6.2f, 13f)
            }
        }
    }

    val DockRight: ImageVector by lazy {
        icon16("DockRight") {
            strokePath(1.4f) {
                moveTo(2f, 3f)
                lineTo(14f, 3f)
                lineTo(14f, 13f)
                lineTo(2f, 13f)
                close()
                moveTo(9.8f, 3f)
                lineTo(9.8f, 13f)
            }
        }
    }

    val DockBottom: ImageVector by lazy {
        icon16("DockBottom") {
            strokePath(1.4f) {
                moveTo(2f, 3f)
                lineTo(14f, 3f)
                lineTo(14f, 13f)
                lineTo(2f, 13f)
                close()
                moveTo(2f, 9.5f)
                lineTo(14f, 9.5f)
            }
        }
    }

    val ToolSelect: ImageVector by lazy {
        icon16("ToolSelect") {
            strokePath(1.5f) {
                // 4 L-corners marquee
                moveTo(2.5f, 5.2f)
                lineTo(2.5f, 2.5f)
                lineTo(5.2f, 2.5f)
                moveTo(10.8f, 2.5f)
                lineTo(13.5f, 2.5f)
                lineTo(13.5f, 5.2f)
                moveTo(2.5f, 10.8f)
                lineTo(2.5f, 13.5f)
                lineTo(5.2f, 13.5f)
                moveTo(10.8f, 13.5f)
                lineTo(13.5f, 13.5f)
                lineTo(13.5f, 10.8f)
            }
        }
    }

    val ToolBrush: ImageVector by lazy {
        icon16("ToolBrush") {
            strokePath(1.4f) {
                moveTo(11.5f, 2.2f)
                lineTo(13.8f, 4.5f)
                lineTo(6.2f, 12.1f)
                lineTo(3f, 13f)
                lineTo(3.9f, 9.8f)
                close()
                moveTo(9.8f, 3.9f)
                lineTo(12.1f, 6.2f)
            }
        }
    }

    val ToolPencil: ImageVector get() = ToolBrush

    val ToolFill: ImageVector by lazy {
        icon16("ToolFill") {
            strokePath(1.4f) {
                moveTo(3f, 8f)
                lineTo(7.5f, 3.5f)
                lineTo(12f, 8f)
                lineTo(7.5f, 12.5f)
                close()
                moveTo(5.5f, 3.5f)
                lineTo(7.5f, 1.8f)
                // Droplet
                moveTo(13.2f, 10.2f)
                curveTo(14.4f, 11.8f, 14.4f, 13.6f, 13.2f, 13.6f)
                curveTo(12f, 13.6f, 12f, 11.8f, 13.2f, 10.2f)
            }
        }
    }

    val ToolEyedropper: ImageVector by lazy {
        icon16("ToolEyedropper") {
            strokePath(1.4f) {
                moveTo(10.2f, 2.5f)
                lineTo(13.5f, 5.8f)
                moveTo(9f, 4.2f)
                lineTo(11.8f, 7f)
                lineTo(6f, 12.8f)
                lineTo(3f, 13.2f)
                lineTo(3.4f, 10.2f)
                close()
            }
        }
    }

    val ToolPanHand: ImageVector by lazy {
        icon16("ToolPanHand") {
            strokePath(1.4f) {
                moveTo(8f, 2f)
                lineTo(8f, 14f)
                moveTo(2f, 8f)
                lineTo(14f, 8f)
                moveTo(6.2f, 3.8f)
                lineTo(8f, 2f)
                lineTo(9.8f, 3.8f)
                moveTo(6.2f, 12.2f)
                lineTo(8f, 14f)
                lineTo(9.8f, 12.2f)
                moveTo(3.8f, 6.2f)
                lineTo(2f, 8f)
                lineTo(3.8f, 9.8f)
                moveTo(12.2f, 6.2f)
                lineTo(14f, 8f)
                lineTo(12.2f, 9.8f)
            }
        }
    }

    val ZoomIn: ImageVector get() = Plus
    val ZoomOut: ImageVector get() = Minus

    val ZoomFit: ImageVector by lazy {
        icon16("ZoomFit") {
            strokePath(1.4f) {
                moveTo(2.5f, 5.5f)
                lineTo(2.5f, 2.5f)
                lineTo(5.5f, 2.5f)
                moveTo(10.5f, 2.5f)
                lineTo(13.5f, 2.5f)
                lineTo(13.5f, 5.5f)
                moveTo(2.5f, 10.5f)
                lineTo(2.5f, 13.5f)
                lineTo(5.5f, 13.5f)
                moveTo(10.5f, 13.5f)
                lineTo(13.5f, 13.5f)
                lineTo(13.5f, 10.5f)
                moveTo(6f, 6f)
                lineTo(10f, 6f)
                lineTo(10f, 10f)
                lineTo(6f, 10f)
                close()
            }
        }
    }

    val PixelGrid: ImageVector by lazy {
        icon16("PixelGrid") {
            strokePath(1.2f) {
                moveTo(2f, 2f)
                lineTo(14f, 2f)
                lineTo(14f, 14f)
                lineTo(2f, 14f)
                close()
                moveTo(6f, 2f)
                lineTo(6f, 14f)
                moveTo(10f, 2f)
                lineTo(10f, 14f)
                moveTo(2f, 6f)
                lineTo(14f, 6f)
                moveTo(2f, 10f)
                lineTo(14f, 10f)
            }
        }
    }

    val SliceRect: ImageVector by lazy {
        icon16("SliceRect") {
            strokePath(1.4f) {
                moveTo(2f, 2f)
                lineTo(14f, 2f)
                lineTo(14f, 14f)
                lineTo(2f, 14f)
                close()
                // Inner top-left sub-rectangle
                moveTo(4.5f, 4.5f)
                lineTo(9.5f, 4.5f)
                lineTo(9.5f, 9.5f)
                lineTo(4.5f, 9.5f)
                close()
            }
        }
    }

    val AliasLink: ImageVector by lazy {
        icon16("AliasLink") {
            strokePath(1.5f) {
                moveTo(6.5f, 9.5f)
                lineTo(9.5f, 6.5f)
                moveTo(5.2f, 7.2f)
                lineTo(3.5f, 8.9f)
                curveTo(2.2f, 10.2f, 2.2f, 12.2f, 3.5f, 13.2f)
                curveTo(4.8f, 14.2f, 6.5f, 13.8f, 7.8f, 12.5f)
                lineTo(8.8f, 11.5f)
                moveTo(10.8f, 8.8f)
                lineTo(12.5f, 7.1f)
                curveTo(13.8f, 5.8f, 13.8f, 3.8f, 12.5f, 2.8f)
                curveTo(11.2f, 1.8f, 9.5f, 2.2f, 8.2f, 3.5f)
                lineTo(7.2f, 4.5f)
            }
        }
    }

    val PinLock: ImageVector by lazy {
        icon16("PinLock") {
            strokePath(1.4f) {
                moveTo(4f, 7.5f)
                lineTo(12f, 7.5f)
                lineTo(12f, 13.5f)
                lineTo(4f, 13.5f)
                close()
                moveTo(5.5f, 7.5f)
                lineTo(5.5f, 5.2f)
                curveTo(5.5f, 3f, 10.5f, 3f, 10.5f, 5.2f)
                lineTo(10.5f, 7.5f)
            }
        }
    }

    val PinUnlock: ImageVector by lazy {
        icon16("PinUnlock") {
            strokePath(1.4f) {
                moveTo(4f, 7.5f)
                lineTo(12f, 7.5f)
                lineTo(12f, 13.5f)
                lineTo(4f, 13.5f)
                close()
                moveTo(5.5f, 7.5f)
                lineTo(5.5f, 5f)
                curveTo(5.5f, 2.8f, 10.5f, 2.8f, 11.2f, 4.6f)
            }
        }
    }

    val ArrowUp: ImageVector by lazy {
        icon16("ArrowUp") {
            strokePath {
                moveTo(8f, 13f)
                lineTo(8f, 3f)
                moveTo(4.5f, 6.5f)
                lineTo(8f, 3f)
                lineTo(11.5f, 6.5f)
            }
        }
    }

    val ArrowDown: ImageVector by lazy {
        icon16("ArrowDown") {
            strokePath {
                moveTo(8f, 3f)
                lineTo(8f, 13f)
                moveTo(4.5f, 9.5f)
                lineTo(8f, 13f)
                lineTo(11.5f, 9.5f)
            }
        }
    }

    val ArrowLeft: ImageVector by lazy {
        icon16("ArrowLeft") {
            strokePath {
                moveTo(13f, 8f)
                lineTo(3f, 8f)
                moveTo(6.5f, 4.5f)
                lineTo(3f, 8f)
                lineTo(6.5f, 11.5f)
            }
        }
    }

    val ArrowRight: ImageVector by lazy {
        icon16("ArrowRight") {
            strokePath {
                moveTo(3f, 8f)
                lineTo(13f, 8f)
                moveTo(9.5f, 4.5f)
                lineTo(13f, 8f)
                lineTo(9.5f, 11.5f)
            }
        }
    }

    val RotateCw: ImageVector by lazy {
        icon16("RotateCw") {
            strokePath {
                moveTo(12.5f, 4.5f)
                lineTo(12.5f, 7.8f)
                lineTo(9.2f, 7.8f)
                moveTo(12.3f, 7.2f)
                curveTo(11.2f, 4.2f, 7.8f, 2.8f, 5f, 4.2f)
                curveTo(2.4f, 5.5f, 2.2f, 9.5f, 4.5f, 11.8f)
                curveTo(6.8f, 14f, 10.5f, 13.6f, 12.2f, 11f)
            }
        }
    }

    val RotateCcw: ImageVector by lazy {
        icon16("RotateCcw") {
            strokePath {
                moveTo(3.5f, 4.5f)
                lineTo(3.5f, 7.8f)
                lineTo(6.8f, 7.8f)
                moveTo(3.7f, 7.2f)
                curveTo(4.8f, 4.2f, 8.2f, 2.8f, 11f, 4.2f)
                curveTo(13.6f, 5.5f, 13.8f, 9.5f, 11.5f, 11.8f)
                curveTo(9.2f, 14f, 5.5f, 13.6f, 3.8f, 11f)
            }
        }
    }

    val ChevronDown: ImageVector by lazy {
        icon16("ChevronDown") {
            strokePath {
                moveTo(4f, 6f)
                lineTo(8f, 10f)
                lineTo(12f, 6f)
            }
        }
    }

    val ChevronRight: ImageVector by lazy {
        icon16("ChevronRight") {
            strokePath {
                moveTo(6f, 4f)
                lineTo(10f, 8f)
                lineTo(6f, 12f)
            }
        }
    }

    val ChevronLeft: ImageVector by lazy {
        icon16("ChevronLeft") {
            strokePath {
                moveTo(10f, 4f)
                lineTo(6f, 8f)
                lineTo(10f, 12f)
            }
        }
    }

    val Edit: ImageVector get() = ToolBrush

    val Trash: ImageVector by lazy {
        icon16("Trash") {
            strokePath(1.4f) {
                moveTo(3f, 4.5f)
                lineTo(13f, 4.5f)
                moveTo(6f, 4.5f)
                lineTo(6f, 2.8f)
                lineTo(10f, 2.8f)
                lineTo(10f, 4.5f)
                moveTo(4.2f, 4.5f)
                lineTo(4.8f, 13.5f)
                lineTo(11.2f, 13.5f)
                lineTo(11.8f, 4.5f)
                moveTo(6.8f, 7f)
                lineTo(6.8f, 11.2f)
                moveTo(9.2f, 7f)
                lineTo(9.2f, 11.2f)
            }
        }
    }

    val Copy: ImageVector by lazy {
        icon16("Copy") {
            strokePath(1.4f) {
                moveTo(5.5f, 5.5f)
                lineTo(13f, 5.5f)
                lineTo(13f, 13.5f)
                lineTo(5.5f, 13.5f)
                close()
                moveTo(10.5f, 5.5f)
                lineTo(10.5f, 2.5f)
                lineTo(3f, 2.5f)
                lineTo(3f, 10.5f)
                lineTo(5.5f, 10.5f)
            }
        }
    }

    val Search: ImageVector by lazy {
        icon16("Search") {
            strokePath(1.4f) {
                moveTo(7f, 2.5f)
                arcTo(4.5f, 4.5f, 0f, true, true, 6.99f, 2.5f)
                close()
                moveTo(10.4f, 10.4f)
                lineTo(13.8f, 13.8f)
            }
        }
    }

    val EyeVisible: ImageVector by lazy {
        icon16("EyeVisible") {
            strokePath(1.4f) {
                moveTo(1.8f, 8f)
                curveTo(3.8f, 4.2f, 12.2f, 4.2f, 14.2f, 8f)
                curveTo(12.2f, 11.8f, 3.8f, 11.8f, 1.8f, 8f)
                close()
                moveTo(8f, 6f)
                arcTo(2f, 2f, 0f, true, true, 7.99f, 6f)
                close()
            }
        }
    }

    val EyeHidden: ImageVector by lazy {
        icon16("EyeHidden") {
            strokePath(1.4f) {
                moveTo(1.8f, 8f)
                curveTo(3.8f, 4.2f, 12.2f, 4.2f, 14.2f, 8f)
                curveTo(12.2f, 11.8f, 3.8f, 11.8f, 1.8f, 8f)
                moveTo(3f, 3f)
                lineTo(13f, 13f)
            }
        }
    }

    val StatusError: ImageVector by lazy {
        icon16("StatusError") {
            strokePath(1.5f) {
                moveTo(8f, 2f)
                arcTo(6f, 6f, 0f, true, true, 7.99f, 2f)
                close()
                moveTo(8f, 5f)
                lineTo(8f, 8.8f)
            }
            fillPath {
                moveTo(7.2f, 10.6f)
                lineTo(8.8f, 10.6f)
                lineTo(8.8f, 12.2f)
                lineTo(7.2f, 12.2f)
                close()
            }
        }
    }

    val StatusWarn: ImageVector by lazy {
        icon16("StatusWarn") {
            strokePath(1.4f) {
                moveTo(8f, 2.2f)
                lineTo(14.2f, 13.5f)
                lineTo(1.8f, 13.5f)
                close()
                moveTo(8f, 6.2f)
                lineTo(8f, 9.6f)
            }
            fillPath {
                moveTo(7.3f, 11f)
                lineTo(8.7f, 11f)
                lineTo(8.7f, 12.3f)
                lineTo(7.3f, 12.3f)
                close()
            }
        }
    }

    val StatusOk: ImageVector by lazy {
        icon16("StatusOk") {
            strokePath(1.5f) {
                moveTo(8f, 2f)
                arcTo(6f, 6f, 0f, true, true, 7.99f, 2f)
                close()
                moveTo(5.2f, 8.2f)
                lineTo(7.2f, 10.2f)
                lineTo(11f, 6f)
            }
        }
    }

    val EntityPlayer: ImageVector by lazy {
        icon16("EntityPlayer") {
            strokePath(1.5f) {
                // Shield + crown point
                moveTo(8f, 2f)
                lineTo(13f, 4.2f)
                lineTo(13f, 8.5f)
                curveTo(13f, 11.5f, 10.5f, 13.2f, 8f, 14.2f)
                curveTo(5.5f, 13.2f, 3f, 11.5f, 3f, 8.5f)
                lineTo(3f, 4.2f)
                close()
                moveTo(8f, 5.5f)
                lineTo(9.8f, 8.8f)
                lineTo(6.2f, 8.8f)
                close()
            }
        }
    }

    val EntityNpcGuard: ImageVector by lazy {
        icon16("EntityNpcGuard") {
            strokePath(1.4f) {
                // Crossed blades / helm
                moveTo(3.5f, 12.5f)
                lineTo(12.5f, 3.5f)
                moveTo(12.5f, 12.5f)
                lineTo(3.5f, 3.5f)
                moveTo(3f, 10.5f)
                lineTo(5.5f, 13f)
                moveTo(13f, 10.5f)
                lineTo(10.5f, 13f)
            }
        }
    }

    val EntityNpcPatrol: ImageVector by lazy {
        icon16("EntityNpcPatrol") {
            strokePath(1.4f) {
                moveTo(8f, 2.5f)
                lineTo(12f, 6.5f)
                lineTo(8f, 10.5f)
                lineTo(4f, 6.5f)
                close()
                moveTo(3f, 13f)
                lineTo(13f, 13f)
                moveTo(10.5f, 11f)
                lineTo(13f, 13f)
            }
        }
    }

    val EntityWaypoint: ImageVector by lazy {
        icon16("EntityWaypoint") {
            strokePath(1.4f) {
                moveTo(4f, 2.2f)
                lineTo(4f, 14f)
                moveTo(4f, 3f)
                lineTo(12.5f, 5.5f)
                lineTo(4f, 8.5f)
            }
        }
    }

    val EntityChest: ImageVector by lazy {
        icon16("EntityChest") {
            strokePath(1.4f) {
                moveTo(2.5f, 5f)
                curveTo(2.5f, 3f, 13.5f, 3f, 13.5f, 5f)
                lineTo(13.5f, 13f)
                lineTo(2.5f, 13f)
                close()
                moveTo(2.5f, 7.8f)
                lineTo(13.5f, 7.8f)
                moveTo(7f, 6.8f)
                lineTo(9f, 6.8f)
                lineTo(9f, 9f)
                lineTo(7f, 9f)
                close()
            }
        }
    }

    val EntitySwitch: ImageVector by lazy {
        icon16("EntitySwitch") {
            strokePath(1.5f) {
                moveTo(2.5f, 12.5f)
                lineTo(13.5f, 12.5f)
                moveTo(5f, 12.5f)
                lineTo(11f, 12.5f)
                moveTo(7.5f, 12.5f)
                lineTo(11.5f, 4.5f)
                moveTo(11.5f, 3f)
                arcTo(1.5f, 1.5f, 0f, true, true, 11.49f, 3f)
                close()
            }
        }
    }

    val EntitySign: ImageVector by lazy {
        icon16("EntitySign") {
            strokePath(1.4f) {
                moveTo(8f, 2f)
                lineTo(8f, 14f)
                moveTo(3f, 4f)
                lineTo(12f, 4f)
                lineTo(13.8f, 6.2f)
                lineTo(12f, 8.5f)
                lineTo(3f, 8.5f)
                close()
            }
        }
    }

    val WalkableCheck: ImageVector by lazy {
        icon16("WalkableCheck") {
            strokePath(1.5f) {
                moveTo(2.5f, 2.5f)
                lineTo(13.5f, 2.5f)
                lineTo(13.5f, 13.5f)
                lineTo(2.5f, 13.5f)
                close()
                moveTo(5f, 8.2f)
                lineTo(7.2f, 10.4f)
                lineTo(11.2f, 5.8f)
            }
        }
    }

    val WalkableBlocked: ImageVector by lazy {
        icon16("WalkableBlocked") {
            strokePath(1.5f) {
                moveTo(8f, 2.2f)
                arcTo(5.8f, 5.8f, 0f, true, true, 7.99f, 2.2f)
                close()
                moveTo(4f, 12f)
                lineTo(12f, 4f)
            }
        }
    }

    val ThemeSun: ImageVector by lazy {
        icon16("ThemeSun") {
            strokePath(1.4f) {
                moveTo(8f, 5f)
                arcTo(3f, 3f, 0f, true, true, 7.99f, 5f)
                close()
                moveTo(8f, 1.5f)
                lineTo(8f, 3.2f)
                moveTo(8f, 12.8f)
                lineTo(8f, 14.5f)
                moveTo(1.5f, 8f)
                lineTo(3.2f, 8f)
                moveTo(12.8f, 8f)
                lineTo(14.5f, 8f)
                moveTo(3.4f, 3.4f)
                lineTo(4.6f, 4.6f)
                moveTo(11.4f, 11.4f)
                lineTo(12.6f, 12.6f)
                moveTo(12.6f, 3.4f)
                lineTo(11.4f, 4.6f)
                moveTo(4.6f, 11.4f)
                lineTo(3.4f, 12.6f)
            }
        }
    }

    val ThemeMoon: ImageVector by lazy {
        icon16("ThemeMoon") {
            strokePath(1.4f) {
                moveTo(11.8f, 10.6f)
                curveTo(9.2f, 11.2f, 6.8f, 9.2f, 7.2f, 6.4f)
                curveTo(7.4f, 5f, 8.2f, 3.8f, 9.4f, 3f)
                curveTo(5.8f, 3f, 3f, 5.8f, 3f, 9.2f)
                curveTo(3f, 12.2f, 5.8f, 14f, 9f, 13.5f)
                curveTo(10.4f, 13.2f, 11.4f, 12.1f, 11.8f, 10.6f)
                close()
            }
        }
    }

    val PaletteSwatch: ImageVector by lazy {
        icon16("PaletteSwatch") {
            strokePath(1.4f) {
                moveTo(8f, 2f)
                curveTo(4.5f, 2f, 2f, 4.8f, 2f, 8.2f)
                curveTo(2f, 11.6f, 4.8f, 14f, 8f, 14f)
                curveTo(9.2f, 14f, 10f, 13.2f, 10f, 12.2f)
                curveTo(10f, 11.4f, 9.5f, 10.8f, 9.5f, 10.2f)
                curveTo(9.5f, 9.4f, 10.2f, 8.8f, 11.2f, 8.8f)
                lineTo(12.2f, 8.8f)
                curveTo(13.4f, 8.8f, 14f, 7.8f, 14f, 6.5f)
                curveTo(14f, 3.9f, 11.4f, 2f, 8f, 2f)
                close()
            }
            fillPath {
                moveTo(5.2f, 6f)
                arcTo(1f, 1f, 0f, true, true, 5.19f, 6f)
                close()
                moveTo(8f, 4.8f)
                arcTo(1f, 1f, 0f, true, true, 7.99f, 4.8f)
                close()
                moveTo(10.8f, 6f)
                arcTo(1f, 1f, 0f, true, true, 10.79f, 6f)
                close()
            }
        }
    }
}

@Composable
fun ArchetypeIcon(
    archetype: ShapeArchetype,
    modifier: Modifier = Modifier,
    tint: Color = StudioTheme.colors.accentHover,
) {
    Canvas(modifier = modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val top = Offset(w * 0.5f, h * 0.2f)
        val right = Offset(w * 0.88f, h * 0.44f)
        val bottom = Offset(w * 0.5f, h * 0.68f)
        val left = Offset(w * 0.12f, h * 0.44f)
        val drop = h * 0.2f

        fun drawDiamond(
            n: Offset,
            e: Offset,
            s: Offset,
            west: Offset,
            color: Color = tint,
        ) {
            val p =
                Path().apply {
                    moveTo(n.x, n.y)
                    lineTo(e.x, e.y)
                    lineTo(s.x, s.y)
                    lineTo(west.x, west.y)
                    close()
                }
            drawPath(p, color = color, style = stroke)
        }

        when (archetype) {
            ShapeArchetype.PRIM_FLAT -> {
                drawDiamond(top, right, bottom, left)
                drawLine(tint, left, left.copy(y = left.y + drop), strokeWidth = 1.4f)
                drawLine(tint, bottom, bottom.copy(y = bottom.y + drop), strokeWidth = 1.4f)
                drawLine(tint, right, right.copy(y = right.y + drop), strokeWidth = 1.4f)
                drawLine(tint, left.copy(y = left.y + drop), bottom.copy(y = bottom.y + drop), strokeWidth = 1.4f)
                drawLine(tint, bottom.copy(y = bottom.y + drop), right.copy(y = right.y + drop), strokeWidth = 1.4f)
            }
            ShapeArchetype.PRIM_WEDGE_STRAIGHT,
            ShapeArchetype.PRIM_WEDGE_STRAIGHT_2H,
            -> {
                val lift = if (archetype.heightSpan1h >= 2) h * 0.18f else h * 0.1f
                val hiN = top.copy(y = top.y - lift)
                val hiE = right.copy(y = right.y - lift)
                drawDiamond(hiN, hiE, bottom, left)
                drawLine(tint.copy(alpha = 0.6f), hiE, right.copy(y = right.y + drop * 0.6f), strokeWidth = 1.3f)
            }
            ShapeArchetype.PRIM_WEDGE_OUTER,
            ShapeArchetype.PRIM_WEDGE_OUTER_2H,
            -> {
                val lift = if (archetype.heightSpan1h >= 2) h * 0.2f else h * 0.12f
                val peak = top.copy(y = top.y - lift)
                drawLine(tint, peak, right, strokeWidth = 1.5f)
                drawLine(tint, peak, bottom, strokeWidth = 1.5f)
                drawLine(tint, peak, left, strokeWidth = 1.5f)
                drawLine(tint, left, bottom, strokeWidth = 1.5f)
                drawLine(tint, bottom, right, strokeWidth = 1.5f)
            }
            ShapeArchetype.PRIM_WEDGE_INNER,
            ShapeArchetype.PRIM_WEDGE_INNER_2H,
            -> {
                val lift = if (archetype.heightSpan1h >= 2) h * 0.16f else h * 0.1f
                drawDiamond(
                    top.copy(y = top.y - lift),
                    right.copy(y = right.y - lift),
                    bottom,
                    left.copy(y = left.y - lift),
                )
                drawLine(tint, top.copy(y = top.y - lift), bottom, strokeWidth = 1.2f)
            }
            ShapeArchetype.PRIM_STAIRS -> {
                for (step in 0..2) {
                    val yOff = step * (h * 0.16f)
                    drawLine(
                        tint,
                        Offset(w * (0.2f + step * 0.12f), h * 0.72f - yOff),
                        Offset(w * (0.55f + step * 0.12f), h * 0.72f - yOff),
                        strokeWidth = 1.5f,
                    )
                }
            }
            ShapeArchetype.PRIM_LIQUID_SHALLOW,
            ShapeArchetype.PRIM_LIQUID_DEEP,
            -> {
                val waveCol = Color(0xFF38BDF8)
                drawDiamond(top.copy(y = top.y + 2f), right.copy(y = right.y + 2f), bottom.copy(y = bottom.y + 2f), left.copy(y = left.y + 2f), color = waveCol)
                drawLine(waveCol, Offset(w * 0.32f, h * 0.46f), Offset(w * 0.68f, h * 0.46f), strokeWidth = 1.4f)
            }
            ShapeArchetype.PRIM_PILLAR -> {
                val p =
                    Path().apply {
                        moveTo(w * 0.34f, h * 0.2f)
                        lineTo(w * 0.66f, h * 0.2f)
                        lineTo(w * 0.66f, h * 0.82f)
                        lineTo(w * 0.34f, h * 0.82f)
                        close()
                    }
                drawPath(p, color = tint, style = stroke)
            }
            ShapeArchetype.PRIM_FOLIAGE_CROSS -> {
                val leafCol = Color(0xFF34D399)
                drawLine(leafCol, Offset(w * 0.22f, h * 0.25f), Offset(w * 0.78f, h * 0.78f), strokeWidth = 1.6f)
                drawLine(leafCol, Offset(w * 0.78f, h * 0.25f), Offset(w * 0.22f, h * 0.78f), strokeWidth = 1.6f)
                drawLine(leafCol, Offset(w * 0.5f, h * 0.16f), Offset(w * 0.5f, h * 0.84f), strokeWidth = 1.4f)
            }
        }
    }
}
