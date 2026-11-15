package com.orbisrobotics.pdfsync

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

object Palette {
    // Orbis Robotics : bleu électrique / blanc
    const val ACCENT = 0xFF1A56FF.toInt()
    const val ACCENT_DARK = 0xFF0B3AC4.toInt()
    const val ACCENT_LIGHT = 0xFFEAF0FF.toInt()
    const val TEXT = 0xFF0F172A.toInt()
    const val MUTED = 0xFF64748B.toInt()
    const val DIVIDER = 0xFFE2E8F0.toInt()
    const val PAGE_BG = 0xFFEEF1F5.toInt()
    const val OK = 0xFF15803D.toInt()
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.button(text: String, filled: Boolean = false, onClick: () -> Unit) = Button(this).apply {
    this.text = text
    setOnClickListener { onClick() }
    setFilled(filled)
}

fun Button.setFilled(filled: Boolean) {
    val radius = context.dp(10).toFloat()
    val shape = GradientDrawable().apply {
        cornerRadius = radius
        setColor(if (filled) Palette.ACCENT else Color.WHITE)
        setStroke(context.dp(2), Palette.ACCENT)
    }
    val mask = GradientDrawable().apply {
        cornerRadius = radius
        setColor(Color.WHITE)
    }
    val ripple = if (filled) 0x40FFFFFF else (Palette.ACCENT and 0xFFFFFF) or 0x30000000
    background = RippleDrawable(ColorStateList.valueOf(ripple), shape, mask)
    setTextColor(if (filled) Color.WHITE else Palette.ACCENT_DARK)
    isAllCaps = false
    textSize = 15f
    stateListAnimator = null
    minHeight = context.dp(44)
    minimumHeight = context.dp(44)
    setPadding(context.dp(16), 0, context.dp(16), 0)
}

fun Context.label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
}

fun startMargin(px: Int) = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
).apply { marginStart = px }

/** Logo : pictogramme + « ORBIS ROBOTICS ». */
fun Context.logo(size: Int) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    addView(ImageView(context).apply { setImageResource(R.drawable.ic_orbis) },
        LinearLayout.LayoutParams(dp(size), dp(size)))
    addView(label("ORBIS", size * 0.42f, Palette.TEXT, bold = true).apply {
        letterSpacing = 0.12f
        setPadding(dp(10), 0, 0, 0)
    })
    addView(label("ROBOTICS", size * 0.42f, Palette.ACCENT).apply {
        letterSpacing = 0.12f
        setPadding(dp(6), 0, 0, 0)
    })
}

/**
 * Android 15 (targetSdk 35) force le mode edge-to-edge : sans ça le haut de l'appli
 * passe sous la barre d'état. On ajoute les insets en padding sur la vue racine.
 */
fun Activity.applySafeArea(root: View) {
    if (Build.VERSION.SDK_INT >= 30) {
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            WindowInsets.CONSUMED
        }
        root.requestApplyInsets()
    } else {
        root.fitsSystemWindows = true
    }
}

@Suppress("DEPRECATION")
fun Activity.hideSystemBars(hide: Boolean) {
    if (Build.VERSION.SDK_INT >= 30) {
        val c = window.insetsController ?: return
        if (hide) {
            c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsets.Type.systemBars())
        } else {
            c.show(WindowInsets.Type.systemBars())
        }
    } else {
        window.decorView.systemUiVisibility = if (hide) {
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        } else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }
}
