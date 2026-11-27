package app.aduster.filters

import java.net.URI
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
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

    /**
     * How many patches can be out at [now] along the chain that starts at [path]: one per
     * validity period since it came due (a list publishes a version at most once a period, so
     * fewer when it changed less often); 0 before it is due, null if [path] is not such a name.
     */
    fun dueCount(path: String, now: Long): Long? {
        val due = dueAt(path) ?: return null
        if (now < due) return 0
        val m = NAME.find(path) ?: return null
        val unit = when (m.groupValues[1]) {
            "s" -> 1_000L
            "m" -> 60_000L
            else -> 3_600_000L
        }
        val period = m.groupValues[3].toLong() * unit
        return (now - due) / period + 1
    }

    /** The patch's address, relative to the list's; null unless on the list's own server. */
    fun patchUrl(listUrl: String, path: String): String? = runCatching {
        val base = URI(listUrl)
        val u = base.resolve(path.substringBefore('#'))
        u.takeIf { it.scheme == base.scheme && it.host == base.host && it.port == base.port }?.toString()
    }.getOrNull()

    /** [old] with the RCS [patch] applied, or null if the patch is not a clean one for it or the result passes [maxBytes]. */
    fun applyRcs(old: ByteArray, patch: ByteArray, maxBytes: Long = Long.MAX_VALUE): ByteArray? {
        // Lines are offsets into the two arrays, never copies: a 7 MB list is a few hundred
        // thousand lines. The first pass checks the patch and sizes the result, the second
        // copies ranges into it.
        val lines = lineStarts(old)
        val p = lineStarts(patch)
        val oldLines = lines.size - 1
        val patchLines = p.size - 1
        if (patchLines == 0) return null
        var out: ByteArray? = null
        for (pass in 0..1) {
            var size = 0L
            var at = 0
            fun copy(from: ByteArray, starts: IntArray, first: Int, end: Int) {
                if (end <= first) return
                val len = starts[end] - starts[first]
                out?.let { System.arraycopy(from, starts[first], it, at, len) }
                at += len
                size += len
            }
            var next = 0 // index of the first original line not yet copied
            var i = 0
            while (i < patchLines) {
                val cmd = COMMAND.matchEntire(String(patch, p[i], p[i + 1] - p[i], Charsets.UTF_8).trimEnd('\r', '\n')) ?: return null
                val n = cmd.groupValues[2].toInt()
                val count = cmd.groupValues[3].toInt()
                i++
                if (size > maxBytes) return null
                if (cmd.groupValues[1] == "d") {
                    if (n < 1 || count < 1 || n - 1 < next || n - 1 + count > oldLines) return null
                    copy(old, lines, next, n - 1)
                    next = n - 1 + count
                } else {
                    if (n < next || n > oldLines || i + count > patchLines) return null
                    copy(old, lines, next, n)
                    next = n
                    copy(patch, p, i, i + count)
                    i += count
                }
            }
            copy(old, lines, next, oldLines)
            if (size > maxBytes) return null
            if (pass == 0) out = ByteArray(size.toInt()) else return out
        }
        return out
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
    fun abpChecksum(text: String): String = collapsed(CharBuffer.wrap(text)).let { checksumOf(it, CHECKSUM.find(it)) }

    /**
     * Whether the list's own `! Checksum:` matches its text; null when it has none. The text is
     * decoded once, straight into its checksum form (no CRs, no blank lines): the checksum line
     * the pattern finds there is the one it finds with blank lines kept (only `\n` runs differ,
     * and every part of the pattern that can hold a `\n` takes a run as it takes one).
     */
    fun checksumHolds(list: ByteArray): Boolean? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        val chars = CharBuffer.allocate(64 * 1024)
        val input = ByteBuffer.wrap(list)
        val text = StringBuilder(list.size)
        // `flip()` on its own line: older Android stubs type it as returning a plain Buffer.
        fun drain() {
            chars.flip()
            collapseInto(chars, text)
            chars.clear()
        }
        while (true) {
            val r = decoder.decode(input, chars, true)
            drain()
            if (r.isUnderflow) break
        }
        while (decoder.flush(chars).isOverflow) drain()
        drain()
        val m = CHECKSUM.find(text) ?: return null
        return checksumOf(text, m) == m.groupValues[1].trimEnd('=')
    }

    /** [text] without CRs and with every run of `\n` as one. */
    private fun collapsed(text: CharBuffer): StringBuilder = StringBuilder(text.remaining()).also { collapseInto(text, it) }

    /** Appends [chars] to [out] without CRs, a `\n` only first or after a character that is not one. */
    private fun collapseInto(chars: CharBuffer, out: StringBuilder) {
        while (chars.hasRemaining()) {
            val c = chars.get()
            if (c == '\r') continue
            if (c == '\n' && out.isNotEmpty() && out[out.length - 1] == '\n') continue
            out.append(c)
        }
    }

    /** MD5 (base64, no padding) of [text]'s UTF-8 without [skip]'s range, encoded in chunks. */
    private fun checksumOf(text: CharSequence, skip: MatchResult?): String {
        val md5 = MessageDigest.getInstance("MD5")
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        val bytes = ByteBuffer.allocate(64 * 1024)
        fun feed(from: Int, to: Int) {
            if (to <= from) return
            val chars = CharBuffer.wrap(text, from, to)
            encoder.reset()
            while (true) {
                val r = encoder.encode(chars, bytes, true)
                md5.update(bytes.array(), 0, bytes.position())
                bytes.clear()
                if (r.isUnderflow) break
            }
            while (true) {
                val r = encoder.flush(bytes)
                md5.update(bytes.array(), 0, bytes.position())
                bytes.clear()
                if (r.isUnderflow) break
            }
        }
        if (skip == null) {
            feed(0, text.length)
        } else {
            feed(0, skip.range.first)
            feed(skip.range.last + 1, text.length)
        }
        return Base64.getEncoder().withoutPadding().encodeToString(md5.digest())
    }

    /** The offset of every line's start (a line ends after its LF; a last line without one too), then the end. */
    private fun lineStarts(b: ByteArray): IntArray {
        var n = 0
        for (x in b) if (x == '\n'.code.toByte()) n++
        val lines = if (b.isNotEmpty() && b[b.size - 1] != '\n'.code.toByte()) n + 1 else n
        val out = IntArray(lines + 1)
        var k = 1
        for (i in b.indices) {
            if (b[i] == '\n'.code.toByte() && k < lines) out[k++] = i + 1
        }
        out[lines] = b.size
        return out
    }
}
