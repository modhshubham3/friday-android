package dev.friday.assistant.ui

import android.graphics.Color

internal fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

/** The same colour turned round the colour wheel, for a second hue that goes with it. */
internal fun shiftHue(color: Int, degrees: Float): Int {
    val hsv = FloatArray(3)
    Color.colorToHSV(color, hsv)
    hsv[0] = (hsv[0] + degrees + 360f) % 360f
    hsv[1] = hsv[1].coerceAtLeast(0.45f) // a grey stays a little coloured, or the second hue would be invisible
    hsv[2] = hsv[2].coerceAtLeast(0.7f)
    return Color.HSVToColor(hsv)
}

internal fun mix(color: Int, with: Int, amount: Float): Int {
    val a = amount.coerceIn(0f, 1f)
    fun channel(x: Int, y: Int) = (x + (y - x) * a).toInt()
    return Color.rgb(
        channel(Color.red(color), Color.red(with)),
        channel(Color.green(color), Color.green(with)),
        channel(Color.blue(color), Color.blue(with)),
    )
}
