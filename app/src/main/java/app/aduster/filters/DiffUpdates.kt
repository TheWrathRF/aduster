package app.aduster.filters

import java.net.URI
import java.security.MessageDigest
import java.util.Base64

/**
 * Differential list updates, as filters.adtidy.org publishes them for AdGuard's lists: the list
 * names its next patch in its header (`! Diff-Path: ../patches/20/20-s-<time>-<valid>.patch`),
 * a small RCS diff (`dN M` deletes M lines from line N, `aN M` adds the M lines that follow
 * after line N; numbers of the original file). A patch is used only when the result checks out
 * (the list's own `! Checksum:`, or the patch's `diff ... checksum:` SHA-1); anything else falls
 * back to the full download.
 */
internal object DiffUpdates {
    /** `name[-h|m|s]-<created>-<valid>.patch[#resource]`, in the resolution's unit (hours if none). */
    private val NAME = Regex("""(?:^|/)[A-Za-z0-9_.]{1,64}(?:-([hms]))?-(\d{1,15})-(\d{1,10})\.patch(?:#[A-Za-z0-9_-]{1,64})?$""")
    private val DIFF_PATH = Regex("""^!\s*Diff-Path:\s*(\S+)\s*$""", RegexOption.IGNORE_CASE)
    private val CHECKSUM = Regex("""^\s*!\s*checksum[\s\-:]+([\w+/=]+).*\n""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val COMMAND = Regex("""^([ad])(\d{1,9}) (\d{1,9})$""")
    private val DIFF_LINE = Regex("""^diff\s(.*)$""")

    /** The `! Diff-Path:` of a list's header (its `!` lines before the first rule), or null. */
    fun diffPath(head: String): String? {
        for (line in head.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("[")) continue
            if (!t.startsWith("!")) return null
            DIFF_PATH.matchEntire(t)?.let { return it.groupValues[1] }
        }
        return null
    }

    /** When the patch [path] names is due (epoch ms): its creation time plus its validity; null if not such a name. */
    fun dueAt(path: String): Long? {
        val m = NAME.find(path) ?: return null
        val unit = when (m.groupValues[1]) {
            "s" -> 1_000L
            "m" -> 60_000L
            else -> 3_600_000L
        }
        val valid = m.groupValues[3].toLong()
        if (valid <= 0) return null
        // Fifteen digits of hours do not fit a millisecond clock: no such patch.
        return runCatching { Math.multiplyExact(Math.addExact(m.groupValues[2].toLong(), valid), unit) }.getOrNull()
    }

    /** The patch's address, relative to the list's; null unless on the list's own server. */
    fun patchUrl(listUrl: String, path: String): String? = runCatching {
        val base = URI(listUrl)
        val u = base.resolve(path.substringBefore('#'))
        u.takeIf { it.scheme == base.scheme && it.host == base.host && it.port == base.port }?.toString()
    }.getOrNull()

    /** [old] with the RCS [patch] applied, or null if the patch is not a clean one for it or the result passes [maxBytes]. */
    fun applyRcs(old: ByteArray, patch: ByteArray, maxBytes: Long = Long.MAX_VALUE): ByteArray? {
        val lines = splitLines(old)
        val p = splitLines(patch)
        if (p.isEmpty()) return null
        val out = java.io.ByteArrayOutputStream(old.size + patch.size)
        var next = 0 // index of the first original line not yet copied
        var i = 0
        while (i < p.size) {
            val cmd = COMMAND.matchEntire(p[i].decodeToString().trimEnd('\r', '\n')) ?: return null
            val n = cmd.groupValues[2].toInt()
            val count = cmd.groupValues[3].toInt()
            i++
            if (out.size() > maxBytes) return null
            if (cmd.groupValues[1] == "d") {
                if (n < 1 || count < 1 || n - 1 < next || n - 1 + count > lines.size) return null
                for (k in next until n - 1) out.write(lines[k])
                next = n - 1 + count
            } else {
                if (n < next || n > lines.size || i + count > p.size) return null
                for (k in next until n) out.write(lines[k])
                next = n
                repeat(count) { out.write(p[i++]) }
            }
        }
        for (k in next until lines.size) out.write(lines[k])
        return if (out.size() > maxBytes) null else out.toByteArray()
    }

    /**
     * A batch or checked patch's own header (`diff name:... checksum:<sha1> lines:<n>`): the
     * SHA-1 the result must have and the patch without that line; the patch as it is otherwise.
     */
    fun split(patch: ByteArray): Pair<String?, ByteArray> {
        val first = patch.indexOf('\n'.code.toByte()).let { if (it < 0) patch.size else it }
        val head = DIFF_LINE.matchEntire(patch.copyOfRange(0, first).decodeToString().trimEnd('\r')) ?: return null to patch
        val sha = Regex("""(?:^|\s)checksum:([0-9a-fA-F]{40})(?:\s|$)""").find(head.groupValues[1])?.groupValues?.get(1)?.lowercase()
        return sha to patch.copyOfRange(minOf(first + 1, patch.size), patch.size)
    }

    fun sha1(b: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    /** Adblock Plus's list checksum: MD5 (base64, no padding) of the text without its checksum line, CRs and blank lines. */
    fun abpChecksum(text: String): String {
        var d = text.replace("\r", "").replace(Regex("\n+"), "\n")
        d = CHECKSUM.replaceFirst(d, "")
        return Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("MD5").digest(d.toByteArray()))
    }

    /** Whether the list's own `! Checksum:` matches its text; null when it has none. */
    fun checksumHolds(list: ByteArray): Boolean? {
        val text = list.decodeToString()
        val want = CHECKSUM.find(text.replace("\r", ""))?.groupValues?.get(1)?.trimEnd('=') ?: return null
        return abpChecksum(text) == want
    }

    /** Lines with their own ends (LF, or CRLF kept whole); a last line without one too. */
    private fun splitLines(b: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var start = 0
        for (i in b.indices) {
            if (b[i] == '\n'.code.toByte()) {
                out += b.copyOfRange(start, i + 1)
                start = i + 1
            }
        }
        if (start < b.size) out += b.copyOfRange(start, b.size)
        return out
    }
}
