package app.aduster.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import app.aduster.R
import app.aduster.ui.MainActivity
import app.aduster.ui.hm

/**
 * One persistent card that shows the protection state (Protected / Paused) and offers the one
 * action that fits it (Pause or Resume). There is no Stop on it: next to
 * Pause it is too easy to hit by mistake, leaving protection off unnoticed; turning off is done
 * in the app only (the Quick Settings tile pauses and resumes).
 *
 * The card holds no counter (the user reads counts in the app): it changes only with the state,
 * so it is posted only then and costs nothing in between.
 */
object Notifications {
    const val ID_PROTECTION = 1
    /**
     * The card's second id: Xiaomi's SystemUI folds an update of a card first posted over 6 h
     * ago, so an old card is replaced by a new one under the other id instead of being updated.
     */
    const val ID_PROTECTION_ALT = 2
    const val ID_REVOKED = 3
    const val ID_LOCKDOWN = 4
    const val ID_STOPPED = 5
    private const val CH_PROTECTION = "protection"
    private const val CH_STATUS = "status"

    /** Amber, the app's colour for "paused". */
    private const val AMBER = 0xFFFFB547.toInt()

    // Request codes: each PendingIntent needs its own (extras are not part of its identity).
    private const val RC_OPEN = 10
    private const val RC_PAUSE = 1
    private const val RC_RESUME = 3
    private const val RC_LOCKDOWN = 5
    private const val RC_REVOKED = 14
    private const val RC_STOPPED = 15
    private const val RC_RENOTIFY = 16

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROTECTION, ctx.getString(R.string.channel_protection), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, ctx.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }

    private fun openApp(ctx: Context, code: Int = RC_OPEN) = PendingIntent.getActivity(
        ctx, code, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceAction(ctx: Context, action: String, code: Int) = PendingIntent.getService(
        ctx, code, Intent(ctx, AdusterVpnService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Silver-blue, the app's accent and its colour for "active" (fixed, not the wallpaper's). */
    private const val ACCENT = 0xFFC2C6D6.toInt()

    /**
     * The protection card. [pausedUntil]: 0 = filtering, [Long.MAX_VALUE] = paused until Resume,
     * otherwise paused until that time. Protected is the title alone; paused adds one line.
     */
    fun protection(ctx: Context, pausedUntil: Long): Notification {
        val paused = pausedUntil > System.currentTimeMillis()
        val text = when {
            !paused -> null
            pausedUntil == Long.MAX_VALUE -> ctx.getString(R.string.notif_paused_text)
            else -> ctx.getString(R.string.notif_paused_until, hm(pausedUntil))
        }
        val color = if (paused) AMBER else ACCENT
        val primary = if (paused) {
            action(ctx, R.drawable.ic_stat_play, R.string.notif_resume, AdusterVpnService.ACTION_RESUME, RC_RESUME)
        } else {
            action(ctx, R.drawable.ic_stat_pause, R.string.notif_pause, AdusterVpnService.ACTION_PAUSE, RC_PAUSE)
        }
        return Notification.Builder(ctx, CH_PROTECTION)
            .setSmallIcon(if (paused) R.drawable.ic_stat_paused else R.drawable.ic_stat_aduster)
            .setColor(color)
            .setContentTitle(ctx.getString(if (paused) R.string.notif_paused_title else R.string.notif_title))
            .setContentText(text)
            .setContentIntent(openApp(ctx))
            // Swiped away anyway (allowed since Android 14): the service posts it again.
            .setDeleteIntent(serviceAction(ctx, AdusterVpnService.ACTION_RENOTIFY, RC_RENOTIFY))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(primary)
            .build()
    }

    private fun action(ctx: Context, icon: Int, label: Int, action: String, code: Int) =
        Notification.Action.Builder(Icon.createWithResource(ctx, icon), ctx.getString(label), serviceAction(ctx, action, code)).build()

    /**
     * Android's "Block connections without VPN" drops every connection that does not go through
     * the VPN, and Aduster's tunnel carries DNS only: with it on, nothing but DNS works.
     */
    fun showLockdown(ctx: Context) {
        val n = Notification.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_aduster)
            .setColor(AMBER)
            .setContentTitle(ctx.getString(R.string.notif_lockdown_title))
            .setContentText(ctx.getString(R.string.notif_lockdown_text))
            .setStyle(Notification.BigTextStyle().bigText(ctx.getString(R.string.notif_lockdown_text)))
            .setContentIntent(
                PendingIntent.getActivity(
                    ctx, RC_LOCKDOWN, Intent(android.provider.Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setOnlyAlertOnce(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_LOCKDOWN, n)
    }

    fun cancelLockdown(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(ID_LOCKDOWN)

    /** Protection stopped without anyone asking ([reason]); tapping opens the app to turn it on. */
    fun showStopped(ctx: Context, reason: String) {
        val text = ctx.getString(R.string.notif_stopped_text, reason)
        val n = Notification.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_aduster)
            .setColor(AMBER)
            .setContentTitle(ctx.getString(R.string.notif_stopped_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx, RC_STOPPED))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_STOPPED, n)
    }

    fun cancelStopped(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(ID_STOPPED)

    fun showRevoked(ctx: Context) {
        val n = Notification.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_aduster)
            .setColor(ACCENT)
            .setContentTitle(ctx.getString(R.string.notif_revoked_title))
            .setContentText(ctx.getString(R.string.notif_revoked_text))
            .setStyle(Notification.BigTextStyle().bigText(ctx.getString(R.string.notif_revoked_text)))
            .setContentIntent(openApp(ctx, RC_REVOKED))
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_REVOKED, n)
    }
}
