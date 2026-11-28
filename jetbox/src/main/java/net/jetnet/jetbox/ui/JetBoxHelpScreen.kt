package net.jetnet.jetbox.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.jetnet.jetbox.R
import net.jetnet.jetbox.engine.HelpOutcome
import net.jetnet.jetbox.logic.JetBoxLog
import net.jetnet.jetbox.ui.theme.JBType
import net.jetnet.jetbox.ui.theme.JetBoxTheme

/** Чем кончилась последняя отправка — строка под кнопкой. */
internal enum class HelpNotice { NONE, SENT, TOO_LONG, RATE_LIMITED, NO_MAIL_APP }

/** Что нужно «Помощи» от приложения; `null` в настройках — пункта нет. */
class JetBoxHelpHooks(
    /** Есть токен приложения (вход по почте) — сообщение уйдёт в Кабинет. */
    val viaCabinet: Boolean,
    val mailAddress: String,
    val diagnostics: () -> String,
    val onSend: suspend (message: String, diagnostics: String?) -> HelpOutcome,
)

/**
 * «Помощь» (спека VPN_Box 2026-11-28-app-help-and-dns-check, §2): поле, «Приложить диагностику» (включено),
 * «Отправить» и под ними — раскрывающийся ровно тот текст диагностики, что уйдёт. [viaCabinet] — есть токен приложения
 * (сообщение ляжет в тред поддержки Кабинета); иначе — письмо на [mailAddress].
 */
@Composable
fun JetBoxHelpScreen(
    onBack: () -> Unit,
    viaCabinet: Boolean,
    mailAddress: String,
    diagnostics: () -> String,
    onSend: suspend (message: String, diagnostics: String?) -> HelpOutcome,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by rememberSaveable { mutableStateOf("") }
    var attach by rememberSaveable { mutableStateOf(true) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf(HelpNotice.NONE) }
    var promise by remember { mutableStateOf<String?>(null) }
    var address by remember { mutableStateOf(mailAddress) }
    // Снимок на время экрана: «что уйдёт» и то, что ушло, — один и тот же текст.
    var diagnosticsText by remember { mutableStateOf(diagnostics()) }

    JetBoxHelpLayout(
        message = message,
        onMessageChange = {
            message = it
            if (notice != HelpNotice.NONE) notice = HelpNotice.NONE
        },
        attach = attach,
        onAttachChange = { attach = it },
        expanded = expanded,
        onToggleExpanded = {
            if (!expanded) diagnosticsText = diagnostics()
            expanded = !expanded
        },
        diagnosticsText = diagnosticsText,
        sending = sending,
        notice = notice,
        promise = promise,
        footer = if (viaCabinet) {
            stringResource(R.string.jb_help_cabinet_footer)
        } else {
            stringResource(R.string.jb_help_mail_footer, address)
        },
        noMailAddress = address,
        onBack = onBack,
        onSend = {
            sending = true
            notice = HelpNotice.NONE
            if (!expanded) diagnosticsText = diagnostics()
            val diag = diagnosticsText.takeIf { attach }
            scope.launch {
                when (val out = onSend(message, diag)) {
                    is HelpOutcome.Sent -> {
                        promise = out.promise
                        notice = HelpNotice.SENT
                        message = ""
                    }
                    HelpOutcome.TooLong -> notice = HelpNotice.TOO_LONG
                    is HelpOutcome.RateLimited -> notice = HelpNotice.RATE_LIMITED
                    is HelpOutcome.NeedsMail -> {
                        address = out.address
                        if (!context.composeMail(out.address, out.subject, out.body)) {
                            context.copyToClipboard(out.body)
                            notice = HelpNotice.NO_MAIL_APP
                        }
                    }
                }
                sending = false
            }
        },
    )
}

/** Письмо в поддержку: `mailto:` с темой и телом. `false` — почтового приложения нет. */
private fun Context.composeMail(address: String, subject: String, body: String): Boolean {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    return try {
        startActivity(Intent.createChooser(intent, getString(R.string.jb_help_mail_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        JetBoxLog.i("JetBoxHelp", "JetBox: help message handed to the mail app")
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

private fun Context.copyToClipboard(text: String) {
    (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("JetBox", text))
}

@Composable
internal fun JetBoxHelpLayout(
    message: String,
    onMessageChange: (String) -> Unit,
    attach: Boolean,
    onAttachChange: (Boolean) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    diagnosticsText: String,
    sending: Boolean,
    notice: HelpNotice,
    promise: String?,
    footer: String,
    noMailAddress: String,
    onBack: () -> Unit,
    onSend: () -> Unit,
) {
    val c = JetBoxTheme.colors
    JBPage(
        title = stringResource(R.string.jb_help_title),
        leading = {
            JBGlassIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.jb_back), onClick = onBack)
        },
    ) {
        JBSection(footer = footer) {
            HelpField(message, onMessageChange, enabled = !sending)
            JBListDivider()
            JBListRow(onClick = { onAttachChange(!attach) }) {
                Text(stringResource(R.string.jb_help_attach), style = JBType.body, color = c.text, modifier = Modifier.weight(1f))
                Switch(
                    checked = attach,
                    onCheckedChange = onAttachChange,
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = Color.White),
                )
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            JBGlassButton(
                title = stringResource(if (sending) R.string.jb_help_sending else R.string.jb_help_send),
                enabled = !sending && message.isNotBlank(),
                onClick = onSend,
            )
            val text = when (notice) {
                HelpNotice.NONE -> null
                HelpNotice.SENT -> promise?.takeIf { it.isNotBlank() } ?: stringResource(R.string.jb_help_sent)
                HelpNotice.TOO_LONG -> stringResource(R.string.jb_help_too_long)
                HelpNotice.RATE_LIMITED -> stringResource(R.string.jb_help_rate_limited)
                HelpNotice.NO_MAIL_APP -> stringResource(R.string.jb_help_no_mail_app, noMailAddress)
            }
            if (text != null) {
                Text(
                    text,
                    style = JBType.subheadline,
                    color = if (notice == HelpNotice.SENT) c.ok else c.danger,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        JBSection {
            JBListRow(onClick = onToggleExpanded) {
                Text(stringResource(R.string.jb_help_whats_inside), style = JBType.body, color = c.text, modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = c.muted.copy(alpha = 0.6f),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    diagnosticsText,
                    style = JBType.caption.copy(fontFamily = FontFamily.Monospace),
                    color = if (attach) c.muted else c.muted.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun HelpField(value: String, onValueChange: (String) -> Unit, enabled: Boolean) {
    val c = JetBoxTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val label = stringResource(R.string.jb_help_field)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        textStyle = JBType.body.copy(color = c.text),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        minLines = 5,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .heightIn(min = 120.dp)
            .semantics { contentDescription = label },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 120.dp).background(c.background, shape).border(1.dp, c.line, shape).padding(12.dp),
            ) {
                if (value.isEmpty()) Text(label, style = JBType.body, color = c.muted)
                inner()
            }
        },
    )
}

@Composable
private fun HelpPreview(dark: Boolean, notice: HelpNotice, expanded: Boolean) = JetBoxTheme(darkTheme = dark) {
    JetBoxHelpLayout(
        message = if (notice == HelpNotice.SENT) "" else "Дома не открываются сайты",
        onMessageChange = {}, attach = true, onAttachChange = {}, expanded = expanded, onToggleExpanded = {},
        diagnosticsText = "JetBox Android 1.0.7 (22), googleplay\nAndroid 16, Google Pixel 8, lang ru\nSign-in: email, iv***@jet-net.net, app token: yes",
        sending = false, notice = notice, promise = null,
        footer = "Сообщение уйдёт в поддержку JetBox. Ответ придёт на почту, с которой вы вошли.",
        noMailAddress = "help@jet-net.net", onBack = {}, onSend = {},
    )
}

@Preview(name = "Помощь — светлая", locale = "ru", heightDp = 800)
@Composable
private fun HelpLightPreview() = HelpPreview(dark = false, notice = HelpNotice.NONE, expanded = true)

@Preview(name = "Помощь — тёмная, отправлено", locale = "ru", heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HelpDarkSentPreview() = HelpPreview(dark = true, notice = HelpNotice.SENT, expanded = false)
