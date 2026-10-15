package dev.ggnotes.store

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Diagnostic over every store under this machine's gg state dir (skips when
 * there is none): the reader must survive all of them, and what it cannot
 * classify or parse is printed — the spec feedback AGENTS.md asks for.
 * Never fails on content; only on an exception escaping the reader.
 */
class LocalStoresDiagnosticTest {

    @Test
    fun `every local store reads without an exception and the oddities are listed`() {
        val base = StoreLocator.defaultBaseDir()
        if (!Files.isDirectory(base)) {
            println("LocalStoresDiagnosticTest: no store base at $base, skipping")
            return
        }
        val reader = StoreReader(retryDelayMillis = 1)
        var stores = 0
        var records = 0
        var legacyOnly = 0
        val problems = ArrayList<String>()
        val unknown = ArrayList<String>()
        val byKind = HashMap<RecordKind, Int>()
        val oddities = ArrayList<String>()
        Files.list(base).use { dirs ->
            for (dir in dirs.filter { Files.isDirectory(it) }.toList()) {
                stores++
                if (Files.isRegularFile(dir.resolve("notes.toml")) && !Files.isRegularFile(dir.resolve("commits.toml"))) legacyOnly++
                val c = reader.read(dir)
                records += c.records.size
                problems += c.problems.map { "${dir.fileName}: $it" }
                for (r in c.records) {
                    byKind[r.kind] = (byKind[r.kind] ?: 0) + 1
                    if (r.kind == RecordKind.UNKNOWN) unknown += "${dir.fileName}/${r.sourceFile.fileName}: ${r.id} tags=${r.tags} state=${r.address.state} path='${r.address.path}' commit='${r.address.commit.take(7)}' worktree='${r.address.worktree}'"
                    if (r.kind == RecordKind.LINE_NOTE && !r.isLive && r.contextHash.isEmpty()) oddities += "${dir.fileName}: line note ${r.id} without context_hash"
                    if (r.kind == RecordKind.LINE_NOTE && r.scope != null) oddities += "${dir.fileName}: line note ${r.id} carries scope=${r.scope}"
                    if ((r.kind == RecordKind.REVIEW || r.kind == RecordKind.WORKING_REVIEW) && Reviews.decode(r.rationale) is ReviewContent.CannotRender) oddities += "${dir.fileName}: review ${r.id} cannot render: ${(Reviews.decode(r.rationale) as ReviewContent.CannotRender).reason}"
                    if (r.kind == RecordKind.OVERVIEW && reader.decodeDocument(r.rationale, r.summary) is DocumentResult.CannotRender) oddities += "${dir.fileName}: overview ${r.id} cannot render"
                }
            }
        }
        println("LocalStoresDiagnosticTest: $stores stores ($legacyOnly legacy notes.toml only), $records records, by kind $byKind")
        println("LocalStoresDiagnosticTest: ${problems.size} parse problems" + problems.take(10).joinToString("") { "\n  $it" })
        println("LocalStoresDiagnosticTest: ${unknown.size} unknown-kind records" + unknown.take(10).joinToString("") { "\n  $it" })
        println("LocalStoresDiagnosticTest: ${oddities.size} oddities" + oddities.take(20).joinToString("") { "\n  $it" })
        assertTrue(stores >= 0)
    }
}
