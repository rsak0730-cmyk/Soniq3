package com.soniq.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class RippleWave(
    val id: Long,
    val center: Offset,
    val anim: Animatable<Float, *>
)

/**
 * Non-intrusive Bubbly Ripple Touch Physics (Water Droplet Splashes).
 * Observes pointer events on PointerEventPass.Initial without consuming them,
 * ensuring zero interference with LazyColumn scrolling, sliders, or buttons.
 */
@Composable
fun FluidTouchCanvas(
    rippleColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val ripples = remember { mutableStateListOf<RippleWave>() }
    val scope = rememberCoroutineScope()

    fun triggerSplash(center: Offset) {
        if (ripples.size > 12) return // Cap active ripples for 120Hz frame stability
        val waveAnim = Animatable(0f)
        val ripple = RippleWave(System.nanoTime(), center, waveAnim)
        ripples.add(ripple)

        scope.launch {
            waveAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 750, easing = LinearEasing)
            )
            ripples.remove(ripple)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    var lastSplashPos = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: break
                        if (change.changedToDown()) {
                            lastSplashPos = change.position
                            triggerSplash(change.position)
                        } else if (change.pressed) {
                            val dist = hypot(
                                change.position.x - lastSplashPos.x,
                                change.position.y - lastSplashPos.y
                            )
                            if (dist > 95f) {
                                lastSplashPos = change.position
                                triggerSplash(change.position)
                            }
                        }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
    ) {
        content()

        Canvas(modifier = Modifier.fillMaxSize()) {
            ripples.forEach { ripple ->
                val progress = ripple.anim.value
                val maxRadius = 115f
                val radius = progress * maxRadius
                val alpha = (1f - progress).coerceIn(0f, 1f)

                // Outer Meniscus Glow Ring
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            rippleColor.copy(alpha = alpha * 0.38f),
                            Color.Transparent
                        ),
                        center = ripple.center,
                        radius = (radius + 16f).coerceAtLeast(1f)
                    ),
                    radius = radius.coerceAtLeast(1f),
                    center = ripple.center,
                    style = Stroke(width = (4.5f * (1f - progress)).coerceAtLeast(0.8f))
                )

                // Inner Crisp Water Ring
                drawCircle(
                    color = Color.White.copy(alpha = alpha * 0.55f),
                    radius = (radius * 0.58f).coerceAtLeast(1f),
                    center = ripple.center,
                    style = Stroke(width = (1.8f * (1f - progress)).coerceAtLeast(0.4f))
                )

                // Radial Micro-Droplets
                for (i in 0..4) {
                    val angle = Math.toRadians((i * 72.0 + (progress * 36.0)))
                    val dist = radius * 1.12f
                    val dx = (dist * cos(angle)).toFloat()
                    val dy = (dist * sin(angle)).toFloat()
                    drawCircle(
                        color = rippleColor.copy(alpha = alpha * 0.75f),
                        radius = (2.8f * (1f - progress)).coerceAtLeast(0.6f),
                        center = Offset(ripple.center.x + dx, ripple.center.y + dy)
                    )
                }
            }
        }
    }
}
