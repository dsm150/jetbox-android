package net.jetnet.jetbox.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Вход по рабочей почте с кодом (саморегистрация из приложения, корпоративный VPN; слово владельца 25.11.2026):
 * «Вход по рабочей почте» → `login/request` → «Введите код» (6 цифр, `login/verify {email, code}`) → ссылка
 * подключения уходит в сценарий, как ссылка `/connect` из Кабинета ([onConnectUrl]). Ссылки из письма в RU-приложении
 * нет — только код.
 *
 * Ответ Кабинета на известный и неизвестный адрес одинаков — экран тоже. Новый код — не чаще раза в [cooldownMs].
 *
 * Живёт на уровне процесса: поворот экрана и сворачивание не сбрасывают шаг и отсчёт. Методы — из главного
 * потока ([scope]). Адрес и код в журнал не пишутся.
 */
class EmailLoginForm(
    private val api: AppLoginApi,
    private val scope: CoroutineScope,
    private val lang: String = "ru",
    private val cooldownMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Ссылка подключения; `false` — ссылка от Кабинета не наша (сценарий её не взял). */
    private val onConnectUrl: (String) -> Boolean = { true },
) {
    enum class Error { INVALID_EMAIL, UNAVAILABLE, TOO_MANY }

    /** Отказы ввода кода. */
    enum class CodeError { BAD_CODE, NO_ATTEMPTS, EXPIRED, USED, DEVICE_LIMIT, NOT_AVAILABLE, TOO_MANY, TRY_AGAIN, UNAVAILABLE }

    sealed class State {
        /** Ввод адреса; [error] — что не так с прошлой попыткой. */
        data class Editing(val error: Error? = null) : State()

        /** Отправляем первый запрос. */
        data class Sending(val email: String) : State()

        /**
         * Ввод кода из письма, отправленного на [email]. [verifying] — код ушёл в Кабинет; [attemptsLeft] — из ответа
         * `bad_code`; [resendAt] — когда можно попросить новый код (время [clock]); [resending] — запрос в пути;
         * [sendError] — новый код попросить не удалось.
         */
        data class Code(
            val email: String,
            val resendAt: Long,
            val verifying: Boolean = false,
            val resending: Boolean = false,
            val error: CodeError? = null,
            val attemptsLeft: Int? = null,
            val sendError: Error? = null,
        ) : State() {
            /** Код больше не примут — нужен новый. */
            val needsNewCode: Boolean get() = error == CodeError.EXPIRED || error == CodeError.USED || error == CodeError.NO_ATTEMPTS
        }
    }

    private val _state = MutableStateFlow<State>(State.Editing())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    /** Последний введённый адрес — чтобы «Указать другой адрес» вернул его в поле. */
    var lastEmail: String = ""
        private set

    fun submit(input: String) {
        if (_state.value is State.Sending) return
        val email = input.trim()
        lastEmail = email
        if (!isValidEmail(email)) {
            _state.value = State.Editing(Error.INVALID_EMAIL)
            return
        }
        _state.value = State.Sending(email)
        job = scope.launch {
            _state.value = when (api.request(email, lang)) {
                LoginRequestResult.Accepted -> State.Code(email, clock() + cooldownMs)
                LoginRequestResult.TooManyRequests -> State.Editing(Error.TOO_MANY)
                LoginRequestResult.BadEmail -> State.Editing(Error.INVALID_EMAIL)
                is LoginRequestResult.Unavailable -> State.Editing(Error.UNAVAILABLE)
            }
        }
    }

    /** Пользователь правит адрес: прежняя ошибка к новому тексту уже не относится — гасим. */
    fun editEmail() {
        val current = _state.value
        if (current is State.Editing && current.error != null) _state.value = State.Editing()
    }

    /** Отправить код: ровно 6 цифр, не во время другого запроса и не после «код истёк». */
    fun submitCode(input: String) {
        val s = _state.value as? State.Code ?: return
        val code = input.filter { it in '0'..'9' }
        if (s.verifying || s.resending || s.needsNewCode || code.length != CODE_LENGTH) return
        _state.value = s.copy(verifying = true, error = null, sendError = null)
        job = scope.launch {
            val result = api.verifyCode(s.email, code)
            val now = _state.value as? State.Code ?: return@launch
            _state.value = when (result) {
                // Дальше — экран «Ваш JetBox» (или отказ ссылки) поверх; форма вернётся к началу, если его закроют.
                is LoginVerifyResult.Success ->
                    if (onConnectUrl(result.connectUrl)) State.Editing()
                    else now.copy(verifying = false, error = CodeError.UNAVAILABLE)
                is LoginVerifyResult.BadCode -> now.copy(
                    verifying = false,
                    error = if (result.attemptsLeft == 0) CodeError.NO_ATTEMPTS else CodeError.BAD_CODE,
                    attemptsLeft = result.attemptsLeft,
                )
                LoginVerifyResult.Expired -> now.copy(verifying = false, error = CodeError.EXPIRED, attemptsLeft = null)
                LoginVerifyResult.Used -> now.copy(verifying = false, error = CodeError.USED, attemptsLeft = null)
                LoginVerifyResult.DeviceLimit -> now.copy(verifying = false, error = CodeError.DEVICE_LIMIT)
                LoginVerifyResult.NotAvailable -> now.copy(verifying = false, error = CodeError.NOT_AVAILABLE)
                LoginVerifyResult.TooManyRequests -> now.copy(verifying = false, error = CodeError.TOO_MANY)
                LoginVerifyResult.TryAgain -> now.copy(verifying = false, error = CodeError.TRY_AGAIN)
                is LoginVerifyResult.Unreachable, is LoginVerifyResult.Failed ->
                    now.copy(verifying = false, error = CodeError.UNAVAILABLE)
            }
        }
    }

    /** «Прислать новый код»: новое письмо, отсчёт [cooldownMs]. */
    fun requestNewCode() {
        val s = _state.value as? State.Code ?: return
        if (s.verifying || s.resending || clock() < s.resendAt) return
        _state.value = s.copy(resending = true, sendError = null)
        job = scope.launch {
            val now = _state.value as? State.Code ?: return@launch
            _state.value = when (api.request(s.email, lang)) {
                LoginRequestResult.Accepted -> State.Code(s.email, clock() + cooldownMs)
                // Лимит Кабинета — новый отсчёт, чтобы не долбить его кнопкой.
                LoginRequestResult.TooManyRequests ->
                    now.copy(resending = false, resendAt = clock() + cooldownMs, sendError = Error.TOO_MANY)
                LoginRequestResult.BadEmail -> State.Editing(Error.INVALID_EMAIL)
                is LoginRequestResult.Unavailable -> now.copy(resending = false, sendError = Error.UNAVAILABLE)
            }
        }
    }

    /** «Указать другой адрес» — назад к вводу; запрос в пути отменяется. */
    fun changeEmail() {
        job?.cancel()
        job = null
        _state.value = State.Editing()
    }

    companion object {
        const val CODE_LENGTH = 6

        private val EMAIL_REGEX = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@.]{2,}")

        /** Проверка «похоже на адрес» — до сети; остальное решает Кабинет. */
        fun isValidEmail(email: String): Boolean = email.length <= 254 && EMAIL_REGEX.matches(email)
    }
}
