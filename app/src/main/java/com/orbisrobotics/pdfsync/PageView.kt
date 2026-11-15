package com.orbisrobotics.pdfsync

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * Affiche une page : pinch-zoom, déplacement, double tap, swipe pour changer de page.
 * Quand on zoome, la zone visible est re-rendue en pleine résolution (onViewportSettled).
 */
class PageView(context: Context) : View(context) {

    var onSwipe: ((direction: Int) -> Unit)? = null
    var onViewportSettled: ((token: Int, region: RectF, w: Int, h: Int) -> Unit)? = null

    private var page: Bitmap? = null
    private var detail: Bitmap? = null
    private val detailRegion = RectF()
    private val matrix = Matrix()
    private val values = FloatArray(9)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var fitScale = 1f
    private var token = 0
    private var scaling = false
    private val settle = Runnable { requestDetail() }

    // Pointeur : sur l'Expert le doigt déplace le point au lieu de zoomer.
    var pointerMode = false
    var onPointerMoved: ((x: Float, y: Float) -> Unit)? = null
    private var pointerX = -1f
    private var pointerY = -1f
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF1A1A.toInt() }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FF1A1A }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                scaling = true
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoomBy(d.scaleFactor, d.focusX, d.focusY)
                return true
            }
        })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (isZoomed()) {
                matrix.postTranslate(-dx, -dy)
                clamp()
                changed()
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaling || isZoomed()) return false
            if (abs(vx) > abs(vy) * 1.5f && abs(vx) > 600f) {
                onSwipe?.invoke(if (vx < 0) 1 else -1)
                return true
            }
            return false
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isZoomed()) resetZoom() else zoomBy(2.5f, e.x, e.y)
            return true
        }
    })

    init {
        setBackgroundColor(0xFFEEF1F5.toInt())
    }

    fun setPage(bmp: Bitmap) {
        page = bmp
        detail = null
        token++
        resetZoom()
    }

    fun clearPage() {
        page = null
        detail = null
        token++
        pointerX = -1f
        invalidate()
    }

    fun setDetail(forToken: Int, bmp: Bitmap, region: RectF) {
        if (forToken != token) return
        detail = bmp
        detailRegion.set(region)
        invalidate()
    }

    fun resetZoom() {
        val b = page ?: return
        if (width == 0 || height == 0) return
        fitScale = min(width / b.width.toFloat(), height / b.height.toFloat())
        matrix.setScale(fitScale, fitScale)
        matrix.postTranslate((width - b.width * fitScale) / 2f, (height - b.height * fitScale) / 2f)
        changed()
    }

    /** Position en coordonnées de page (0..1), x < 0 pour cacher. */
    fun setPointer(x: Float, y: Float) {
        pointerX = x
        pointerY = y
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetZoom()
    }

    private fun scale(): Float {
        matrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun isZoomed() = scale() > fitScale * 1.02f

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        if (page == null) return
        val cur = scale()
        val target = (cur * factor).coerceIn(fitScale, fitScale * 12f)
        matrix.postScale(target / cur, target / cur, fx, fy)
        clamp()
        changed()
    }

    // garde la page dans l'écran, centrée si plus petite
    private fun clamp() {
        val b = page ?: return
        val r = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
        matrix.mapRect(r)
        val w = width.toFloat()
        val h = height.toFloat()
        val dx = when {
            r.width() <= w -> (w - r.width()) / 2f - r.left
            r.left > 0 -> -r.left
            r.right < w -> w - r.right
            else -> 0f
        }
        val dy = when {
            r.height() <= h -> (h - r.height()) / 2f - r.top
            r.top > 0 -> -r.top
            r.bottom < h -> h - r.bottom
            else -> 0f
        }
        matrix.postTranslate(dx, dy)
    }

    private fun changed() {
        invalidate()
        removeCallbacks(settle)
        postDelayed(settle, 250)
    }

    private fun requestDetail() {
        val b = page ?: return
        if (width == 0 || height == 0) return
        val s = scale()
        if (s <= 1.05f) {
            // la page de base suffit à ce zoom
            if (detail != null) {
                detail = null
                invalidate()
            }
            return
        }
        val inv = Matrix()
        if (!matrix.invert(inv)) return
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        inv.mapRect(r)
        if (!r.intersect(0f, 0f, b.width.toFloat(), b.height.toFloat())) return
        val w = (r.width() * s).toInt()
        val h = (r.height() * s).toInt()
        if (w <= 0 || h <= 0) return
        onViewportSettled?.invoke(token, RectF(r.left / b.width, r.top / b.height, r.right / b.width, r.bottom / b.height), w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = page ?: return
        canvas.drawBitmap(b, matrix, paint)
        detail?.let { d ->
            val dst = RectF(detailRegion.left * b.width, detailRegion.top * b.height,
                detailRegion.right * b.width, detailRegion.bottom * b.height)
            matrix.mapRect(dst)
            canvas.drawBitmap(d, null, dst, paint)
        }
        if (pointerX >= 0f && pointerY >= 0f) {
            val pt = floatArrayOf(pointerX * b.width, pointerY * b.height)
            matrix.mapPoints(pt)
            val r = 9 * resources.displayMetrics.density
            canvas.drawCircle(pt[0], pt[1], r * 2.2f, haloPaint)
            canvas.drawCircle(pt[0], pt[1], r, pointerPaint)
        }
    }

    private fun toPage(x: Float, y: Float): FloatArray? {
        val b = page ?: return null
        val inv = Matrix()
        if (!matrix.invert(inv)) return null
        val pt = floatArrayOf(x, y)
        inv.mapPoints(pt)
        return floatArrayOf((pt[0] / b.width).coerceIn(0f, 1f), (pt[1] / b.height).coerceIn(0f, 1f))
    }

    private fun pointerTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val p = toPage(e.x, e.y) ?: return
                setPointer(p[0], p[1])
                onPointerMoved?.invoke(p[0], p[1])
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                setPointer(-1f, -1f)
                onPointerMoved?.invoke(-1f, -1f)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pointerMode) {
            pointerTouch(event)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) scaling = false
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }
}
