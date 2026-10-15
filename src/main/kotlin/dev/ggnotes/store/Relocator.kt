package dev.ggnotes.store

import java.security.MessageDigest

/** SPEC-part2 §5.2 outcomes, plus UNVERIFIED for a committed note that carries no fingerprint. */
enum class RelocationStatus { ACTIVE, STALE, ORPHANED, UNVERIFIED }

/** Where a note is drawn now and how sure that is. */
data class Relocation(val status: RelocationStatus, val first: Int, val last: Int)

/** SPEC-part2 §4.3 (the fingerprint) and §5.2 (relocation), pure functions over lines. */
object Relocator {

    /** `hex(sha256(join("\n", trim(line) for line in lines)))`, full 64 hex. */
    fun contextHash(lines: List<String>): String {
        val joined = lines.joinToString("\n") { it.trim() }
        return MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** git's blob id of these bytes: `sha1("blob <len>\0" + bytes)`. */
    fun blobId(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** File bytes → lines as git numbers them: split on `\n`, a trailing newline adds no line, `\r` before it dropped. */
    fun lines(bytes: ByteArray): List<String> {
        if (bytes.isEmpty()) return emptyList()
        val text = String(bytes, Charsets.UTF_8)
        val parts = text.split('\n').toMutableList()
        if (parts.last().isEmpty()) parts.removeAt(parts.size - 1)
        return parts.map { it.removeSuffix("\r") }
    }

    /** §5.2 for a LIVE note: active where it was, else the nearest window with the same fingerprint, else stale and clamped. */
    fun relocate(lines: List<String>?, first: Int, last: Int, contextHash: String): Relocation {
        if (first == 0 && last == 0) return Relocation(RelocationStatus.ACTIVE, 0, 0)
        if (lines == null) return Relocation(RelocationStatus.ORPHANED, first, last)
        val span = last - first + 1
        val len = lines.size
        if (contextHash.isNotEmpty() && span >= 1) {
            fun matches(start: Int): Boolean =
                start >= 1 && start + span - 1 <= len && contextHash(lines.subList(start - 1, start - 1 + span)) == contextHash
            if (matches(first)) return Relocation(RelocationStatus.ACTIVE, first, last)
            val maxDistance = maxOf(first - 1, (len - span + 1) - first)
            for (d in 1..maxDistance) {
                if (matches(first - d)) return Relocation(RelocationStatus.ACTIVE, first - d, first - d + span - 1)
                if (matches(first + d)) return Relocation(RelocationStatus.ACTIVE, first + d, first + d + span - 1)
            }
        }
        return clamp(RelocationStatus.STALE, first, last, len)
    }

    /** §4.3 for a COMMITTED note: the lines never change, so the hash is only a check. */
    fun verifyCommitted(lines: List<String>?, first: Int, last: Int, contextHash: String): Relocation {
        if (first == 0 && last == 0) return Relocation(RelocationStatus.ACTIVE, 0, 0)
        if (lines == null) return Relocation(RelocationStatus.ORPHANED, first, last)
        val len = lines.size
        if (contextHash.isEmpty()) return Relocation(RelocationStatus.UNVERIFIED, first, last)
        if (first >= 1 && last <= len && last >= first && contextHash(lines.subList(first - 1, last)) == contextHash) {
            return Relocation(RelocationStatus.ACTIVE, first, last)
        }
        return clamp(RelocationStatus.STALE, first, last, len)
    }

    private fun clamp(status: RelocationStatus, first: Int, last: Int, len: Int): Relocation {
        if (len == 0) return Relocation(status, 0, 0)
        val f = first.coerceIn(1, len)
        val l = last.coerceIn(f, len)
        return Relocation(status, f, l)
    }
}
