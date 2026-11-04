package dev.bunnyloader.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** The background behind everything: the night of the launcher scenery, without the scenery. */
val AppBackground = Color(0xFF0F1226)

@Composable
fun EditorTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Bl.PressedText,
        onPrimary = Bl.Ink,
        secondary = Bl.TitleFill,
        background = AppBackground,
        onBackground = Bl.Text,
        surface = Bl.Panel,
        onSurface = Bl.Text,
        surfaceVariant = Bl.Select,
        onSurfaceVariant = Bl.TextFaint,
        outline = Bl.Outline,
        error = Bl.Bad,
    )
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(
            LocalContentColor provides Bl.Text,
            LocalTextSelectionColors provides TextSelectionColors(Bl.PressedText, Bl.TitleFill.copy(alpha = 0.6f)),
        ) {
            Box(Modifier.fillMaxSize().background(AppBackground)) { content() }
        }
    }
}
