package dev.friday.assistant.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.friday.assistant.MainActivity
import dev.friday.assistant.Settings

/**
 * Invisible stepping stone for the notification shade tile. Android refuses to start the microphone
 * service straight from the tile (the voice log showed exactly that), but allows it from a screen the user
 * can see. So the tile opens this see-through screen, which starts the service.
 *
 * It stays open until the service says it is running: Android decides whether the microphone service may
 * start at the moment the service asks, which is a little after this screen asked for it, and a screen that
 * had already closed by then would be too late.
 */
class QuickStartActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var checks = 0

    override fun onResume() {
        super.onResume()
        val ready = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED && ModelStore.isReady(this)
        if (!ready) {
            startActivity(Intent(this, MainActivity::class.java)) // setup is missing: do it in the app
            close()
            return
        }
        Settings(this).voiceWanted = true
        FridayService.start(this)
        waitForService()
    }

    private fun waitForService() {
        if (FridayService.running || checks++ >= MAX_CHECKS) {
            close()
        } else {
            handler.postDelayed({ waitForService() }, 100)
        }
    }

    private fun close() {
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val MAX_CHECKS = 30 // three seconds
    }
}
