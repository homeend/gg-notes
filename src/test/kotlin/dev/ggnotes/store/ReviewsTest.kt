package dev.ggnotes.store

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** SPEC-part2 §3: review records, their document, remark identity, replies, counts and the composed view. */
class ReviewsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val examples: Path = Paths.get("examples")

    private fun fixtureStore(): Path {
        val dir = Files.createTempDirectory(tmp.root.toPath(), "store")
        Files.copy(examples.resolve("commits.toml"), dir.resolve("commits.toml"))
        Files.copy(examples.resolve("previews.toml"), dir.resolve("previews.toml"))
        Files.createDirectories(dir.resolve("worktrees"))
        Files.copy(examples.resolve("worktrees/cc36f09eabab0c81.toml"), dir.resolve("worktrees/cc36f09eabab0c81.toml"))
        return dir
    }

    private fun contents() = StoreReader().read(fixtureStore())
    private fun reviews() = Reviews.build(contents())

    @Test
    fun `remark_fp vectors`() {
        val vectors = JsonParser.parseString(Files.readString(Paths.get("docs/gg-notes-store/vectors.json"))).asJsonObject
        for (v in vectors.getAsJsonArray("remark_fp")) {
            val o = v.asJsonObject
            val range = o.getAsJsonArray("range")
            assertEquals(o.toString(), o.get("fp").asString,
                Reviews.remarkFp(o.get("path").asString, o.get("side").asString, range[0].asInt, range[1].asInt, o.get("summary").asString))
        }
    }

    @Test
    fun `the commit review document decodes with both sides meta and display`() {
        val c = contents()
        val doc = (Reviews.decode(c.records.single { it.id == "c0ffee01" }.rationale) as ReviewContent.Document).document
        assertTrue(doc.summary.startsWith("## Summary"))
        assertEquals("Start at [the refusal](internal/x/gate.go:3-4).", doc.overview)
        assertEquals(mapOf("verdict" to "approve"), doc.meta)
        val severity = doc.display.getValue("severity")
        assertEquals(listOf("badge", "count"), severity.show)
        assertEquals("level", severity.asKind)
        assertEquals("red", severity.values.getValue("high").color)
        assertEquals("!", severity.values.getValue("high").glyph)
        assertEquals("grey", severity.other?.color)
        val file = doc.files.single()
        assertEquals("internal/x/gate.go", file.path)
        assertEquals("the gate itself", file.summary)
        val (r0, r1) = file.remarks
        assertEquals(0, r0.index)
        assertEquals(RemarkSide.NEW, r0.side)
        assertEquals(3 to 4, r0.first to r0.last)
        assertEquals("The refusal returns false.", r0.summary)
        assertEquals("A closed door must not pass.", r0.rationale)
        assertEquals(mapOf("severity" to "high"), r0.meta)
        assertEquals(1, r1.index)
        assertEquals(RemarkSide.OLD, r1.side)
        assertEquals(2 to 2, r1.first to r1.last)
        assertEquals("f79be9ae71f8abb9", r1.fp)
        assertEquals(mapOf("severity" to "low"), r1.meta)
    }

    @Test
    fun `a rationale that is not JSON is a prose review and a broken document is cannot render`() {
        assertEquals(ReviewContent.Prose("Looks fine.\n\n- one nit"), Reviews.decode("Looks fine.\n\n- one nit"))
        assertTrue(Reviews.decode("""{"version": 2, "summary": "x"}""") is ReviewContent.CannotRender)
        assertTrue(Reviews.decode("""{"version": 1, "summary": "x", "files": [{"path": "a", "annotations": [{"newRange": [1,1], "oldRange": [1,1], "summary": "both"}]}]}""") is ReviewContent.CannotRender)
        assertTrue(Reviews.decode("""{"version": 1, "summary": "x", "files": [{"path": "a", "annotations": [{"newRange": [3,1], "summary": "reversed"}]}]}""") is ReviewContent.CannotRender)
        assertTrue("non-string meta values are kept as compact JSON",
            ((Reviews.decode("""{"version": 1, "summary": "x", "meta": {"n": 3, "ok": true}}""") as ReviewContent.Document).document.meta) == mapOf("n" to "3", "ok" to "true"))
    }

    @Test
    fun `fixture yields the three reviews with their kinds scopes and threads`() {
        val all = reviews()
        assertEquals(listOf("bb220002", "c0ffee01", "c0ffee02"), all.map { it.record.id }.sorted())
        val commit = all.single { it.record.id == "c0ffee01" }
        assertEquals(ReviewKind.COMMIT, commit.kind)
        assertEquals("d5efa035df1f3c4c949040bebb924a35d0ea61a0", commit.scopeTip)
        assertEquals("d5efa035df1f3c4c949040bebb924a35d0ea61a0^", commit.scopeBase)
        val pr = all.single { it.record.id == "c0ffee02" }
        assertEquals(ReviewKind.PULL_REQUEST, pr.kind)
        assertEquals(7, pr.record.prNumber)
        assertEquals("0a0b0c0d0e0f1a1b2c3d4e5f6a7b8c9d0e1f2a3b", pr.scopeBase)
        assertEquals(listOf("c0ffee03"), pr.remarkReplies.getValue(0).map { it.id })
        assertEquals(emptyList<NoteRecord>(), pr.unmatchedReplies)
        val working = all.single { it.record.id == "bb220002" }
        assertEquals(ReviewKind.WORKING, working.kind)
        assertEquals("/home/dev/src/gate", working.record.address.worktree)
    }

    @Test
    fun `count-declared meta is counted over the remarks that are not moved`() {
        val commit = reviews().single { it.record.id == "c0ffee01" }
        assertEquals(listOf(MetaCount("severity", "high", 1), MetaCount("severity", "low", 1)), commit.counts())
        assertEquals("1 high · 1 low", Reviews.countsText(commit.counts()))
        val pr = reviews().single { it.record.id == "c0ffee02" }
        assertEquals("the PR review declares no display, so nothing is counted", emptyList<MetaCount>(), pr.counts())
    }

    @Test
    fun `a remark moved to the forge is neither placed nor counted but keeps its index`() {
        val c = contents()
        val record = c.records.single { it.id == "c0ffee01" }
        val fp0 = Reviews.remarkFp("internal/x/gate.go", "new", 3, 4, "The refusal returns false.")
        val moved = record.copy(remarkSends = listOf(RemarkSend(fp0, moved = true)))
        val review = Reviews.build(c.copy(records = c.records.map { if (it.id == "c0ffee01") moved else it })).single { it.record.id == "c0ffee01" }
        assertEquals(listOf(1), review.activeRemarks.map { it.index })
        assertEquals(listOf(MetaCount("severity", "low", 1)), review.counts())
    }

    @Test
    fun `review kind from the record`() {
        fun kind(scope: String?, preview: String?, branch: String = "") = Reviews.kindOf(scope, preview, branch, live = false)
        assertEquals(ReviewKind.COMMIT, kind("abc^..abc", null))
        assertEquals(ReviewKind.COMMIT, kind("abc", null))
        assertEquals(ReviewKind.RANGE, kind("abc..def", null))
        assertEquals(ReviewKind.BRANCH, kind("abc..def", null, branch = "feat/x"))
        assertEquals(ReviewKind.PULL_REQUEST, kind("abc..def", "main...refs/gg/pr/12"))
        assertEquals(ReviewKind.MERGE_PREVIEW, kind("abc..def", "main...feature"))
        assertEquals(ReviewKind.PAIR, kind("abc..def", "abc1234..def5678"))
        assertEquals(ReviewKind.WORKING, Reviews.kindOf(null, null, "", live = true))
    }

    @Test
    fun `the composed document places new-side remarks as anchors and lists old ones`() {
        val commit = reviews().single { it.record.id == "c0ffee01" }
        val doc = ReviewComposer.compose(commit)
        assertEquals("Review: d5efa03 Add the gate (d5efa03^..d5efa03)", doc.title)
        assertEquals(listOf(FileRemarks("internal/x/gate.go", listOf(Remark(3, 4, "The refusal returns false.", "A closed door must not pass.")))), doc.files)
        val rendered = MarkdownRenderer.render(doc.overview)
        assertEquals(listOf(
            LinkTarget.Anchor("internal/x/gate.go", 3, 4),   // remark 0
            LinkTarget.Anchor("internal/x/gate.go", 3, 4),   // the review's own overview: "the refusal"
        ), rendered.anchors.map { it.target })
        assertEquals("The refusal returns false.", rendered.anchors[0].label)
        assertTrue(doc.overview, doc.overview.contains("old 2"))
        assertTrue(doc.overview, doc.overview.contains("severity: high"))
        assertTrue(doc.overview, doc.overview.contains("verdict: approve"))
        assertTrue(doc.overview, doc.overview.contains("the gate itself"))
    }

    @Test
    fun `replies thread under their remark in the composed document and the summary has no anchors`() {
        val c = contents()
        val record = c.records.single { it.id == "c0ffee02" }
        val withLink = record.copy(rationale = record.rationale.replace("PR 7 adds the gate. Fine.", "See [the gate](internal/x/gate.go:1)."))
        val pr = Reviews.build(c.copy(records = c.records.map { if (it.id == "c0ffee02") withLink else it })).single { it.record.id == "c0ffee02" }
        val doc = ReviewComposer.compose(pr)
        assertTrue(doc.overview, doc.overview.contains("Done in the next push."))
        assertTrue(doc.overview, doc.overview.contains("me"))
        val rendered = MarkdownRenderer.render(doc.overview)
        assertEquals("only the remark is an anchor; the summary's link is text", 1, rendered.anchors.size)
        assertTrue(rendered.html, rendered.html.contains("the gate (internal/x/gate.go:1)"))
    }

    @Test
    fun `a prose review composes to its text with no remarks`() {
        val c = contents()
        val record = c.records.single { it.id == "c0ffee01" }.copy(rationale = "Plain text review.\n\nSee [a](internal/x/gate.go:2).")
        val review = Reviews.build(c.copy(records = c.records.map { if (it.id == "c0ffee01") record else it })).single { it.record.id == "c0ffee01" }
        assertTrue(review.content is ReviewContent.Prose)
        val doc = ReviewComposer.compose(review)
        assertEquals(emptyList<FileRemarks>(), doc.files)
        assertEquals(0, MarkdownRenderer.render(doc.overview).anchors.size)
        assertNull(review.counts().firstOrNull())
        assertFalse(doc.overview.isBlank())
    }
}
