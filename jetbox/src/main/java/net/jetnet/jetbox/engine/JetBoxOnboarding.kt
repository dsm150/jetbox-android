package net.jetnet.jetbox.engine

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import net.jetnet.jetbox.link.JetBoxConfig
import net.jetnet.jetbox.link.JetBoxLink
import net.jetnet.jetbox.link.JetBoxLinkPayload
import net.jetnet.jetbox.logic.ExitNodeResolver
import net.jetnet.jetbox.logic.ExitOutcome
import net.jetnet.jetbox.logic.JetBoxFailure
import net.jetnet.jetbox.logic.JetBoxIdentity
import net.jetnet.jetbox.logic.RouteInfo
import net.jetnet.jetbox.logic.VpnDisplayState
import net.jetnet.jetbox.store.JetBoxStore

/**
 * Сценарий «одной кнопки» — порт `JetBoxOnboarding.swift` (iOS): ссылка из Кабинета →
 * регистрация по setup key → туннель → выбор выхода → «вы под защитой».
 *
 * Живёт на уровне приложения: ссылка может прийти раньше, чем откроется экран.
 * Без Android-UI: [scope] передаётся снаружи (в приложении — главный поток), поэтому сценарий
 * проверяется на JVM с виртуальным временем. Все методы — из одного потока ([scope]).
 *
 * Отличия от iOS (Android):
 * - перед подъёмом туннеля — системное согласие на VPN ([Step.NeedsVpnConsent]);
 * - туннель ждём не только по CONNECTED: ошибка ядра или «нужен вход» на шаге Connecting —
 *   сразу отказ, без 45 секунд ожидания;
 * - переключение на другой JetBox или вход с новой личностью останавливает прежний туннель до
 *   регистрации. Точка фиксации (LINK-CONTRACT 1.3 §2.11, как iOS d47f61b) — запись нового
 *   подключения в хранилище (колбэк `engine.register`). Любой обрыв ДО неё — чужая или битая
 *   ссылка, новая ссылка, «Закрыть», ошибка регистрации — откат к состоянию до сценария, одним путём
 *   [returnPrevious]: `AndroidEngine.register` возвращает прежние файлы профиля (новая личность) или
 *   адрес сервера, затем прежний туннель поднимается снова — если он был поднят и согласие на VPN
 *   есть; гаснущий туннель сначала дожидаемся. Прежние файлы не вернулись — туннель не поднимаем:
 *   «Попробовать снова» начнёт с возврата. ПОСЛЕ фиксации новое подключение уже текущее: обрыв или
 *   сбой подключения — обычный отказ, прежний туннель не поднимаем (его уже нет);
 * - хранилище пишет `engine.register` колбэком — после входа, до фиксации файлов профиля
 *   ([IdentityRegistration]): так смерть процесса между ними разбирается при старте;
 * - новая личность (новый ключ WireGuard) — по отпечатку setup key, [JetBoxIdentity.plan]: экран
 *   «Другой JetBox» решает удостоверение подключения, ключ — отпечаток (новая ссылка того же
 *   подключения входит с новым ключом без вопроса);
 * - `first_launch` (экран первого запуска апстрима) гасится после успешной регистрации, в
 *   `AndroidEngine.register`; в iOS `skipUpstreamOnboarding` делается при приёме ссылки;
 * - «нужен вход» ядра (`isLoginRequired`) залипает до интерактивного входа и вход по ключу его не
 *   снимает, поэтому отказом считается только переход false→true во время ожидания туннеля.
 */
class JetBoxOnboarding(
    private val engine: EngineApi,
    private val store: JetBoxStore,
    private val scope: CoroutineScope,
    /** Сколько ждём туннель после регистрации (iOS — 45 с). */
    private val tunnelTimeoutMs: Long = 45_000,
    /** Сколько ждём маршруты после подъёма туннеля (iOS — 15 с). */
    private val routesTimeoutMs: Long = 15_000,
    /** Сколько ждём остановки прежнего туннеля при переключении JetBox. */
    private val stopTimeoutMs: Long = 10_000,
    /** Журнал. Значение ключа сюда не попадает — только маска. */
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed class Step {
        /** Экрана нет, приложение живёт обычной жизнью. */
        data object Idle : Step()

        /** Ссылка разобрана, ждём нажатия единственной кнопки. */
        data class Ready(val payload: JetBoxLinkPayload) : Step()

        /** Ссылка ведёт на другой JetBox — спрашиваем подтверждение. */
        data class ConfirmSwitch(val payload: JetBoxLinkPayload, val current: String) : Step()

        /** Регистрируем устройство по setup key. */
        data class Registering(val payload: JetBoxLinkPayload) : Step()

        /** Поднимаем туннель. */
        data class Connecting(val payload: JetBoxLinkPayload) : Step()

        /** Туннель есть, ищем и включаем выход в интернет. */
        data class SelectingExit(val payload: JetBoxLinkPayload) : Step()

        /** Выход не определился сам — просим выбрать. */
        data class ChooseExit(val payload: JetBoxLinkPayload, val options: List<String>) : Step()

        /** Готово. */
        data class Protected(val exitNode: String?) : Step()

        data class Failed(val failure: JetBoxFailure) : Step()

        /** Android: UI запускает системный диалог (`engine.needsVpnConsent()`) и зовёт [onVpnConsent]. */
        data object NeedsVpnConsent : Step()
    }

    private val _step = MutableStateFlow<Step>(Step.Idle)
    val step: StateFlow<Step> = _step.asStateFlow()

    private var flowJob: Job? = null

    /** Ответ системного диалога VPN, которого ждёт сценарий на шаге [Step.NeedsVpnConsent]. */
    private var consent: CompletableDeferred<Boolean>? = null

    /**
     * Ссылка, по которой идёт (или шёл) сценарий. Отдельно от [step]: в `Failed` шаг полезной
     * нагрузки уже не несёт, а «Попробовать снова» обязано повторить именно её.
     */
    private var lastPayload: JetBoxLinkPayload? = null

    val isPresenting: Boolean get() = _step.value != Step.Idle

    // region Вход по ссылке

    /** Точка входа из интента. `true` — ссылка наша и обработана, `false` — не наша. */
    fun handle(url: String): Boolean {
        when (val outcome = JetBoxLink.parse(url)) {
            JetBoxLink.Outcome.NotOurs -> return false

            JetBoxLink.Outcome.Broken -> {
                log("JetBox: ссылка не проходит проверку формата, в сеть не идём")
                cancelFlow()
                _step.value = Step.Failed(JetBoxFailure.BrokenLink)
                return true
            }

            // До любого вопроса («Другой JetBox») и действия: в сеть не идём, подключение не трогаем.
            JetBoxLink.Outcome.ForeignServer -> {
                log("JetBox: адрес management вне маски *${JetBoxConfig.MANAGEMENT_SUFFIX} — ссылка не на сервер JetBox, в сеть не идём")
                cancelFlow()
                _step.value = Step.Failed(JetBoxFailure.ForeignServer)
                return true
            }

            is JetBoxLink.Outcome.Payload -> {
                val payload = outcome.payload
                // Повторное открытие той же ссылки посреди сценария не откатывает его в начало.
                if (activePayload == payload && !isRestartable) return true
                log("JetBox: ссылка принята, объект ${payload.displayName}, ключ ${payload.maskedKey}")
                cancelFlow()
                lastPayload = payload

                val box = store.currentBox
                if (box != null && !box.isSameConnection(payload)) {
                    _step.value = Step.ConfirmSwitch(payload, box.displayName)
                } else {
                    // Тот же JetBox (LINK-CONTRACT 1.3 §2.11): если у записи не было имени, а в ссылке
                    // оно есть — запоминаем сразу. Дальше — тот же путь, что и в первый раз (как iOS).
                    box?.renamed(payload)?.let { store.currentBox = it }
                    _step.value = Step.Ready(payload)
                }
                return true
            }
        }
    }

    // endregion
    // region Кнопки

    fun connect() {
        val payload = activePayload ?: return
        start(payload)
    }

    fun confirmSwitch() {
        val s = _step.value as? Step.ConfirmSwitch ?: return
        start(s.payload)
    }

    fun retry() {
        val payload = lastPayload ?: return dismiss()
        start(payload)
    }

    /** `null` — «Пока не выбирать». */
    fun chooseExit(name: String?) {
        if (_step.value !is Step.ChooseExit) return
        cancelFlow()
        flowJob = scope.launch {
            if (name != null) {
                engine.routes.value.firstOrNull { it.name == name }?.let { select(it) }
                store.rememberExitNode(name)
            }
            _step.value = Step.Protected(name)
        }
    }

    /** Ответ системного диалога VPN. Вне шага [Step.NeedsVpnConsent] не делает ничего. */
    fun onVpnConsent(granted: Boolean) {
        if (_step.value != Step.NeedsVpnConsent) return
        consent?.complete(granted)
    }

    fun dismiss() {
        cancelFlow()
        lastPayload = null
        _step.value = Step.Idle
    }

    // endregion
    // region Сценарий целиком

    private fun start(payload: JetBoxLinkPayload) {
        cancelFlow()
        flowJob = scope.launch { run(payload) }
    }

    private fun cancelFlow() {
        flowJob?.cancel()
        flowJob = null
        consent = null
    }

    private suspend fun run(payload: JetBoxLinkPayload) {
        // 1. Сеть. Без неё регистрация всё равно упадёт по таймауту — лучше сказать честно и сразу.
        if (!engine.isOnline()) {
            _step.value = Step.Failed(JetBoxFailure.Offline)
            return
        }

        // 2. Регистрация устройства по setup key.
        _step.value = Step.Registering(payload)

        // Android: туннель прежнего JetBox гасим до регистрации, иначе start() застанет ядро
        // запущенным со старым конфигом (а при новой личности файлы профиля уходят из-под него).
        // Хранилище пишет engine.register колбэком — только после успешного входа (точка фиксации).
        val current = store.currentBox
        val switching = current != null && !current.isSameConnection(payload)
        // Новая ссылка — новый ключ WireGuard: иначе management узнает прежний пир и setup key
        // (его группы) не применит. Повтор той же ссылки — прежний ключ.
        val fresh = JetBoxIdentity.plan(current, payload, engine.hasDeviceConfig()) == JetBoxIdentity.Plan.FRESH
        if (fresh) log("JetBox: новая ссылка — регистрируем новую личность устройства")
        // Остатки оборванной замены регистрация разбирает первой (и при прежнем ключе) — только при
        // остановленном ядре: файлы профиля нельзя менять под ним.
        val unfinished = engine.hasUnfinishedSwap()
        if (unfinished) log("JetBox: остатки оборванной замены подключения — разберём перед входом")
        val swap = Swap()
        val registered = try {
            if ((switching || fresh || unfinished) &&
                (engine.isRunning() || engine.state.value != VpnDisplayState.DISCONNECTED)
            ) {
                swap.stopped = true
                log("JetBox: замена подключения — останавливаем прежний туннель")
                engine.stop()
                if (withTimeoutOrNull(stopTimeoutMs) { awaitStopped() } == null) {
                    // Ядро не вышло — оно и работает: второй раз не запускаем, регистрацию не начинаем.
                    log("JetBox: прежний туннель не остановился за ${stopTimeoutMs / 1000} с")
                    _step.value = Step.Failed(JetBoxFailure.TunnelFailed)
                    return
                }
                swap.stopCompleted = true
            }
            engine.register(payload.managementURL, payload.setupKey, freshIdentity = fresh) {
                store.remember(payload, clock())
                swap.committed.set(true)
            }
        } catch (e: CancellationException) {
            // Обрыв посреди остановки или регистрации (чужая, битая или новая ссылка, «Закрыть»): файлы
            // профиля уже вернул engine.register — возвращаем прежний туннель. Шаг задал тот, кто отменил;
            // правим его, только если возврат не удался, а на экране отказ ссылки («Подключение не изменено»).
            withContext(NonCancellable) {
                if (!returnPrevious(swap, "сценарий прерван")) {
                    val shown = (_step.value as? Step.Failed)?.failure
                    if (shown == JetBoxFailure.ForeignServer || shown == JetBoxFailure.BrokenLink) {
                        _step.value = Step.Failed(JetBoxFailure.Other(RESTORE_INCOMPLETE))
                    }
                }
            }
            throw e
        }
        registered.exceptionOrNull()?.let { e ->
            if (e is IdentityRegistration.RestoreFailed) {
                // На диске ни прежняя, ни новая личность — туннель не поднимаем.
                log("JetBox: прежнее подключение не вернулось: ${e.message}")
                _step.value = Step.Failed(JetBoxFailure.Other(e.message ?: ""))
                return
            }
            if (!withContext(NonCancellable) { returnPrevious(swap, "переключение не удалось") }) {
                _step.value = Step.Failed(JetBoxFailure.Other(RESTORE_INCOMPLETE))
                return
            }
            _step.value = Step.Failed(
                if (e is IdentityRegistration.EngineStillRunning) JetBoxFailure.TunnelFailed
                else JetBoxFailure.classify(e.message, engine.isOnline()),
            )
            return
        }
        log("JetBox: устройство зарегистрировано на объекте")

        // 3. Android: системное согласие на VPN.
        if (engine.needsVpnConsent() != null) {
            val answer = CompletableDeferred<Boolean>()
            consent = answer
            _step.value = Step.NeedsVpnConsent
            val granted = answer.await()
            consent = null
            if (!granted) {
                log("JetBox: в системном диалоге VPN отказано")
                _step.value = Step.Failed(JetBoxFailure.VpnPermissionDenied)
                return
            }
        }

        // 4. Туннель.
        _step.value = Step.Connecting(payload)
        waitForTunnel()?.let {
            _step.value = Step.Failed(it)
            return
        }

        // 5. Выход в интернет.
        _step.value = Step.SelectingExit(payload)
        when (val outcome = resolveExitNode(payload)) {
            is ExitOutcome.Selected -> {
                store.rememberExitNode(outcome.name)
                _step.value = Step.Protected(outcome.name)
            }
            is ExitOutcome.Ambiguous -> {
                log("JetBox: выход из ссылки не найден, показываем выбор (${outcome.options.size} вариантов)")
                _step.value = Step.ChooseExit(payload, outcome.options)
            }
            ExitOutcome.None -> _step.value = Step.Protected(null)
        }
    }

    /** Замена подключения, начатая остановкой прежнего туннеля, — для единого возврата [returnPrevious]. */
    private class Swap {
        /** Прежний туннель был поднят, и мы его гасили. */
        var stopped = false

        /** Ядро действительно вышло. */
        var stopCompleted = false

        /** Точка фиксации пройдена: хранилище уже новое (колбэк зовётся из потока ядра). */
        val committed = AtomicBoolean(false)

        /** Возврат уже сделан — второго запуска не будет. */
        var returned = false
    }

    /** «Отключено» и ядро вышло из цикла запуска; таймаут накладывает вызывающий. */
    private suspend fun awaitStopped() {
        engine.state.first { it == VpnDisplayState.DISCONNECTED }
        // «Отключено» приходит раньше, чем ядро выходит из цикла запуска (а при старте без
        // сети состояние и не покидало DISCONNECTED). Регистрация при работающем ядре
        // перепишет конфиг под ногами у прежнего туннеля, а start() застанет его запущенным.
        while (engine.isRunning()) delay(STOP_POLL_MS)
    }

    /**
     * Единый возврат при обрыве замены подключения — туннель как до сценария, не больше одного раза.
     * Туннель не гасили — делать нечего. Остановка ещё идёт — дожидаемся её (не дольше [stopTimeoutMs]):
     * не дошла — ядро и так работает, второй раз не запускаем. Отмена пришла уже после точки фиксации
     * (хранилище новое, а `register` ещё не вернулся) — прежнего подключения нет; туннель был поднят —
     * поднимаем новое, как сделал бы сценарий (iOS после фиксации подключается без точки отмены).
     * Иначе — [restartPrevious]. `false` — прежние файлы вернулись не до конца, туннель не поднят.
     */
    private suspend fun returnPrevious(swap: Swap, why: String): Boolean {
        if (!swap.stopped || swap.returned) return true
        swap.returned = true
        if (!swap.stopCompleted && withTimeoutOrNull(stopTimeoutMs) { awaitStopped() } == null) {
            log("JetBox: $why, а прежний туннель так и не остановился — второй раз не запускаем")
            return true
        }
        if (swap.committed.get()) {
            log("JetBox: $why после записи нового подключения — прежнего уже нет, поднимаем новое")
            if (engine.needsVpnConsent() == null) engine.start()
            return true
        }
        return restartPrevious(why)
    }

    /**
     * Прежние файлы профиля (или адрес сервера) уже вернул `engine.register` — поднимаем прежний
     * JetBox, если согласие на VPN есть. На диске остатки оборванной замены (возврат не удался) —
     * `false`, туннель не поднимаем: ни прежней, ни новой личности целиком нет.
     */
    private suspend fun restartPrevious(why: String): Boolean {
        if (engine.hasUnfinishedSwap()) {
            log("JetBox: $why, а прежние файлы профиля вернулись не до конца — туннель не поднимаем")
            return false
        }
        log("JetBox: $why — возвращаем прежний JetBox")
        if (engine.needsVpnConsent() == null) engine.start()
        return true
    }

    /**
     * Поднять туннель и дождаться первого из: CONNECTED (`null`), ошибка ядра или «нужен вход»
     * (отказ), таймаут (TunnelFailed). Ошибка учитывается только свежая: AndroidEngine сбрасывает
     * `lastError` в `start()`; если значение после `start()` то же, что до, — это прошлая ошибка
     * (кроме «нет согласия на VPN»: согласие было получено перед стартом, значит его отозвали).
     */
    private suspend fun waitForTunnel(): JetBoxFailure? {
        val before = engine.lastError.value
        // Ядро держит «нужен вход» (NeedsLogin) до интерактивного входа, а вход по setup key его не
        // снимает. Поэтому флаг, уже поднятый до start(), — прошлое: отказом ключа считаем только
        // переход false→true.
        val loginLatched = engine.isLoginRequired()
        engine.start()
        // Метки самого start() (нет согласия, запуск из фона) свежие всегда: start() их ставит заново.
        val stale = engine.lastError.value
            ?.takeIf { it == before && it !in START_LABELS }

        return when (val outcome = withTimeoutOrNull(tunnelTimeoutMs) { awaitTunnel(stale, loginLatched) }) {
            Tunnel.Up -> null
            is Tunnel.Down -> outcome.failure
            null -> {
                log("JetBox: туннель не поднялся за ${tunnelTimeoutMs / 1000} с")
                JetBoxFailure.TunnelFailed
            }
        }
    }

    /** Крутится, пока не будет исхода; таймаут накладывает вызывающий. */
    private suspend fun awaitTunnel(initialStale: String?, loginLatched: Boolean): Tunnel {
        var stale = initialStale
        while (true) {
            if (engine.state.value == VpnDisplayState.CONNECTED) return Tunnel.Up
            val error = engine.lastError.value
            if (error == null) {
                stale = null
            } else if (error != stale) {
                log("JetBox: туннель не поднялся: $error")
                val failure = when (error) {
                    EngineApi.VPN_PERMISSION_REQUIRED -> JetBoxFailure.VpnPermissionDenied
                    // Окно свернули (например, «Назад» во время регистрации) — Android 12+ не дал
                    // запустить сервис туннеля из фона. Ключ и сервер в порядке: «Не удалось
                    // включить защиту» с повтором, а не «Что-то пошло не так».
                    EngineApi.FOREGROUND_START_NOT_ALLOWED -> JetBoxFailure.TunnelFailed
                    else -> JetBoxFailure.classify(error, engine.isOnline())
                }
                return Tunnel.Down(failure)
            }
            if (!loginLatched && engine.isLoginRequired()) {
                log("JetBox: ядро требует вход — ключ не принят")
                return Tunnel.Down(JetBoxFailure.KeyRejected(null))
            }
            // Просыпаемся на смену состояния/ошибки; isLoginRequired потока не имеет — опрос.
            withTimeoutOrNull(TUNNEL_POLL_MS) {
                combine(engine.state, engine.lastError) { s, e -> s to e }.drop(1).first()
            }
        }
    }

    private sealed class Tunnel {
        data object Up : Tunnel()
        data class Down(val failure: JetBoxFailure) : Tunnel()
    }

    /**
     * Выход в интернет клиент включает НЕ сам: политики на management только разрешают маршрут,
     * а выбирает его клиент. Имя приходит в ссылке (`e=`); если его нет или он не нашёлся —
     * фолбэк [ExitNodeResolver].
     */
    private suspend fun resolveExitNode(payload: JetBoxLinkPayload): ExitOutcome {
        val routes = withTimeoutOrNull(routesTimeoutMs) { awaitExitRoutes() } ?: emptyList()

        val outcome = ExitNodeResolver.resolve(payload.exitNodeName, routes)
        if (outcome is ExitOutcome.Selected) {
            routes.firstOrNull { it.name == outcome.name }?.let { select(it) }
            if (outcome.name != payload.exitNodeName) {
                log("JetBox: выход выбран автоматически — единственный доступный")
            }
        }
        return outcome
    }

    /** Опрашивает ядро, пока среди маршрутов не появится выход; таймаут накладывает вызывающий. */
    private suspend fun awaitExitRoutes(): List<RouteInfo> {
        while (true) {
            engine.refresh()
            val found = withTimeoutOrNull(ROUTES_POLL_MS) {
                engine.routes.first { list -> list.any { it.isExitNode } }
            }
            if (found != null) return found
        }
    }

    private suspend fun select(route: RouteInfo) {
        if (route.selected) return
        engine.selectRoute(route.name).exceptionOrNull()?.let {
            // Как iOS (setExitNode без результата): сценарий не валим, но фиксируем.
            log("JetBox: выбор выхода ${route.name} не удался: ${it.message}")
        }
        // Дать ядру применить маршрут до того, как экран скажет «под защитой».
        delay(SELECT_SETTLE_MS)
    }

    // endregion
    // region Мелочи

    private val activePayload: JetBoxLinkPayload?
        get() = when (val s = _step.value) {
            is Step.Ready -> s.payload
            is Step.ConfirmSwitch -> s.payload
            is Step.Registering -> s.payload
            is Step.Connecting -> s.payload
            is Step.SelectingExit -> s.payload
            is Step.ChooseExit -> s.payload
            Step.NeedsVpnConsent -> lastPayload
            Step.Idle, is Step.Protected, is Step.Failed -> null
        }

    /** Шаги, из которых повторный приход той же ссылки начинает сценарий заново. */
    private val isRestartable: Boolean
        get() = when (_step.value) {
            is Step.Ready, is Step.ConfirmSwitch, is Step.Failed, Step.Idle, is Step.Protected -> true
            is Step.Registering, is Step.Connecting, is Step.SelectingExit, is Step.ChooseExit,
            Step.NeedsVpnConsent -> false
        }

    // endregion

    companion object {
        private const val TAG = "JetBoxOnboarding"
        private const val TUNNEL_POLL_MS = 500L
        private const val ROUTES_POLL_MS = 900L
        private const val SELECT_SETTLE_MS = 700L
        private const val STOP_POLL_MS = 200L
        private const val RESTORE_INCOMPLETE =
            "не удалось вернуть прежнее подключение: файлы профиля вернулись не до конца"
        private val START_LABELS = setOf(EngineApi.VPN_PERMISSION_REQUIRED, EngineApi.FOREGROUND_START_NOT_ALLOWED)
    }
}
