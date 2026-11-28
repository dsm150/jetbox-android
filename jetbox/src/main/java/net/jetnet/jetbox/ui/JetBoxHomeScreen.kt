@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package net.jetnet.jetbox.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import net.jetnet.jetbox.JetBoxProcess
import net.jetnet.jetbox.R
import net.jetnet.jetbox.engine.AndroidEngine
import net.jetnet.jetbox.engine.HomeProbeRunner
import net.jetnet.jetbox.logic.PowerAction
import net.jetnet.jetbox.logic.ShieldPhase
import net.jetnet.jetbox.logic.UpdateInfo
import net.jetnet.jetbox.logic.VpnDisplayState
import net.jetnet.jetbox.store.JetBoxBox
import net.jetnet.jetbox.store.JetBoxStore
import net.jetnet.jetbox.store.SharedPrefsStore
import net.jetnet.jetbox.ui.icons.JetBoxIcons
import net.jetnet.jetbox.ui.theme.JBType
import net.jetnet.jetbox.ui.theme.JetBoxTheme

/*
 * Порт `JetBoxHomeView.swift`: главный экран простого режима — щит-кнопка, состояние защиты и
 * стеклянная панель дома. Включает и выключает только щит.
 */

/** Боевая фабрика [HomeViewModel]: движок процесса, настройки JetBox, DNS-замер «до дома». */
fun homeViewModelFactory(context: Context): ViewModelProvider.Factory {
    val app = context.applicationContext
    return viewModelFactory {
        initializer {
            HomeViewModel(
                engine = AndroidEngine.get(app),
                store = JetBoxStore(SharedPrefsStore(app.getSharedPreferences(JetBoxStore.PREFS_NAME, Context.MODE_PRIVATE))),
                probe = HomeProbeRunner(),
                updates = JetBoxProcess.updates(app),
                privateDns = { net.jetnet.jetbox.engine.PrivateDnsReader.hostname(app) },
                dnsCanary = systemDnsCanary(app)::check,
            )
        }
    }
}

/**
 * Главный экран с данными: опрос и замеры — только пока экран в `STARTED`; согласие VPN — системным
 * диалогом; лист подробностей — по нажатию на панель.
 */
@Composable
fun JetBoxHomeScreen(vm: HomeViewModel, presenting: Boolean, onOpenSettings: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val vpnDenied by vm.vpnDenied.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var showsDetails by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.whileVisible() }
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        vm.onVpnConsent(result.resultCode == Activity.RESULT_OK)
    }
    val now = rememberSecondTicker(active = ui.connectedSinceMillis != null)
    val openUrl = rememberUrlOpener()

    // Пришла ссылка — экран сценария перекрывает всё (iOS: оверлей поверх листов). Листы и диалог —
    // отдельные окна над нашим, поэтому закрываем их сами.
    var showsPrivateDns by rememberSaveable { mutableStateOf(false) }
    var showsDnsBypass by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(presenting) {
        if (!presenting) return@LaunchedEffect
        showsDetails = false
        showsPrivateDns = false
        showsDnsBypass = false
        vm.dismissVpnDenied()
    }

    JetBoxHomeLayout(
        ui = ui,
        nowMillis = now,
        onPower = {
            when (val r = vm.press()) {
                is PressResult.NeedsVpnConsent -> consent.launch(r.intent)
                PressResult.Done -> Unit
            }
        },
        onSettings = onOpenSettings,
        onOpenDetails = { showsDetails = true },
        onUpdate = { ui.update?.let { openUrl(it.url) } },
        onPrivateDns = { showsPrivateDns = true },
        onDnsBypass = { showsDnsBypass = true },
    )

    if (showsDnsBypass && ui.showsDnsBypassWarning) {
        val context = LocalContext.current
        DnsBypassDialog(
            onOpenSettings = {
                showsDnsBypass = false
                openNetworkSettings(context)
            },
            onDismiss = { showsDnsBypass = false },
        )
    }

    if (showsPrivateDns && ui.privateDnsHost != null) {
        val context = LocalContext.current
        PrivateDnsDialog(
            hostname = ui.privateDnsHost.orEmpty(),
            onOpenSettings = {
                showsPrivateDns = false
                openNetworkSettings(context)
            },
            onDismiss = { showsPrivateDns = false },
        )
    }

    if (showsDetails && ui.box != null && !presenting) {
        LaunchedEffect(Unit) { vm.refreshRoutes() }
        JetBoxConnectionSheet(
            ui = ui,
            nowMillis = now,
            onSelectExit = vm::selectExit,
            onClearExit = vm::clearExit,
            onDismiss = { showsDetails = false },
        )
    }

    if (vpnDenied && !presenting) {
        AlertDialog(
            onDismissRequest = vm::dismissVpnDenied,
            title = { Text(stringResource(R.string.jb_err_vpn_permission_title)) },
            text = { Text(stringResource(R.string.jb_err_tunnel_body)) },
            confirmButton = { TextButton(onClick = vm::dismissVpnDenied) { Text(stringResource(R.string.jb_close)) } },
        )
    }
}

/**
 * Часы для «в сети»: раз в секунду, ровно на границе секунды, и только пока экран виден
 * (`TimelineView(.periodic(by: 1))` iOS).
 */
@Composable
private fun rememberSecondTicker(active: Boolean): Long {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lifecycle, active) {
        if (!active) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val t = System.currentTimeMillis()
                now.longValue = t
                delay(1000 - t % 1000)
            }
        }
    }
    return now.longValue
}

/** Главный экран без привязки к данным (`JetBoxHomeLayout` iOS): всё приходит параметрами. */
@Composable
fun JetBoxHomeLayout(
    ui: HomeUiState,
    nowMillis: Long,
    onPower: () -> Unit,
    onSettings: () -> Unit,
    onOpenDetails: () -> Unit,
    onUpdate: () -> Unit = {},
    onPrivateDns: () -> Unit = {},
    onDnsBypass: () -> Unit = {},
) {
    val c = JetBoxTheme.colors
    val phase = ui.phase
    val statusText = stringResource(ui.status.text)
    val shieldLabel = when (ui.power) {
        PowerAction.CONNECT -> stringResource(R.string.jb_home_connect)
        PowerAction.DISCONNECT -> stringResource(R.string.jb_home_disconnect)
        PowerAction.NONE -> statusText
    }
    val statusColor by animateColorAsState(
        if (phase == ShieldPhase.ON) c.accent else c.text,
        tween(300, easing = EaseInOut),
        label = "statusColor",
    )

    Box(Modifier.fillMaxSize()) {
        JetBoxBackdrop(phase)
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                JetBoxLogo(markHeight = 34.dp)
                Spacer(Modifier.weight(1f))
                JBGlassIconButton(JetBoxIcons.gearshape, stringResource(R.string.jb_settings_title), onClick = onSettings)
            }

            Spacer(Modifier.weight(1f).heightIn(min = 12.dp))

            // Вход по рабочей почте: пока набирают адрес или код, щит со статусом уступает место форме над клавиатурой.
            if (ui.hasBox || !WindowInsets.isImeVisible) {
                JetBoxShield(
                    phase = phase,
                    label = shieldLabel,
                    onClick = if (ui.power == PowerAction.NONE) null else onPower,
                )
                Spacer(Modifier.height(26.dp))
                Text(
                    statusText,
                    style = JBType.title2.copy(fontWeight = FontWeight.SemiBold),
                    color = statusColor,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.weight(1f).heightIn(min = 24.dp))

            // Новая версия на сайте — тихая капсула над панелью; пропадает, когда нечего предложить.
            AnimatedVisibility(
                visible = ui.update != null,
                enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                exit = fadeOut(tween(300)) + shrinkVertically(tween(300)),
            ) {
                // Последнюю версию держим на время исчезновения.
                val shown = remember { arrayOf("") }
                ui.update?.let { shown[0] = it.versionName }
                JetBoxUpdateCapsule(shown[0], onClick = onUpdate, modifier = Modifier.padding(bottom = 6.dp))
            }

            // Системный «Частный DNS» — DNS идёт мимо JetBox, реклама не блокируется: капсула-предупреждение над панелью.
            AnimatedVisibility(
                visible = ui.showsPrivateDnsWarning,
                enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                exit = fadeOut(tween(300)) + shrinkVertically(tween(300)),
            ) {
                JetBoxNoticeCapsule(
                    stringResource(R.string.jb_private_dns_capsule),
                    dot = c.warn,
                    onClick = onPrivateDns,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            // DNS мимо JetBox (канарейка) — то же место и вид, что у частного DNS; обе сразу не показываются.
            AnimatedVisibility(
                visible = ui.showsDnsBypassWarning,
                enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                exit = fadeOut(tween(300)) + shrinkVertically(tween(300)),
            ) {
                JetBoxNoticeCapsule(
                    stringResource(R.string.jb_dns_bypass_capsule),
                    dot = c.warn,
                    onClick = onDnsBypass,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            Box(Modifier.widthIn(max = 420.dp).padding(bottom = 8.dp)) {
                if (ui.hasBox) {
                    JetBoxHomeDock(ui, nowMillis, onOpen = onOpenDetails)
                } else {
                    // Вход по рабочей почте с кодом на месте «JetBox не подключён»; ссылка из Кабинета работает, как раньше.
                    val emailLogin = rememberEmailLogin()
                    if (emailLogin != null) JetBoxEmailLoginDock(emailLogin) else JetBoxNoBoxDock()
                }
            }
        }
    }
}

/**
 * «Доступна новая версия 1.1 ›» — стеклянная капсула по ширине текста, как кнопки шапки: видимая
 * высота 40 dp, зона касания 48 dp; синяя точка — единственный акцент, чтобы не спорить со щитом.
 */
@Composable
fun JetBoxUpdateCapsule(versionName: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    JetBoxNoticeCapsule(stringResource(R.string.jb_update_available, versionName), JetBoxTheme.colors.accent, onClick, modifier)

/** Стеклянная капсула-строка над панелью: точка цвета [dot], текст и «›». Обновление, частный DNS. */
@Composable
fun JetBoxNoticeCapsule(text: String, dot: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = JetBoxTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val alpha by animateFloatAsState(if (pressed) 0.7f else 1f, tween(150), label = "updateAlpha")
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .graphicsLayer { this.alpha = alpha }
                .jbGlassPanel(cornerRadius = 20.dp)
                .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                style = JBType.footnote.copy(fontWeight = FontWeight.Medium),
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = c.muted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** «DNS идёт мимо JetBox»: что это значит, как исправить и кнопка в настройки сети Android. */
@Composable
private fun DnsBypassDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.jb_dns_bypass_title)) },
        text = { Text(stringResource(R.string.jb_dns_bypass_body)) },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.jb_private_dns_open)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.jb_close)) } },
    )
}

/**
 * Боевая канарейка: (а) A-запрос прямо в DNS дома по mesh-адресу (UDP 53, через туннель — приложение из своего VPN не
 * исключено); (б) системный резолвер, как браузер — `InetAddress.getAllByName`, не дольше 3 с.
 */
/**
 * Сеть VPN JetBox, если поднята: системный резолвер спрашиваем через неё ([android.net.Network.getAllByName]) — так
 * проверка не зависит от того, идёт ли трафик самого приложения в туннель. `null` — VPN нет, спрашиваем как обычно.
 */
@Suppress("DEPRECATION")
private fun vpnNetwork(context: Context): android.net.Network? {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return null
    return runCatching {
        cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true }
    }.getOrNull()
}

private fun systemDnsCanary(context: Context) = net.jetnet.jetbox.logic.DnsCanary(
    name = net.jetnet.jetbox.link.JetBoxConfig.DNS_CHECK_NAME,
    boxQuery = { ip, name ->
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { net.jetnet.jetbox.logic.DnsCanary.queryA(java.net.InetAddress.getByName(ip), name) }.getOrNull()
        }
    },
    systemResolve = { name ->
        kotlinx.coroutines.coroutineScope {
            val lookup = async(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val vpn = vpnNetwork(context)
                    // Нет доступа к сети VPN (например, приложение вне своего туннеля) — обычный резолвер.
                    val found = vpn?.let { n -> runCatching { n.getAllByName(name) }.getOrElse { e -> if (e is java.net.UnknownHostException) throw e else null } }
                        ?: java.net.InetAddress.getAllByName(name)
                    net.jetnet.jetbox.logic.SystemAnswer.Addresses(found.mapNotNull { it.hostAddress })
                } catch (e: java.net.UnknownHostException) {
                    net.jetnet.jetbox.logic.SystemAnswer.NotFound
                } catch (e: Exception) {
                    net.jetnet.jetbox.logic.SystemAnswer.NoAnswer
                }
            }
            kotlinx.coroutines.withTimeoutOrNull(3_000) { lookup.await() }
                ?: net.jetnet.jetbox.logic.SystemAnswer.NoAnswer.also { lookup.cancel() }
        }
    },
)

/** «Включён частный DNS»: что это значит и кнопка в настройки сети Android. */
@Composable
private fun PrivateDnsDialog(hostname: String, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.jb_private_dns_title)) },
        text = { Text(stringResource(R.string.jb_private_dns_body, hostname)) },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.jb_private_dns_open)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.jb_close)) } },
    )
}

/**
 * Настройки, где живёт «Частный DNS»: прямого экрана в Android нет — «Сеть и интернет» (там «Дополнительно → Частный
 * DNS»). У Xiaomi (MIUI) этот же интент открывает «Дополнительные функции» без частного DNS (проверено на Mi Note 3,
 * 26.11.2026) — там главные настройки, путь из диалога начинается с них.
 */
private fun openNetworkSettings(context: Context) {
    val main = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
    val network = android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
    val miui = android.os.Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
    val intents = if (miui) listOf(main) else listOf(network, main)
    for (intent in intents) {
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
}

// --- Превью: выключено / подключение / под защитой, светлая и тёмная ---

private val PreviewBox = JetBoxBox(
    managementURL = "https://netbird.jet-net.net",
    exitNodeName = "home-ivanov",
    selectedExitNode = null,
    boxName = "Дом на Озёрной",
    registeredAtMillis = 0L,
)

private fun previewUi(state: VpnDisplayState) = HomeUiState(
    box = PreviewBox,
    state = state,
    connectedSinceMillis = if (state == VpnDisplayState.CONNECTED) 0L else null,
    latencyMs = 24,
    route = RouteKind.DIRECT,
    bytesRx = 48_200_000,
    bytesTx = 6_100_000,
)

// Адрес в превью не открывается (.invalid — зарезервированный домен); адресов сайта и APK в main нет
// вовсе (магазинные сборки, JETBOX-BUILD.md §6.1).
private val PreviewUpdate = UpdateInfo(11, "1.1", "https://example.invalid/preview", null)

@Composable
private fun HomePreview(state: VpnDisplayState, dark: Boolean, update: Boolean = false) = JetBoxTheme(darkTheme = dark) {
    JetBoxHomeLayout(
        previewUi(state).copy(update = if (update) PreviewUpdate else null),
        nowMillis = 756_000L, onPower = {}, onSettings = {}, onOpenDetails = {},
    )
}

@Preview(name = "Главный — выкл, светлая", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
private fun HomeOffPreview() = HomePreview(VpnDisplayState.DISCONNECTED, dark = false)

@Preview(name = "Главный — подключение, светлая", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
private fun HomeConnectingPreview() = HomePreview(VpnDisplayState.CONNECTING, dark = false)

@Preview(name = "Главный — под защитой, светлая", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
private fun HomeOnPreview() = HomePreview(VpnDisplayState.CONNECTED, dark = false)

@Preview(name = "Главный — выкл, тёмная", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeOffDarkPreview() = HomePreview(VpnDisplayState.DISCONNECTED, dark = true)

@Preview(name = "Главный — подключение, тёмная", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeConnectingDarkPreview() = HomePreview(VpnDisplayState.CONNECTING, dark = true)

@Preview(name = "Главный — под защитой, тёмная", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeOnDarkPreview() = HomePreview(VpnDisplayState.CONNECTED, dark = true)

@Preview(name = "Главный — есть обновление, светлая", widthDp = 393, heightDp = 852, showBackground = true)
@Composable
private fun HomeUpdatePreview() = HomePreview(VpnDisplayState.CONNECTED, dark = false, update = true)

@Preview(name = "Главный — есть обновление, тёмная", widthDp = 393, heightDp = 852, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeUpdateDarkPreview() = HomePreview(VpnDisplayState.DISCONNECTED, dark = true, update = true)
