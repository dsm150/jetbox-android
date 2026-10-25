package net.jetnet.jetbox

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import kotlinx.coroutines.delay
import net.jetnet.jetbox.engine.JetBoxOnboarding.Step
import net.jetnet.jetbox.logic.VpnDisplayState
import net.jetnet.jetbox.store.KeyValueStore

/**
 * Порт `JetBoxNotifications.swift` (iOS). Разрешение на уведомления спрашиваем не при запуске
 * (клиент увидел бы системный вопрос раньше, чем понял, зачем), а когда защита впервые включилась:
 * уведомление приходит, если связь с домом прервалась. Пауза 1,6 с — чтобы системный вопрос не
 * закрыл анимацию успеха щита.
 *
 * Отличия от iOS (Android):
 * - разрешение `POST_NOTIFICATIONS` нужно только с Android 13 (API 33); раньше — ничего не делаем;
 * - iOS спрашивает, пока статус `notDetermined` (система сама не покажет вопрос второй раз);
 *   Android после двух отказов молчит, но первый отказ не окончательный — поэтому спрашиваем ровно
 *   один раз, маркер [ASKED_KEY] в настройках JetBox.
 */
object JetBoxNotifications {
    const val ASKED_KEY = "jetbox.notificationsAsked"
    const val DELAY_MS = 1_600L
    const val PERMISSION = "android.permission.POST_NOTIFICATIONS"
    private const val API_TIRAMISU = 33

    fun shouldAsk(sdkInt: Int, granted: Boolean, asked: Boolean): Boolean =
        sdkInt >= API_TIRAMISU && !granted && !asked

    /**
     * Защита включена: туннель CONNECTED (главный экран, `onChange(of: vpnDisplayState)` iOS) или
     * сценарий по ссылке дошёл до «Под защитой» (`JetBoxOnboarding.swift`).
     */
    fun isTrigger(state: VpnDisplayState, step: Step): Boolean =
        state == VpnDisplayState.CONNECTED || step is Step.Protected

    fun isGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < API_TIRAMISU ||
            ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
}

/**
 * Спросить разрешение на уведомления на переходе [protected] false → true, увиденном этим окном:
 * защита, включённая уже к открытию окна, вопроса не вызывает. Ожидание (флаг и пауза) переживает
 * пересоздание окна — смену языка или темы, возврат после смерти процесса.
 *
 * Системный вопрос запускаем только в `RESUMED` (свёрнутое окно его не покажет, а результат
 * потерялся бы); маркер «спрашивали» — только когда запрос действительно ушёл.
 */
@Composable
fun NotificationPermissionEffect(protected: Boolean, kv: KeyValueStore) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Log.i(TAG, "JetBox: разрешение на уведомления: $granted")
    }
    var wasProtected by rememberSaveable { mutableStateOf(protected) }
    var pending by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(protected) {
        if (protected && !wasProtected) pending = true
        wasProtected = protected
    }
    LaunchedEffect(pending) {
        if (!pending) return@LaunchedEffect
        delay(JetBoxNotifications.DELAY_MS)
        // Окно свернули во время паузы — ждём возвращения; закрыли совсем — флаг сохранён в
        // состоянии, пересозданное окно спросит само.
        lifecycle.withResumed {
            pending = false
            val ask = JetBoxNotifications.shouldAsk(
                sdkInt = Build.VERSION.SDK_INT,
                granted = JetBoxNotifications.isGranted(context),
                asked = kv.getBoolean(JetBoxNotifications.ASKED_KEY, false),
            )
            if (!ask) return@withResumed
            try {
                launcher.launch(JetBoxNotifications.PERMISSION)
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "JetBox: системный вопрос об уведомлениях недоступен")
                return@withResumed
            }
            kv.putBoolean(JetBoxNotifications.ASKED_KEY, true)
        }
    }
}

private const val TAG = "JetBox"
