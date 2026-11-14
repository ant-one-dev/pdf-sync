package com.orbisrobotics.pdfsync

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper

// Pas de routeur : l'Expert crée un groupe Wi-Fi Direct (WPA2) et les Clients le rejoignent.

private fun reason(code: Int) = when (code) {
    WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct non supporté"
    WifiP2pManager.BUSY -> "Wi-Fi occupé"
    WifiP2pManager.ERROR -> "erreur interne, Wi-Fi activé ?"
    else -> "code $code"
}

/** Côté Expert : crée le groupe. onInfo est appelé sur le thread UI. */
@SuppressLint("MissingPermission")
class WifiDirectHost(private val ctx: Context, private val onInfo: (String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
    private val channel = manager?.initialize(ctx, Looper.getMainLooper(), null)
    private var attempts = 0
    private var stopped = false

    fun start() {
        if (manager == null || channel == null) {
            onInfo("Wi-Fi Direct indisponible sur cette tablette")
            return
        }
        onInfo("Création du réseau…")
        // un ancien groupe peut traîner (appli tuée), on repart de zéro
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = create()
            override fun onFailure(reason: Int) = create()
        })
    }

    private fun create() {
        if (stopped) return
        val m = manager ?: return
        val ch = channel ?: return
        val config = NetworkSettings.p2pConfig(ctx)
        if (config == null) {
            onInfo("L'Expert doit être sous Android 10 minimum")
            return
        }
        m.createGroup(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                handler.postDelayed({ showGroup() }, 1000)
            }

            override fun onFailure(code: Int) {
                attempts++
                if (attempts < 5 && !stopped) {
                    onInfo("Échec de création du réseau (${reason(code)}), nouvel essai…")
                    handler.postDelayed({ create() }, 2000)
                } else {
                    onInfo("Impossible de créer le réseau (${reason(code)})")
                }
            }
        })
    }

    private fun showGroup() {
        val m = manager ?: return
        val ch = channel ?: return
        m.requestGroupInfo(ch) { g ->
            if (g == null) {
                if (!stopped) handler.postDelayed({ showGroup() }, 1000)
            } else {
                onInfo("Réseau Wi-Fi Direct « ${g.networkName} » actif")
            }
        }
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        val m = manager ?: return
        val ch = channel ?: return
        m.removeGroup(ch, null)
        if (Build.VERSION.SDK_INT >= 27) ch.close()
    }
}

/** Côté Client (Android 10+) : rejoint le groupe et s'y reconnecte tout seul. */
@SuppressLint("MissingPermission")
class WifiDirectJoin(private val ctx: Context, private val onStatus: (String, Boolean) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
    private val channel = manager?.initialize(ctx, Looper.getMainLooper(), null)
    private var stopped = false
    private var connected = false
    private val retry = Runnable { connect() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
            val m = manager ?: return
            val ch = channel ?: return
            m.requestConnectionInfo(ch) { info ->
                if (stopped) return@requestConnectionInfo
                if (info != null && info.groupFormed && !info.isGroupOwner) {
                    connected = true
                    handler.removeCallbacks(retry)
                    onStatus("Wi-Fi Direct connecté", true)
                } else {
                    if (connected) onStatus("Réseau de l'Expert perdu, reconnexion…", false)
                    connected = false
                    retryIn(3000)
                }
            }
        }
    }

    fun start() {
        if (manager == null || channel == null) {
            onStatus("Wi-Fi Direct indisponible sur cette tablette", false)
            return
        }
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(receiver, filter)
        connect()
    }

    private fun retryIn(ms: Long) {
        if (stopped || connected) return
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, ms)
    }

    private fun connect() {
        if (stopped || connected) return
        val m = manager ?: return
        val ch = channel ?: return
        val config = NetworkSettings.p2pConfig(ctx) ?: return
        onStatus("Recherche du réseau « ${NetworkSettings.ssid(ctx)} »…", false)
        m.connect(ch, config, object : WifiP2pManager.ActionListener {
            // la vraie confirmation arrive par le broadcast, sinon on retente
            override fun onSuccess() = retryIn(15_000)

            override fun onFailure(code: Int) {
                onStatus("Connexion impossible (${reason(code)}), nouvel essai…", false)
                retryIn(3000)
            }
        })
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        try {
            ctx.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        val m = manager ?: return
        val ch = channel ?: return
        m.cancelConnect(ch, null)
        m.removeGroup(ch, null)
        if (Build.VERSION.SDK_INT >= 27) ch.close()
    }
}
