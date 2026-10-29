package org.example.otppager

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

/**
 * Shared look for the everyday screens. Black on white with rounded shapes and a clean sans-serif:
 * modern, but still high-contrast and colour-free so it reads well on e-ink. Monospace is kept for
 * codes and technical values only.
 */
internal object UiStyle {
    val ink = Color.rgb(17, 17, 17)
    val muted = Color.rgb(88, 88, 88)
    val line = Color.rgb(205, 205, 205)
    val paper = Color.WHITE
    val onInkMuted = Color.rgb(205, 205, 205)
    val body: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    const val BUTTON_RADIUS = 14
    const val CARD_RADIUS = 16
    const val BUBBLE_RADIUS = 18
    fun shape(fill: Int, radiusPx: Float, border: Int? = null, strokePx: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = radiusPx; if (border != null && strokePx > 0) setStroke(strokePx, border)
    }
    /** Touch feedback over [content]; the ripple is clipped to the same rounded shape. */
    fun pressable(content: Drawable, radiusPx: Float): Drawable =
        RippleDrawable(ColorStateList.valueOf(Color.argb(48, 0, 0, 0)), content, shape(Color.BLACK, radiusPx))
}
