package app.aduster.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import app.aduster.ICore
import app.aduster.vpn.AdusterVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Binds the UI process to the :core process while the UI is visible (onStart..onStop), so the
 * UI never keeps the core alive or busy in the background.
 */
class CoreClient(private val app: Context) {
    private val _core = MutableStateFlow<ICore?>(null)
    val core: StateFlow<ICore?> = _core
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            _core.value = ICore.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            _core.value = null
        }

        override fun onBindingDied(name: ComponentName) {
            unbind()
            bind()
        }
    }

    fun bind() {
        if (bound) return
        bound = app.bindService(
            Intent(app, AdusterVpnService::class.java).setAction(AdusterVpnService.ACTION_BIND),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    fun unbind() {
        if (!bound) return
        try {
            app.unbindService(connection)
        } catch (_: IllegalArgumentException) {
        }
        bound = false
        _core.value = null
    }

    fun <T> call(default: T, f: (ICore) -> T): T {
        val c = _core.value ?: return default
        return try {
            f(c)
        } catch (_: RemoteException) {
            default
        } catch (_: RuntimeException) {
            default
        }
    }
}
