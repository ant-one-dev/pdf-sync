package com.orbisrobotics.pdfsync

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import java.io.File

/*
 * Protocole TCP, uniquement sur le réseau Wi-Fi Direct (Expert = 192.168.49.1:5050).
 *
 *   Expert -> Client   HELLO
 *   Client -> Expert   long idDuPdfEnCache, UTF idTablette, UTF nom
 *   Expert -> Client   ACCEPTED
 *
 * Ensuite Expert -> Client :
 *   PDF   long id, UTF nom, long taille, octets
 *   PAGE  int page
 *   PING                        toutes les 3 s
 *   END                         fin de séance, le Client efface le document
 */
object Protocol {
    const val PORT = 5050

    const val HELLO = 10
    const val ACCEPTED = 11
    const val PDF = 1
    const val PAGE = 2
    const val PING = 3
    const val END = 4

    const val PING_MS = 3_000L
    const val READ_TIMEOUT_MS = 10_000
}

/** PDF temporaires (jamais en stockage partagé). */
object Storage {
    fun expertCopy(ctx: Context) = File(ctx.filesDir, "expert_${System.currentTimeMillis()}.pdf")
    fun clientDir(ctx: Context) = File(ctx.filesDir, "received")
}

/** Wi-Fi en faible latence tant que l'écran est allumé. */
@Suppress("DEPRECATION")
fun Context.acquireWifiLock(): WifiManager.WifiLock? = try {
    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
    else WifiManager.WIFI_MODE_FULL_HIGH_PERF
    wm.createWifiLock(mode, "pdfsync").apply {
        setReferenceCounted(false)
        acquire()
    }
} catch (e: Exception) {
    null
}
