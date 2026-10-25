package net.jetnet.jetbox.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.jetnet.jetbox.ui.theme.JBType
import net.jetnet.jetbox.ui.theme.JetBoxTheme

/*
 * Порт `JetBoxGlass.swift`, ветка «ниже iOS 26»: Liquid Glass на Android нет, поэтому —
 * полупрозрачная поверхность (замена `.ultraThinMaterial`) + тонкая рамка + мягкая тень.
 */

/** Тактильный отклик: нажатие и успешное подключение (`JBHaptics`). */
object JBHaptics {
    /** `UIImpactFeedbackGenerator(.medium)` — ближайший системный отклик Android: `VIRTUAL_KEY`. */
    fun tap(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** `UINotificationFeedbackGenerator().notificationOccurred(.success)`. */
    fun success(view: View) {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(constant)
    }
}

enum class JBGlassRole { PRIMARY, SECONDARY }

private val Capsule = RoundedCornerShape(percent = 50)

/** Мягкая тень стеклянных элементов (на iOS 26 стекло отбрасывает такую же). */
private fun Modifier.jbSoftShadow(shape: Shape, elevation: Dp, dark: Boolean): Modifier =
    shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = if (dark) 0.35f else 0.10f),
        spotColor = Color.Black.copy(alpha = if (dark) 0.45f else 0.14f),
    )

/**
 * Капсула действия во всю ширину (до 420 dp), высота 54 dp. Главное действие — заливка фирменным
 * синим, второстепенное («Выключить», «Отмена») — полупрозрачная поверхность с рамкой.
 */
@Composable
fun JBGlassButton(
    title: String,
    modifier: Modifier = Modifier,
    role: JBGlassRole = JBGlassRole.PRIMARY,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = JetBoxTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val spec = tween<Float>(durationMillis = 150, easing = EaseOut)
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, spec, label = "capsuleScale")
    val alpha by animateFloatAsState(if (pressed) 0.85f else 1f, spec, label = "capsuleAlpha")
    val primary = role == JBGlassRole.PRIMARY

    Box(
        modifier = modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = if (enabled) alpha else 0.5f
            }
            .then(if (primary) Modifier else Modifier.jbSoftShadow(Capsule, 10.dp, c.isDark))
            .clip(Capsule)
            .background(if (primary) c.accent else c.material)
            .then(if (primary) Modifier else Modifier.border(1.dp, c.line, Capsule))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = JBType.headline,
            color = if (primary) Color.White else c.text,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Круглая кнопка-значок (настройки в шапке): видимый круг 44 dp, как на iOS, зона касания 48 dp.
 * Без Material-ряби: при нажатии — лёгкое сжатие и приглушение, как у кнопки iOS.
 */
@Composable
fun JBGlassIconButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = JetBoxTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val spec = tween<Float>(durationMillis = 150, easing = EaseOut)
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, spec, label = "iconButtonScale")
    val alpha by animateFloatAsState(if (pressed) 0.7f else 1f, spec, label = "iconButtonAlpha")
    Box(
        modifier = modifier
            .size(48.dp)
            .semantics { contentDescription = label }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .jbSoftShadow(CircleShape, 8.dp, c.isDark)
                .clip(CircleShape)
                .background(c.material)
                .border(1.dp, c.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = c.text, modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * Стеклянная подложка панели (`jbGlassPanel` в `JetBoxHomeDock.swift`): поверхность-«материал»
 * + рамка `Line` 0,8 + мягкая тень.
 */
@Composable
fun Modifier.jbGlassPanel(cornerRadius: Dp = 28.dp): Modifier {
    val c = JetBoxTheme.colors
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .jbSoftShadow(shape, 12.dp, c.isDark)
        .clip(shape)
        .background(c.material)
        .border(1.dp, c.line.copy(alpha = 0.8f), shape)
}
