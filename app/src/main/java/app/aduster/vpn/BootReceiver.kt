package app.aduster.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import app.aduster.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restores protection after a reboot or an app update when it was on. With Always-on VPN the
 * system starts the service as well; both paths end in the same start, which does nothing the
 * second time.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val s = SettingsStore.get(context).data.first()
                val from = if (intent.action == Intent.ACTION_BOOT_COMPLETED) "at boot" else "after an app update"
                if (s.enabled && VpnService.prepare(context) == null) AdusterVpnService.start(context, from)
            } finally {
                pending.finish()
            }
        }
    }
}
