package dev.secondsun.sfxstudio.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.secondsun.sfxstudio.desktop.shell.StudioState
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButton
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioButtonVariant
import dev.secondsun.sfxstudio.desktop.ui.components.primitives.StudioModalDialog
import dev.secondsun.sfxstudio.desktop.ui.theme.StudioTheme

@Composable
fun AboutModal(studioState: StudioState) {
    if (!studioState.showAboutModal) return
    val colors = StudioTheme.colors
    val typography = StudioTheme.typography

    StudioModalDialog(
        onDismissRequest = { studioState.showAboutModal = false },
        title = "SuperFX Studio",
        subtitle = "Version 1.0 — Texture Atlas, BGR555 Palette & Isometric Level Studio",
        preferredWidth = 520.dp,
        footerActions = {
            StudioButton(
                label = "Close",
                variant = StudioButtonVariant.Primary,
                onClick = { studioState.showAboutModal = false },
            )
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(colors.surface0, RoundedCornerShape(StudioTheme.metrics.radiusSM))
                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(StudioTheme.metrics.radiusSM))
                    .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "About This Program",
                style = typography.bodyMedium,
                color = colors.textPrimary,
            )
            Text(
                text =
                    "SuperFX Studio provides an integrated multi-document authoring environment for SuperFX (GSU / FX3) " +
                        "homebrew development. Author unpacked 1-byte-per-texel Texture Atlases (.bin), 16-color SNES BGR555 " +
                        "palettes (.pal with \$0001 opaque black), Virtual Slices, Descriptor Aliases, relocatable Texture Tables (.tab), " +
                        "ca65 headers (.i), and isometric tactical Levels (.map + .gmap).",
                style = typography.bodyRegular,
                color = colors.textSecondary,
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(colors.surface0, RoundedCornerShape(StudioTheme.metrics.radiusSM))
                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(StudioTheme.metrics.radiusSM))
                    .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Open Source License (Apache 2.0)",
                style = typography.bodyMedium,
                color = colors.textPrimary,
            )
            Text(
                text =
                    "Licensed under the Apache License, Version 2.0. Built on JetBrains Compose Multiplatform Desktop, " +
                        "Skiko Graphics Engine, Kotlin Standard Library, Google Gson, and JUnit Jupiter.",
                style = typography.caption,
                color = colors.textSecondary,
            )
        }
    }
}
