package dev.secondsun.sfxstudio.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.secondsun.sfxstudio.desktop.atlas.AtlasEditorState
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButton
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButtonVariant
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioSectionHeader
import dev.secondsun.sfxstudio.desktop.ui.icons.StudioIcons
import dev.secondsun.sfxstudio.desktop.ui.theme.HairlineEdge
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioTheme
import dev.secondsun.sfxstudio.desktop.ui.theme.drawEdgeHairline

private val HEX_LITERAL_REGEX = Regex("\\$[0-9A-Fa-f]+")
private val DIRECTIVE_REGEX = Regex("^\\s*(\\.[A-Za-z_][A-Za-z0-9_]*)")

private fun highlightCa65Line(
    line: String,
    isErrorPreview: Boolean,
    textPrimary: Color,
    textMuted: Color,
    directiveColor: Color,
    hexColor: Color,
    errorColor: Color,
): AnnotatedString {
    if (isErrorPreview) {
        return AnnotatedString(line, SpanStyle(color = errorColor))
    }
    return buildAnnotatedString {
        append(line)
        val commentStart = line.indexOf(';')
        val codeEnd = if (commentStart >= 0) commentStart else line.length

        if (codeEnd > 0) {
            addStyle(SpanStyle(color = textPrimary), 0, codeEnd)
            val codeSlice = line.substring(0, codeEnd)

            val dirMatch = DIRECTIVE_REGEX.find(codeSlice)
            if (dirMatch != null) {
                val group = dirMatch.groups[1]
                if (group != null) {
                    addStyle(
                        SpanStyle(color = directiveColor),
                        group.range.first,
                        group.range.last + 1,
                    )
                }
            }

            for (m in HEX_LITERAL_REGEX.findAll(codeSlice)) {
                addStyle(
                    SpanStyle(color = hexColor),
                    m.range.first,
                    m.range.last + 1,
                )
            }
        }

        if (commentStart >= 0) {
            addStyle(SpanStyle(color = textMuted), commentStart, line.length)
        }
    }
}

@Composable
fun IndexPreviewTab(
    state: AtlasEditorState,
    modifier: Modifier = Modifier,
) {
    val colors = StudioTheme.colors
    val metrics = StudioTheme.metrics
    val typography = StudioTheme.typography

    val previewResult =
        remember(state.document) {
            state.document.renderIndexPreview(state.projectName)
        }
    val indexContent =
        previewResult.getOrElse { err ->
            ";; Cannot generate ca65 Atlas Index (.i) until descriptor errors are resolved:\n" +
                ";; ${err.message}"
        }
    val lines = remember(indexContent) { indexContent.lines() }
    val horizontalScroll = rememberScrollState()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.bgCanvas)
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StudioSectionHeader(
            title = "ca65 Atlas Index",
            badge = "${state.projectName}.i · ${lines.size} lines",
            trailing = {
                StudioButton(
                    label = "Copy",
                    variant = StudioButtonVariant.Secondary,
                    compact = true,
                    icon = StudioIcons.Copy,
                    enabled = previewResult.isSuccess,
                    onClick = { state.copyTextToClipboard(indexContent) },
                )
                StudioButton(
                    label = "Export .i...",
                    variant = StudioButtonVariant.Secondary,
                    compact = true,
                    icon = StudioIcons.Export,
                    enabled = previewResult.isSuccess,
                    onClick = { state.promptExportIndexFile() },
                )
            },
        )

        val borderCol = if (previewResult.isSuccess) colors.borderSubtle else colors.semanticError

        LazyColumn(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(metrics.radiusSM))
                    .background(colors.surface0)
                    .border(1.dp, borderCol, RoundedCornerShape(metrics.radiusSM))
                    .padding(vertical = 8.dp),
        ) {
            itemsIndexed(lines) { index, line ->
                val styledLine =
                    remember(line, previewResult.isFailure, colors) {
                        highlightCa65Line(
                            line = line,
                            isErrorPreview = previewResult.isFailure,
                            textPrimary = colors.textPrimary,
                            textMuted = colors.textMuted,
                            directiveColor = colors.accentHover,
                            hexColor = colors.aliasAmber,
                            errorColor = colors.semanticErrorText,
                        )
                    }

                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "${index + 1}",
                        style = typography.monoCaption,
                        color = colors.textMuted,
                        textAlign = TextAlign.End,
                        modifier =
                            Modifier
                                .width(44.dp)
                                .drawEdgeHairline(HairlineEdge.RIGHT, colors.borderSubtle)
                                .padding(end = 8.dp, top = 1.dp, bottom = 1.dp),
                    )
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .horizontalScroll(horizontalScroll)
                                .padding(horizontal = 10.dp),
                    ) {
                        Text(
                            text = styledLine,
                            style = typography.monoBody,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}
