package app.aduster.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import app.aduster.settings.ThemeMode
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aduster.ui.theme.AdusterTheme

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_REQUEST_VPN = "request_vpn"
        private const val UI_PREFS = "ui"
        private const val LIGHT_WINDOW = "light_window"
    }

    private val vm: MainViewModel by viewModels()

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        // Cancelled, or refused without a dialog because another app is the Always-on VPN: Home
        // says so instead of doing nothing.
        if (r.resultCode == RESULT_OK) vm.start() else vm.vpnRefused.value = true
    }

    // Whatever the answer, the VPN consent comes next: one system dialog at a time.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { askVpnConsent() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The window takes the theme the app last showed before the settings are read, so a Light
        // start is light from its first frame (the settings arrive a moment later).
        val lightWindow = getSharedPreferences(UI_PREFS, MODE_PRIVATE).getBoolean(LIGHT_WINDOW, false)
        if (lightWindow) setTheme(app.aduster.R.style.Theme_Aduster_Light)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val read by vm.settingsRead.collectAsStateWithLifecycle()
            // Until the stored settings are read, the theme the window opened with (the
            // placeholder settings would draw one Black frame over a Light window).
            val theme = if (read) settings.theme else if (lightWindow) ThemeMode.LIGHT else ThemeMode.BLACK
            // Status and navigation bar icons follow the app's theme, not the system's: Black
            // with a light system theme would otherwise draw dark icons on black.
            val dark = when (theme) {
                ThemeMode.BLACK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            LaunchedEffect(dark, read) {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                // Remembered for the next cold start (this activity's window, and Android's
                // launch screen before it), from the stored settings only.
                if (read) {
                    getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putBoolean(LIGHT_WINDOW, !dark).apply()
                    runCatching { splashScreen.setSplashScreenTheme(if (dark) app.aduster.R.style.Theme_Aduster else app.aduster.R.style.Theme_Aduster_Light) }
                }
            }
            AdusterTheme(theme) {
                AdusterApp(vm, onStart = ::requestStart)
            }
        }
        // Only for a new start, not when the activity is recreated (rotation, theme change):
        // protection turned off meanwhile must not be switched on again.
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        vm.client.bind()
    }

    override fun onStop() {
        vm.client.unbind()
        super.onStop()
    }

    /**
     * The tile's "turn on" request: handled once, then removed from the intent. Any app could
     * send it, but it can only turn protection on, so it is left open.
     */
    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REQUEST_VPN, false) == true) {
            intent.removeExtra(EXTRA_REQUEST_VPN)
            requestStart()
        }
    }

    /** Notification permission (for the status card), then VPN consent, then start. */
    private fun requestStart() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askVpnConsent()
        }
    }

    private fun askVpnConsent() {
        val consent = VpnService.prepare(this)
        if (consent != null) vpnConsent.launch(consent) else vm.start()
    }
}
