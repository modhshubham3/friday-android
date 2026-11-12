package dev.friday.assistant.inbox

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import dev.friday.assistant.Settings

/** What is never stored or spoken, and when it is fine to read a message's words aloud. */
object Privacy {
    private val OTP = Regex("(?i)\\b(otp|one[- ]?time|verification code|security code|passcode|login code)\\b")
    private val CODE = Regex("(?i)\\b(code|pin)\\b.{0,20}\\b\\d{4,8}\\b|\\b\\d{4,8}\\b.{0,20}\\b(code|otp)\\b")

    /** Banking, payment and authenticator apps: nothing from them is ever kept. */
    private val BLOCKED = setOf(
        "com.phonepe.app", "net.one97.paytm", "com.google.android.apps.nbu.paisa.user", "in.org.npci.upiapp",
        "com.csam.icici.bank.imobile", "com.snapwork.hdfc", "com.sbi.SBIFreedomPlus", "com.sbi.lotusintouch",
        "com.axis.mobile", "com.google.android.apps.authenticator2", "com.microsoft.msa.authenticator",
        "com.authy.authy", "in.amazon.mShop.android.shopping.pay",
    )

    /** One-time passwords and anything from a blocked app is dropped before it is looked at. */
    fun shouldDrop(p: Parsed) = p.pkg in BLOCKED || OTP.containsMatchIn(p.text) || CODE.containsMatchIn(p.text)

    /**
     * Whether the words of a message may be read aloud. By default only when the sound goes to earbuds, a headset or
     * the car, so that nobody else in the room or in the car hears a private message. The user can force it either way.
     */
    fun mayReadContent(context: Context): Boolean = when (Settings(context).contentMode) {
        "always" -> true
        "never" -> false
        else -> privateOutput(context)
    }

    fun privateOutput(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        val private = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID, 26, // 26 is a Bluetooth LE headset
        )
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in private }
    }
}
