package dev.secondsun.sfxstudio.desktop.ui.components.primitives

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.secondsun.sfxstudio.desktop.awt.PlatformStudioTooltip
import dev.secondsun.sfxstudio.desktop.awt.STUDIO_MODAL_DRAWS_OWN_SCRIM
import dev.secondsun.sfxstudio.desktop.awt.studioModalDialogProperties
import dev.secondsun.sfxstudio.desktop.ui.icons.StudioIcons
import dev.secondsun.sfxstudio.desktop.ui.theme.HairlineEdge
import dev.secondsun.sfxstudio.desktop.ui.theme.ProvideStudioTheme
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioTheme
import dev.secondsun.sfxstudio.desktop.ui.theme.drawEdgeHairline

enum class StudioButtonVariant {
    Primary,
    Secondary,
    Ghost,
    DangerGhost,
    DangerSolid,
}

@Composable
fun StudioButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: StudioButtonVariant = StudioButtonVariant.Secondary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
    badgeDotColor: Color? = null,
    tooltip: String? = null,
    shortcut: String? = null,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    val height = if (compact) metrics.controlHeightSM else metrics.controlHeight
    val shape = RoundedCornerShape(metrics.radiusSM)

    val bg =
        when (variant) {
            StudioButtonVariant.Primary -> if (hovered && enabled) colors.accentHover else colors.accentPrimary
            StudioButtonVariant.Secondary -> if (hovered && enabled) colors.surface3 else colors.surface2
            StudioButtonVariant.Ghost -> if (hovered && enabled) colors.surface2 else Color.Transparent
            StudioButtonVariant.DangerGhost -> if (hovered && enabled) colors.semanticErrorSubtle else Color.Transparent
            StudioButtonVariant.DangerSolid -> colors.semanticError
        }

    val borderCol =
        when (variant) {
            StudioButtonVariant.Secondary -> if (hovered && enabled) colors.borderStrong else colors.borderSubtle
            StudioButtonVariant.DangerGhost -> if (hovered && enabled) colors.semanticError.copy(alpha = 0.5f) else colors.borderSubtle
            else -> null
        }

    val contentCol =
        when (variant) {
            StudioButtonVariant.Primary -> colors.onAccent
            StudioButtonVariant.DangerSolid -> Color.White
            StudioButtonVariant.Secondary -> colors.textPrimary
            StudioButtonVariant.Ghost -> if (hovered && enabled) colors.textPrimary else colors.textSecondary
            StudioButtonVariant.DangerGhost -> if (hovered && enabled) colors.semanticErrorText else colors.textSecondary
        }

    val buttonContent: @Composable () -> Unit = {
        Row(
            modifier =
                modifier
                    .height(height)
                    .alpha(if (enabled) 1f else 0.45f)
                    .clip(shape)
                    .background(bg, shape)
                    .let { mod ->
                        if (borderCol != null) mod.border(1.dp, borderCol, shape) else mod
                    }.hoverable(interactionSource, enabled = enabled)
                    .let { mod ->
                        if (enabled) {
                            mod.clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = onClick,
                            )
                        } else {
                            mod
                        }
                    }.padding(horizontal = if (compact) 8.dp else 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentCol,
                    modifier = Modifier.size(if (compact) 14.dp else 15.dp),
                )
            }
            Text(
                text = label,
                style = if (compact) typography.caption else typography.bodyMedium,
                color = contentCol,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            if (badgeDotColor != null) {
                Box(
                    modifier =
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(badgeDotColor),
                )
            }
        }
    }

    if (tooltip != null || shortcut != null) {
        StudioTooltip(label = tooltip ?: label, shortcut = shortcut, content = buttonContent)
    } else {
        buttonContent()
    }
}

@Composable
fun StudioIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    dangerOnHover: Boolean = false,
    tint: Color? = null,
    size: Dp? = null,
    tooltip: String? = contentDescription,
    shortcut: String? = null,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val boxSize = size ?: metrics.iconButtonSize
    val shape = RoundedCornerShape(metrics.radiusSM)

    val bg =
        when {
            selected -> colors.accentSubtle
            dangerOnHover && hovered && enabled -> colors.semanticErrorSubtle
            hovered && enabled -> colors.surface2
            else -> Color.Transparent
        }

    val borderCol =
        when {
            selected -> colors.accentBorder
            else -> null
        }

    val resolvedTint =
        when {
            dangerOnHover && hovered && enabled -> colors.semanticError
            tint != null -> tint
            selected -> colors.accentHover
            hovered && enabled -> colors.textPrimary
            else -> colors.textSecondary
        }

    StudioTooltip(label = tooltip, shortcut = shortcut) {
        Box(
            modifier =
                modifier
                    .size(boxSize)
                    .alpha(if (enabled) 1f else 0.4f)
                    .clip(shape)
                    .background(bg, shape)
                    .let { mod ->
                        if (borderCol != null) mod.border(1.dp, borderCol, shape) else mod
                    }.hoverable(interactionSource, enabled = enabled)
                    .let { mod ->
                        if (enabled) {
                            mod.clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = onClick,
                            )
                        } else {
                            mod
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = resolvedTint,
                modifier = Modifier.size(metrics.iconSize),
            )
        }
    }
}

data class SegmentItem<T>(
    val value: T,
    val label: String,
    val icon: ImageVector? = null,
    val badge: String? = null,
    val enabled: Boolean = true,
)

@Composable
fun <T> StudioSegmentedControl(
    items: List<SegmentItem<T>>,
    selectedValue: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    compact: Boolean = false,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography
    val outerHeight = if (compact) metrics.controlHeightSM else metrics.controlHeight
    val outerShape = RoundedCornerShape(metrics.radiusSM)
    val thumbShape = RoundedCornerShape(metrics.radiusXS)

    Row(
        modifier =
            modifier
                .height(outerHeight)
                .clip(outerShape)
                .background(colors.surface0, outerShape)
                .border(1.dp, colors.borderSubtle, outerShape)
                .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (item in items) {
            val isSelected = item.value == selectedValue
            val interactionSource = remember(item.value) { MutableInteractionSource() }
            val hovered by interactionSource.collectIsHoveredAsState()

            val bg =
                when {
                    isSelected && prominent -> colors.accentPrimary
                    isSelected -> if (colors.isDark) colors.surface3 else colors.surface1
                    hovered && item.enabled -> colors.surface2.copy(alpha = 0.6f)
                    else -> Color.Transparent
                }
            val fg =
                when {
                    isSelected && prominent -> colors.onAccent
                    isSelected -> colors.textPrimary
                    hovered && item.enabled -> colors.textPrimary
                    else -> colors.textSecondary
                }

            Row(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .alpha(if (item.enabled) 1f else 0.45f)
                        .clip(thumbShape)
                        .background(bg, thumbShape)
                        .let { mod ->
                            if (isSelected && !prominent && !colors.isDark) {
                                mod.border(1.dp, colors.borderSubtle, thumbShape)
                            } else {
                                mod
                            }
                        }.hoverable(interactionSource, enabled = item.enabled)
                        .let { mod ->
                            if (item.enabled) {
                                mod.clickable(
                                    interactionSource = interactionSource,
                                    indication = null,
                                    onClick = { onSelect(item.value) },
                                )
                            } else {
                                mod
                            }
                        }.padding(horizontal = if (compact) 7.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (item.icon != null) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = fg,
                        modifier = Modifier.size(13.dp),
                    )
                }
                Text(
                    text = item.label,
                    style = if (compact) typography.caption else typography.bodyMedium,
                    color = fg,
                    maxLines = 1,
                    softWrap = false,
                )
                if (item.badge != null) {
                    Box(
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (isSelected) colors.accentSubtle else colors.surface2)
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = item.badge,
                            style = typography.microBadge,
                            color = if (isSelected) colors.accentHover else colors.textMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StudioTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    monospace: Boolean = false,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    isError: Boolean = false,
    errorText: String? = null,
    leadingIcon: ImageVector? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()

    val shape = RoundedCornerShape(metrics.radiusSM)
    val borderCol =
        when {
            isError -> colors.semanticError
            focused -> colors.accentHover
            hovered && enabled -> colors.borderStrong
            else -> colors.borderSubtle
        }
    val textStyle =
        (if (monospace) typography.monoBody else typography.bodyRegular).copy(
            color = if (enabled) colors.textPrimary else colors.textMuted,
        )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = textStyle,
            cursorBrush = SolidColor(colors.accentHover),
            interactionSource = interactionSource,
            decorationBox = { innerTextField ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(metrics.controlHeight)
                            .clip(shape)
                            .background(if (colors.isDark) colors.surface0 else colors.surface1, shape)
                            .border(1.dp, borderCol, shape)
                            .hoverable(interactionSource, enabled = enabled)
                            .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (leadingIcon != null) {
                        Icon(
                            imageVector = leadingIcon,
                            contentDescription = null,
                            tint = colors.textMuted,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                text = placeholder,
                                style = textStyle.copy(color = colors.textMuted),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                    trailingContent?.invoke()
                }
            },
        )
        if (!errorText.isNullOrBlank()) {
            Text(
                text = errorText,
                style = typography.caption,
                color = colors.semanticErrorText,
            )
        }
    }
}

@Composable
fun StudioNumberStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    modifier: Modifier = Modifier,
    step: Int = 1,
    prefix: String = "",
    suffix: String = "",
    enabled: Boolean = true,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography
    val height = metrics.controlHeightSM
    val shape = RoundedCornerShape(metrics.radiusSM)
    val canDec = enabled && value > range.first
    val canInc = enabled && value < range.last

    Row(
        modifier =
            modifier
                .height(height)
                .clip(shape)
                .background(colors.surface0, shape)
                .border(1.dp, colors.borderSubtle, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperHalfButton(
            icon = StudioIcons.Minus,
            description = "Decrease",
            enabled = canDec,
            width = height,
        ) {
            onValueChange((value - step).coerceIn(range))
        }
        Box(
            modifier =
                Modifier
                    .widthIn(min = 34.dp)
                    .fillMaxHeight()
                    .drawEdgeHairline(HairlineEdge.LEFT, colors.borderSubtle)
                    .drawEdgeHairline(HairlineEdge.RIGHT, colors.borderSubtle)
                    .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$prefix$value$suffix",
                style = typography.monoCaption,
                color = if (enabled) colors.textPrimary else colors.textMuted,
                maxLines = 1,
                softWrap = false,
            )
        }
        StepperHalfButton(
            icon = StudioIcons.Plus,
            description = "Increase",
            enabled = canInc,
            width = height,
        ) {
            onValueChange((value + step).coerceIn(range))
        }
    }
}

@Composable
private fun StepperHalfButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    width: Dp,
    onClick: () -> Unit,
) {
    val colors = StudioTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier =
            Modifier
                .width(width)
                .fillMaxHeight()
                .background(if (hovered && enabled) colors.surface3 else colors.surface2)
                .hoverable(interactionSource, enabled = enabled)
                .let { mod ->
                    if (enabled) {
                        mod.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClick,
                        )
                    } else {
                        mod
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) colors.textPrimary else colors.textMuted.copy(alpha = 0.5f),
            modifier = Modifier.size(12.dp),
        )
    }
}

@Composable
fun StudioSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    collapsible: Boolean = false,
    expanded: Boolean = true,
    onToggleExpand: (() -> Unit)? = null,
    topDivider: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography

    Column(modifier = modifier.fillMaxWidth()) {
        if (topDivider) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .height(1.dp)
                        .background(colors.borderSubtle),
            )
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 26.dp)
                    .let { mod ->
                        if (collapsible && onToggleExpand != null) {
                            mod
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(onClick = onToggleExpand)
                        } else {
                            mod
                        }
                    },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                if (collapsible) {
                    Icon(
                        imageVector = if (expanded) StudioIcons.ChevronDown else StudioIcons.ChevronRight,
                        contentDescription = null,
                        tint = colors.textMuted,
                        modifier = Modifier.size(13.dp),
                    )
                }
                Text(
                    text = title.uppercase(),
                    style = typography.sectionHeader,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badge != null) {
                    StudioBadge(text = badge, monospace = true)
                }
            }
            if (trailing != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    content = trailing,
                )
            }
        }
    }
}

@Composable
fun StudioPanelSheet(
    edge: HairlineEdge,
    modifier: Modifier = Modifier,
    isBottomSheet: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics

    if (isBottomSheet) {
        val shape = RoundedCornerShape(topStart = metrics.radiusLG, topEnd = metrics.radiusLG)
        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(colors.surface1, shape)
                    .drawEdgeHairline(HairlineEdge.TOP, colors.borderStrong),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(width = 32.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(colors.borderStrong),
                )
            }
            content()
        }
    } else {
        Column(
            modifier =
                modifier
                    .background(colors.surface1)
                    .drawEdgeHairline(edge, colors.borderSubtle),
            content = content,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun StudioModalDialog(
    onDismissRequest: () -> Unit,
    title: String,
    subtitle: String? = null,
    preferredWidth: Dp = 560.dp,
    scrollableBody: Boolean = true,
    footerLeading: (@Composable RowScope.() -> Unit)? = null,
    footerActions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val parentColors = StudioTheme.colors
    val parentTypography = StudioTheme.typography
    val parentViewport = StudioTheme.viewport
    val parentDensity = LocalDensity.current

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = studioModalDialogProperties(parentColors.scrim),
    ) {
        CompositionLocalProvider(LocalDensity provides parentDensity) {
            ProvideStudioTheme(
                viewport = parentViewport,
                colors = parentColors,
                typography = parentTypography,
            ) {
                val colors = StudioTheme.colors
                val metrics = StudioTheme.metrics
                val typography = StudioTheme.typography
                val viewport = StudioTheme.viewport

                val modalWidth = minOf(preferredWidth, (viewport.widthDp * 0.92f).coerceAtLeast(280.dp))
                val maxModalHeight = (viewport.heightDp * 0.90f).coerceAtLeast(280.dp)
                val shape = RoundedCornerShape(metrics.radiusLG)

                val card: @Composable () -> Unit = {
                    Surface(
                        modifier =
                            Modifier
                                .width(modalWidth)
                                .heightIn(max = maxModalHeight)
                                .shadow(16.dp, shape)
                                .border(1.dp, colors.borderStrong, shape),
                        shape = shape,
                        color = colors.surface1,
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // 1. Sticky Header
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .drawEdgeHairline(HairlineEdge.BOTTOM, colors.borderSubtle)
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = title,
                                        style = typography.titleModal,
                                        color = colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (!subtitle.isNullOrBlank()) {
                                        Text(
                                            text = subtitle,
                                            style = typography.caption,
                                            color = colors.textSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                StudioIconButton(
                                    icon = StudioIcons.Close,
                                    contentDescription = "Close dialog",
                                    onClick = onDismissRequest,
                                )
                            }

                            // 2. Body (Scrollable or Bounded Flex)
                            if (scrollableBody) {
                                Column(
                                    modifier =
                                        Modifier
                                            .weight(1f, fill = false)
                                            .fillMaxWidth()
                                            .verticalScroll(rememberScrollState())
                                            .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    content = content,
                                )
                            } else {
                                Column(
                                    modifier =
                                        Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    content = content,
                                )
                            }

                            // 3. Sticky Footer
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .background(colors.surface0)
                                        .drawEdgeHairline(HairlineEdge.TOP, colors.borderSubtle)
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    footerLeading?.invoke(this)
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    content = footerActions,
                                )
                            }
                        }
                    }
                }

                if (STUDIO_MODAL_DRAWS_OWN_SCRIM) {
                    val backdropInteraction = remember { MutableInteractionSource() }
                    val cardConsumeInteraction = remember { MutableInteractionSource() }
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(colors.scrim)
                                .clickable(
                                    interactionSource = backdropInteraction,
                                    indication = null,
                                    onClick = onDismissRequest,
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier =
                                Modifier.clickable(
                                    interactionSource = cardConsumeInteraction,
                                    indication = null,
                                    onClick = {},
                                ),
                        ) {
                            card()
                        }
                    }
                } else {
                    card()
                }
            }
        }
    }
}

@Composable
fun StudioBadge(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = StudioTheme.colors.textSecondary,
    backgroundColor: Color = StudioTheme.colors.surface3,
    borderColor: Color? = null,
    monospace: Boolean = false,
) {
    val typography = StudioTheme.typography
    val shape = RoundedCornerShape(StudioTheme.metrics.radiusXS)
    Box(
        modifier =
            modifier
                .heightIn(min = 18.dp)
                .clip(shape)
                .background(backgroundColor, shape)
                .let { mod ->
                    if (borderColor != null) mod.border(1.dp, borderColor, shape) else mod
                }.padding(horizontal = 5.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = if (monospace) typography.monoCaption else typography.microBadge,
            color = textColor,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
fun StudioKeycap(
    keyLabel: String,
    modifier: Modifier = Modifier,
) {
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography
    val shape = RoundedCornerShape(StudioTheme.metrics.radiusXS)
    Box(
        modifier =
            modifier
                .height(18.dp)
                .widthIn(min = 18.dp)
                .clip(shape)
                .background(colors.surface0, shape)
                .border(1.dp, colors.borderSubtle, shape)
                .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = keyLabel,
            style = typography.monoCaption,
            color = colors.textMuted,
            maxLines = 1,
        )
    }
}

@Composable
fun StudioTooltip(
    label: String?,
    shortcut: String? = null,
    content: @Composable () -> Unit,
) {
    val viewport = StudioTheme.viewport
    if (viewport.touchComfort || label.isNullOrBlank()) {
        content()
        return
    }
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography
    val shape = RoundedCornerShape(StudioTheme.metrics.radiusSM)

    PlatformStudioTooltip(
        tooltip = {
            Row(
                modifier =
                    Modifier
                        .shadow(8.dp, shape)
                        .clip(shape)
                        .background(colors.surface3, shape)
                        .border(1.dp, colors.borderStrong, shape)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = label,
                    style = typography.caption,
                    color = colors.textPrimary,
                )
                if (!shortcut.isNullOrBlank()) {
                    StudioKeycap(shortcut)
                }
            }
        },
        content = content,
    )
}

data class StudioDiagnosticItem(
    val isError: Boolean,
    val code: String,
    val message: String,
    val onClick: (() -> Unit)? = null,
)

@Composable
fun StudioDiagnosticsBanner(
    items: List<StudioDiagnosticItem>,
    activeIndex: Int,
    onActiveIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography

    val safeIndex = activeIndex.coerceIn(0, items.lastIndex)
    val current = items[safeIndex]
    val errorCount = items.count { it.isError }
    val warnCount = items.size - errorCount
    val hasErrors = errorCount > 0

    val bg = if (hasErrors) colors.semanticErrorSubtle else colors.semanticWarningSubtle
    val stripe = if (hasErrors) colors.semanticError else colors.semanticWarning
    val textCol = if (current.isError) colors.semanticErrorText else colors.semanticWarningText

    var listExpanded by remember { mutableStateOf(false) }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = if (metrics.touchComfort) 40.dp else 32.dp)
                .background(colors.surface1)
                .background(bg)
                .drawEdgeHairline(HairlineEdge.LEFT, stripe, strokeDp = 3.dp)
                .drawEdgeHairline(HairlineEdge.BOTTOM, stripe.copy(alpha = 0.45f))
                .padding(start = 10.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (current.isError) StudioIcons.StatusError else StudioIcons.StatusWarn,
            contentDescription = null,
            tint = stripe,
            modifier = Modifier.size(15.dp),
        )

        StudioBadge(
            text = current.code,
            textColor = textCol,
            backgroundColor = stripe.copy(alpha = 0.22f),
            monospace = true,
        )

        Text(
            text = current.message,
            style = typography.caption,
            color = textCol,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .weight(1f)
                    .let { mod ->
                        if (current.onClick != null) mod.clickable { current.onClick.invoke() } else mod
                    },
        )

        if (items.size > 1) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                StudioIconButton(
                    icon = StudioIcons.ChevronLeft,
                    contentDescription = "Previous diagnostic",
                    size = 24.dp,
                    onClick = {
                        val prev = if (safeIndex == 0) items.lastIndex else safeIndex - 1
                        onActiveIndexChange(prev)
                        items[prev].onClick?.invoke()
                    },
                )
                Text(
                    text = "${safeIndex + 1}/${items.size}",
                    style = typography.monoCaption,
                    color = textCol,
                )
                StudioIconButton(
                    icon = StudioIcons.ChevronRight,
                    contentDescription = "Next diagnostic",
                    size = 24.dp,
                    onClick = {
                        val next = (safeIndex + 1) % items.size
                        onActiveIndexChange(next)
                        items[next].onClick?.invoke()
                    },
                )
            }
        }

        Box {
            val summaryText =
                buildList {
                    if (errorCount > 0) add("$errorCount err")
                    if (warnCount > 0) add("$warnCount warn")
                }.joinToString(", ")
            StudioButton(
                label = summaryText,
                variant = StudioButtonVariant.Ghost,
                compact = true,
                icon = StudioIcons.ChevronDown,
                onClick = { listExpanded = true },
            )
            DropdownMenu(
                expanded = listExpanded,
                onDismissRequest = { listExpanded = false },
                modifier =
                    Modifier
                        .widthIn(min = 320.dp, max = 520.dp)
                        .heightIn(max = 260.dp)
                        .background(colors.surface2),
            ) {
                items.forEachIndexed { idx, diag ->
                    DropdownMenuItem(
                        onClick = {
                            listExpanded = false
                            onActiveIndexChange(idx)
                            diag.onClick?.invoke()
                        },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = if (diag.isError) StudioIcons.StatusError else StudioIcons.StatusWarn,
                                contentDescription = null,
                                tint = if (diag.isError) colors.semanticError else colors.semanticWarning,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "[${diag.code}] ${diag.message}",
                                style = typography.caption,
                                color = if (diag.isError) colors.semanticErrorText else colors.semanticWarningText,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
