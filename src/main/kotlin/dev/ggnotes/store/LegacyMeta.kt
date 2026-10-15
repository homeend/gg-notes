package dev.ggnotes.store

import java.math.BigDecimal

/**
 * SPEC-part2 §2, legacy fields: a tag of the form `key: value` is a meta
 * entry, and a `confidence` number is meta `confidence` as a word. Applied
 * on every read of every part, whatever its `format`.
 */
object LegacyMeta {

    private val TAG_META = Regex("""^([A-Za-z0-9_.-]{1,64}): (.+)$""")

    /** Splits tags into (real tags, meta entries) in order. */
    fun splitTags(tags: List<String>): Pair<List<String>, List<MetaEntry>> {
        val real = ArrayList<String>()
        val meta = ArrayList<MetaEntry>()
        for (t in tags) {
            val m = TAG_META.matchEntire(t)
            if (m != null) meta += MetaEntry(m.groupValues[1], m.groupValues[2]) else real += t
        }
        return real to meta
    }

    /** `0.3 → low`, `0.6 → medium`, `0.9 → high`, else the shortest decimal text; `0` is dropped (null). */
    fun confidenceWord(value: Double): String? {
        if (value == 0.0) return null
        return when (value) {
            0.3 -> "low"
            0.6 -> "medium"
            0.9 -> "high"
            else -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        }
    }
}
