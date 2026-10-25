package net.jetnet.jetbox.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jetnet.jetbox.logic.ShieldPhase
import net.jetnet.jetbox.ui.icons.JetBoxIcons
import net.jetnet.jetbox.ui.theme.JetBoxColors
import net.jetnet.jetbox.ui.theme.JetBoxTheme
import kotlin.math.PI
import kotlin.math.sin

/**
 * Щит главного экрана — он же кнопка. Порт `JetBoxShield.swift` 1:1, вариант А «Спокойный»:
 * приглашающее дыхание кольца, хвост по кругу на подключении, при успехе — подскок щита, одна волна,
 * затем ровное дыхание свечения. «Уменьшение движения» — только смена цвета.
 *
 * Диск — ветка iOS 15–25: «материал» + тонировка фазы + тонкое белое кольцо. Нажатие принимается
 * только в круге диска (`contentShape(Circle())`).
 *
 * @param label метка для TalkBack (действие или состояние); без [onClick] щит — просто индикатор.
 * @param celebratesOnAppear экран по ссылке показывает щит сразу в «Под защитой» — там успех
 * празднуется при появлении; на главном экране — нет.
 */
@Composable
fun JetBoxShield(
    phase: ShieldPhase,
    modifier: Modifier = Modifier,
    diameter: Dp = 188.dp,
    label: String = "",
    celebratesOnAppear: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = JetBoxTheme.colors
    val reduceMotion = JetBoxTheme.reduceMotion
    val view = LocalView.current

    val pop = remember { Animatable(1f) }
    val waveScale = remember { Animatable(1f) }
    val waveOpacity = remember { Animatable(0f) }
    val previous = remember { arrayOfNulls<ShieldPhase>(1) }
    // Празднование живёт в своей области, как `withAnimation` iOS: смена фазы его не обрывает
    // (подскок и волна доигрывают до конца), а при любой отмене значения доводятся до покоя.
    val celebrationScope = rememberCoroutineScope()
    val celebration = remember { arrayOfNulls<Job>(1) }

    LaunchedEffect(phase) {
        val prev = previous[0]
        val celebrate = phase == ShieldPhase.ON && if (prev == null) celebratesOnAppear else prev != ShieldPhase.ON
        previous[0] = phase
        if (!celebrate) return@LaunchedEffect
        JBHaptics.success(view)
        if (reduceMotion) return@LaunchedEffect
        val before = celebration[0]
        celebration[0] = celebrationScope.launch {
            before?.cancelAndJoinQuietly()
            celebrate(pop, waveScale, waveOpacity)
        }
    }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) 0.93f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = swiftStiffness(0.25f)),
        label = "shieldPress",
    )

    val phaseSpec = tween<Color>(350, easing = EaseInOut)
    // Цвета читаются только при рисовании: смена фазы перерисовывает, но не перекомпоновывает щит.
    val tint = animateColorAsState(phase.tint(c), phaseSpec, label = "shieldTint")
    val discTint = animateColorAsState(phase.discTint(c), phaseSpec, label = "discTint")

    Box(
        modifier = modifier
            .size(diameter)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .then(if (onClick == null) Modifier.clearAndSetSemantics { if (label.isNotEmpty()) contentDescription = label } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (!reduceMotion) {
            ShieldDecorations(phase, diameter, c.accent, interactive = onClick != null)
        }
        ShieldDisc(diameter, c, discTint)
        AnimatedContent(
            targetState = phase.symbol,
            transitionSpec = {
                (fadeIn(tween(350, easing = EaseInOut)) + scaleIn(tween(350, easing = EaseInOut), initialScale = 0.88f))
                    .togetherWith(fadeOut(tween(350, easing = EaseInOut)) + scaleOut(tween(350, easing = EaseInOut), targetScale = 0.88f))
            },
            label = "shieldSymbol",
        ) { symbol ->
            ShieldGlyph(symbol, tint, pop, diameter * JetBoxIcons.SIZE_TO_DIAMETER)
        }
        // Волна успеха: кольцо 2 dp по краю диска расходится до ×1,55 и гаснет.
        Spacer(
            Modifier
                .requiredSize(diameter * 1.6f)
                .graphicsLayer()
                .drawWithCache {
                    val r = diameter.toPx() / 2
                    val stroke = Stroke(2.dp.toPx())
                    val accent = c.accent
                    onDrawBehind {
                        val opacity = waveOpacity.value
                        if (opacity > 0f) {
                            scale(waveScale.value) { drawCircle(accent, radius = r, alpha = opacity, style = stroke) }
                        }
                    }
                },
        )
        if (onClick != null) {
            // Зона нажатия — круг диска: углы квадрата и кольца вокруг не нажимаются.
            Spacer(
                Modifier
                    .matchParentSize()
                    .clip(CircleShape)
                    .semantics(mergeDescendants = true) { contentDescription = label; role = Role.Button }
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                        JBHaptics.tap(view)
                        onClick()
                    },
            )
        }
    }
}

/** Подскок щита (пружина 0,5/0,5) и волна (1,1 с) — `celebrate()` iOS. Значения всегда приходят в покой. */
private suspend fun celebrate(pop: Animatable<Float, *>, waveScale: Animatable<Float, *>, waveOpacity: Animatable<Float, *>) {
    try {
        coroutineScope {
            launch {
                pop.snapTo(0.6f)
                pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = swiftStiffness(0.5f)))
            }
            launch {
                waveScale.snapTo(1f)
                waveOpacity.snapTo(0.8f)
                launch { waveScale.animateTo(1.55f, tween(1100, easing = EaseOut)) }
                waveOpacity.animateTo(0f, tween(1100, easing = EaseOut))
            }
        }
    } finally {
        withContext(NonCancellable) {
            pop.snapTo(1f)
            waveOpacity.snapTo(0f)
            waveScale.snapTo(1f)
        }
    }
}

private suspend fun Job.cancelAndJoinQuietly() {
    cancel()
    join()
}

/**
 * Диск: «материал», тонировка фазы, белое кольцо 1 dp (strokeBorder — внутрь). Снаружи — мягкая
 * тень-ореол строго за краем диска (внутри прозрачно, чтобы не темнить полупрозрачную заливку).
 * Кисти и обводка строятся один раз в кэше рисования.
 */
@Composable
private fun ShieldDisc(diameter: Dp, c: JetBoxColors, discTint: State<Color>) {
    Spacer(
        Modifier
            .requiredSize(diameter * 1.2f)
            .drawWithCache {
                val r = diameter.toPx() / 2
                val haloRadius = r * HALO
                val edge = 1f / HALO
                val shadow = Color.Black.copy(alpha = if (c.isDark) 0.15f else 0.07f)
                val halo = Brush.radialGradient(
                    0f to Color.Transparent,
                    (edge - 0.001f) to Color.Transparent,
                    edge to shadow,
                    1f to Color.Transparent,
                    center = size.center,
                    radius = haloRadius,
                )
                val w = 1.dp.toPx()
                val rim = Stroke(w)
                // Светлая тема: ободок заметно белый (≈0,6), как у `.ultraThinMaterial` на iOS.
                val rimColor = Color.White.copy(alpha = if (c.isDark) 0.22f else 0.6f)
                val material = c.material
                onDrawBehind {
                    drawCircle(halo, radius = haloRadius)
                    drawCircle(material, radius = r)
                    drawCircle(discTint.value, radius = r)
                    drawCircle(rimColor, radius = r - w / 2, style = rim)
                }
            },
    )
}

private const val HALO = 1.16f

/**
 * Значок фазы. Вектор строится один раз на символ (белым) и тонируется цветом фазы при рисовании;
 * галочка «Под защитой» — отдельный белый слой поверх щита (палитра iOS).
 */
@Composable
private fun ShieldGlyph(symbol: ShieldSymbol, tint: State<Color>, pop: Animatable<Float, *>, size: Dp) {
    val base = rememberVectorPainter(remember(symbol) { symbol.baseVector() })
    val mark = if (symbol == ShieldSymbol.CHECK) rememberVectorPainter(remember { JetBoxIcons.shieldCheckmark() }) else null
    Spacer(
        Modifier
            .size(size)
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            }
            .drawBehind {
                with(base) { draw(this@drawBehind.size, colorFilter = ColorFilter.tint(tint.value)) }
                if (mark != null) with(mark) { draw(this@drawBehind.size) }
            },
    )
}

/** Кольца вокруг диска; время — как у `TimelineView(minimumInterval: 1/60)`, анимация не накапливает состояние. */
@Composable
private fun ShieldDecorations(phase: ShieldPhase, diameter: Dp, accent: Color, interactive: Boolean) {
    val needsTicks = when (phase) {
        ShieldPhase.OFF -> interactive
        ShieldPhase.WORKING, ShieldPhase.WORKING_REVERSE, ShieldPhase.ON -> true
        ShieldPhase.OFFLINE -> false
    }
    if (!needsTicks) return
    val time = rememberAnimationClock(paused = false, minIntervalMs = 16)
    Spacer(
        Modifier
            .requiredSize(diameter * 1.6f)
            .graphicsLayer()
            .drawWithCache {
                val d = diameter.toPx()
                when (phase) {
                    ShieldPhase.OFF -> {
                        // Кольцо-приглашение: strokeBorder 2 по рамке d+28, масштаб 0,94…1, прозрачность = вдох.
                        val w = 2.dp.toPx()
                        val r = (d + 28.dp.toPx()) / 2 - w / 2
                        val stroke = Stroke(w)
                        onDrawBehind {
                            val b = breath(time.doubleValue, 2.8)
                            scale(0.94f + 0.06f * b) { drawCircle(accent, radius = r, alpha = 0.45f * b, style = stroke) }
                        }
                    }
                    ShieldPhase.WORKING, ShieldPhase.WORKING_REVERSE -> {
                        val reverse = phase == ShieldPhase.WORKING_REVERSE
                        val period = if (reverse) 1.4 else 1.1
                        val tail = Tail(accent, (d + 20.dp.toPx()) / 2, 5.dp.toPx(), size.center)
                        onDrawBehind {
                            val turns = (time.doubleValue % period) / period
                            val degrees = ((if (reverse) -1 else 1) * turns * 360).toFloat()
                            rotate(degrees) { drawArc(tail.brush, 0f, TAIL_SWEEP, false, tail.topLeft, tail.size, style = tail.stroke) }
                        }
                    }
                    ShieldPhase.ON -> {
                        // Свечение под защитой: радиальный градиент 0,35d…0,78d, рамка 1,6d, дыхание 4 с.
                        val glow = accent.copy(alpha = 0.40f)
                        val brush = Brush.radialGradient(
                            0f to glow,
                            (0.35f / 0.78f) to glow,
                            1f to accent.copy(alpha = 0f),
                            center = size.center,
                            radius = d * 0.78f,
                        )
                        onDrawBehind {
                            val b = breath(time.doubleValue, 4.0)
                            scale(0.96f + 0.08f * b) { drawCircle(brush, radius = d * 0.8f, alpha = 0.45f + 0.55f * b) }
                        }
                    }
                    ShieldPhase.OFFLINE -> onDrawBehind { }
                }
            },
    )
}

/**
 * Хвост: дуга 0…0,28 оборота (100,8°) с угловым градиентом от прозрачного к фирменному, толщина 5,
 * скруглённые концы. Начальный скруглённый конец попадает в отрицательные углы — там тоже прозрачно.
 */
private class Tail(accent: Color, radius: Float, width: Float, center: Offset) {
    val brush = Brush.sweepGradient(
        0f to accent.copy(alpha = 0f),
        0.28f to accent,
        0.30f to accent,
        0.31f to accent.copy(alpha = 0f),
        1f to accent.copy(alpha = 0f),
        center = center,
    )
    val topLeft = Offset(center.x - radius, center.y - radius)
    val size = Size(radius * 2, radius * 2)
    val stroke = Stroke(width, cap = StrokeCap.Round)
}

private const val TAIL_SWEEP = 100.8f

/** 0…1 по синусоиде с заданным периодом. */
private fun breath(t: Double, period: Double): Float = ((sin(t * 2 * PI / period) + 1) / 2).toFloat()

/** Жёсткость пружины Compose для SwiftUI `.spring(response:)`: k = (2π / response)². */
private fun swiftStiffness(response: Float): Float = (2 * PI / response).let { (it * it).toFloat() }

/** Цвет фазы (`JetBoxShieldPhase.tint`): под защитой — фирменный синий, не зелёный. */
fun ShieldPhase.tint(c: JetBoxColors): Color = when (this) {
    ShieldPhase.OFF -> c.muted
    ShieldPhase.WORKING, ShieldPhase.WORKING_REVERSE -> c.accent
    ShieldPhase.ON -> c.accent
    ShieldPhase.OFFLINE -> c.warn
}

private fun ShieldPhase.discTint(c: JetBoxColors): Color = when (this) {
    ShieldPhase.OFF -> c.muted.copy(alpha = 0.10f)
    ShieldPhase.WORKING, ShieldPhase.WORKING_REVERSE -> tint(c).copy(alpha = 0.16f)
    ShieldPhase.ON -> tint(c).copy(alpha = 0.30f)
    ShieldPhase.OFFLINE -> tint(c).copy(alpha = 0.24f)
}

/** SF Symbol фазы (`JetBoxShieldPhase.symbol`). */
private enum class ShieldSymbol { SLASH, HALF, CHECK, EXCLAMATION }

private val ShieldPhase.symbol: ShieldSymbol
    get() = when (this) {
        ShieldPhase.OFF -> ShieldSymbol.SLASH
        ShieldPhase.WORKING, ShieldPhase.WORKING_REVERSE -> ShieldSymbol.HALF
        ShieldPhase.ON -> ShieldSymbol.CHECK
        ShieldPhase.OFFLINE -> ShieldSymbol.EXCLAMATION
    }

/**
 * Белая основа значка (тонируется при рисовании). Под защитой — палитра: сплошной щит здесь, белая
 * галочка — отдельным слоем; остальное — иерархия.
 */
private fun ShieldSymbol.baseVector(): ImageVector = when (this) {
    ShieldSymbol.SLASH -> JetBoxIcons.shieldSlash()
    ShieldSymbol.HALF -> JetBoxIcons.shieldLeftHalfFilled()
    ShieldSymbol.CHECK -> JetBoxIcons.shieldFill()
    ShieldSymbol.EXCLAMATION -> JetBoxIcons.exclamationmarkShieldFill()
}

@Composable
private fun ShieldPhasesPreview(dark: Boolean) {
    JetBoxTheme(darkTheme = dark) {
        Box {
            JetBoxBackdrop(ShieldPhase.OFF)
            Row(Modifier.padding(32.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                ShieldPhase.entries.forEach { JetBoxShield(it, diameter = 120.dp, onClick = {}) }
            }
        }
    }
}

@Preview(name = "Щит — светлая", widthDp = 900, heightDp = 200)
@Composable
private fun ShieldLightPreview() = ShieldPhasesPreview(dark = false)

@Preview(name = "Щит — тёмная", widthDp = 900, heightDp = 200, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ShieldDarkPreview() = ShieldPhasesPreview(dark = true)
