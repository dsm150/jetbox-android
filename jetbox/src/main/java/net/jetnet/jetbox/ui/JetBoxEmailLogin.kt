package net.jetnet.jetbox.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import net.jetnet.jetbox.R
import net.jetnet.jetbox.engine.EmailLoginForm
import net.jetnet.jetbox.engine.EmailLoginForm.State
import net.jetnet.jetbox.ui.theme.JBType
import net.jetnet.jetbox.ui.theme.JetBoxTheme

/*
 * Вход по рабочей почте с кодом (корпоративный VPN, саморегистрация из приложения; слово владельца 25.11.2026):
 * на месте пустого состояния «JetBox не подключён» — «Вход по рабочей почте» → «Введите код» (6 цифр с подсказкой
 * автозаполнения одноразового кода). Код принят — дальше сценарий [net.jetnet.jetbox.engine.JetBoxOnboarding], как у
 * ссылки из Кабинета (она по-прежнему работает: открыть ссылку или QR из Личного кабинета). Форма одна на процесс
 * ([EmailLoginForm]); на экран её даёт [LocalEmailLogin], в превью её нет.
 */

/** Форма входа по почте; `null` (превью, витрина) — прежний текст «Откройте ссылку из письма». */
val LocalEmailLogin = compositionLocalOf<EmailLoginForm?> { null }

@Composable
fun rememberEmailLogin(): EmailLoginForm? = LocalEmailLogin.current

/** Оформление формы (стекло главного экрана). */
private class LoginSkin(
    val title: TextStyle,
    val body: TextStyle,
    val ink: Color,
    val muted: Color,
    val error: Color,
    val fieldBackground: Color,
    val fieldLine: Color,
    val accent: Color,
)

@Composable
private fun technoSkin(): LoginSkin {
    val c = JetBoxTheme.colors
    return LoginSkin(
        title = JBType.headline,
        body = JBType.subheadline,
        ink = c.text,
        muted = c.muted,
        error = c.danger,
        fieldBackground = c.surface,
        fieldLine = c.line,
        accent = c.accent,
    )
}

/** Панель «техно»: стекло, как [JetBoxNoBoxDock]. */
@Composable
fun JetBoxEmailLoginDock(form: EmailLoginForm, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().jbGlassPanel(28.dp).padding(18.dp)) {
        EmailLoginContent(form, technoSkin())
    }
}


@Composable
private fun EmailLoginContent(form: EmailLoginForm, skin: LoginSkin) {
    val state by form.state.collectAsStateWithLifecycle()
    AnimatedContent(
        targetState = if (state is State.Code) 1 else 0,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
        label = "emailLogin",
    ) { page ->
        when (page) {
            1 -> (state as? State.Code)?.let { CodeView(it, form, skin) }
            else -> EnterView(state, form, skin)
        }
    }
}

@Composable
private fun EnterView(state: State, form: EmailLoginForm, skin: LoginSkin) {
    var email by rememberSaveable { mutableStateOf(form.lastEmail) }
    val focus = LocalFocusManager.current
    val sending = state is State.Sending
    val submit = {
        focus.clearFocus()
        form.submit(email)
    }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.jb_login_title),
            style = skin.title,
            color = skin.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.jb_login_body), style = skin.body, color = skin.muted, textAlign = TextAlign.Center)
        EmailField(
            value = email,
            onValueChange = {
                email = it
                form.editEmail()
            },
            enabled = !sending,
            onDone = submit,
            skin = skin,
        )
        (state as? State.Editing)?.error?.let { ErrorText(it, skin) }
        val title = stringResource(if (sending) R.string.jb_login_sending else R.string.jb_login_send)
        JBGlassButton(title, enabled = !sending && email.isNotBlank(), onClick = submit)
    }
}

/** Небольшая текстовая кнопка под главной. */
@Composable
private fun SmallButton(title: String, skin: LoginSkin, accent: Boolean = false, onClick: () -> Unit) {
    val color = if (accent) skin.accent else skin.muted
    JBTextButton(title, color = color, onClick = onClick)
}

/** Секунды до [at] — тикает раз в секунду, пока не дойдёт до нуля. */
@Composable
private fun secondsUntil(at: Long): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(at) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= at) break
            delay(1000 - now % 1000)
        }
    }
    return ((at - now + 999) / 1000).coerceAtLeast(0)
}

/** «Введите код»: 6 цифр из письма; шесть набрано (или подставлено автозаполнением) — отправляем сами. */
@Composable
private fun CodeView(state: State.Code, form: EmailLoginForm, skin: LoginSkin) {
    var code by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val busy = state.verifying || state.resending
    val submit = {
        if (code.length == EmailLoginForm.CODE_LENGTH) {
            focus.clearFocus()
            form.submitCode(code)
        }
    }
    // Неверный код — поле чистим, чтобы набирать заново (attemptsLeft меняется с каждой попыткой).
    LaunchedEffect(state.error, state.attemptsLeft) {
        if (state.error == EmailLoginForm.CodeError.BAD_CODE) code = ""
    }
    // Пришёл новый код — старый в поле не нужен.
    LaunchedEffect(state.resendAt) { code = "" }
    val left = secondsUntil(state.resendAt)
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.jb_login_code_title),
            style = skin.title,
            color = skin.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.jb_login_code_body, state.email),
            style = skin.body,
            color = skin.muted,
            textAlign = TextAlign.Center,
        )
        if (!state.needsNewCode) {
            CodeField(
                value = code,
                onValueChange = { typed ->
                    val digits = typed.filter { it in '0'..'9' }.take(EmailLoginForm.CODE_LENGTH)
                    val completed = digits.length == EmailLoginForm.CODE_LENGTH && digits != code
                    code = digits
                    if (completed) {
                        focus.clearFocus()
                        form.submitCode(digits)
                    }
                },
                enabled = !busy,
                onDone = submit,
                skin = skin,
            )
        }
        state.error?.let { CodeErrorText(it, state.attemptsLeft, skin) }
        state.sendError?.let { ErrorText(it, skin) }
        if (state.needsNewCode) {
            val title = when {
                state.resending -> stringResource(R.string.jb_login_sending)
                left > 0 -> stringResource(R.string.jb_login_code_new_in, left.toInt())
                else -> stringResource(R.string.jb_login_code_new)
            }
            val enabled = !busy && left == 0L
            JBGlassButton(title, enabled = enabled, onClick = form::requestNewCode)
        } else {
            val title = stringResource(if (state.verifying) R.string.jb_login_code_checking else R.string.jb_login_code_submit)
            val enabled = !busy && code.length == EmailLoginForm.CODE_LENGTH
            JBGlassButton(title, enabled = enabled, onClick = submit)
            // Письма нет — новый код (после отсчёта); путь назад к адресу — всегда виден.
            val resend = when {
                state.resending -> stringResource(R.string.jb_login_sending)
                left > 0 -> stringResource(R.string.jb_login_code_new_in, left.toInt())
                else -> stringResource(R.string.jb_login_code_new)
            }
            SmallButton(resend, skin, accent = !busy && left == 0L, onClick = form::requestNewCode)
        }
        SmallButton(stringResource(R.string.jb_login_change), skin, onClick = form::changeEmail)
    }
}

@Composable
private fun CodeErrorText(error: EmailLoginForm.CodeError, attemptsLeft: Int?, skin: LoginSkin) {
    val text = when (error) {
        EmailLoginForm.CodeError.BAD_CODE ->
            if (attemptsLeft != null) pluralStringResource(R.plurals.jb_login_code_err_bad_left, attemptsLeft, attemptsLeft)
            else stringResource(R.string.jb_login_code_err_bad)
        EmailLoginForm.CodeError.NO_ATTEMPTS -> stringResource(R.string.jb_login_code_err_no_attempts)
        EmailLoginForm.CodeError.EXPIRED -> stringResource(R.string.jb_login_code_err_expired)
        EmailLoginForm.CodeError.USED -> stringResource(R.string.jb_login_code_err_used)
        EmailLoginForm.CodeError.DEVICE_LIMIT -> stringResource(R.string.jb_login_code_err_device_limit)
        EmailLoginForm.CodeError.NOT_AVAILABLE -> stringResource(R.string.jb_login_code_err_not_available)
        EmailLoginForm.CodeError.TOO_MANY -> stringResource(R.string.jb_login_err_too_many)
        EmailLoginForm.CodeError.TRY_AGAIN -> stringResource(R.string.jb_login_code_err_try_again)
        EmailLoginForm.CodeError.UNAVAILABLE -> stringResource(R.string.jb_login_err_unavailable)
    }
    Text(
        text,
        style = skin.body,
        color = skin.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Поле кода: только цифры, крупно и вразрядку; подсказка автозаполнения — одноразовый код (SMS / менеджер паролей). */
@Composable
private fun CodeField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onDone: () -> Unit,
    skin: LoginSkin,
) {
    val shape = RoundedCornerShape(14.dp)
    val label = stringResource(R.string.jb_login_code_field)
    val height = 56.dp
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = skin.title.copy(color = skin.ink, textAlign = TextAlign.Center, letterSpacing = 8.sp),
        cursorBrush = SolidColor(skin.accent),
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .semantics {
                contentDescription = label
                contentType = ContentType.SmsOtpCode
            },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = height)
                    .background(skin.fieldBackground, shape)
                    .border(1.dp, skin.fieldLine, shape)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (value.isEmpty()) {
                    Text(
                        "• • • • • •",
                        style = skin.title.copy(letterSpacing = 4.sp),
                        color = skin.muted.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                    )
                }
                inner()
            }
        },
    )
}

@Composable
private fun ErrorText(error: EmailLoginForm.Error, skin: LoginSkin) {
    val text = stringResource(
        when (error) {
            EmailLoginForm.Error.INVALID_EMAIL -> R.string.jb_login_err_invalid
            EmailLoginForm.Error.UNAVAILABLE -> R.string.jb_login_err_unavailable
            EmailLoginForm.Error.TOO_MANY -> R.string.jb_login_err_too_many
        },
    )
    Text(
        text,
        style = skin.body,
        color = skin.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun EmailField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onDone: () -> Unit,
    skin: LoginSkin,
) {
    val shape = RoundedCornerShape(14.dp)
    val label = stringResource(R.string.jb_login_field)
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.replace("\n", "")) },
        enabled = enabled,
        singleLine = true,
        textStyle = skin.body.copy(color = skin.ink, fontWeight = FontWeight.Medium),
        cursorBrush = SolidColor(skin.accent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Send,
        ),
        keyboardActions = KeyboardActions(onSend = { onDone() }),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .semantics { contentDescription = label },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .background(skin.fieldBackground, shape)
                    .border(1.dp, skin.fieldLine, shape)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(stringResource(R.string.jb_login_placeholder), style = skin.body, color = skin.muted.copy(alpha = 0.6f))
                }
                inner()
            }
        },
    )
}
