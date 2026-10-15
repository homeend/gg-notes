package dev.ggnotes.steer

/**
 * gg's `agentdocs.reanchor`, ported: a live note follows the exact-line
 * alignment of the text it was aligned to against the current text; it
 * moves when every one of its lines survived unchanged and still sits
 * together, otherwise it goes outdated and keeps its numbers clamped to the
 * file. An outdated note comes back only when its remembered text is at its
 * old place again, or at exactly ONE place in the file.
 */
object Reanchor {

    class Note(var start: Int, var end: Int, var outdated: Boolean, val anchor: List<String>)

    fun reanchor(notes: List<Note>, old: List<String>, cur: List<String>) {
        val to = sameLineMap(old, cur)
        for (n in notes) {
            if (!n.outdated) {
                val s = mapRange(to, n.start, n.end)
                if (s != null) {
                    val span = n.end - n.start
                    n.start = s
                    n.end = s + span
                    continue
                }
            } else {
                val s = relocate(cur, n.anchor, n.start)
                if (s > 0) {
                    n.start = s
                    n.end = s + n.anchor.size - 1
                    n.outdated = false
                    continue
                }
            }
            n.outdated = true
            if (n.end > cur.size) n.end = cur.size
            if (n.start > n.end) n.start = n.end
            if (n.start < 1) {
                n.start = 1
                n.end = 1
            }
        }
    }

    /** Maps each old line (1-based index) to the new line it survived as, unchanged; 0 = edited or gone. */
    fun sameLineMap(old: List<String>, cur: List<String>): IntArray {
        val to = IntArray(old.size + 1)
        for ((i, j) in matchedPairs(old, cur)) to[i + 1] = j + 1
        return to
    }

    /** Maps old lines start..end through [to]: the new start, only when every line survived and they are still consecutive. */
    fun mapRange(to: IntArray, start: Int, end: Int): Int? {
        if (start < 1 || end >= to.size || to[start] == 0) return null
        for (i in start..end) {
            if (to[i] != to[start] + (i - start)) return null
        }
        return to[start]
    }

    /** Finds [anchor] in [cur]: at [start] when it is there, else at its one and only occurrence; 0 = not found or ambiguous. */
    fun relocate(cur: List<String>, anchor: List<String>, start: Int): Int {
        if (anchor.isEmpty()) return 0
        fun at(s: Int): Boolean {
            if (s < 1 || s + anchor.size - 1 > cur.size) return false
            for (i in anchor.indices) if (cur[s - 1 + i] != anchor[i]) return false
            return true
        }
        if (at(start)) return start
        var found = 0
        var s = 1
        while (s + anchor.size - 1 <= cur.size) {
            if (at(s)) {
                if (found != 0) return 0
                found = s
            }
            s++
        }
        return found
    }

    /** Myers' O(ND) line diff: the (oldIndex, newIndex) pairs of lines that survived unchanged, 0-based, in order. */
    fun matchedPairs(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return emptyList()
        val max = n + m
        val offset = max + 1
        var v = IntArray(2 * max + 3)
        val trace = ArrayList<IntArray>()
        var done = false
        var d = 0
        while (d <= max && !done) {
            trace += v.copyOf()
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) v[offset + k + 1] else v[offset + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                v[offset + k] = x
                if (x >= n && y >= m) {
                    done = true
                    break
                }
                k += 2
            }
            d++
        }
        // backtrack
        val pairs = ArrayList<Pair<Int, Int>>()
        var x = n
        var y = m
        for (dd in trace.size - 1 downTo 0) {
            val vv = trace[dd]
            val k = x - y
            val prevK = if (k == -dd || (k != dd && vv[offset + k - 1] < vv[offset + k + 1])) k + 1 else k - 1
            val prevX = vv[offset + prevK]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) {
                pairs += (x - 1) to (y - 1)
                x--
                y--
            }
            if (dd > 0) {
                x = prevX
                y = prevY
            }
        }
        pairs.reverse()
        return pairs
    }
}
