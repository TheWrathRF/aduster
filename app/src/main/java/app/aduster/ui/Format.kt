package app.aduster.ui

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val intFmt: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
private val hmFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

fun fmt(n: Long): String = synchronized(intFmt) { intFmt.format(n) }
fun fmt(n: Int): String = synchronized(intFmt) { intFmt.format(n) }

fun percent(r: Double): String = if (r <= 0.0) "0%" else if (r < 0.1) "%.1f%%".format(Locale.US, r * 100) else "%.0f%%".format(Locale.US, r * 100)

/** In the current time zone (a SimpleDateFormat would keep the zone it was made in). */
fun clock(ms: Long): String = timeFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0) return "never"
    val s = (now - ms) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
    }
}

fun micros(us: Long): String = when {
    us <= 0 -> "–"
    us < 1000 -> "$us µs"
    us < 10_000 -> "%.1f ms".format(Locale.US, us / 1000.0)
    else -> "${us / 1000} ms"
}

/** A time of day, the one format every screen uses for it (24-hour, as the Log's times). */
fun hm(ms: Long): String = hmFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/**
 * The system's own lookups (root's daemons, the system server) under one name; null for apps.
 * Neither has an app's icon or label to show.
 */
fun systemAppName(uid: Int): String? = if (uid == 0 || uid == 1000) "Android system" else null

/** An app's name from the resolved labels (see MainViewModel.labels); resolving shows "…". */
fun appName(labels: Map<Int, String>, uid: Int): String = if (uid < 0) "Unknown" else labels[uid] ?: "…"
