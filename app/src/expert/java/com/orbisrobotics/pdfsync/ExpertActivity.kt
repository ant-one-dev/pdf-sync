package com.orbisrobotics.pdfsync

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

/** Tablette du présentateur : crée le réseau, ouvre le PDF, pilote les pages et le pointeur. */
class ExpertActivity : Activity(), SyncServer.Listener {

    private lateinit var pageView: PageView
    private lateinit var pages: PageController
    private lateinit var server: SyncServer
    private var hotspot: WifiDirectHost? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var pdfFile: File? = null
    private var connected = 0

    private lateinit var header: View
    private lateinit var headerLine: View
    private lateinit var titleText: TextView
    private lateinit var networkText: TextView
    private lateinit var statusText: TextView
    private lateinit var pageText: TextView
    private lateinit var pointerButton: Button
    private lateinit var thumbsScroll: HorizontalScrollView
    private lateinit var thumbsRow: LinearLayout
    private var thumbsDoc: PdfDocument? = null

    // demandes d'accès (id, nom), affichées une par une
    private val requests = ArrayDeque<Pair<Int, String>>()
    private var requestDialog: AlertDialog? = null
    private var shownRequest = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) // pas de capture d'écran
        buildUi()

        pages = PageController(this, pageView)
        pages.onPageShown = { i, n ->
            pageText.text = "${i + 1} / $n"
            highlightThumb(i)
        }
        pageView.onSwipe = { goTo(pages.current + it) }
        pageView.onPointerMoved = { x, y -> server.sendPointer(x, y) }

        server = SyncServer(this)
        server.start()
        hotspot = WifiDirectHost(this) { networkText.text = it }.also { it.start() }
        wifiLock = acquireWifiLock()
        updateStatus()
    }

    override fun onDestroy() {
        server.stop()
        // on laisse partir le END avant de couper le réseau
        val h = hotspot
        Handler(Looper.getMainLooper()).postDelayed({ h?.stop() }, 1500)
        pages.close()
        thumbsDoc?.close()
        wifiLock?.release()
        pdfFile?.delete() // copie interne, le fichier d'origine n'est pas touché
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(10))
        }
        header = top
        top.addView(button("Ouvrir un PDF", filled = true) { pickPdf() })
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(8), 0)
        }
        titleText = label("Aucun document", 16f, Palette.TEXT, bold = true).apply { maxLines = 1 }
        networkText = label("", 13f, Palette.ACCENT_DARK).apply { maxLines = 1 }
        statusText = label("", 13f, Palette.MUTED)
        statusText.setOnClickListener { showParticipants() }
        info.addView(titleText)
        info.addView(networkText)
        info.addView(statusText)
        top.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(button("Participants") { showParticipants() })
        root.addView(top)
        headerLine = View(this).apply { setBackgroundColor(Palette.ACCENT) }
        root.addView(headerLine, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)))

        pageView = PageView(this)
        root.addView(pageView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        thumbsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        thumbsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xFFF6F7F9.toInt())
            addView(thumbsRow)
            visibility = View.GONE
        }
        root.addView(thumbsScroll)

        root.addView(View(this).apply { setBackgroundColor(Palette.DIVIDER) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        bottom.addView(button("Vignettes") { toggleThumbs() })
        pointerButton = button("●  Pointeur") { togglePointer() }
        bottom.addView(pointerButton, startMargin(dp(8)))
        bottom.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bottom.addView(button("◀  Précédente", filled = true) { goTo(pages.current - 1) })
        pageText = label("– / –", 18f, Palette.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(110)
        }
        bottom.addView(pageText)
        bottom.addView(button("Suivante  ▶", filled = true) { goTo(pages.current + 1) })
        bottom.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bottom.addView(button("Terminer") { confirmEnd() }, startMargin(dp(8)))
        root.addView(bottom)

        setContentView(root)
    }

    private fun updateStatus() {
        var text = "$connected tablette(s) connectée(s)"
        val pending = requests.size + if (shownRequest >= 0) 1 else 0
        if (pending > 0) text += "  •  $pending demande(s) en attente"
        statusText.text = text
    }

    private fun nextRequest() {
        if (requestDialog?.isShowing == true || isFinishing) return
        val (id, name) = requests.removeFirstOrNull() ?: run {
            shownRequest = -1
            updateStatus()
            return
        }
        shownRequest = id
        updateStatus()
        requestDialog = AlertDialog.Builder(this)
            .setTitle("Demande d'accès")
            .setMessage("« $name » demande à rejoindre la présentation.\n\n" +
                "Vérifiez qu'il s'agit bien d'un participant avant d'accepter.")
            .setCancelable(false)
            .setPositiveButton("Accepter") { _, _ -> server.approve(id); requestDone() }
            .setNegativeButton("Refuser") { _, _ -> server.reject(id); requestDone() }
            .show()
    }

    private fun requestDone() {
        requestDialog = null
        shownRequest = -1
        nextRequest()
    }

    override fun onApprovalRequest(requestId: Int, name: String) = runOnUiThread {
        requests.addLast(requestId to name)
        nextRequest()
        updateStatus()
    }

    override fun onApprovalCancelled(requestId: Int) = runOnUiThread {
        requests.removeAll { it.first == requestId }
        if (shownRequest == requestId) {
            requestDialog?.dismiss()
            requestDone()
        }
        updateStatus()
    }

    private fun showParticipants() {
        val list = server.connectedClients()
        if (list.isEmpty()) {
            Toast.makeText(this, "Aucune tablette connectée", Toast.LENGTH_SHORT).show()
            return
        }
        val names = list.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Participants")
            .setItems(names) { _, which ->
                val (id, name) = list[which]
                AlertDialog.Builder(this)
                    .setTitle("Retirer « $name » ?")
                    .setMessage("Le document sera effacé de sa tablette. Elle devra être acceptée à nouveau pour revenir.")
                    .setPositiveButton("Retirer") { _, _ -> server.kick(id) }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    override fun onClientCountChanged(count: Int) = runOnUiThread {
        connected = count
        updateStatus()
    }

    private fun togglePointer() {
        pageView.pointerMode = !pageView.pointerMode
        pointerButton.text = if (pageView.pointerMode) "●  Pointeur actif" else "●  Pointeur"
        pointerButton.setFilled(pageView.pointerMode)
        if (!pageView.pointerMode) {
            pageView.setPointer(-1f, -1f)
            server.sendPointer(-1f, -1f)
        }
    }

    private fun confirmEnd() {
        AlertDialog.Builder(this)
            .setTitle("Terminer la présentation ?")
            .setMessage("Le document sera effacé de toutes les tablettes et le réseau sera fermé.")
            .setPositiveButton("Terminer") { _, _ -> finish() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = confirmEnd()

    private fun goTo(index: Int) {
        if (pages.pageCount == 0) return
        val i = index.coerceIn(0, pages.pageCount - 1)
        if (i == pages.current) return
        server.setPage(i) // les clients d'abord, ça réduit le décalage
        pages.show(i)
    }

    // télécommandes Bluetooth de présentation, clavier, boutons de volume
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                goTo(pages.current + 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_VOLUME_UP -> {
                goTo(pages.current - 1)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toggleThumbs() {
        thumbsScroll.visibility = if (thumbsScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        highlightThumb(pages.current)
    }

    private fun buildThumbnails(file: File, count: Int) {
        thumbsDoc?.close()
        thumbsRow.removeAllViews()
        val doc = PdfDocument() // moteur séparé pour ne pas ralentir la page principale
        thumbsDoc = doc
        val h = dp(96)
        for (i in 0 until count) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(3), dp(3), dp(3), dp(3))
                setOnClickListener { goTo(i) }
            }
            cell.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                setBackgroundColor(0xFFE2E8F0.toInt())
                minimumWidth = h * 7 / 10
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, h))
            cell.addView(label("${i + 1}", 11f, Palette.MUTED).apply { gravity = Gravity.CENTER })
            thumbsRow.addView(cell)
        }
        doc.open(file, { n ->
            for (i in 0 until n) doc.renderPage(i, h * 3 / 2) { bmp, idx ->
                val cell = thumbsRow.getChildAt(idx) as? LinearLayout ?: return@renderPage
                (cell.getChildAt(0) as? ImageView)?.setImageBitmap(bmp)
            }
        }, {})
        highlightThumb(pages.current)
    }

    private fun highlightThumb(index: Int) {
        for (i in 0 until thumbsRow.childCount) {
            thumbsRow.getChildAt(i).background = if (i == index) GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setStroke(dp(3), Palette.ACCENT)
            } else null
        }
        val cell = thumbsRow.getChildAt(index) ?: return
        thumbsScroll.post {
            thumbsScroll.smoothScrollTo((cell.left - (thumbsScroll.width - cell.width) / 2).coerceAtLeast(0), 0)
        }
    }

    @Suppress("DEPRECATION")
    private fun pickPdf() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/pdf")
        startActivityForResult(intent, REQ_PDF)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_PDF || resultCode != RESULT_OK || uri == null) return
        titleText.text = "Chargement…"
        thread {
            try {
                val name = displayName(uri)
                // copie interne : on garde la main sur le fichier pendant toute la séance
                val dest = Storage.expertCopy(this)
                contentResolver.openInputStream(uri)!!.use { input ->
                    dest.outputStream().use { input.copyTo(it, 64 * 1024) }
                }
                runOnUiThread { openPdf(dest, name) }
            } catch (e: Exception) {
                runOnUiThread {
                    titleText.text = "Aucun document"
                    Toast.makeText(this, "Impossible de lire le fichier : ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return "document.pdf"
    }

    private fun openPdf(file: File, name: String) {
        val previous = pdfFile
        pages.open(file, 0, onOpened = { n ->
            pdfFile = file
            titleText.text = name
            server.setPdf(file, name, pages.current)
            buildThumbnails(file, n)
            // l'ancien fichier peut encore être en cours d'envoi
            if (previous != null && previous != file) {
                Handler(Looper.getMainLooper()).postDelayed({ previous.delete() }, 60_000)
            }
        }, onError = { e ->
            titleText.text = "Aucun document"
            file.delete()
            Toast.makeText(this, "PDF illisible : ${e.message}", Toast.LENGTH_LONG).show()
        })
    }

    companion object {
        private const val REQ_PDF = 1
    }
}
