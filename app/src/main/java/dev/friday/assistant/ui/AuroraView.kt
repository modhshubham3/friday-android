package dev.friday.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * A slow, soft drift of coloured light behind everything, so the screen is never quite still.
 * Three big blurred blobs wander on their own paths. It redraws about 25 times a second rather than 60,
 * since nothing here needs to be fast and it is on screen all the time.
 */
class AuroraView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var intensity = 0f
    private var colors = intArrayOf(Color.BLUE, Color.CYAN, Color.MAGENTA)
    private var radius = 0f
    private val paints = Array(3) { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val born = SystemClock.uptimeMillis()

    /** [strength] 0 turns it off completely; around 0.15 is a hint of colour, 0.3 is clearly visible. */
    fun configure(accent: Int, strength: Float) {
        intensity = strength
        colors = intArrayOf(accent, shiftHue(accent, 65f), shiftHue(accent, -75f))
        visibility = if (strength > 0f) VISIBLE else GONE
        rebuild()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        radius = max(w, h) * 0.55f
        rebuild()
    }

    private fun rebuild() {
        if (radius <= 0f) return
        for (i in 0..2) {
            paints[i].shader = RadialGradient(0f, 0f, radius, withAlpha(colors[i], 255), withAlpha(colors[i], 0), Shader.TileMode.CLAMP)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (intensity <= 0f || radius <= 0f) return
        val t = (SystemClock.uptimeMillis() - born) / 1000f
        for (i in 0..2) {
            val x = width * (0.5f + 0.42f * sin(t * 0.11f + i * 2.1f))
            val y = height * (0.5f + 0.38f * cos(t * 0.083f * (i + 1) + i * 1.3f))
            paints[i].alpha = (intensity * 255f * (0.8f + 0.2f * sin(t * 0.3f + i))).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.translate(x, y)
            canvas.drawCircle(0f, 0f, radius * (0.75f + 0.25f * sin(t * 0.2f + i)), paints[i])
            canvas.restore()
        }
        if (ValueAnimator.areAnimatorsEnabled() && isShown) postInvalidateDelayed(40)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate()
    }
}
