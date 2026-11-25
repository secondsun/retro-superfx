package dev.secondsun.sfxstudio.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.secondsun.sfxstudio.core.atlas.SourceTexture
import dev.secondsun.sfxstudio.core.atlas.VirtualSlice
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState
import dev.secondsun.sfxstudio.desktop.ui.TextureComposeRenderer
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.SegmentItem
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioBadge
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButton
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButtonVariant
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioModalDialog
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioNumberStepper
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioSegmentedControl
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioTextField
import dev.secondsun.sfxstudio.desktop.ui.icons.StudioIcons
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioTheme
import dev.secondsun.sfxstudio.desktop.ui.theme.checkerboardBackground
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SliceEditorModal(state: AtlasEditorState) {
    if (!state.showSliceModal) return

    val textures = state.atlas.textures
    if (textures.isEmpty()) {
        state.closeSliceModal()
        return
    }

    val editingIdx = state.editingSliceIndex
    val existingSlice = editingIdx?.let { state.atlas.slices.getOrNull(it) }

    val initialParent =
        remember(existingSlice, state.sliceParentTextureHint, textures) {
            val hint = existingSlice?.sourceTexture ?: state.sliceParentTextureHint
            textures.find { it.name.equals(hint, ignoreCase = true) } ?: textures.first()
        }

    var selectedParentName by remember(existingSlice, initialParent) { mutableStateOf(initialParent.name) }
    val parentTex =
        textures.find { it.name.equals(selectedParentName, ignoreCase = true) } ?: textures.first()

    val defaultSuggestedName =
        remember(existingSlice, initialParent, state.atlas.slices) {
            if (existingSlice != null) {
                existingSlice.name
            } else {
                var suffix = 1
                var cand = "${initialParent.name}_slice"
                while (
                    state.atlas.textures.any { it.name.equals(cand, ignoreCase = true) } ||
                    state.atlas.slices.any { it.name.equals(cand, ignoreCase = true) } ||
                    state.atlas.aliases.any { it.name.equals(cand, ignoreCase = true) }
                ) {
                    suffix++
                    cand = "${initialParent.name}_slice_$suffix"
                }
                cand
            }
        }

    var sliceName by remember(existingSlice, defaultSuggestedName) { mutableStateOf(defaultSuggestedName) }
    var sliceWidth by remember(existingSlice, initialParent) {
        mutableStateOf((existingSlice?.width ?: 16).coerceIn(16, initialParent.width))
    }
    var sliceHeight by remember(existingSlice, initialParent) {
        mutableStateOf((existingSlice?.height ?: 16).coerceIn(16, initialParent.height))
    }
    var sliceX by remember(existingSlice, initialParent) {
        mutableStateOf((existingSlice?.x ?: 0).coerceIn(0, (initialParent.width - sliceWidth).coerceAtLeast(0)))
    }
    var sliceY by remember(existingSlice, initialParent) {
        mutableStateOf((existingSlice?.y ?: 0).coerceIn(0, (initialParent.height - sliceHeight).coerceAtLeast(0)))
    }

    var parentMenuExpanded by remember { mutableStateOf(false) }

    val trimmedName = sliceName.trim()
    val nameError =
        remember(trimmedName, editingIdx, state.atlas) {
            val conflictTex = state.atlas.textures.any { it.name.equals(trimmedName, ignoreCase = true) }
            val conflictSlice =
                state.atlas.slices.withIndex().any { (idx, s) ->
                    idx != editingIdx && s.name.equals(trimmedName, ignoreCase = true)
                }
            val conflictAlias = state.atlas.aliases.any { it.name.equals(trimmedName, ignoreCase = true) }
            when {
                trimmedName.isEmpty() -> "Slice symbol name is required"
                !SourceTexture.isValidCa65Identifier(trimmedName) ->
                    "Must match ca65 identifier syntax ([A-Za-z_][A-Za-z0-9_]*)"
                conflictTex || conflictSlice || conflictAlias ->
                    "Identifier '$trimmedName' collides with an existing descriptor (case-insensitive)"
                else -> null
            }
        }

    val validWidths =
        remember(parentTex.width) {
            SourceTexture.VALID_DIMENSIONS.filter { it <= parentTex.width }.sorted()
        }
    val validHeights =
        remember(parentTex.height) {
            SourceTexture.VALID_DIMENSIONS.filter { it <= parentTex.height }.sorted()
        }

    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography

    val canCommit =
        nameError == null &&
            sliceWidth in validWidths &&
            sliceHeight in validHeights &&
            sliceX in 0..(parentTex.width - sliceWidth) &&
            sliceY in 0..(parentTex.height - sliceHeight)

    StudioModalDialog(
        onDismissRequest = { state.closeSliceModal() },
        title = if (existingSlice != null) "Edit Virtual Slice" else "New Virtual Slice",
        subtitle = "Zero-VRAM power-of-two sub-rectangle inside '${parentTex.name}'",
        preferredWidth = 560.dp,
        footerLeading = {
            StudioBadge(
                text = "Atlas @ (${parentTex.x + sliceX}, ${parentTex.y + sliceY})",
                textColor = colors.sliceCyan,
                backgroundColor = colors.sliceCyan.copy(alpha = 0.16f),
                monospace = true,
            )
        },
        footerActions = {
            StudioButton(
                label = "Cancel",
                variant = StudioButtonVariant.Ghost,
                onClick = { state.closeSliceModal() },
            )
            StudioButton(
                label = if (existingSlice != null) "Save Slice" else "Create Slice",
                variant = StudioButtonVariant.Primary,
                icon = StudioIcons.SliceRect,
                enabled = canCommit,
                onClick = {
                    val candidate =
                        VirtualSlice(
                            name = trimmedName,
                            sourceTexture = parentTex.name,
                            x = sliceX,
                            y = sliceY,
                            width = sliceWidth,
                            height = sliceHeight,
                        )
                    if (editingIdx != null) {
                        state.updateSlice(editingIdx, candidate)
                    } else {
                        state.addSlice(candidate)
                    }
                },
            )
        },
    ) {
        // 1. Slice Symbol Name
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Slice Symbol Name", style = typography.bodyMedium, color = colors.textSecondary)
            StudioTextField(
                value = sliceName,
                onValueChange = { sliceName = it },
                placeholder = "e.g. roof_top_half",
                monospace = true,
                isError = nameError != null,
                errorText = nameError,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 2. Parent Source Texture Dropdown
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Parent Source Texture", style = typography.bodyMedium, color = colors.textSecondary)
            Box(modifier = Modifier.fillMaxWidth()) {
                val parentBitmap =
                    remember(parentTex, state.palette) {
                        TextureComposeRenderer.renderToImageBitmap(parentTex, state.palette)
                    }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(metrics.controlHeight)
                            .clip(RoundedCornerShape(metrics.radiusSM))
                            .background(colors.surface0)
                            .border(1.dp, colors.borderSubtle, RoundedCornerShape(metrics.radiusSM))
                            .clickable { parentMenuExpanded = true }
                            .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(20.dp)
                                .clip(RoundedCornerShape(metrics.radiusXS))
                                .checkerboardBackground(3.dp, colors.checkerDark, colors.checkerLight)
                                .border(1.dp, colors.borderSubtle, RoundedCornerShape(metrics.radiusXS)),
                    ) {
                        Image(
                            bitmap = parentBitmap,
                            contentDescription = parentTex.name,
                            filterQuality = FilterQuality.None,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Text(
                        text = parentTex.name,
                        style = typography.monoBody,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    StudioBadge(
                        text = "${parentTex.width}x${parentTex.height}",
                        monospace = true,
                    )
                    Icon(
                        imageVector = StudioIcons.ChevronDown,
                        contentDescription = null,
                        tint = colors.textMuted,
                        modifier = Modifier.size(14.dp),
                    )
                }

                DropdownMenu(
                    expanded = parentMenuExpanded,
                    onDismissRequest = { parentMenuExpanded = false },
                    modifier =
                        Modifier
                            .heightIn(max = 240.dp)
                            .background(colors.surface2),
                ) {
                    for (tex in textures) {
                        DropdownMenuItem(
                            onClick = {
                                parentMenuExpanded = false
                                selectedParentName = tex.name
                                sliceWidth = sliceWidth.coerceAtMost(tex.width)
                                sliceHeight = sliceHeight.coerceAtMost(tex.height)
                                sliceX = sliceX.coerceIn(0, (tex.width - sliceWidth).coerceAtLeast(0))
                                sliceY = sliceY.coerceIn(0, (tex.height - sliceHeight).coerceAtLeast(0))
                            },
                        ) {
                            val itemBmp =
                                remember(tex, state.palette) {
                                    TextureComposeRenderer.renderToImageBitmap(tex, state.palette)
                                }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(22.dp)
                                            .clip(RoundedCornerShape(metrics.radiusXS))
                                            .checkerboardBackground(3.dp, colors.checkerDark, colors.checkerLight)
                                            .border(1.dp, colors.borderSubtle, RoundedCornerShape(metrics.radiusXS)),
                                ) {
                                    Image(
                                        bitmap = itemBmp,
                                        contentDescription = tex.name,
                                        filterQuality = FilterQuality.None,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                Text(tex.name, style = typography.monoBody, color = colors.textPrimary)
                                Text(
                                    "(${tex.width}x${tex.height})",
                                    style = typography.caption,
                                    color = colors.textSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Power-of-Two Dimensions & Sub-Rect Offset Steppers
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Width (px)", style = typography.caption, color = colors.textSecondary)
                StudioSegmentedControl(
                    items = validWidths.map { SegmentItem(it, "$it") },
                    selectedValue = sliceWidth,
                    onSelect = { newW ->
                        sliceWidth = newW
                        sliceX = sliceX.coerceIn(0, (parentTex.width - newW).coerceAtLeast(0))
                    },
                    compact = true,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Height (px)", style = typography.caption, color = colors.textSecondary)
                StudioSegmentedControl(
                    items = validHeights.map { SegmentItem(it, "$it") },
                    selectedValue = sliceHeight,
                    onSelect = { newH ->
                        sliceHeight = newH
                        sliceY = sliceY.coerceIn(0, (parentTex.height - newH).coerceAtLeast(0))
                    },
                    compact = true,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Offset X", style = typography.caption, color = colors.textSecondary)
                StudioNumberStepper(
                    value = sliceX,
                    onValueChange = { sliceX = it },
                    range = 0..(parentTex.width - sliceWidth).coerceAtLeast(0),
                    prefix = "X ",
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Offset Y", style = typography.caption, color = colors.textSecondary)
                StudioNumberStepper(
                    value = sliceY,
                    onValueChange = { sliceY = it },
                    range = 0..(parentTex.height - sliceHeight).coerceAtLeast(0),
                    prefix = "Y ",
                )
            }
        }

        // 4. Interactive Parent Sub-Rectangle Picker Canvas + Extracted Slice Preview
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BoxWithConstraints(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(210.dp)
                        .clip(RoundedCornerShape(metrics.radiusSM))
                        .background(colors.bgCanvas)
                        .border(1.dp, colors.borderSubtle, RoundedCornerShape(metrics.radiusSM))
                        .clipToBounds(),
                contentAlignment = Alignment.Center,
            ) {
                val density = LocalDensity.current
                val availW = (maxWidth - 24.dp).coerceAtLeast(32.dp)
                val availH = (maxHeight - 24.dp).coerceAtLeast(32.dp)
                val texelDp = minOf(availW / parentTex.width, availH / parentTex.height, 14.dp).coerceAtLeast(1.dp)
                val drawW = texelDp * parentTex.width
                val drawH = texelDp * parentTex.height
                val texelPx = with(density) { texelDp.toPx() }

                val curSliceW by rememberUpdatedState(sliceWidth)
                val curSliceH by rememberUpdatedState(sliceHeight)
                val curSliceX by rememberUpdatedState(sliceX)
                val curSliceY by rememberUpdatedState(sliceY)

                val parentBitmap =
                    remember(parentTex, state.palette) {
                        TextureComposeRenderer.renderToImageBitmap(parentTex, state.palette)
                    }

                Box(
                    modifier =
                        Modifier
                            .size(drawW, drawH)
                            .checkerboardBackground(6.dp, colors.checkerDark, colors.checkerLight)
                            .border(1.dp, colors.borderStrong)
                            .pointerInput(parentTex, texelPx) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    val downTexX = down.position.x / texelPx
                                    val downTexY = down.position.y / texelPx
                                    val insideSlice =
                                        downTexX >= curSliceX &&
                                            downTexX <= curSliceX + curSliceW &&
                                            downTexY >= curSliceY &&
                                            downTexY <= curSliceY + curSliceH

                                    val grabOffsetX = if (insideSlice) downTexX - curSliceX else curSliceW * 0.5f
                                    val grabOffsetY = if (insideSlice) downTexY - curSliceY else curSliceH * 0.5f

                                    val maxX = (parentTex.width - curSliceW).coerceAtLeast(0)
                                    val maxY = (parentTex.height - curSliceH).coerceAtLeast(0)

                                    sliceX = (downTexX - grabOffsetX).roundToInt().coerceIn(0, maxX)
                                    sliceY = (downTexY - grabOffsetY).roundToInt().coerceIn(0, maxY)

                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: break
                                        if (!change.pressed) break
                                        change.consume()
                                        val moveTexX = change.position.x / texelPx
                                        val moveTexY = change.position.y / texelPx
                                        sliceX = (moveTexX - grabOffsetX).roundToInt().coerceIn(0, maxX)
                                        sliceY = (moveTexY - grabOffsetY).roundToInt().coerceIn(0, maxY)
                                    }
                                }
                            },
                ) {
                    Image(
                        bitmap = parentBitmap,
                        contentDescription = parentTex.name,
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.fillMaxSize(),
                    )

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val sx = sliceX * texelPx
                        val sy = sliceY * texelPx
                        val sw = sliceWidth * texelPx
                        val sh = sliceHeight * texelPx
                        val maskCol = Color(0x9606080C)

                        // Dimmed outer regions around active sub-rectangle
                        if (sy > 0f) {
                            drawRect(maskCol, Offset(0f, 0f), Size(size.width, sy))
                        }
                        if (sy + sh < size.height) {
                            drawRect(maskCol, Offset(0f, sy + sh), Size(size.width, size.height - (sy + sh)))
                        }
                        if (sx > 0f) {
                            drawRect(maskCol, Offset(0f, sy), Size(sx, sh))
                        }
                        if (sx + sw < size.width) {
                            drawRect(maskCol, Offset(sx + sw, sy), Size(size.width - (sx + sw), sh))
                        }

                        // Active dashed cyan selection box
                        drawRect(
                            color = colors.sliceCyan,
                            topLeft = Offset(sx, sy),
                            size = Size(sw, sh),
                            style =
                                Stroke(
                                    width = 2.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f),
                                ),
                        )
                    }
                }
            }

            // Live Extracted Slice Tile Preview Card
            val slicePreviewBmp =
                remember(parentTex, sliceX, sliceY, sliceWidth, sliceHeight, state.palette) {
                    val subPixels = ByteArray(sliceWidth * sliceHeight)
                    for (r in 0 until sliceHeight) {
                        val srcRow = sliceY + r
                        if (srcRow in 0 until parentTex.height) {
                            for (c in 0 until sliceWidth) {
                                val srcCol = sliceX + c
                                if (srcCol in 0 until parentTex.width) {
                                    subPixels[r * sliceWidth + c] =
                                        parentTex.indexedPixels[srcRow * parentTex.width + srcCol]
                                }
                            }
                        }
                    }
                    val tempTex =
                        SourceTexture(
                            name = "slice_preview",
                            width = sliceWidth,
                            height = sliceHeight,
                            indexedPixels = subPixels,
                        )
                    TextureComposeRenderer.renderToImageBitmap(tempTex, state.palette)
                }

            Column(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(metrics.radiusSM))
                        .background(colors.surface0)
                        .border(1.dp, colors.borderSubtle, RoundedCornerShape(metrics.radiusSM))
                        .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Slice Preview", style = typography.caption, color = colors.textSecondary)
                Box(
                    modifier =
                        Modifier
                            .size(64.dp)
                            .checkerboardBackground(4.dp, colors.checkerDark, colors.checkerLight)
                            .border(1.dp, colors.sliceCyan.copy(alpha = 0.6f)),
                ) {
                    Image(
                        bitmap = slicePreviewBmp,
                        contentDescription = "Slice preview",
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                StudioBadge(
                    text = "${sliceWidth}x$sliceHeight",
                    textColor = colors.sliceCyan,
                    backgroundColor = colors.sliceCyan.copy(alpha = 0.16f),
                    monospace = true,
                )
            }
        }
    }
}
