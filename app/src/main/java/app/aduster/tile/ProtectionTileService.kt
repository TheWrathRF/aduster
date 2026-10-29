package app.aduster.tile

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.aduster.ui.MainActivity
import app.aduster.vpn.AdusterVpnService

/**
 * Active tile (runs in :core): bound only while the shade is open or when we request a refresh.
 * Tap: Pause while protected, Resume while paused, Turn on while off. Long-press opens the app
 * (turning protection off is done there).
 */
class ProtectionTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        when {
            AdusterVpnService.isPaused() -> AdusterVpnService.resume(this)
            AdusterVpnService.running -> AdusterVpnService.pause(this)
            VpnService.prepare(this) == null -> AdusterVpnService.start(this, "from the Quick Settings tile")
            else -> {
                val ask = Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_REQUEST_VPN, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // The PendingIntent form exists from Android 14 (which refuses the Intent form).
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    startActivityAndCollapse(PendingIntent.getActivity(this, 11, ask, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                } else {
                    @Suppress("DEPRECATION")
                    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
                    startActivityAndCollapse(ask)
                }
            }
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val running = AdusterVpnService.running
        val paused = AdusterVpnService.isPaused()
        tile.state = if (running && !paused) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when {
            paused -> "Paused"
            running -> "Protected"
            else -> "Off"
        }
        tile.updateTile()
    }
}
