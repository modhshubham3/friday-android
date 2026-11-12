package dev.friday.assistant.inbox

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.friday.assistant.Settings
import dev.friday.assistant.voice.VoiceLog

/** While on, important messages are read out as they arrive. It turns on by itself when the phone joins the car's Bluetooth. */
object DriveMode {
    fun set(context: Context, on: Boolean, reason: String = "voice") {
        val app = context.applicationContext
        Settings(app).driveMode = on
        VoiceLog.add(app, "Drive mode ${if (on) "on" else "off"} ($reason)")
        if (on) Announcer.request(app) // anything important that arrived before it was switched on
    }
}

/** Switches drive mode with the car: on when one of the chosen Bluetooth devices connects, off when it disconnects. */
class DriveBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val address = device?.address ?: return
        if (address !in Settings(context).carDevices) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> DriveMode.set(context, true, "car connected")
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> DriveMode.set(context, false, "car disconnected")
        }
    }
}
