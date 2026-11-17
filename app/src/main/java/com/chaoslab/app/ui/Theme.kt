package com.chaoslab.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object ChaosColors {
    val Neon = Color(0xFF6FE3FF)
    val Violet = Color(0xFFB07CFF)
    val Ember = Color(0xFFFFB86B)
    val Glass = Color(0xB30B1122)
    val GlassStrong = Color(0xE60B1122)
    val GlassBorder = Color(0x405FD8FF)
    val Text = Color(0xFFE6EEFF)
    val TextDim = Color(0xFF8A9BC0)
    val Danger = Color(0xFFFF6B8A)
}

@Composable
fun ChaosTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ChaosColors.Neon,
            secondary = ChaosColors.Violet,
            background = Color.Black,
            surface = ChaosColors.GlassStrong,
            onPrimary = Color.Black,
            onSurface = ChaosColors.Text,
            onBackground = ChaosColors.Text,
        ),
        content = content,
    )
}
