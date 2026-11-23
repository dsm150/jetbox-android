package net.jetnet.jetbox.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.jetnet.jetbox.R
import net.jetnet.jetbox.ui.theme.JBType
import net.jetnet.jetbox.ui.theme.JetBoxTheme

/*
 * Порт `JetBoxUI.swift`: мелкие общие детали наших экранов. Иконки только векторные — фирменный
 * знак из ресурсов и значки-векторы, никаких эмодзи.
 */

/** Имя продукта (`JBText.brand`) — не переводится. */
const val JB_BRAND = "JetBox"

/** Фирменный знак + слово. Знак — вектор `jb_mark` (буквы светлеют в тёмной теме). */
@Composable
fun JetBoxLogo(
    modifier: Modifier = Modifier,
    markHeight: Dp = 44.dp,
    showsWordmark: Boolean = true,
) {
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = JB_BRAND },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.jb_mark),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(markHeight),
        )
        if (showsWordmark) {
            // lineHeight задан явно: иначе берётся из стиля M3 (24 sp) — меньше кегля, и буквы обрезаются.
            Text(
                text = JB_BRAND,
                fontSize = (markHeight.value * 0.62f).sp,
                lineHeight = (markHeight.value * 0.62f * 1.2f).sp,
                fontWeight = FontWeight.Bold,
                color = JetBoxTheme.colors.ink,
                maxLines = 1,
            )
        }
    }
}

/** Карточка на фоне страницы: поверхность, рамка `Line` 1 dp, скругление 22. */
@Composable
fun JBCard(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = JetBoxTheme.colors
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape)
            .padding(18.dp),
        content = content,
    )
}

/**
 * Строка «подпись — значение» для карточек и настроек. Значение сжимается до 0,7 (как
 * `minimumScaleFactor`); подпись и значение стоят на общей базовой линии (`HStack(alignment: .firstTextBaseline)`).
 */
@Composable
fun JBRow(title: String, value: String, modifier: Modifier = Modifier) {
    val c = JetBoxTheme.colors
    Row(modifier = modifier.fillMaxWidth()) {
        Text(text = title, style = JBType.subheadline, color = c.muted, modifier = Modifier.alignByBaseline())
        Spacer(Modifier.width(12.dp))
        BasicText(
            text = value,
            modifier = Modifier.weight(1f).alignByBaseline(),
            style = JBType.subheadline.copy(fontWeight = FontWeight.Medium, color = c.text, textAlign = TextAlign.End),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = (15 * 0.7f).sp, maxFontSize = 15.sp, stepSize = 0.5.sp),
        )
    }
}

/**
 * Текстовая кнопка iOS (`Button(...).font(.subheadline).foregroundColor(JBColor.muted)`): без
 * Material-ряби, при нажатии приглушается; зона касания не меньше 48 dp.
 */
@Composable
fun JBTextButton(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = JetBoxTheme.colors.muted,
    style: TextStyle = JBType.subheadline,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = style, color = if (pressed) color.copy(alpha = 0.5f) else color, textAlign = TextAlign.Center)
    }
}

/** Открыть ссылку системным браузером (`Link` iOS). */
@Composable
fun rememberUrlOpener(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) { { url -> context.openUrl(url) } }
}

fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Log.w("JetBox", "нет приложения для открытия ссылки: $url")
    }
}
