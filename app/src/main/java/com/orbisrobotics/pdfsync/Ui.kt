package com.orbisrobotics.pdfsync

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object Palette {
    const val ACCENT = 0xFF3F51B5.toInt()
    const val ACCENT_DARK = 0xFF303F9F.toInt()
    const val ACCENT_LIGHT = 0xFFE8EAF6.toInt()
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
