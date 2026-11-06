package com.soniq.player.ui.theme

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp

// ============================================================================
// THEME 2 - Soft neumorphic mint (full app + music player)
// ============================================================================
val NeoBg = Color(0xFFC9DCD4)
val NeoSurface = Color(0xFFC9DCD4)
val NeoInset = Color(0xFFB8CFC6)
val NeoDialog = Color(0xFFD6E7DF)
val NeoLight = Color(0xFFEEF8F3)
val NeoShadowDark = Color(0xFF86A399)
val NeoInk = Color(0xFF233733)
val NeoMuted = Color(0xFF5B7069)
val NeoEdge = Color(0x99FFFFFF)
val NeoAccent = Color(0xFF1B6FE0)
val NeoAccentSoft = Color(0xFF3F9CFF)
val NeoDanger = Color(0xFFD13A6C)
val NeoOk = Color(0xFF1E8C5A)

// ============================================================================
// THEME 1 - Liquid glass with blue gradient (navigation bar + search bar only)
// ============================================================================
val GlassBlueA = Color(0xFF6ED3FF)
val GlassBlueB = Color(0xFF2F7BFF)
val GlassBlueC = Color(0xFF1D3FCF)
val GlassFrost = Color(0xC7FFFFFF)
val GlassGlow = Color(0x993F9BFF)

// ============================================================================
// Lyrics highlight (current word)
// ============================================================================
val NeonBlue = Color(0xFF0A84FF)
val NeonGlow = Color(0xCC2F9BFF)

/**
 * Raised neumorphic surface: soft dark shadow bottom-right + soft light
 * highlight top-left. Draws behind the content, so place it before clip/padding.
 */
fun Modifier.neuRaised(
    cornerRadius: Dp = 22.dp,
    depth: Dp = 8.dp,
    fill: Color = NeoSurface
): Modifier = this.drawBehind {
    val r = cornerRadius.toPx()
    val d = depth.toPx()
    val canvas = drawContext.canvas.nativeCanvas
    val rect = RectF(0f, 0f, size.width, size.height)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill.toArgb() }
    paint.setShadowLayer(d * 1.6f, d * 0.6f, d * 0.6f, NeoShadowDark.copy(alpha = 0.75f).toArgb())
    canvas.drawRoundRect(rect, r, r, paint)
    paint.setShadowLayer(d * 1.6f, -d * 0.6f, -d * 0.6f, NeoLight.toArgb())
    canvas.drawRoundRect(rect, r, r, paint)
}

/** Pressed/recessed neumorphic surface used for tracks, wells and lyric panels. */
fun Modifier.neuInset(
    cornerRadius: Dp = 18.dp,
    fill: Color = NeoInset
): Modifier = this.drawBehind {
    val r = cornerRadius.toPx()
    drawRoundRect(color = fill, size = size, cornerRadius = CornerRadius(r, r))
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(NeoShadowDark.copy(alpha = 0.55f), Color.Transparent, NeoLight.copy(alpha = 0.8f)),
            start = Offset.Zero,
            end = Offset(size.width, size.height)
        ),
        size = size,
        cornerRadius = CornerRadius(r, r),
        style = Stroke(width = 2.dp.toPx())
    )
}

fun soniqLightColorScheme(): ColorScheme = lightColorScheme(
    primary = NeoAccent,
    onPrimary = Color.White,
    secondary = NeoAccentSoft,
    onSecondary = Color.White,
    background = NeoBg,
    onBackground = NeoInk,
    surface = NeoBg,
    onSurface = NeoInk,
    surfaceVariant = NeoInset,
    onSurfaceVariant = NeoMuted,
    outline = NeoShadowDark
)
