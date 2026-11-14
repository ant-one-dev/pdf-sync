package com.orbisrobotics.pdfsync

import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.os.Build

/**
 * Réseau Wi-Fi Direct commun à toutes les tablettes. Nom et mot de passe sont fixés
 * à l'avance (Android 10+) pour que les Clients se connectent sans popup de confirmation.
 */
object NetworkSettings {
    /** Dans un groupe Wi-Fi Direct, le propriétaire du groupe est toujours en .49.1 */
    const val HOST_IP = "192.168.49.1"
    const val MIN_PASS = 12
    private const val PREFS = "network"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Android impose le préfixe DIRECT-xy-
    fun ssid(ctx: Context) = "DIRECT-OR-" + room(ctx)

    fun room(ctx: Context): String = prefs(ctx).getString("room", "") ?: ""

    fun passphrase(ctx: Context): String =
        prefs(ctx).getString("pass", null) ?: ""

    fun isConfigured(ctx: Context) = room(ctx).isNotEmpty() && passphrase(ctx).length >= MIN_PASS

    fun save(ctx: Context, room: String, pass: String) {
        prefs(ctx).edit()
            .putString("room", room)
            .putString("pass", pass)
            .apply()
    }

    fun p2pConfig(ctx: Context): WifiP2pConfig? =
        if (Build.VERSION.SDK_INT >= 29) {
            WifiP2pConfig.Builder()
                .setNetworkName(ssid(ctx))
                .setPassphrase(passphrase(ctx))
                .build()
        } else null
}
