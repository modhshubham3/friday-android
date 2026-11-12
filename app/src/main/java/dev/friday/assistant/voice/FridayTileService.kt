package dev.friday.assistant.voice

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * A switch for "Hey Friday" in the notification shade. Android only lets a microphone service start
 * from a user action, and a tap on a tile counts, so this is how to turn listening back on without
 * opening the app, for instance after the phone has restarted or the system has stopped the service.
 */
class FridayTileService : TileService() {

    override fun onStartListening() = refresh()

    override fun onClick() {
        if (FridayService.running) {
            FridayService.stop(this)
        } else {
            // Starting the microphone service needs a visible screen, so go through one.
            openScreen(QuickStartActivity::class.java)
        }
        // The service takes a moment to start or stop, so show the state it is heading for.
        qsTile?.apply {
            state = if (FridayService.running) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            updateTile()
        }
    }

    private fun refresh() {
        qsTile?.apply {
            label = "Hey Friday"
            state = if (FridayService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @Suppress("DEPRECATION")
    private fun openScreen(screen: Class<*>) {
        val intent = Intent(this, screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
