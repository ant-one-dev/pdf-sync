package com.orbisrobotics.pdfsync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Wrapper autour de PdfRenderer. PdfRenderer n'est pas thread-safe donc tout passe
 * par un seul thread, les bitmaps reviennent sur le thread UI.
 */
class PdfDocument {
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var fd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    fun open(file: File, onOpened: (pageCount: Int) -> Unit, onError: (Exception) -> Unit) = enqueue {
        try {
            release()
            fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val r = PdfRenderer(fd!!)
            renderer = r
            val n = r.pageCount
            ui.post { onOpened(n) }
        } catch (e: Exception) {
            release()
            ui.post { onError(e) }
        }
    }

    /** Page entière, plus grand côté = maxSide px. */
    fun renderPage(index: Int, maxSide: Int, done: (Bitmap, Int) -> Unit) = enqueue {
        val r = renderer ?: return@enqueue
        if (index !in 0 until r.pageCount) return@enqueue
        try {
            r.openPage(index).use { page ->
                val pw = page.width.toFloat()
                val ph = page.height.toFloat()
                var scale = maxSide / max(pw, ph)
                // on reste sous ~9 Mpx sinon OOM sur les tablettes d'entrée de gamme
                val px = pw * scale * ph * scale
                if (px > 9e6f) scale *= sqrt(9e6f / px)
                val bmp = whiteBitmap((pw * scale).toInt(), (ph * scale).toInt())
                page.render(bmp, null, Matrix().apply { setScale(bmp.width / pw, bmp.height / ph) },
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                ui.post { done(bmp, index) }
            }
        } catch (_: Exception) {
        } catch (_: OutOfMemoryError) {
        }
    }

    /** Zone de la page (coordonnées 0..1) rendue en w x h, pour le zoom. */
    fun renderRegion(index: Int, region: RectF, w: Int, h: Int, done: (Bitmap, Int) -> Unit) = enqueue {
        val r = renderer ?: return@enqueue
        if (index !in 0 until r.pageCount) return@enqueue
        try {
            r.openPage(index).use { page ->
                var bw = w.toFloat()
                var bh = h.toFloat()
                if (bw * bh > 12e6f) {
                    val k = sqrt(12e6f / (bw * bh))
                    bw *= k; bh *= k
                }
                val bmp = whiteBitmap(bw.toInt(), bh.toInt())
                val pw = page.width.toFloat()
                val ph = page.height.toFloat()
                val sx = bmp.width / (region.width() * pw)
                val sy = bmp.height / (region.height() * ph)
                val m = Matrix().apply {
                    setScale(sx, sy)
                    postTranslate(-region.left * pw * sx, -region.top * ph * sy)
                }
                page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                ui.post { done(bmp, index) }
            }
        } catch (_: Exception) {
        } catch (_: OutOfMemoryError) {
        }
    }

    fun closeDocument() = enqueue { release() }

    fun close() {
        enqueue { release() }
        worker.shutdown()
    }

    private fun whiteBitmap(w: Int, h: Int) =
        Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.WHITE) }

    private fun enqueue(block: () -> Unit) {
        try {
            worker.execute(block)
        } catch (_: RejectedExecutionException) {
            // déjà fermé
        }
    }

    private fun release() {
        try { renderer?.close() } catch (_: Exception) {}
        try { fd?.close() } catch (_: Exception) {}
        renderer = null
        fd = null
    }
}

/** Fait le lien entre un PdfDocument et la PageView : page courante + rendu net au zoom. */
class PageController(context: Context, private val view: PageView) {
    private val doc = PdfDocument()
    private val baseSize: Int

    var pageCount = 0
        private set
    var current = 0
        private set
    private var opening = false
    // incrémenté à chaque clear() pour ignorer les rendus en retard
    private var generation = 0

    var onPageShown: ((index: Int, count: Int) -> Unit)? = null

    init {
        val dm = context.resources.displayMetrics
        baseSize = minOf(3072, 2 * max(dm.widthPixels, dm.heightPixels))
        view.onViewportSettled = { token, region, w, h ->
            val gen = generation
            doc.renderRegion(current, RectF(region), w, h) { bmp, idx ->
                if (gen == generation && idx == current) view.setDetail(token, bmp, region)
            }
        }
    }

    fun open(file: File, startPage: Int, onOpened: (Int) -> Unit, onError: (Exception) -> Unit) {
        opening = true
        current = startPage
        val gen = generation
        doc.open(file, { n ->
            if (gen != generation) return@open
            opening = false
            pageCount = n
            show(current)
            onOpened(n)
        }, { e ->
            if (gen != generation) return@open
            opening = false
            onError(e)
        })
    }

    fun show(index: Int) {
        if (opening || pageCount == 0) {
            current = index // affichée quand le document sera ouvert
            return
        }
        val idx = index.coerceIn(0, pageCount - 1)
        current = idx
        val gen = generation
        doc.renderPage(idx, baseSize) { bmp, i ->
            if (gen == generation && i == current && pageCount > 0) {
                view.setPage(bmp)
                onPageShown?.invoke(i, pageCount)
            }
        }
    }

    fun clear() {
        generation++
        pageCount = 0
        current = 0
        opening = false
        doc.closeDocument()
        view.clearPage()
    }

    fun close() = doc.close()
}
