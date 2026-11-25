package dev.secondsun.sfxstudio.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState
import dev.secondsun.sfxstudio.desktop.ui.TextureComposeRenderer
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.SegmentItem
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButton
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButtonVariant
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioIconButton
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioModalDialog
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioSegmentedControl
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioTextField
import dev.secondsun.sfxstudio.desktop.ui.icons.StudioIcons
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioTheme
import dev.secondsun.sfxstudio.desktop.ui.theme.checkerboardBackground
import java.util.ArrayDeque
import kotlin.math.abs

private enum class EditTool {
    PENCIL,
    FLOOD_FILL,
    EYEDROPPER,
    PAN,
}

private fun drawLine(
    x0: Int,
    y0: Int,
    x1: Int,
    y1: Int,
    slot: Int,
    buffer: ByteArray,
    w: Int,
    h: Int,
) {
    var x = x0
    var y = y0
    val dx = abs(x1 - x0)
    val dy = -abs(y1 - y0)
    val sx = if (x0 < x1) 1 else -1
    val sy = if (y0 < y1) 1 else -1
    var err = dx + dy
    val byteVal = (slot and 0x0F).toByte()

    while (true) {
        if (x in 0 until w && y in 0 until h) {
            buffer[y * w + x] = byteVal
        }
        if (x == x1 && y == y1) break
        val e2 = 2 * err
        if (e2 >= dy) {
            err += dy
            x += sx
        }
        if (e2 <= dx) {
            err += dx
            y += sy
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun TextureEditModal(state: AtlasEditorState) {
    val editIdx = state.editingTextureIndex ?: return
    val originalTex =
        state.atlas.textures.getOrNull(editIdx) ?: run {
            state.editingTextureIndex = null
            return
        }

    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography

    var textureName by remember(editIdx, originalTex.name) { mutableStateOf(originalTex.name) }
    var pixels by remember(editIdx) { mutableStateOf(originalTex.indexedPixels.copyOf()) }
    var activeSlot by remember(editIdx) { mutableStateOf(state.selectedPaletteSlot) }
    var activeTool by remember { mutableStateOf(EditTool.PENCIL) }
    var showGrid by remember { mutableStateOf(true) }
    var zoomScale by remember(editIdx) { mutableStateOf(1f) }
    var panOffset by remember(editIdx) { mutableStateOf(Offset.Zero) }
    var historyRev by remember(editIdx) { mutableStateOf(0) }

    val trimmedName = textureName.trim()
    val nameError =
        remember(trimmedName, editIdx, state.atlas) {
            val duplicate =
                state.atlas.textures.withIndex().any { (idx, t) ->
                    idx != editIdx && t.name.equals(trimmedName, ignoreCase = true)
                } ||
                    state.atlas.slices.any { it.name.equals(trimmedName, ignoreCase = true) } ||
                    state.atlas.aliases.any { it.name.equals(trimmedName, ignoreCase = true) }
            when {
                !SourceTexture.isValidCa65Identifier(trimmedName) ->
                    "Must be a valid ca65 identifier ([A-Za-z_][A-Za-z0-9_]*)"
                duplicate ->
                    "Identifier '$trimmedName' collides with an existing descriptor (case-insensitive)"
                else -> null
            }
        }

    val undoStack = remember(editIdx) { ArrayDeque<ByteArray>() }
    val redoStack = remember(editIdx) { ArrayDeque<ByteArray>() }

    fun pushUndo(current: ByteArray) {
        if (undoStack.size >= 64) {
            undoStack.removeLast()
        }
        undoStack.push(current.copyOf())
        redoStack.clear()
        historyRev++
    }

    val canUndoLocal = remember(historyRev) { undoStack.isNotEmpty() }
    val canRedoLocal = remember(historyRev) { redoStack.isNotEmpty() }

    val livePreviewBitmap =
        remember(pixels, originalTex.width, originalTex.height, state.palette) {
            val previewTex =
                SourceTexture(
                    name = "preview",
                    width = originalTex.width,
                    height = originalTex.height,
                    indexedPixels = pixels,
                )
            TextureComposeRenderer.renderToImageBitmap(previewTex, state.palette)
        }

    StudioModalDialog(
        onDismissRequest = { state.editingTextureIndex = null },
        title = "Edit Source Texture",
        subtitle = "${originalTex.name} (${originalTex.width}x${originalTex.height})",
        preferredWidth = 680.dp,
        scrollableBody = false,
        footerLeading = {
            val curColor = state.palette[activeSlot]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .let { mod ->
                                if (activeSlot == 0) {
                                    mod.checkerboardBackground(3.dp, colors.checkerDark, colors.checkerLight)
                                } else {
                                    mod.background(Color(curColor.argb))
                                }
                            }.border(1.dp, colors.swatchInnerRim, RoundedCornerShape(3.dp)),
                )
                Text(
                    text =
                        if (activeSlot == 0) {
                            "Active: Slot 0 ($0000 Transparent)"
                        } else {
                            "Active: Slot $activeSlot (${curColor.hexString})"
                        },
                    style = typography.monoCaption,
                    color = colors.textSecondary,
                )
            }
        },
        footerActions = {
            StudioButton(
                label = "Cancel",
                variant = StudioButtonVariant.Ghost,
                onClick = { state.editingTextureIndex = null },
            )
            StudioButton(
                label = "Apply Changes",
                variant = StudioButtonVariant.Primary,
                enabled = nameError == null,
                onClick = {
                    if (nameError == null) {
                        state.applyTextureEdit(editIdx, trimmedName, pixels)
                    }
                },
            )
        },
    ) {
        // 1. Symbol Name Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Symbol Name",
                style = typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
            StudioTextField(
                value = textureName,
                onValueChange = { textureName = it },
                monospace = true,
                isError = nameError != null,
                errorText = nameError,
                modifier = Modifier.weight(1f),
            )
        }

        // 2. Responsive FlowRow Tool & Viewport Bar + 1x Live Preview
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StudioSegmentedControl(
                items =
                    listOf(
                        SegmentItem(EditTool.PENCIL, "Pencil", icon = StudioIcons.ToolPencil),
                        SegmentItem(EditTool.FLOOD_FILL, "Fill", icon = StudioIcons.ToolFill),
                        SegmentItem(EditTool.EYEDROPPER, "Eyedrop", icon = StudioIcons.ToolEyedropper),
                        SegmentItem(EditTool.PAN, "Pan", icon = StudioIcons.ToolPanHand),
                    ),
                selectedValue = activeTool,
                onSelect = { activeTool = it },
                compact = true,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                StudioIconButton(
                    icon = StudioIcons.Undo,
                    contentDescription = "Undo Stroke",
                    enabled = canUndoLocal,
                    size = metrics.controlHeightSM,
                    onClick = {
                        if (undoStack.isNotEmpty()) {
                            redoStack.push(pixels.copyOf())
                            pixels = undoStack.pop()
                            historyRev++
                        }
                    },
                )
                StudioIconButton(
                    icon = StudioIcons.Redo,
                    contentDescription = "Redo Stroke",
                    enabled = canRedoLocal,
                    size = metrics.controlHeightSM,
                    onClick = {
                        if (redoStack.isNotEmpty()) {
                            undoStack.push(pixels.copyOf())
                            pixels = redoStack.pop()
                            historyRev++
                        }
                    },
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                StudioIconButton(
                    icon = StudioIcons.PixelGrid,
                    contentDescription = "Toggle Pixel Grid",
                    selected = showGrid,
                    size = metrics.controlHeightSM,
                    onClick = { showGrid = !showGrid },
                )
                StudioIconButton(
                    icon = StudioIcons.ZoomOut,
                    contentDescription = "Zoom Out",
                    size = metrics.controlHeightSM,
                    onClick = { zoomScale = (zoomScale / 1.5f).coerceAtLeast(0.5f) },
                )
                StudioButton(
                    label = "Fit",
                    variant =
                        if (zoomScale == 1f && panOffset == Offset.Zero) {
                            StudioButtonVariant.Secondary
                        } else {
                            StudioButtonVariant.Ghost
                        },
                    compact = true,
                    onClick = {
                        zoomScale = 1f
                        panOffset = Offset.Zero
                    },
                )
                StudioIconButton(
                    icon = StudioIcons.ZoomIn,
                    contentDescription = "Zoom In",
                    size = metrics.controlHeightSM,
                    onClick = { zoomScale = (zoomScale * 1.5f).coerceAtMost(8f) },
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("1x:", style = typography.caption, color = colors.textMuted)
                Box(
                    modifier =
                        Modifier
                            .size(metrics.controlHeightSM)
                            .clip(RoundedCornerShape(metrics.radiusXS))
                            .checkerboardBackground(3.dp, colors.checkerDark, colors.checkerLight)
                            .border(1.dp, colors.borderStrong, RoundedCornerShape(metrics.radiusXS)),
                ) {
                    Image(
                        bitmap = livePreviewBitmap,
                        contentDescription = "1x live preview",
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // 3. 16-Slot Palette Strip
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(metrics.radiusSM))
                    .background(colors.surface0)
                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(metrics.radiusSM))
                    .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            for (slot in 0..15) {
                val isSelected = activeSlot == slot
                val color = state.palette[slot]
                val isAssigned = state.palette.isAssigned(slot)
                val swatchShape = RoundedCornerShape(metrics.radiusXS)

                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(metrics.swatchHeight)
                            .clip(swatchShape)
                            .let { mod ->
                                when {
                                    slot == 0 ->
                                        mod.checkerboardBackground(3.dp, colors.checkerDark, colors.checkerLight)
                                    isAssigned ->
                                        mod.background(Color(color.argb), swatchShape)
                                    else ->
                                        mod.background(colors.surface1, swatchShape)
                                }
                            }.let { mod ->
                                if (isSelected) {
                                    mod
                                        .border(2.dp, colors.accentHover, swatchShape)
                                        .padding(2.dp)
                                        .border(1.dp, colors.bgCanvas, swatchShape)
                                } else {
                                    mod.border(
                                        1.dp,
                                        if (isAssigned) colors.swatchInnerRim else colors.borderSubtle,
                                        swatchShape,
                                    )
                                }
                            }.clickable {
                                activeSlot = slot
                                state.selectedPaletteSlot = slot
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    val textColor =
                        if (slot == 0 || !isAssigned) {
                            colors.textMuted
                        } else {
                            val lum = (color.r * 299 + color.g * 587 + color.b * 114) / 1000
                            if (lum > 16) Color(0xFF0A0C10) else Color.White
                        }
                    Text(
                        text = "$slot",
                        style = typography.microBadge,
                        color = textColor,
                    )
                }
            }
        }

        // 4. Strictly Bounded & Clipped Pixel Viewport (Never overflows onto Symbol bar or Footer)
        BoxWithConstraints(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(metrics.radiusSM))
                    .background(colors.bgCanvas)
                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(metrics.radiusSM))
                    .clipToBounds(),
        ) {
            val density = LocalDensity.current
            val w = originalTex.width
            val h = originalTex.height
            val availW = (maxWidth - 20.dp).coerceAtLeast(16.dp)
            val availH = (maxHeight - 20.dp).coerceAtLeast(16.dp)
            val baseTexelDp = minOf(availW / w, availH / h, 22.dp).coerceAtLeast(1.dp)
            val texelPx = with(density) { (baseTexelDp * zoomScale).toPx() }
            val viewportW = with(density) { maxWidth.toPx() }
            val viewportH = with(density) { maxHeight.toPx() }
            val sheetW = texelPx * w
            val sheetH = texelPx * h
            val originX = (viewportW - sheetW) * 0.5f + panOffset.x
            val originY = (viewportH - sheetH) * 0.5f + panOffset.y

            val currentTool by rememberUpdatedState(activeTool)
            val currentSlot by rememberUpdatedState(activeSlot)
            val curOriginX by rememberUpdatedState(originX)
            val curOriginY by rememberUpdatedState(originY)
            val curTexelPx by rememberUpdatedState(texelPx)
            val curZoomScale by rememberUpdatedState(zoomScale)

            Canvas(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .pointerInput("scrollZoomPan") {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Scroll) {
                                        val delta = event.changes.firstOrNull()?.scrollDelta ?: continue
                                        val ctrl = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                                        if (ctrl) {
                                            zoomScale =
                                                if (delta.y < 0f) {
                                                    (curZoomScale * 1.25f).coerceAtMost(8f)
                                                } else {
                                                    (curZoomScale / 1.25f).coerceAtLeast(0.5f)
                                                }
                                        } else {
                                            panOffset += Offset(-delta.x * 20f, -delta.y * 20f)
                                        }
                                    }
                                }
                            }
                        }.pointerInput(w, h) {
                            awaitEachGesture {
                                val firstEvent = awaitPointerEvent()
                                val middleMouse = firstEvent.buttons.isTertiaryPressed
                                val down = firstEvent.changes.firstOrNull { it.pressed } ?: return@awaitEachGesture
                                down.consume()

                                if (currentTool == EditTool.PAN || middleMouse) {
                                    var lastPos = down.position
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull() ?: break
                                        if (!ch.pressed) break
                                        val d = ch.position - lastPos
                                        lastPos = ch.position
                                        ch.consume()
                                        panOffset += d
                                    }
                                    return@awaitEachGesture
                                }

                                val rawDownX = (down.position.x - curOriginX) / curTexelPx
                                val rawDownY = (down.position.y - curOriginY) / curTexelPx
                                if (rawDownX < 0f || rawDownY < 0f || rawDownX >= w || rawDownY >= h) {
                                    // Dragging outside the texture inside the viewport pans safely
                                    var lastPos = down.position
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull() ?: break
                                        if (!ch.pressed) break
                                        val d = ch.position - lastPos
                                        lastPos = ch.position
                                        ch.consume()
                                        panOffset += d
                                    }
                                    return@awaitEachGesture
                                }

                                var lastPx = rawDownX.toInt().coerceIn(0, w - 1)
                                var lastPy = rawDownY.toInt().coerceIn(0, h - 1)
                                val startIdx = lastPy * w + lastPx

                                when (currentTool) {
                                    EditTool.PENCIL -> {
                                        pushUndo(pixels)
                                        val working = pixels.copyOf()
                                        working[startIdx] = (currentSlot and 0x0F).toByte()
                                        pixels = working

                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull() ?: break
                                            if (!change.pressed) break
                                            change.consume()
                                            val curPx =
                                                ((change.position.x - curOriginX) / curTexelPx)
                                                    .toInt()
                                                    .coerceIn(0, w - 1)
                                            val curPy =
                                                ((change.position.y - curOriginY) / curTexelPx)
                                                    .toInt()
                                                    .coerceIn(0, h - 1)
                                            if (curPx != lastPx || curPy != lastPy) {
                                                val stroke = pixels.copyOf()
                                                drawLine(
                                                    lastPx,
                                                    lastPy,
                                                    curPx,
                                                    curPy,
                                                    currentSlot,
                                                    stroke,
                                                    w,
                                                    h,
                                                )
                                                pixels = stroke
                                                lastPx = curPx
                                                lastPy = curPy
                                            }
                                        }
                                    }
                                    EditTool.FLOOD_FILL -> {
                                        val target = pixels[startIdx].toInt() and 0x0F
                                        if (target != currentSlot) {
                                            pushUndo(pixels)
                                            val working = pixels.copyOf()
                                            SourceTexture.floodFillBuffer(
                                                working,
                                                w,
                                                h,
                                                lastPx,
                                                lastPy,
                                                target,
                                                currentSlot,
                                            )
                                            pixels = working
                                        }
                                    }
                                    EditTool.EYEDROPPER -> {
                                        val picked = pixels[startIdx].toInt() and 0x0F
                                        activeSlot = picked
                                        state.selectedPaletteSlot = picked

                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull() ?: break
                                            if (!change.pressed) break
                                            change.consume()
                                            val curPx =
                                                ((change.position.x - curOriginX) / curTexelPx)
                                                    .toInt()
                                                    .coerceIn(0, w - 1)
                                            val curPy =
                                                ((change.position.y - curOriginY) / curTexelPx)
                                                    .toInt()
                                                    .coerceIn(0, h - 1)
                                            val curIdx = curPy * w + curPx
                                            val curPicked = pixels[curIdx].toInt() and 0x0F
                                            if (activeSlot != curPicked) {
                                                activeSlot = curPicked
                                                state.selectedPaletteSlot = curPicked
                                            }
                                        }
                                    }
                                    EditTool.PAN -> Unit
                                }
                            }
                        },
            ) {
                // Draw 0dp sharp texels with true Slot 0 two-tone dark checkerboard
                for (y in 0 until h) {
                    val top = originY + y * texelPx
                    if (top + texelPx < 0f || top > size.height) continue
                    for (x in 0 until w) {
                        val left = originX + x * texelPx
                        if (left + texelPx < 0f || left > size.width) continue
                        val slot = pixels[y * w + x].toInt() and 0x0F
                        if (slot == 0) {
                            val half = texelPx * 0.5f
                            drawRect(colors.checkerDark, Offset(left, top), Size(texelPx, texelPx))
                            if (half >= 2f) {
                                drawRect(colors.checkerLight, Offset(left + half, top), Size(half, half))
                                drawRect(colors.checkerLight, Offset(left, top + half), Size(half, half))
                            } else if ((x + y) and 1 == 1) {
                                drawRect(colors.checkerLight, Offset(left, top), Size(texelPx, texelPx))
                            }
                        } else {
                            drawRect(
                                color = Color(state.palette[slot].argb),
                                topLeft = Offset(left, top),
                                size = Size(texelPx, texelPx),
                            )
                        }
                    }
                }

                // Optional crisp pixel lattice when zoomed enough
                if (showGrid && texelPx >= 6f) {
                    val gridCol = colors.pixelGridLine
                    for (x in 1 until w) {
                        val gx = originX + x * texelPx
                        drawLine(gridCol, Offset(gx, originY), Offset(gx, originY + sheetH), strokeWidth = 1f)
                    }
                    for (y in 1 until h) {
                        val gy = originY + y * texelPx
                        drawLine(gridCol, Offset(originX, gy), Offset(originX + sheetW, gy), strokeWidth = 1f)
                    }
                }

                // Sharp 0dp outer frame around the w x h texture bounds
                drawRect(
                    color = colors.borderStrong,
                    topLeft = Offset(originX, originY),
                    size = Size(sheetW, sheetH),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }
    }
}
