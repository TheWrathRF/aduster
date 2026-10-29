package app.aduster.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import app.aduster.R
import app.aduster.core.EngineConfig
import app.aduster.core.NativeCore
import app.aduster.settings.Settings
import app.aduster.settings.SettingsJson
import app.aduster.settings.ThemeMode
import app.aduster.ui.MainActivity
import app.aduster.ui.fmt
import app.aduster.ui.theme.BlackPalette
import app.aduster.ui.theme.LightPalette
import app.aduster.vpn.AdusterVpnService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.util.TimeZone

/**
 * RemoteViews widget (no Glance/Compose runtime in the always-on process). Never schedules
 * updates. It runs in :core, where the protection state is known: today's count while
 * protected, "Paused" or "Off" otherwise, in the app's theme.
 */
class StatsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Called only with widgets on the home screen.
        placed = true
        update(context)
    }

    override fun onEnabled(context: Context) {
        placed = true
    }

    override fun onDisabled(context: Context) {
        placed = false
    }

    companion object {
        /** Whether any widget is on the home screen; null = not asked yet. Saves a binder call per screen-on. */
        @Volatile
        private var placed: Boolean? = null

        fun update(ctx: Context) {
            if (placed == false) return
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, StatsWidget::class.java))
            placed = ids.isNotEmpty()
            if (ids.isEmpty()) return
            val paused = AdusterVpnService.isPaused()
            val (big, small) = lines(AdusterVpnService.running, paused, blockedToday(ctx))
            val p = if (light(ctx)) LightPalette else BlackPalette
            val views = RemoteViews(ctx.packageName, R.layout.widget_stats).apply {
                setInt(R.id.widget_root, "setBackgroundResource", if (p.dark) R.drawable.widget_bg else R.drawable.widget_bg_light)
                setTextViewText(R.id.widget_count, big)
                setTextColor(R.id.widget_count, (if (paused) p.warn else if (AdusterVpnService.running) p.text else p.muted).toArgb())
                setTextViewText(R.id.widget_caption, small)
                setTextColor(R.id.widget_caption, p.muted.toArgb())
                setOnClickPendingIntent(
                    R.id.widget_root,
                    PendingIntent.getActivity(ctx, 12, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
                )
            }
            mgr.updateAppWidget(ids, views)
        }

        /**
         * Asks the widget in :core to redraw (from the app's process, after a theme change): the
         * system delivers the update to the widget's own process.
         */
        fun refresh(ctx: Context) {
            val ids = AppWidgetManager.getInstance(ctx)?.getAppWidgetIds(ComponentName(ctx, StatsWidget::class.java)) ?: return
            if (ids.isEmpty()) return
            ctx.sendBroadcast(
                Intent(ctx, StatsWidget::class.java).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }

        /** The widget's two lines: the count while protected, otherwise the state above the count. */
        fun lines(running: Boolean, paused: Boolean, blocked: Long): Pair<String, String> = when {
            paused -> "Paused" to "${fmt(blocked)} blocked today"
            running -> fmt(blocked) to "Blocked today"
            else -> "Off" to "${fmt(blocked)} blocked today"
        }

        /** The app's theme, read from its settings file (a small file; the store's reader is asynchronous). */
        private fun light(ctx: Context): Boolean {
            val theme = runCatching { SettingsJson.decodeFromString(Settings.serializer(), File(ctx.filesDir, "settings.json").readText()).theme }
                .getOrDefault(ThemeMode.BLACK)
            return when (theme) {
                ThemeMode.LIGHT -> true
                ThemeMode.BLACK -> false
                ThemeMode.SYSTEM -> ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
            }
        }

        private fun blockedToday(ctx: Context): Long {
            NativeCore.nativeBlockedToday().takeIf { it >= 0 }?.let { return it }
            // Engine not initialized in this process: fall back to the persisted counters.
            val saved = runCatching { Json.parseToJsonElement(EngineConfig.statsFile(ctx).readText()).jsonObject }.getOrNull() ?: return 0
            val now = System.currentTimeMillis()
            val today = Math.floorDiv(now / 1000 + TimeZone.getDefault().getOffset(now) / 1000, 86_400L)
            return if (saved["day"]?.jsonPrimitive?.long == today) saved["blocked"]?.jsonPrimitive?.long ?: 0 else 0
        }
    }
}
