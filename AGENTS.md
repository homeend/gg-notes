# AGENTS.md — building the GgNotes plugin

Read this first. It tells you what the plugin must do, which document is the
authority, what data to develop against, and how to prove the result.
`CLAUDE.md` holds the build/test commands and the IntelliJ-platform
constraints inherited from GitFocus; this file holds the product.

## 1. What you are building

gg (gigagit, `/work/gigagit`) lets an AI agent present a result as an
**overview**: a short markdown document whose links are **anchors** into the
code at one commit, plus **remarks** on exact lines. gg stores each
permanent overview in a machine-local store. The plugin shows the SAME
overview inside IntelliJ IDEA, from the SAME files, with no gg running:

1. a tool window listing the overviews of the open repository;
2. the document rendered, anchors clickable;
3. an anchor opens the file AT THE OVERVIEW'S COMMIT, scrolled to the line,
   the range selected; Back returns to the overview;
4. the document's remarks shown beside the lines of the opened file.

**v1 is read-only.** No writing to the store, no line notes, no PR comments,
nothing that needs gg to run. Those come later under the same format.

## 2. The authority: the format spec

`docs/gg-notes-store/SPEC.md` — "gg notes store format — overview records".
It is a copy of gigagit's `docs/superpowers/specs/2026-10-15-gg-notes-store-format-design.md`
(branch `spec/gg-notes-store-format`, not yet merged to gg's `main`; the gg
side ships the same material under `docs/specs/gg-notes-store/`). Build
from the spec, not from gg's source. When the spec and this file disagree,
the spec wins; when the spec and the real files on disk disagree, the files
win — report the mismatch.

Beside it:

- `overview-document.schema.json` — strict (gg's own drift guard; NOT for
  runtime validation);
- `overview-document.lenient.schema.json` — what a reader validates with;
- `fixture/commits.toml` + `fixture/previews.toml` — a store gg itself
  reads in a test: two overviews + three records a reader must skip.

The spec in one screen (details and edge rules are in the spec; do not
guess them from this summary):

| Step | Rule |
|------|------|
| Base dir | `$XDG_STATE_HOME/gg/notes/` → Windows `%LocalAppData%\gg\notes\` → `~/.local/state/gg/notes/` |
| Key | hex of the first 8 bytes of `sha256(Clean(git rev-parse --path-format=absolute --git-common-dir))` — Go's `filepath.Clean` (on Windows `/`→`\`); git's output already has symlinks resolved; no case folding |
| Files | `commits.toml` (commit overviews) AND `previews.toml` (pull-request overviews). Ignore `*.lock`, `.notes-*.tmp`, `*.corrupt-*`, `*.migrated-*` |
| Envelope | TOML 1.0, `format = 3`, `[[notes]]` records, `[notes.address]` with Go field names `Commit`/`Branch`/`Path`/`State` |
| Overview record | `tags` contains `overview` AND `address.State == 0` AND `address.Commit != ''` AND `address.Path == ''` AND no `parent_id` |
| Document | `rationale` = JSON text: `{version:1, title, overview:<markdown>, files[]{path, annotations[]{newRange:[a,b], summary, rationale?}}}`; ranges 1-based inclusive at `address.Commit` |
| Anchor | a link dest `[./]path[:N[-M]]`, N ≥ 1, M ≥ N; rejected: absolute, `\`, `://`, drive letter, `..`, control chars, `note:`; the first 100 count |
| Non-anchor links | dropped scheme (`javascript:`/`data:`/`vbscript:`) → label only; http(s) → link; else `label (dest)` as text |
| Identity | (commit, title) — or (PR number, title) — names one overview; `id` is machine-local |
| Reader | never take the lock; re-read on mtime change; retry a parse failure once; a bad document is a row marked "cannot render", never dropped |

## 3. Data to develop against

Everything is on this machine; nothing needs gg to run.

### 3.1 `examples/` (committed, start here)

See `examples/README.md` for a walkthrough of each file.

- `examples/commits.toml`, `examples/previews.toml` — the spec's fixture
  store (hand-built but gg-verified). Point the plugin at a directory
  holding these two files and it must list exactly two overviews:
  "How the gate works" (commit `d5efa035…`, branch `main`, 2 files, 2
  remarks) and "PR 7: the gate, reviewed" (`preview = 'main...refs/gg/pr/7'`).
- `examples/gitfocus-real/commits.toml` — a REAL record gg wrote on
  2026-10-13 about the GitFocus plugin (27 anchors into
  `src/main/kotlin/dev/gitfocus/worktrees/…`, 3 files with remarks).
  `overview-document.json` beside it is that record's document, pretty-printed.

### 3.2 The live store of the GitFocus checkout (the acceptance scenario)

| | |
|-|-|
| Checkout | `~/git-focus` (branch `master`) |
| Common dir git prints | `/home/homeend/git-focus/.git` |
| Key | `4acf7db7161a42e0` (confirmed on disk) |
| Store | `~/.local/state/gg/notes/4acf7db7161a42e0/commits.toml` (8 records, 1 overview) |
| The overview | "GitFocus tab: branch-first rows", commit `d5efa035df1f3c4c949040bebb924a35d0ea61a0` (present in the checkout: `Merge branch 'feat/worktrees-branch-first'`), author "Claude Opus 5.5" |

**Acceptance:** open `~/git-focus` in the sandbox IDE → the tool window
shows that one overview → the document renders with its headings and
anchors → clicking `rowText` opens `WorktreesList.kt` at lines 20–28 (at
that commit) → the remark boxes appear beside those lines → Back returns
to the overview. Then point `XDG_STATE_HOME` at a temp dir holding
`notes/4acf7db7161a42e0/` with the two `examples/*.toml` files and the
window shows exactly the two fixture overviews.

A second confirmed key, for a unit test of the derivation:
`/work/gigagit/.git` → `d10200a3a219735f`.

### 3.3 The oracle (needs the unmerged gg build)

`gg overview store --json` prints `{dir, key, files}` for the current
repository — run it in any checkout to check the plugin's key derivation.
Until gg's branch is merged, the binary is
`/work/gigagit/.claude/worktrees/gg-notes-store-format/bin/gg`
(the installed `gg` does not have the verb yet). The Windows spelling of the
hashed path (`T:\…\.git`) is the one rule the spec could not confirm from
disk — confirm it with this oracle on Windows before trusting it there.

## 4. Suggested shape (not binding)

- **Store reader** (pure Kotlin, no IDE types, unit-tested against
  `examples/`): locate → parse TOML → select overview records → decode the
  document → model objects. Use a real TOML 1.0 parser (gg writes both
  `'literal'` and `"basic"` strings; a text match on one spelling misses
  the other). Candidates with permissive licences: `org.tomlj:tomlj`
  (Apache-2.0) or `com.akuleshov7:ktoml` (MIT). JSON: Gson is bundled by the
  platform; kotlinx.serialization is fine too.
- **Anchor parser**: the spec's §6 grammar, table-tested with the spec's
  examples (`a.go:9-3` is text, `./x.go:3` is an anchor, `note:t12` is text,
  `https://…` is a link).
- **Markdown**: any CommonMark/GFM renderer whose link destinations you can
  intercept, with raw HTML off. JetBrains' `org.jetbrains:markdown`
  (Apache-2.0) is the natural choice on this platform. Every newline inside
  a paragraph is a hard break (GitHub-comment style).
- **Git content**: file content at `address.Commit` through git4idea
  (`GitLineHandler` — see CLAUDE.md for the constructor that is stable
  across 242→262), never on the EDT. The working-tree file is only a
  convenience when identical.
- **Tool window**: group by commit (HEAD's first, then newest `updated`),
  rows: title · author · date · short sha · PR number when a PR overview.
  Watch the store DIRECTORY for changes (files are replaced by rename, so a
  file watch dies on the first write).
- **Remarks**: inlay/gutter hints on `newRange` showing `summary`,
  `rationale` on hover, shown only while that overview is open.

## 5. Part 2 — reviews, line notes, live notes, meta

`docs/gg-notes-store/SPEC-part2.md` adds the next record kinds on the same
envelope; its §1.1 table classifies every record, its §8 table says what
the plugin shows for each. Build in this order: (1) reviews on commits and
PRs — new-side remarks placed, old-side remarks listed; (2) committed line
notes + replies + `[[resolved]]`; (3) live notes in `worktrees/<wkey>.toml`
with the relocation rule of §5.2; (4) meta/display badges.

Test data, all in `docs/gg-notes-store/` (copies in `examples/`):

- `fixture/` — every record kind, written by gg's own store; `fixture/README.md`
  lists each record by id and the lines the `context_hash` values fingerprint.
  Reading `commits.toml` + `previews.toml` + `worktrees/cc36f09eabab0c81.toml`
  with the §1.1 predicates must yield: 2 overviews, 2 committed reviews
  (`c0ffee01`, `c0ffee02`), 1 working review (`bb220002`), 1 committed line
  note (`aa110001`) with 1 reply, 1 live line note (`bb220001`), 1 remark
  reply (`c0ffee03` on `review:c0ffee02:0`), 1 resolution.
- `review-document.schema.json` / `.lenient.` — validate review documents
  leniently; the strict one is gg's drift guard.
- `vectors.json` — `context_hash`, `remark_fp`, repository and worktree keys:
  port the formulas and assert these strings.
- `relocation-cases.json` — run each case against your relocation port;
  `status` and `at` must match (the "lower wins" tie-break is case 6).

Not in the store (SPEC-part2 §7): GitHub comments — a PR comment view needs
`gh` itself; only local draft replies to forge threads are stored.

## 6. Live steering — be a `gg session` target

`docs/gg-steer/SPEC.md` is the protocol (a copy of gigagit's spec);
`docs/gg-steer/PLUGIN.md` is what the plugin implements, in build order:
presence (`ide.json`, touched every 1 s), the `ide/` sub-inbox drain, the
verb handler with gg's exact reply prose, and the session memory (open
files `f<n>`, temporary notes `t<n>` with gg's exact-line re-anchoring,
in-memory overviews). A bare `gg session <verb>` reaches the IDE when it is
the only live side (gigagit main 69c01a9a, 2026-10-18; before that, only
`--to ide` or `GG_SESSION_TO=ide` did); while a gg TUI or web page is live
they keep the verb and `--to ide` picks the IDE. gg's side is independent
of the plugin — develop against hand-written command files (PLUGIN.md §7)
or with the installed `gg`. Conformance files in `docs/gg-steer/`: `command.schema.json`,
`reply.schema.json`, `presence.schema.json` (validate leniently),
`steer-cases.json` (run EVERY case against your handler, `ide_only` ones
included), `reanchor-cases.json` (the eight re-anchoring cases),
`repokey-vectors.json` (the inbox path encoding).

## 7. Workflow reminders

- Feature branches + worktrees as `CLAUDE.md` says; per-task commits; the
  suite green before merging.
- Tests first: a failing unit test against `examples/` before the reader
  code; the acceptance scenario of §3.2 is the manual gate.
- Report any place where the real store differs from the spec — that is a
  spec bug to feed back to gigagit, not something to work around silently.

## 8. Session docs — the next contract (coming)

Agents will write what they show during work as FILES, with no gg process
in the path: `<steer inbox>/docs/<id>.md` (an overview: `+++` TOML front
matter, then markdown whose links are anchors) and `<id>.toml` (a note on
working-tree lines, carrying the lines it was written on). Every gg reader
of the worktree — the TUI, gg web, this plugin — lists the directory and
shows what is there; the in-memory `overview_*`/`note_*` steer verbs are
retired once gg's side lands. Read `docs/gg-session-docs/SPEC.md`; the
reader can be built now against `docs/gg-session-docs/examples/`,
`cold-anchor-cases.json` and `id-vectors.json`. The gigagit side is on
branch `spec/gg-session-docs` (part 1: the file layer; parts 2–4: the TUI,
gg web, the CLI and skills).
