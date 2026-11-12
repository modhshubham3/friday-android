package dev.friday.assistant

import android.graphics.Color

/**
 * App appearance: colour themes and text sizes.
 * Colours and size steps are the same ones the health app uses, so both apps look related.
 */
data class Palette(
    val id: String,
    val label: String,
    val bg: Int,      // screen background
    val surface: Int, // Friday's bubbles, input field
    val line: Int,    // borders
    val ink: Int,     // main text
    val mute: Int,    // secondary text
    val accent: Int,  // buttons, your bubbles
)

/** A whole-app style layered on top of the colour theme. */
data class AppLook(val id: String, val label: String)

/**
 * The final answer to "how should the screen be painted", after the theme, the look
 * and the text size are combined. Views read only this, never the raw settings.
 */
data class Style(
    val bg: Int,
    val surface: Int,
    val line: Int,
    val ink: Int,
    val mute: Int,
    val accent: Int,
    val onAccent: Int,          // text or icon drawn on top of the accent colour
    val bubbleRadiusDp: Float,
    val plainReplies: Boolean,  // Friday's replies as bare text, no bubble
    val lightFont: Boolean,
    val lightBars: Boolean,     // true on a light background: dark status-bar icons
    val lively: Boolean,        // bigger, bouncier motion
    val scale: Float,
)

data class TextSize(val id: String, val label: String, val scale: Float)

object Look {
    private fun c(hex: String) = Color.parseColor(hex)
    private val INK = c("#f6f6f8")

    val THEMES = listOf(
        Palette("classic", "Classic", c("#09090b"), c("#1b1b21"), c("#26262e"), INK, c("#8d8d9a"), c("#c8ff2e")),
        Palette("midnight", "Midnight", c("#070b14"), c("#161f33"), c("#1f2a42"), INK, c("#8d99b3"), c("#5cc8ff")),
        Palette("forest", "Forest", c("#07100b"), c("#14241a"), c("#1f3326"), INK, c("#8ea594"), c("#8fe36b")),
        Palette("sunset", "Sunset", c("#120a0d"), c("#26161c"), c("#35202a"), INK, c("#b39a95"), c("#ffb347")),
        Palette("rose", "Rose", c("#120a12"), c("#251730"), c("#35214a"), INK, c("#a894b3"), c("#ff8ad1")),
        Palette("aurora", "Aurora", c("#05060d"), c("#151826"), c("#23273a"), INK, c("#9aa3c0"), c("#34f5c5")),
        Palette("calm", "Calm", c("#101615"), c("#1f2c29"), c("#2c3d39"), c("#eef4f1"), c("#93a8a1"), c("#9fd8c0")),
        Palette("pop", "Pop", c("#0d0a1f"), c("#251c52"), c("#3a2d7a"), INK, c("#a79fd6"), c("#ffd23f")),
    )

    val SIZES = listOf(
        TextSize("s", "Chhota", 0.9f),
        TextSize("m", "Normal", 1f),
        TextSize("l", "Bada", 1.12f),
        TextSize("xl", "Bahut bada", 1.25f),
    )

    val LOOKS = listOf(
        AppLook("std", "Standard"),
        AppLook("anim", "Animated"),
        AppLook("min", "Minimal"),
        AppLook("ios", "iOS Dark"),
        AppLook("iosl", "iOS Light"),
    )

    /** The two iOS looks bring their own colours and ignore the theme, as in the health app. */
    fun style(themeId: String, lookId: String, sizeId: String): Style {
        val p = theme(themeId)
        val scale = size(sizeId).scale
        return when (lookId) {
            "ios" -> Style(
                bg = c("#000000"), surface = c("#1c1c1e"), line = c("#38383a"), ink = c("#ffffff"),
                mute = c("#8e8e93"), accent = c("#0a84ff"), onAccent = c("#ffffff"),
                bubbleRadiusDp = 20f, plainReplies = false, lightFont = false, lightBars = false,
                lively = false, scale = scale,
            )
            "iosl" -> Style(
                bg = c("#f2f2f7"), surface = c("#ffffff"), line = c("#d1d1d6"), ink = c("#000000"),
                mute = c("#6c6c70"), accent = c("#007aff"), onAccent = c("#ffffff"),
                bubbleRadiusDp = 20f, plainReplies = false, lightFont = false, lightBars = true,
                lively = false, scale = scale,
            )
            else -> Style(
                bg = p.bg, surface = p.surface, line = p.line, ink = p.ink, mute = p.mute,
                accent = p.accent, onAccent = p.bg,
                bubbleRadiusDp = if (lookId == "min") 12f else 18f,
                plainReplies = lookId == "min", lightFont = lookId == "min", lightBars = false,
                lively = lookId == "anim", scale = scale,
            )
        }
    }

    fun theme(id: String) = THEMES.firstOrNull { it.id == id } ?: THEMES[0]
    fun size(id: String) = SIZES.firstOrNull { it.id == id } ?: SIZES[1]
}
