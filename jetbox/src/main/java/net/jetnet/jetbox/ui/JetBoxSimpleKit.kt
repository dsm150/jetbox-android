package net.jetnet.jetbox.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.jetnet.jetbox.logic.JetBoxStyle
import net.jetnet.jetbox.logic.ShieldPhase
import net.jetnet.jetbox.R
import net.jetnet.jetbox.ui.icons.JetBoxIcons
import net.jetnet.jetbox.ui.theme.JetBoxTheme
import androidx.compose.ui.res.stringResource

/*
 * Порт `JetBoxSimpleKit.swift` — детали стиля «простое» (макет Алексея 20.11.2026): белый фон, один
 * фиолетовый акцент, крупный шрифт — ничего мельче 17 sp. Эмодзи макета заменены значками Material
 * (и нашими векторами) в акценте, раскладка макета сохранена.
 *
 * Шрифт: макет набран Nunito, iOS берёт системный округлый SF Rounded; здесь — системный шрифт тех же
 * весов (600–900): он уже есть на телефоне и не утяжеляет сборку.
 */

/** Цвета и шрифты стиля «простое» (`JBSimple` iOS). Только светлые — стиль не бывает тёмным. */
object JBSimple {
    val accent = Color(0xFF7457EE)
    val accentTint = Color(0xFFEFEAFE)
    val ink = Color(0xFF1F1A2E)
    val muted = Color(0xFF5D5870)
    val faint = Color(0xFF7A7390)
    val background = Color.White
    val card = Color(0xFFF6F4FB)
    val line = Color(0xFFEBE8F2)
    val offFill = Color(0xFFF3F2F6)
    val offDash = Color(0xFFD6D2E3)
    val offSymbol = Color(0xFFB4AEC4)
    val error = Color(0xFFC2334D)

    /** Наименьший кегль стиля — правило макета «ничего мельче 17». */
    const val MIN_SP = 17

    fun font(size: Int, weight: FontWeight = FontWeight.Bold): TextStyle {
        require(size >= MIN_SP) { "в стиле «простое» шрифт не мельче $MIN_SP sp" }
        return TextStyle(fontSize = size.sp, lineHeight = (size * 1.25f).sp, fontWeight = weight)
    }

    val black = FontWeight.Black
    val heavy = FontWeight.ExtraBold
    val bold = FontWeight.Bold
    val semibold = FontWeight.SemiBold

    /** Вторичный текст — один стиль на всё приложение: 19 / 600 / приглушённый. */
    val secondary = font(19, semibold)
}

/**
 * Значки «Что такое JetBox» и щиты значка круга, которых нет в Material core: контуры Material Icons
 * (Apache-2.0) — роутер, смартфон, «не видно», щит.
 */
object JBSimpleIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
        .build()

    val router by lazy {
        icon(
            "router",
            "M20.2,5.9l0.8,-0.8C19.6,3.7 17.8,3 16,3s-3.6,0.7 -5,2.1l0.8,0.8C13,4.8 14.5,4.2 16,4.2s3,0.6 4.2,1.7z" +
                "M19.3,6.7c-0.9,-0.9 -2.1,-1.4 -3.3,-1.4s-2.4,0.5 -3.3,1.4l0.8,0.8c0.7,-0.7 1.6,-1 2.5,-1 0.9,0 1.8,0.3 2.5,1l0.8,-0.8z" +
                "M19,13h-2V9h-2v4H5c-1.1,0 -2,0.9 -2,2v4c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2v-4c0,-1.1 -0.9,-2 -2,-2z" +
                "M8,18H6v-2h2v2zM11.5,18h-2v-2h2v2zM15,18h-2v-2h2v2z",
        )
    }

    val smartphone by lazy {
        icon(
            "smartphone",
            "M17,1.01L7,1c-1.1,0 -2,0.9 -2,2v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-1.99 -2,-1.99z" +
                "M17,19H7V5h10v14z",
        )
    }

    val visibilityOff by lazy {
        icon(
            "visibility_off",
            "M12,7c2.76,0 5,2.24 5,5 0,0.65 -0.13,1.26 -0.36,1.83l2.92,2.92c1.51,-1.26 2.7,-2.89 3.43,-4.75 " +
                "-1.73,-4.39 -6,-7.5 -11,-7.5 -1.4,0 -2.74,0.25 -3.98,0.7l2.16,2.16C10.74,7.13 11.35,7 12,7z" +
                "M2,4.27l2.28,2.28 0.46,0.46C3.08,8.3 1.78,10.02 1,12c1.73,4.39 6,7.5 11,7.5 1.55,0 3.03,-0.3 " +
                "4.38,-0.84l0.42,0.42L19.73,22 21,20.73 3.27,3 2,4.27z" +
                "M7.53,9.8l1.55,1.55c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.66 1.34,3 3,3 0.22,0 0.44,-0.03 " +
                "0.65,-0.08l1.55,1.55c-0.67,0.33 -1.41,0.53 -2.2,0.53 -2.76,0 -5,-2.24 -5,-5 0,-0.79 0.2,-1.53 0.53,-2.2z" +
                "M11.84,9.02l3.15,3.15 0.02,-0.16c0,-1.66 -1.34,-3 -3,-3l-0.17,0.01z",
        )
    }

    val shield by lazy { icon("shield", "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4z") }

    val shieldOutline by lazy {
        icon(
            "shield_outline",
            "M12,1L3,5v6c0,5.55 3.84,10.74 9,12c5.16,-1.26 9,-6.45 9,-12V5L12,1z" +
                "M19,11c0,4.52 -2.98,8.69 -7,9.93c-4.02,-1.24 -7,-5.41 -7,-9.93V6.3l7,-3.11l7,3.11V11z",
        )
    }
}

/** Нажатие без ряби: лёгкое сжатие и приглушение (`JBSimplePressStyle`). */
@Composable
fun Modifier.jbSimplePress(role: Role = Role.Button, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(150), label = "pressScale")
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            alpha = if (pressed) 0.85f else 1f
        }
        .clickable(interactionSource = interaction, indication = null, role = role, onClick = onClick)
}

// region Кнопки

/** Главная кнопка: 64 dp, капсула, акцент с тенью. */
@Composable
fun JBSimplePrimaryButton(title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .jbSimplePress(onClick = onClick)
            .shadow(14.dp, RoundedCornerShape(50), ambientColor = JBSimple.accent, spotColor = JBSimple.accent)
            .background(JBSimple.accent, RoundedCornerShape(50))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = JBSimple.font(21, JBSimple.black), color = Color.White, textAlign = TextAlign.Center)
    }
}

/** Текстовая кнопка-действие: 19 / 800, зона касания не меньше 48 dp. */
@Composable
fun JBSimpleTextButton(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = JBSimple.accent,
    onClick: () -> Unit,
) {
    Box(
        modifier.heightIn(min = 48.dp).jbSimplePress(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = JBSimple.font(19, JBSimple.heavy), color = color, textAlign = TextAlign.Center)
    }
}

/** Пилюля «Закрыть» в шапке. */
@Composable
fun JBSimpleCapsuleButton(title: String, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 84.dp)
            .jbSimplePress(onClick = onClick)
            .background(JBSimple.card, RoundedCornerShape(50))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = JBSimple.font(18, JBSimple.black), color = JBSimple.ink, maxLines = 1)
    }
}

/** Круглая кнопка-значок шапки (шестерёнка): 56 dp, фон карточки. */
@Composable
fun JBSimpleIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .jbSimplePress(onClick = onClick)
            .background(JBSimple.card, CircleShape)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = JBSimple.muted, modifier = Modifier.size(28.dp))
    }
}

// endregion
// region Шапка, карточки, строки

/**
 * Шапка экрана: заголовок по центру, пилюля справа. Длинный заголовок, которому по центру мешает
 * пилюля, сдвигается влево, а не обрезается; не влез и так — многоточие.
 */
@Composable
fun JBSimpleHeader(title: String, trailingTitle: String? = null, onTrailing: (() -> Unit)? = null) {
    val gap = 8.dp
    Layout(
        content = {
            Text(
                title,
                style = JBSimple.font(22, JBSimple.black),
                color = JBSimple.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (trailingTitle != null && onTrailing != null) JBSimpleCapsuleButton(trailingTitle, onTrailing)
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val pill = measurables.getOrNull(1)?.measure(loose)
        val width = constraints.maxWidth
        val reserved = pill?.let { it.width + gap.roundToPx() } ?: 0
        val titlePlaceable = measurables[0].measure(loose.copy(maxWidth = (width - reserved).coerceAtLeast(0)))
        val height = maxOf(constraints.minHeight, titlePlaceable.height, pill?.height ?: 0)
        layout(width, height) {
            val centered = (width - titlePlaceable.width) / 2
            val x = minOf(centered, width - reserved - titlePlaceable.width).coerceAtLeast(0)
            titlePlaceable.placeRelative(x, (height - titlePlaceable.height) / 2)
            pill?.placeRelative(width - pill.width, (height - pill.height) / 2)
        }
    }
}

/** Карточка: фон #F6F4FB, скругление 26. */
@Composable
fun JBSimpleCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(JBSimple.card),
        content = content,
    )
}

/** Подпись секции над карточкой. */
@Composable
fun JBSimpleSectionLabel(title: String) {
    Text(
        title,
        style = JBSimple.font(18, JBSimple.heavy),
        color = JBSimple.muted,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 14.dp).semantics { heading() },
    )
}

/** Пояснение под карточкой: 17 / 600 / приглушённый. */
@Composable
fun JBSimpleFootnote(text: String) {
    Text(
        text,
        style = JBSimple.font(17, JBSimple.semibold),
        color = JBSimple.muted,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

/** Строка настроек: подпись 20 / 700, значение 20 / 600 приглушённое, шеврон. */
@Composable
fun JBSimpleRow(
    title: String,
    value: String? = null,
    chevron: Boolean = false,
    titleColor: Color = JBSimple.ink,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 66.dp)
            .then(if (onClick != null) Modifier.jbSimplePress(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = JBSimple.font(20, JBSimple.bold),
            color = titleColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            BasicText(
                value,
                modifier = Modifier.widthIn(max = 220.dp),
                style = JBSimple.font(20, JBSimple.semibold).copy(color = JBSimple.muted, textAlign = TextAlign.End),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = 17.sp, maxFontSize = 20.sp, stepSize = 0.5.sp),
            )
        }
        if (chevron) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = JBSimple.muted,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
fun JBSimpleDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 20.dp).height(1.dp).background(JBSimple.line))
}

/** Значок в акцентной плитке (вместо эмодзи макета). */
@Composable
fun JBSimpleIconTile(
    icon: ImageVector,
    size: Dp = 44.dp,
    tint: Color = JBSimple.accent,
    fill: Color = JBSimple.accentTint,
) {
    Box(
        Modifier.size(size).background(fill, RoundedCornerShape(size * 0.32f)).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

/**
 * Строка выхода (выбор выхода с главного экрана и по ссылке): дом в плитке, имя, справа — галочка
 * выбранного или шеврон.
 */
@Composable
fun JBSimpleExitOption(name: String, selected: Boolean, showsChevron: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .jbSimplePress(onClick = onClick)
            .clip(shape)
            .background(JBSimple.card)
            .border(2.dp, if (selected) JBSimple.accent else Color.Transparent, shape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        JBSimpleIconTile(
            Icons.Rounded.Home,
            size = 48.dp,
            tint = if (selected) Color.White else JBSimple.accent,
            fill = if (selected) JBSimple.accent else JBSimple.accentTint,
        )
        Spacer(Modifier.width(14.dp))
        Text(
            name,
            style = JBSimple.font(20, JBSimple.bold),
            color = JBSimple.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = JBSimple.accent, modifier = Modifier.size(28.dp))
        } else if (showsChevron) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = JBSimple.muted,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

// endregion
// region Большой круг

/**
 * Большой круг главного экрана — он же кнопка (`JBSimpleCircle`). Не подключено: серый с пунктиром;
 * подключаемся: пульс; подключено: акцентная заливка с ореолом. Значок в углу — состояние защиты.
 */
@Composable
fun JBSimpleCircle(
    phase: ShieldPhase,
    modifier: Modifier = Modifier,
    diameter: Dp = 240.dp,
    label: String = "",
    onClick: (() -> Unit)? = null,
) {
    val on = phase == ShieldPhase.ON
    val working = phase.isWorking
    val reduceMotion = JetBoxTheme.reduceMotion
    val pulse = rememberInfiniteTransition(label = "simplePulse")
    val pulseT by pulse.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
        label = "simplePulseT",
    )
    val houseTint by animateColorAsState(
        when {
            on -> Color.White
            working -> JBSimple.accent.copy(alpha = 0.7f)
            else -> JBSimple.offSymbol
        },
        tween(350),
        label = "simpleHouse",
    )
    val dashColor = if (working) JBSimple.accent.copy(alpha = 0.55f) else JBSimple.offDash

    val face = Modifier
        .size(diameter + 40.dp)
        .then(if (onClick != null) Modifier.jbSimplePress(onClick = onClick) else Modifier)
        .semantics {
            contentDescription = label
            if (onClick != null) role = Role.Button
        }
    Box(modifier.then(face), contentAlignment = Alignment.Center) {
        if (on) {
            // Ореол и мягкая фиолетовая тень вниз (`shadow(radius: 30, y: 24)` iOS).
            Box(
                Modifier
                    .size(diameter + 32.dp)
                    .drawBehind {
                        drawCircle(
                            Brush.radialGradient(
                                listOf(JBSimple.accent.copy(alpha = 0.40f), Color.Transparent),
                                center = center + Offset(0f, 24.dp.toPx()),
                                radius = diameter.toPx() / 2 + 34.dp.toPx(),
                            ),
                            radius = diameter.toPx() / 2 + 34.dp.toPx(),
                            center = center + Offset(0f, 24.dp.toPx()),
                        )
                    }
                    .background(JBSimple.accentTint, CircleShape),
            )
            Box(Modifier.size(diameter).background(JBSimple.accent, CircleShape))
        } else {
            if (working && !reduceMotion) {
                val grow = 1f + pulseT * (40f / diameter.value)
                Box(
                    Modifier
                        .size(diameter)
                        .scale(grow)
                        .graphicsLayer { alpha = 1f - pulseT }
                        .background(JBSimple.accentTint, CircleShape),
                )
            }
            Box(
                Modifier
                    .size(diameter)
                    .background(JBSimple.offFill, CircleShape)
                    .drawBehind {
                        val w = 3.dp.toPx()
                        drawCircle(
                            dashColor,
                            radius = size.minDimension / 2 - w / 2,
                            style = Stroke(
                                width = w,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 7.dp.toPx())),
                            ),
                        )
                    },
            )
        }

        Icon(
            if (on) Icons.Rounded.Home else Icons.Outlined.Home,
            contentDescription = null,
            tint = houseTint,
            modifier = Modifier.size(diameter * 0.44f),
        )

        Badge(phase, diameter, Modifier.offset(diameter * 0.26f, diameter * 0.26f))
    }
}

@Composable
private fun Badge(phase: ShieldPhase, diameter: Dp, modifier: Modifier) {
    val size = diameter * 0.27f
    val on = phase == ShieldPhase.ON
    Box(
        modifier
            .size(size)
            .then(if (on) Modifier.shadow(8.dp, CircleShape, ambientColor = JBSimple.ink, spotColor = JBSimple.ink) else Modifier)
            .background(Color.White, CircleShape)
            .then(if (!on) Modifier.border(2.dp, JBSimple.offDash, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val iconSize = Modifier.size(size * 0.56f)
        when (phase) {
            ShieldPhase.ON -> Icon(
                remember { JetBoxIcons.checkmarkShieldFill(JBSimple.accent, Color.White) },
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = iconSize,
            )
            ShieldPhase.OFFLINE -> Icon(
                remember { JetBoxIcons.exclamationmarkShieldFill() },
                contentDescription = null,
                tint = JBSimple.error,
                modifier = iconSize,
            )
            ShieldPhase.WORKING, ShieldPhase.WORKING_REVERSE -> Icon(
                remember { JetBoxIcons.shieldLeftHalfFilled() },
                contentDescription = null,
                tint = JBSimple.accent,
                modifier = iconSize,
            )
            ShieldPhase.OFF -> Icon(JBSimpleIcons.shieldOutline, contentDescription = null, tint = JBSimple.offSymbol, modifier = iconSize)
        }
    }
}

/** Бегущий пунктир под «подключено»: движение по линии — трафик идёт. */
@Composable
fun JBSimpleTrafficLine(width: Dp = 170.dp) {
    val reduceMotion = JetBoxTheme.reduceMotion
    val move = rememberInfiniteTransition(label = "traffic")
    val t by move.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "trafficT",
    )
    Canvas(Modifier.size(width, 4.dp).clearAndSetSemantics { }) {
        val y = size.height / 2
        val w = 4.dp.toPx()
        drawLine(JBSimple.accentTint, Offset(0f, y), Offset(size.width, y), strokeWidth = w)
        val period = 24.dp.toPx()
        drawLine(
            JBSimple.accent,
            Offset(0f, y),
            Offset(size.width, y),
            strokeWidth = w,
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(10.dp.toPx(), 14.dp.toPx()),
                phase = if (reduceMotion) 0f else period * (1f - t),
            ),
        )
    }
}

/** Переключатель стиля оформления — две пилюли. */
@Composable
fun JBSimpleStylePicker(style: JetBoxStyle, onSelect: (JetBoxStyle) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(JBSimple.line, RoundedCornerShape(50))
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        JetBoxStyle.entries.forEach { item ->
            val selected = item == style
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) JBSimple.accent else Color.White)
                    .selectable(selected = selected, role = Role.RadioButton) { onSelect(item) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(item.titleRes),
                    style = JBSimple.font(18, JBSimple.heavy),
                    color = if (selected) Color.White else JBSimple.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Название стиля в переключателе: «Простое» / «Техно». */
val JetBoxStyle.titleRes: Int
    get() = when (this) {
        JetBoxStyle.SIMPLE -> R.string.jb_style_simple
        JetBoxStyle.TECHNO -> R.string.jb_style_techno
    }

// endregion
