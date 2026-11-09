package com.abrignoni.knowndatalogger

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Fixed dark palette that matches the app icon, the same on every phone (no wallpaper colours),
 * so the app looks like docs/ui-mockups and screenshots compare across devices.
 */
object Kdl {
    val Bg = Color(0xFF111722)
    val Surface = Color(0xFF1A2230)
    val Surface2 = Color(0xFF222C3C)
    val Line = Color(0xFF2C384A)
    val Outline = Color(0xFF4A5A70)
    val Text = Color(0xFFE3E8EF)
    val Muted = Color(0xFF95A2B5)
    val Primary = Color(0xFFAFC6FF)
    val OnPrimary = Color(0xFF0E1A33)
    val Tonal = Color(0xFF2E3D57)
    /** Stop button: darker than the mark red so white text passes contrast. */
    val Stop = Color(0xFFB3261E)
    val Mark = Color(0xFFD64541)
    val Amber = Color(0xFFF2C27A)
    val Green = Color(0xFF8FE0B5)
}

private val scheme = darkColorScheme(
    primary = Kdl.Primary, onPrimary = Kdl.OnPrimary,
    secondaryContainer = Kdl.Tonal, onSecondaryContainer = Kdl.Text,
    background = Kdl.Bg, onBackground = Kdl.Text,
    surface = Kdl.Bg, onSurface = Kdl.Text,
    surfaceVariant = Kdl.Surface2, onSurfaceVariant = Kdl.Muted,
    surfaceContainer = Kdl.Surface, surfaceContainerLow = Kdl.Surface, surfaceContainerHigh = Kdl.Surface,
    surfaceContainerLowest = Kdl.Bg, surfaceContainerHighest = Kdl.Surface2,
    outline = Kdl.Outline, outlineVariant = Kdl.Line,
    error = Kdl.Mark, onError = Color.White,
)

@Composable
fun KdlTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)
