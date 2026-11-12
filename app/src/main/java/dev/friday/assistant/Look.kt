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

    fun theme(id: String) = THEMES.firstOrNull { it.id == id } ?: THEMES[0]
    fun size(id: String) = SIZES.firstOrNull { it.id == id } ?: SIZES[1]
}
