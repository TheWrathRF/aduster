package app.aduster.filters

import app.aduster.core.NativeCore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * The user's own rules feed two layers. Lines the DNS matcher accepts (`||host^`, `@@||host^`,
 * hosts lines, regexes) apply to every app; lines only a page filter can use (element hiding
 * `##`, URL paths, request-type options) go to the browsers' page filter.
 */
object UserRules {
    private val COSMETIC = listOf("##", "#@#", "#?#", "#@?#", "#$#", "#@$#")

    enum class Layer { DNS, CHROME, INVALID }

    /** Layer of every line (1-based line number, comments omitted) and why invalid lines are. */
    data class Analysis(val layers: Map<Int, Layer>, val errors: List<Pair<Int, String>>)

    /**
     * Comments: `!` lines and hosts-file `# text`. Generic cosmetic rules start with `#` too
     * (`##.ad`, `#@#.ad`, `#?#…`, `#$#…`): they are rules.
     */
    fun isComment(line: String): Boolean {
        val l = line.trim()
        return l.isEmpty() || l.startsWith("!") || (l.startsWith("#") && COSMETIC.none { l.startsWith(it) })
    }

    /** How many rule lines [text] has (comments and blank lines left out). */
    fun ruleCount(text: String): Int = text.lines().count { !isComment(it) }

    private fun looksLikeWebRule(l: String) = COSMETIC.any { l.contains(it) } || l.contains('/') || l.contains('$') || l.contains('*') || l.startsWith("|")

    /** One pass over the rules with a single call into the DNS matcher. */
    fun analyze(text: String): Analysis {
        val rejected: Map<Int, String> = runCatching {
            Json.parseToJsonElement(NativeCore.nativeValidateRules(text)).jsonArray.associate { item ->
                val a = item.jsonArray
                a[0].jsonPrimitive.int to a[1].jsonPrimitive.content
            }
        }.getOrDefault(emptyMap())
        val layers = LinkedHashMap<Int, Layer>()
        text.lines().forEachIndexed { i, raw ->
            val l = raw.trim()
            if (isComment(l)) return@forEachIndexed
            layers[i + 1] = when {
                COSMETIC.any { l.contains(it) } -> Layer.CHROME
                (i + 1) !in rejected -> Layer.DNS
                looksLikeWebRule(l) -> Layer.CHROME
                else -> Layer.INVALID
            }
        }
        // Lines the page filter takes are not errors, even though DNS cannot use them.
        val errors = rejected.filterKeys { layers[it] == Layer.INVALID }.toList().sortedBy { it.first }
        return Analysis(layers, errors)
    }

    fun classify(text: String): Map<Int, Layer> = analyze(text).layers

    /** The lines for the browsers' page filter. */
    fun webPart(text: String): String {
        val layers = classify(text)
        val lines = text.lines()
        return layers.filterValues { it == Layer.CHROME }.keys.joinToString("\n") { lines[it - 1].trim() }
    }

    /** A site rule: `||host^` (block) or `@@||host^` (allow), with nothing else on the line. */
    private val SITE = Regex("""^(@@)?\|\|([a-z0-9_.-]+)\^$""")

    data class Sites(val allowed: List<String>, val blocked: List<String>)

    /** The whole-site rules among the user's rules, for the Sites screen. */
    fun sites(text: String): Sites {
        val allowed = mutableListOf<String>()
        val blocked = mutableListOf<String>()
        text.lines().forEach { raw ->
            val m = SITE.matchEntire(raw.trim().lowercase()) ?: return@forEach
            if (m.groupValues[1].isEmpty()) blocked += m.groupValues[2] else allowed += m.groupValues[2]
        }
        return Sites(allowed.distinct(), blocked.distinct())
    }

    /**
     * The host a person typed or pasted for the Sites screen (`https://www.site.example/x` →
     * `www.site.example`), or null.
     */
    fun siteHost(input: String): String? {
        var s = input.trim().lowercase()
        s = s.substringAfter("://", s).substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        s = s.removePrefix("*.").trim('.')
        return s.takeIf { it.contains('.') && Regex("""^[a-z0-9_.-]+$""").matches(it) && !it.contains("..") }
    }

    /**
     * [text] with [rule] added once at the end, and its opposite (`@@` added or taken away)
     * removed. Every other line stays as the user wrote it, blank lines and comments included.
     */
    fun withRule(text: String, rule: String): String {
        val counterpart = if (rule.startsWith("@@")) rule.removePrefix("@@") else "@@$rule"
        val lines = text.lines().filter { it.trim() != rule && it.trim() != counterpart }.toMutableList()
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        return (lines + rule).joinToString("\n")
    }

    /** [text] with [host]'s site rule set to allow/block (the opposite rule removed), or removed when [rule] is null. */
    fun withSite(text: String, host: String, rule: Boolean?): String {
        val lines = text.lines().filter { l ->
            val m = SITE.matchEntire(l.trim().lowercase())
            m == null || m.groupValues[2] != host
        }.toMutableList()
        while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
        when (rule) {
            true -> lines += "@@||$host^"
            false -> lines += "||$host^"
            null -> {}
        }
        return lines.joinToString("\n")
    }
}
