package dev.friday.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import dev.friday.assistant.voice.Mode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Friday's face: a glowing orb that is always slightly alive and changes with what Friday is doing.
 *
 *  - idle: breathes slowly
 *  - wake word heard: a ring bursts outwards
 *  - listening: rings spread out and the orb swells with the user's voice
 *  - thinking: two arcs spin around it
 *  - speaking: it pulses and its edge ripples
 *
 * It is drawn by hand each frame (no images), so it takes any size and any theme colour. When the phone is set
 * to remove animations it is drawn once and holds still.
 */
class OrbView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var mode: Mode = Mode.IDLE
        set(value) {
            if (field != value) {
                field = value
                modeSince = SystemClock.uptimeMillis()
            }
            invalidate()
        }

    /** 0 to 1: how loud the user is. Only matters while listening. */
    var level = 0f

    var accent: Int = 0xFF4DA3FF.toInt()
        set(value) {
            field = value
            accent2 = shiftHue(value, 48f)
            buildShaders()
            invalidate()
        }

    private var accent2 = shiftHue(accent, 48f)
    private val born = SystemClock.uptimeMillis()
    private var modeSince = born
    private var smooth = 0f
    private var base = 0f

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val GLOW = 2.6f // how far the glow reaches, in orb radii
    private val oval = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The glow reaches 2.6 times this, and nothing may be drawn past the edge of the view or it shows as a
        // hard square cut, so the orb itself takes only about a third of the width.
        base = min(w, h) / 2f * 0.37f
        buildShaders()
    }

    /** Gradients are built once per size and colour and moved with the canvas, never rebuilt per frame. */
    private fun buildShaders() {
        if (base <= 0f) return
        glowPaint.shader = RadialGradient(
            0f, 0f, base * GLOW,
            intArrayOf(withAlpha(accent, 150), withAlpha(accent2, 55), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        // A highlight towards the top left makes it read as a sphere rather than a flat disc.
        corePaint.shader = RadialGradient(
            -base * 0.32f, -base * 0.36f, base * 1.45f,
            intArrayOf(mix(accent, Color.WHITE, 0.72f), accent, mix(accent2, Color.BLACK, 0.45f)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        arcPaint.shader = SweepGradient(
            0f, 0f,
            intArrayOf(Color.TRANSPARENT, accent2, Color.TRANSPARENT),
            floatArrayOf(0f, 0.28f, 0.58f),
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (base <= 0f) return
        val now = SystemClock.uptimeMillis()
        val t = (now - born) / 1000f
        val sinceMode = (now - modeSince) / 1000f

        smooth += (level - smooth) * 0.2f
        val activity = when (mode) {
            Mode.IDLE -> 0.05f + 0.03f * sin(t * 1.1f)
            Mode.LISTENING -> 0.18f + smooth * 0.82f
            Mode.THINKING -> 0.28f
            // No loudness is available from the speech engine, so the pulse is made up, but it reads as speech.
            Mode.SPEAKING -> 0.35f + 0.3f * (0.5f + 0.5f * sin(t * 8.3f)) * (0.65f + 0.35f * sin(t * 2.3f))
            Mode.WAKE -> 0.7f * (1f - (sinceMode / 0.7f).coerceIn(0f, 1f)) + 0.15f
        }
        val breathe = 1f + 0.035f * sin(t * 1.6f)
        val scale = breathe * (1f + activity * 0.17f)

        canvas.save()
        canvas.translate(width / 2f, height / 2f)

        // Glow
        glowPaint.alpha = (95 + 150 * activity).toInt().coerceIn(0, 255)
        canvas.drawCircle(0f, 0f, base * GLOW * (1f + activity * 0.1f), glowPaint)

        // Rings spreading outwards
        val rings = when (mode) {
            Mode.LISTENING, Mode.WAKE -> 3
            Mode.SPEAKING -> 2
            else -> 0
        }
        for (i in 0 until rings) {
            val phase = (t * 0.6f + i / rings.toFloat()) % 1f
            ringPaint.color = accent
            ringPaint.alpha = ((1f - phase) * (60 + 160 * activity)).toInt().coerceIn(0, 255)
            ringPaint.strokeWidth = base * 0.03f
            canvas.drawCircle(0f, 0f, ringRadius(scale, phase), ringPaint)
        }
        if (mode == Mode.WAKE) { // one bright ring that bursts out when the name is heard
            val p = (sinceMode / 0.65f).coerceIn(0f, 1f)
            ringPaint.color = accent2
            ringPaint.alpha = ((1f - p) * 220).toInt().coerceIn(0, 255)
            ringPaint.strokeWidth = base * 0.07f * (1f - p * 0.6f)
            canvas.drawCircle(0f, 0f, ringRadius(scale, p), ringPaint)
        }

        // The orb itself, drawn at unit size and scaled so its gradient does not need rebuilding
        canvas.save()
        canvas.scale(scale, scale)
        canvas.drawCircle(0f, 0f, base, corePaint)
        canvas.restore()

        // A wobbling outline, calm when idle and lively when there is sound
        path.rewind()
        val points = 72
        val amp = base * (0.012f + 0.115f * activity)
        val r0 = base * scale * 1.07f
        for (i in 0..points) {
            val a = (i * 2.0 * PI / points).toFloat()
            val wobble = sin(3f * a + t * 2.4f) + 0.6f * sin(5f * a - t * 1.9f) + 0.4f * sin(2f * a + t * 3.1f)
            val r = r0 + amp * wobble
            if (i == 0) path.moveTo(r * cos(a), r * sin(a)) else path.lineTo(r * cos(a), r * sin(a))
        }
        path.close()
        ringPaint.color = accent2
        ringPaint.alpha = (120 + 110 * activity).toInt().coerceIn(0, 255)
        ringPaint.strokeWidth = base * 0.04f
        canvas.drawPath(path, ringPaint)

        // Two arcs circling while Friday thinks
        if (mode == Mode.THINKING) {
            for (k in 0..1) {
                canvas.save()
                canvas.rotate(if (k == 0) t * 290f else -t * 210f)
                val r = base * scale * (1.3f + 0.18f * k)
                oval.set(-r, -r, r, r)
                arcPaint.strokeWidth = base * (0.075f - 0.02f * k)
                canvas.drawArc(oval, 0f, 210f - 60f * k, false, arcPaint)
                canvas.restore()
            }
        }
        canvas.restore()

        if (ValueAnimator.areAnimatorsEnabled() && isShown) postInvalidateOnAnimation()
    }

    /** A ring grows from just outside the orb to the edge of the glow (a [phase] of 0 to 1) and never beyond it. */
    private fun ringRadius(scale: Float, phase: Float): Float {
        val from = base * scale * 1.1f
        return from + phase * (base * GLOW - from)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        invalidate()
    }
}
