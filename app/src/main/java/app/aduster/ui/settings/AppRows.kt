package app.aduster.ui.settings

import app.aduster.ui.InstalledApp

/** What the system says about one installed package. */
data class PackageFacts(val pkg: String, val label: String, val system: Boolean, val uid: Int, val internet: Boolean, val launchable: Boolean)

/**
 * Apps without internet access of their own whose connections another app makes (Google's
 * assistant app, whose connections the Google app makes): excluding them changes nothing,
 * excluding that app does.
 */
private val CARRIERS = mapOf("com.google.android.apps.bard" to "com.google.android.googlequicksearchbox")

/**
 * The rows Excluded apps can show, by label. A package reaches the network when it holds
 * INTERNET or another package of its UID does (exclusion works per UID). Launchable apps without
 * it are kept for search, with a note, so a person looking for one learns why it does not matter.
 */
fun appRows(facts: List<PackageFacts>): List<InstalledApp> {
    val netUids = facts.filter { it.internet }.map { it.uid }.toSet()
    val byPkg = facts.associateBy { it.pkg }
    return facts.mapNotNull { f ->
        val network = f.uid in netUids
        if (!network && !f.launchable) return@mapNotNull null
        val note = if (network) {
            null
        } else {
            CARRIERS[f.pkg]?.let { byPkg[it] }?.takeIf { it.internet }?.let { "Uses ${it.label}'s connection: exclude ${it.label}" } ?: "Has no internet access of its own"
        }
        InstalledApp(f.pkg, f.label, f.system, f.uid, launchable = f.launchable, network = network, note = note)
    }.sortedBy { it.label.lowercase() }
}

/**
 * The rows shown for [query]: without one, the apps with a launcher entry that reach the network
 * ([background]: also the packages without a launcher entry); a search covers every row. Excluded
 * apps ([excluded]) are always listed, first.
 */
fun shownApps(apps: List<InstalledApp>, query: String, background: Boolean, excluded: Set<String>): List<InstalledApp> {
    val q = query.trim().lowercase()
    return apps
        .filter { a ->
            if (q.isNotEmpty()) {
                a.label.lowercase().contains(q) || a.pkg.contains(q)
            } else {
                a.pkg in excluded || (a.network && (a.launchable || background))
            }
        }
        .sortedByDescending { it.pkg in excluded }
}
