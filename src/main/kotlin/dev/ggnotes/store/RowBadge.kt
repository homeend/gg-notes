package dev.ggnotes.store

/**
 * What a tool-window row refers to beyond the commit its group is headed by,
 * as a short badge after the title. gg's `overview list` spells the same
 * states `commit | on-branch | was-tip | pr`; reviews add their scope kinds.
 * A plain commit overview or review has none: the header already says it.
 */
sealed class RowBadge {
    data class Pr(val number: Int) : RowBadge()
    /** The branch recorded at save time still points at the row's commit. */
    data class Branch(val name: String) : RowBadge()
    /** The recorded branch has moved on (or is gone): gg's `was-tip`. */
    data class WasTip(val name: String) : RowBadge()
    object Range : RowBadge()
    object Preview : RowBadge()
    object Pair : RowBadge()
    object Working : RowBadge()

    companion object {
        /** [branchTip] is the current sha of the overview's recorded branch, null when unknown or gone. */
        fun of(o: Overview, branchTip: String?): RowBadge? {
            o.prNumber?.let { return Pr(it) }
            if (o.branch.isBlank()) return null
            return branchBadge(o.branch, o.commit, branchTip)
        }

        fun ofReview(r: Review, branchTip: String?): RowBadge? = when (r.kind) {
            ReviewKind.COMMIT -> null
            ReviewKind.RANGE -> Range
            ReviewKind.BRANCH -> branchBadge(r.record.address.branch, r.record.address.commit, branchTip)
            ReviewKind.PULL_REQUEST -> r.record.prNumber?.let { Pr(it) }
            ReviewKind.MERGE_PREVIEW -> Preview
            ReviewKind.PAIR -> Pair
            ReviewKind.WORKING -> Working
        }

        private fun branchBadge(branch: String, commit: String, tip: String?): RowBadge? {
            if (branch.isBlank()) return null
            val still = tip != null && (tip.startsWith(commit) || commit.startsWith(tip))
            return if (still) Branch(branch) else WasTip(branch)
        }
    }
}
