package net.jetnet.jetbox.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DoubleState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import net.jetnet.jetbox.logic.ShieldPhase
import net.jetnet.jetbox.ui.theme.JetBoxTheme
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Живой фон под «стеклом» — порт `JetBoxBackdrop.swift`: три мягких пятна фирменного цвета медленно
 * дрейфуют; под защитой пятна насыщаются фирменным синим. Как и на iOS — радиальные градиенты с
 * альфа-спадом вместо blur (blur на Android только с API 31). «Уменьшение движения» → статично.
 */
@Composable
fun JetBoxBackdrop(phase: ShieldPhase, modifier: Modifier = Modifier) {
    val c = JetBoxTheme.colors
    val reduceMotion = JetBoxTheme.reduceMotion
    val strength = if (c.isDark) 0.34f else 0.24f

    // Только фирменные цвета: синий насыщается от «выключено» к «под защитой».
    val primaryTarget = when (phase) {
        ShieldPhase.OFF -> c.accent.copy(alpha = strength * 0.6f)
        ShieldPhase.WORKING, ShieldPhase.WORKING_REVERSE -> c.accent.copy(alpha = strength)
        ShieldPhase.ON -> c.accent.copy(alpha = min(strength * 1.45f, 0.5f))
        ShieldPhase.OFFLINE -> c.warn.copy(alpha = strength * 0.8f)
    }
    // Второй тон — голубее фирменного синего, чтобы фон не был плоским.
    val secondaryTarget = when (phase) {
        ShieldPhase.OFFLINE -> c.warn.copy(alpha = strength * 0.5f)
        ShieldPhase.OFF -> Sky.copy(alpha = strength * 0.5f)
        ShieldPhase.WORKING, ShieldPhase.WORKING_REVERSE -> Sky.copy(alpha = strength * 0.8f)
        ShieldPhase.ON -> Sky.copy(alpha = strength * 1.2f)
    }
    val spec = tween<Color>(durationMillis = 1200, easing = EaseInOut)
    val primary by animateColorAsState(primaryTarget, spec, label = "backdropPrimary")
    val secondary by animateColorAsState(secondaryTarget, spec, label = "backdropSecondary")
    val time = rememberAnimationClock(paused = reduceMotion, minIntervalMs = 50)
    val background = c.background

    // Свой слой: покадровая перерисовка фона не тянет за собой остальной экран. Кисти пятен строятся
    // в кэше (заново — только при смене размера или цвета), на кадре — лишь сдвиг.
    Spacer(
        modifier
            .fillMaxSize()
            .graphicsLayer()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val big = Blob(primary, w * 1.25f)
                val side = Blob(secondary, w * 1.05f)
                val low = Blob(primary, w * 0.95f)
                onDrawBehind {
                    val t = if (reduceMotion) 0.0 else time.doubleValue
                    drawRect(background)
                    big.draw(this, w * (0.22f + 0.06f * sin(t / 7).toFloat()), h * (0.26f + 0.04f * cos(t / 9).toFloat()))
                    side.draw(this, w * (0.82f + 0.05f * cos(t / 8).toFloat()), h * (0.46f + 0.05f * sin(t / 6).toFloat()))
                    low.draw(this, w * (0.45f + 0.07f * sin(t / 11).toFloat()), h * (0.92f + 0.03f * cos(t / 7).toFloat()))
                }
            },
    )
}

/** `Color(red: 0.36, green: 0.76, blue: 1.0)` из iOS. */
private val Sky = Color(0.36f, 0.76f, 1.0f)

/** Пятно: радиальный градиент от цвета к прозрачному; кисть с центром в нуле, на кадре — сдвиг. */
private class Blob(color: Color, diameter: Float) {
    private val radius = diameter / 2
    private val brush = Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center = Offset.Zero, radius = radius)

    fun draw(scope: DrawScope, x: Float, y: Float) = scope.translate(x, y) {
        drawCircle(brush, radius = radius, center = Offset.Zero)
    }
}

/**
 * Время в секундах для покадровых анимаций — аналог `TimelineView(.animation(minimumInterval:))`:
 * значение читается в фазе рисования, поэтому перерисовывается только холст, без рекомпозиции.
 * Источник — время кадра хореографа (`frameTimeNanos`, монотонное), а не настенные часы: все
 * анимации кадра видят одно и то же время. [minIntervalMs] ограничивает частоту (16 → ~60 к/с даже
 * на 120 Гц). На паузе не тикает. `DoubleState` — без упаковки `Double` на каждом кадре.
 */
@Composable
internal fun rememberAnimationClock(paused: Boolean, minIntervalMs: Long = 0): DoubleState {
    val time = remember { mutableDoubleStateOf(System.nanoTime() / NANOS_PER_SECOND) }
    LaunchedEffect(paused, minIntervalMs) {
        if (paused) return@LaunchedEffect
        val minIntervalNanos = minIntervalMs * 1_000_000
        var last = Long.MIN_VALUE / 2
        while (true) {
            withInfiniteAnimationFrameNanos { frame ->
                if (frame - last >= minIntervalNanos) {
                    last = frame
                    time.doubleValue = frame / NANOS_PER_SECOND
                }
            }
        }
    }
    return time
}

private const val NANOS_PER_SECOND = 1_000_000_000.0
