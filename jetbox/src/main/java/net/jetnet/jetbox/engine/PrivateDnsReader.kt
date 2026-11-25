package net.jetnet.jetbox.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * Системный «Частный DNS» в строгом режиме (Android 9+): имя сервера DoT у сети телефона (не у VPN), иначе `null`.
 * Пока он задан, ядро NetBird не ставит в туннель свой резолвер («private dns hostname … is set, leaving the tunnel
 * resolver out»), имена разрешает этот сервер — блокировка рекламы JetBox не работает (приёмка Fabrika 26.11.2026).
 * Режим «автоматически» имени не имеет — резолвер туннеля работает.
 */
object PrivateDnsReader {
    fun hostname(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return runCatching {
            @Suppress("DEPRECATION")
            cm.allNetworks.asSequence()
                .filter { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false }
                .mapNotNull { n -> cm.getLinkProperties(n)?.privateDnsServerName?.takeIf { it.isNotBlank() } }
                .firstOrNull()
        }.getOrNull()
    }
}
