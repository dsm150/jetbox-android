package net.jetnet.jetbox.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import net.jetnet.jetbox.engine.JetBoxOnboarding.Step
import net.jetnet.jetbox.logic.JetBoxAdvancedAccess
import net.jetnet.jetbox.logic.UpdateInfo
import net.jetnet.jetbox.store.JetBoxBox
import net.jetnet.jetbox.store.MemoryStore

/** Экраны витрины задачи 12 (`--es screen`). */
enum class ShowcaseScreenKind { SETTINGS, SETTINGS_ADVANCED, SETTINGS_NOBOX, ABOUT }

/**
 * Витрина экрана по ссылке: шаг [name] из [PreviewFlowSteps] (без сценария и ядра). Кнопки
 * переключают шаги так, как это сделал бы сценарий, — чтобы пройтись руками.
 */
@Composable
fun ShowcaseFlow(name: String, detailsExpanded: Boolean) {
    var step by remember { mutableStateOf(PreviewFlowSteps[name] ?: PreviewFlowSteps.getValue("READY")) }
    val steps = PreviewFlowSteps
    JetBoxFlowLayout(
        step = step,
        actions = JetBoxFlowActions(
            onConnect = { step = steps.getValue("REGISTERING") },
            onConfirmSwitch = { step = steps.getValue("REGISTERING") },
            onRetry = { step = steps.getValue("CONNECTING") },
            onChooseExit = { step = Step.Protected(it) },
            onDismiss = { step = steps.getValue("READY") },
            onOpenUrl = {},
        ),
        detailsInitiallyExpanded = detailsExpanded,
    )
}

/** Витрина настроек и «О программе»: настоящий [JetBoxSettingsScreen] на памяти вместо SharedPreferences. */
@Composable
fun ShowcaseSettings(kind: ShowcaseScreenKind, update: Boolean = false) {
    val access = remember {
        JetBoxAdvancedAccess(MemoryStore()).also {
            if (kind == ShowcaseScreenKind.SETTINGS_ADVANCED) repeat(JetBoxAdvancedAccess.TAPS_REQUIRED) { _ -> it.registerTap() }
        }
    }
    val box = if (kind == ShowcaseScreenKind.SETTINGS_NOBOX) {
        null
    } else {
        JetBoxBox(
            managementURL = "https://netbird.jet-net.net",
            exitNodeName = "home-ivanov",
            selectedExitNode = "home-ivanov",
            boxName = "Дом на Озёрной",
            registeredAtMillis = 0L,
        )
    }
    when (kind) {
        ShowcaseScreenKind.ABOUT -> JetBoxAboutScreen(onBack = {})
        else -> JetBoxSettingsScreen(
            access = access,
            box = box,
            onClose = {},
            update = if (update) UpdateInfo(11, "1.1", "https://box.jet-net.net/android/JetBox.apk", null) else null,
        )
    }
}
