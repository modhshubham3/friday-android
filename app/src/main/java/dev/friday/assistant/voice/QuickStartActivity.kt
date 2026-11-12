package dev.friday.assistant.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.friday.assistant.MainActivity
import dev.friday.assistant.Settings

/**
 * Invisible stepping stone for the notification shade tile. Android refuses to start the microphone
 * service straight from the tile (the voice log showed exactly that), but allows it from a screen the user
 * can see. So the tile opens this see-through screen, which starts the service and closes itself at once.
 */
class QuickStartActivity : Activity() {

    override fun onResume() {
        super.onResume()
        val ready = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED && ModelStore.isReady(this)
        if (ready) {
            Settings(this).voiceWanted = true
            FridayService.start(this)
        } else {
            startActivity(Intent(this, MainActivity::class.java)) // setup is missing: do it in the app
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
