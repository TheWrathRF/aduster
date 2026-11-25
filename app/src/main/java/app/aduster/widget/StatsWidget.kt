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
 * protected, "Paused" or "Off" otherwise, in the app's theme, with a dot in the state's colour
 * and a Pause / Resume button.
 */
class StatsWidget : AppWidgetProvider() {
    /** The widget's button: what it does, and its words for TalkBack. */
    enum class Control(val label: String, val icon: Int, val action: String, val code: Int) {
        PAUSE("Pause protection", R.drawable.ic_stat_pause, AdusterVpnService.ACTION_PAUSE, 20),
        RESUME("Resume protection", R.drawable.ic_stat_play, AdusterVpnService.ACTION_RESUME, 21),
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Called only with widgets on the home screen. Drawn off the main thread: it reads the
        // settings and today's count from disk.
        placed = true
        val pending = goAsync()
        Thread {
            try {
                update(context)
            } finally {
                pending.finish()
            }
        }.start()
    }

    override fun onEnabled(context: Context) {
        placed = true
    }

    override fun onDisabled(context: Context) {
        // Asked again at the next redraw: the receiver is exported, so another app can send this
        // too, and a plain "none placed" would have frozen a widget still on the home screen.
        placed = null
    }

    companion object {
        /** Whether any widget is on the home screen; null = not asked yet. Saves a binder call per screen-on. */
        @Volatile
        private var placed: Boolean? = null

        /** Serializes reading protection state and calling `updateAppWidget`, so an older redraw never overwrites a newer state. */
        private val drawLock = Any()

        internal fun renderLocked(
            readRunning: () -> Boolean,
            readPausedUntil: () -> Long,
            readBlocked: () -> Long,
            nowMs: () -> Long = System::currentTimeMillis,
            draw: (running: Boolean, paused: Boolean, big: String, small: String) -> Unit,
        ) = synchronized(drawLock) {
            // Read `running` once under the lock so text, color and pause state stay consistent
            // and a later stop/pause always draws last.
            val running = readRunning()
            val paused = running && readPausedUntil() > nowMs()
            val (big, small) = lines(running, paused, readBlocked())
            draw(running, paused, big, small)
        }

        fun update(ctx: Context) {
            if (placed == false) return
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, StatsWidget::class.java))
            placed = ids.isNotEmpty()
            if (ids.isEmpty()) return
            renderLocked(
                readRunning = { AdusterVpnService.running },
                readPausedUntil = { AdusterVpnService.pausedUntil },
                readBlocked = { blockedToday(ctx) },
            ) { running, paused, big, small ->
                val p = if (light(ctx)) LightPalette else BlackPalette
                val views = RemoteViews(ctx.packageName, R.layout.widget_stats).apply {
                    setInt(R.id.widget_root, "setBackgroundResource", if (p.dark) R.drawable.widget_bg else R.drawable.widget_bg_light)
                    setTextViewText(R.id.widget_count, big)
                    setTextColor(R.id.widget_count, (if (paused) p.warn else if (running) p.text else p.muted).toArgb())
                    setTextViewText(R.id.widget_caption, small)
                    setTextColor(R.id.widget_caption, p.muted.toArgb())
                    setInt(R.id.widget_dot, "setColorFilter", dot(running, paused, p).toArgb())
                    val control = control(running, paused)
                    if (control == null) {
                        setViewVisibility(R.id.widget_button, android.view.View.GONE)
                    } else {
                        setViewVisibility(R.id.widget_button, android.view.View.VISIBLE)
                        setImageViewResource(R.id.widget_button, control.icon)
                        setInt(R.id.widget_button, "setColorFilter", p.text.toArgb())
                        setInt(R.id.widget_button, "setBackgroundResource", if (p.dark) R.drawable.widget_button_bg else R.drawable.widget_button_bg_light)
                        setContentDescription(R.id.widget_button, control.label)
                        // The tile's pause: until resumed (EXTRA_MINUTES absent is 0).
                        setOnClickPendingIntent(
                            R.id.widget_button,
                            PendingIntent.getService(
                                ctx, control.code, Intent(ctx, AdusterVpnService::class.java).setAction(control.action),
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                            ),
                        )
                    }
                    setOnClickPendingIntent(
                        R.id.widget_root,
                        PendingIntent.getActivity(ctx, 12, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
                    )
                }
                mgr.updateAppWidget(ids, views)
            }
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
            running && paused -> "Paused" to "${fmt(blocked)} blocked today"
            running -> fmt(blocked) to "Blocked today"
            else -> "Off" to "${fmt(blocked)} blocked today"
        }

        /** The button for the state: Pause while protected, Resume while paused, none while off. */
        fun control(running: Boolean, paused: Boolean): Control? = when {
            running && paused -> Control.RESUME
            running -> Control.PAUSE
            else -> null
        }

        /** The dot's colour: the accent while protected, amber while paused, muted while off. */
        fun dot(running: Boolean, paused: Boolean, p: app.aduster.ui.theme.Palette): androidx.compose.ui.graphics.Color = when {
            running && paused -> p.warn
            running -> p.accent
            else -> p.muted
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
