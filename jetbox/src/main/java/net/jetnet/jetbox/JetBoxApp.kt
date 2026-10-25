package net.jetnet.jetbox

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.MainScope
import net.jetnet.jetbox.engine.EngineApi
import net.jetnet.jetbox.engine.JetBoxEngine
import net.jetnet.jetbox.engine.JetBoxOnboarding
import net.jetnet.jetbox.engine.JetBoxOnboarding.Step
import net.jetnet.jetbox.engine.HttpUpdateSource
import net.jetnet.jetbox.engine.UpdateChecker
import net.jetnet.jetbox.logic.ConnectionDefaults
import net.jetnet.jetbox.logic.JetBoxAdvancedAccess
import net.jetnet.jetbox.store.JetBoxStore
import net.jetnet.jetbox.store.KeyValueStore
import net.jetnet.jetbox.store.SharedPrefsStore
import net.jetnet.jetbox.ui.HomeViewModel
import net.jetnet.jetbox.ui.JetBoxFlowActions
import net.jetnet.jetbox.ui.JetBoxFlowScreen
import net.jetnet.jetbox.ui.JetBoxHomeScreen
import net.jetnet.jetbox.ui.JetBoxSettingsScreen
import net.jetnet.jetbox.ui.homeViewModelFactory
import net.jetnet.jetbox.ui.rememberUrlOpener
import net.jetnet.jetbox.widget.JetBoxWidget
import java.net.URL

/**
 * Что живёт на уровне процесса — как `@StateObject` в `NetBirdApp` (iOS): сценарий по ссылке
 * переживает поворот экрана и закрытие окна (регистрация, начатая до «Назад», не обрывается),
 * а ссылка может прийти раньше, чем откроется экран.
 */
object JetBoxProcess {
    /** Апстримовые SharedPreferences (`tool/Preferences.java`). */
    private const val UPSTREAM_PREFS = "netbird"

    @Volatile private var onboarding: JetBoxOnboarding? = null
    @Volatile private var updates: UpdateChecker? = null

    /** Настройки JetBox (`JetBoxStore.PREFS_NAME`). */
    fun prefs(context: Context): KeyValueStore =
        SharedPrefsStore((context.applicationContext ?: context).getSharedPreferences(JetBoxStore.PREFS_NAME, Context.MODE_PRIVATE))

    /** Сценарий «одной кнопки». Вызывать с главного потока: его [MainScope] — главный поток. */
    fun onboarding(context: Context): JetBoxOnboarding =
        onboarding ?: synchronized(this) {
            onboarding ?: create(context.applicationContext ?: context).also { onboarding = it }
        }

    /**
     * Проверка обновлений — одна на процесс (главный экран и настройки видят один ответ). В сборке
     * для магазина выключена: обновляет магазин.
     */
    fun updates(context: Context): UpdateChecker =
        updates ?: synchronized(this) {
            updates ?: UpdateChecker(
                source = HttpUpdateSource(URL(UpdateChecker.VERSION_URL), "JetBox-Android/${BuildConfig.APP_VERSION_NAME}"),
                kv = prefs(context),
                currentCode = BuildConfig.APP_VERSION_CODE,
                enabled = BuildConfig.STORE == STORE_APK,
            ).also { updates = it }
        }

    /** `BuildConfig.STORE` сборки «файл с сайта». */
    const val STORE_APK = "apk"

    private fun create(app: Context): JetBoxOnboarding {
        // Обычно уже сделано при старте процесса ([JetBoxInitProvider]); повтор безвреден — метка «применено».
        startup(app)
        return JetBoxOnboarding(
            engine = JetBoxEngine.get(app),
            store = JetBoxStore(prefs(app)),
            scope = MainScope(),
        )
    }

    /**
     * Разовые настройки при запуске процесса — `JetBoxStartup.run()` iOS. Вызывается из
     * [JetBoxInitProvider] (до окна и до плитки) и страховкой из [create]; идемпотентно.
     */
    internal fun startup(context: Context) {
        // В `ContentProvider.onCreate` applicationContext ещё может быть null — берём сам контекст.
        val app = context.applicationContext ?: context
        // Главный поток провайдера: запись через apply() (без синхронного диска). Идемпотентность
        // не страдает: маркер виден в памяти сразу, а повтор после смерти процесса до сброса на диск
        // лишь снова выставит то же значение «напрямую».
        ConnectionDefaults.apply(
            ours = SharedPrefsStore(app.getSharedPreferences(JetBoxStore.PREFS_NAME, Context.MODE_PRIVATE), async = true),
            upstream = SharedPrefsStore(app.getSharedPreferences(UPSTREAM_PREFS, Context.MODE_PRIVATE), async = true),
        )
        // Есть виджет — движок нужен с первой секунды процесса: туннель могут включить плиткой или
        // always-on без окна, а перерисовывает виджет только движок. После onCreate приложения —
        // в провайдере applicationContext ещё не готов.
        Handler(Looper.getMainLooper()).post {
            val ctx = context.applicationContext ?: context
            if (JetBoxWidget.hasWidgets(ctx)) JetBoxEngine.get(ctx)
        }
    }
}

/** Экраны простого режима. «О программе» — внутри настроек (`NavigationLink` iOS). */
enum class JetBoxScreen { Home, Settings }

/**
 * Корень простого режима — `iOSMainView` (iOS) без апстримовых вкладок: главный экран, настройки
 * поверх (лист iOS — выезжают снизу), и над всем — экран по ссылке, пока идёт сценарий
 * (`.transition(.opacity)`, 0,2 с).
 */
@Composable
fun JetBoxApp(onboarding: JetBoxOnboarding, engine: EngineApi, prefs: KeyValueStore) {
    val context = LocalContext.current
    val homeVm: HomeViewModel = viewModel(factory = homeViewModelFactory(context))
    val access = remember(prefs) { JetBoxAdvancedAccess(prefs) }
    val step by onboarding.step.collectAsStateWithLifecycle()
    val engineState by engine.state.collectAsStateWithLifecycle()
    val ui by homeVm.ui.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(JetBoxScreen.Home) }
    val presenting = step != Step.Idle

    // Сценарий мог прописать или переименовать JetBox — главный экран и настройки читают свежий.
    LaunchedEffect(step) { homeVm.reloadBox() }
    // Пришла ссылка — после сценария клиент оказывается на главном экране, а не в настройках.
    LaunchedEffect(presenting) { if (presenting) screen = JetBoxScreen.Home }

    NotificationPermissionEffect(protected = JetBoxNotifications.isTrigger(engineState, step), kv = prefs)

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = screen,
            // Под экраном по ссылке главный экран для TalkBack не существует (iOS — модальный оверлей).
            modifier = if (presenting) Modifier.clearAndSetSemantics {} else Modifier,
            transitionSpec = {
                if (targetState == JetBoxScreen.Settings) {
                    slideInVertically(tween(SHEET_MS)) { it } togetherWith fadeOut(tween(SHEET_MS))
                } else {
                    fadeIn(tween(SHEET_MS)) togetherWith slideOutVertically(tween(SHEET_MS)) { it }
                }.apply { targetContentZIndex = if (targetState == JetBoxScreen.Settings) 1f else 0f }
            },
            label = "jetboxScreen",
        ) { s ->
            when (s) {
                JetBoxScreen.Home -> JetBoxHomeScreen(
                    homeVm,
                    presenting = presenting,
                    onOpenSettings = { screen = JetBoxScreen.Settings },
                )
                JetBoxScreen.Settings -> JetBoxSettingsScreen(
                    access = access,
                    box = ui.box,
                    onClose = { screen = JetBoxScreen.Home },
                    update = ui.update,
                    onCheckUpdates = homeVm::checkUpdatesNow,
                )
            }
        }

        // Последний непустой шаг: на исчезновении экран по ссылке гаснет с тем, что показывал
        // (SwiftUI держит снимок уходящего вида), а не пустым фоном шага Idle.
        val lastStep = remember { arrayOf<Step>(Step.Idle) }
        if (presenting) lastStep[0] = step
        val shown = if (presenting) step else lastStep[0]
        val openUrl = rememberUrlOpener()
        val flowActions = remember(onboarding, openUrl) {
            JetBoxFlowActions(
                onConnect = onboarding::connect,
                onConfirmSwitch = onboarding::confirmSwitch,
                onRetry = onboarding::retry,
                onChooseExit = onboarding::chooseExit,
                onDismiss = onboarding::dismiss,
                onOpenUrl = openUrl,
            )
        }
        AnimatedVisibility(
            visible = presenting,
            // Экран по ссылке забирает все касания: сквозь него до главного экрана ничего не доходит.
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {},
            enter = fadeIn(tween(FLOW_FADE_MS)),
            exit = fadeOut(tween(FLOW_FADE_MS)),
        ) {
            JetBoxFlowScreen(
                step = shown,
                actions = flowActions,
                vpnConsentIntent = engine::needsVpnConsent,
                onVpnConsent = onboarding::onVpnConsent,
            )
        }
    }
}

private const val SHEET_MS = 300
private const val FLOW_FADE_MS = 200
