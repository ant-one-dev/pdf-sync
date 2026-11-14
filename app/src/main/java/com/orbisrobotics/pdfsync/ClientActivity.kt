package com.orbisrobotics.pdfsync

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.util.UUID

/**
 * Tablette participant : rejoint le réseau de l'Expert et affiche la même page.
 * Le zoom reste local. Le document est effacé dès qu'on quitte ou que la séance se termine.
 */
class ClientActivity : Activity(), SyncClient.Listener {

    private lateinit var pageView: PageView
    private lateinit var pages: PageController
    private lateinit var statusText: TextView
    private lateinit var pageText: TextView
    private lateinit var messageText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var dir: File
    private var client: SyncClient? = null
    private var join: WifiDirectJoin? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var currentFile: File? = null
    private var wifiStatus = ""
    private var linkStatus = ""
    private var linked = false
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("client", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        buildUi()
        pages = PageController(this, pageView)
        pages.onPageShown = { i, n -> pageText.text = "Page ${i + 1} / $n" }

        dir = Storage.clientDir(this)
        dir.deleteRecursively()
        wifiLock = acquireWifiLock()
        // Android 7 à 9 : connexion manuelle au réseau DIRECT-OR-… dans les réglages
        if (Build.VERSION.SDK_INT >= 29) {
            join = WifiDirectJoin(this) { text, _ ->
                wifiStatus = text
                refreshStatus()
            }.also { it.start() }
        }
        askName(null)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        client?.stop()
        join?.stop()
        pages.close()
        wifiLock?.release()
        dir.deleteRecursively()
        super.onDestroy()
    }

    // identifiant aléatoire de la tablette, sert à la réadmettre après une coupure
    private fun deviceId(): String = prefs.getString("device_id", null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }

    private fun askName(message: String?) {
        val field = EditText(this).apply {
            hint = "Votre nom (vu par le présentateur)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            filters = arrayOf(InputFilter.LengthFilter(40))
            setText(prefs.getString("name", ""))
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(field)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Rejoindre la présentation")
            .setMessage(message ?: "Le présentateur verra votre nom et validera votre tablette.")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Rejoindre", null)
            .setNegativeButton("Quitter") { _, _ -> finish() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = field.text.toString().trim()
                if (name.isEmpty()) {
                    field.error = "Indiquez votre nom"
                    return@setOnClickListener
                }
                prefs.edit().putString("name", name).apply()
                dialog.dismiss()
                client?.stop()
                client = SyncClient(deviceId(), name, dir, this).also { it.start() }
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    private fun clearDocument(message: String) {
        pages.clear()
        currentFile = null
        pageText.text = ""
        progress.visibility = View.GONE
        dir.listFiles()?.forEach { it.delete() }
        messageText.text = message
        messageText.visibility = View.VISIBLE
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(12), dp(8))
        }
        statusText = label("", 13f, Palette.MUTED).apply {
            maxLines = 1
            setPadding(dp(16), 0, dp(8), 0)
        }
        top.addView(statusText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pageText = label("", 16f, Palette.ACCENT_DARK, bold = true).apply { setPadding(dp(8), 0, dp(12), 0) }
        top.addView(pageText)
        top.addView(button("Vue entière") { pageView.resetZoom() }, startMargin(dp(8)))
        top.addView(button("Quitter") { finish() }, startMargin(dp(8)))
        root.addView(top)
        val line = View(this).apply { setBackgroundColor(Palette.ACCENT) }
        root.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)))

        val area = FrameLayout(this)
        pageView = PageView(this)
        area.addView(pageView)
        messageText = label("En attente du document de l'Expert…", 20f, Palette.TEXT).apply {
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), 0)
        }
        area.addView(messageText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(Palette.ACCENT)
        }
        area.addView(progress, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(8), Gravity.BOTTOM))
        root.addView(area, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun refreshStatus() {
        // une fois relié à l'Expert l'état du Wi-Fi Direct n'intéresse plus personne
        statusText.text = when {
            linked || wifiStatus.isEmpty() -> linkStatus
            linkStatus.isEmpty() -> wifiStatus
            else -> "$wifiStatus  •  $linkStatus"
        }
        statusText.setTextColor(if (linked) Palette.OK else Palette.ACCENT_DARK)
    }

    override fun onStatus(text: String, connected: Boolean) = runOnUiThread {
        linkStatus = text
        linked = connected
        refreshStatus()
        if (connected && currentFile == null) {
            messageText.visibility = View.VISIBLE
            messageText.text = "Connecté.\nEn attente du document…"
        }
    }

    override fun onPdfProgress(received: Long, total: Long) = runOnUiThread {
        if (received >= total) {
            progress.visibility = View.GONE
            return@runOnUiThread
        }
        progress.visibility = View.VISIBLE
        progress.progress = if (total > 0) (received * 1000 / total).toInt() else 0
        if (currentFile == null) messageText.text = "Réception du document… ${received * 100 / maxOf(total, 1)} %"
    }

    override fun onPdfReceived(file: File, name: String) = runOnUiThread {
        val previous = currentFile
        pages.open(file, pages.current, onOpened = {
            currentFile = file
            messageText.visibility = View.GONE
            if (previous != null && previous != file) previous.delete()
        }, onError = { e ->
            messageText.visibility = View.VISIBLE
            messageText.text = "Document illisible : ${e.message}"
        })
    }

    override fun onPage(page: Int) = runOnUiThread { pages.show(page) }


    override fun onSessionEnded() = runOnUiThread {
        linked = false
        linkStatus = "Présentation terminée"
        refreshStatus()
        clearDocument("La présentation est terminée.\nLe document a été effacé de cette tablette.")
        askName("La présentation est terminée. Vous pourrez rejoindre la suivante.")
    }
}
