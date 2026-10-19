# AgentNotes

IntelliJ-platform plugin that shows the **overviews** the
[gigagit](https://gigagit.code-drill.eu/) CLI (`gg`) stores for a repository:
an AI agent's markdown document whose links are anchors into the code at one
commit, plus remarks on exact lines. Read from gg's own store on disk
(`docs/gg-notes-store/SPEC.md`), with no gg running. Read-only.

- **Agent Notes tool window** (right stripe): the overviews of the open
  repository grouped by commit, HEAD's first, then newest; rows read
  `title · author · date · short sha · branch | PR #n`.
- **Document**: selecting a row renders it; anchors are links numbered in
  document order; Previous/Next anchor on the toolbar.
- **Anchor**: opens the file *at the overview's commit* (the working file
  only when identical), scrolled to the line with the range selected; the
  banner above the editor goes back to the overview (also `Alt+Shift+O`).
- **Remarks**: boxes above their lines, a tint over them, gutter bands on
  every anchor of the file — only while that overview is open.
- **Reviews** (commit, range, branch, pull request, merge preview, pair,
  working changes): listed under their commit with the author, date and
  the counts of count-declared meta; opened, the summary, the files with
  every remark (new-side remarks clickable and boxed, old-side listed),
  replies under their remark, the review's own overview; prose reviews
  render as markdown; a working review says when the tree has changed.
- **Line notes**: under their commit's files as threads with replies and
  resolutions (collapsed, never hidden); a scoped note under its review;
  committed notes verified against the file at the commit, live notes in
  `worktrees/<wkey>.toml` relocated against the working file (active,
  moved, stale, orphaned) and opened in the working file itself; notes
  gg's startup sweep will drop are marked.

## Build

```bash
./test.sh                 # unit + integration tests
./dist.sh                 # build/distributions/agent-notes-<version>.zip
./dist.sh /some/deploy/dir
```

Needs a JDK 17–23 (`JAVA_HOME`, or the sdkman fallback in the scripts).
Install the zip via Settings ▸ Plugins ▸ gear ▸ *Install Plugin from Disk…*.

- **A `gg session … --to ide` target**: with the project open, an agent in a
  terminal can open a file at a line or range (working tree, index or a
  commit), highlight lines, leave temporary remarks that follow the text as
  you edit, list the open files, and show an in-memory overview in the tool
  window — through gg's file inbox, with no gg running (`docs/gg-steer/`).

To try it on a checkout: `./gradlew runIde --args=/path/to/checkout`
(`-Pggnotes.stateHome=<dir>` reads a throwaway store under `<dir>/gg/notes/`).

## Layout

- `src/main/kotlin/dev/ggnotes/store` — pure Kotlin: store locator and reader, link classifier, markdown renderer, grouping (unit-tested against `examples/`)
- `src/main/kotlin/dev/ggnotes/overview` — service, tool window, document pane, anchor navigation, remark overlays, editor banner
- `src/main/kotlin/dev/ggnotes/git` — git4idea access (common dir, subject, file at commit)
- `THIRD_PARTY_NOTICES.md` — bundled libraries and licences
- `src/main/resources/META-INF/plugin.xml` — descriptor
- `src/main/resources/messages/GgNotesBundle.properties` — all user-visible strings
- `src/test/kotlin` — JUnit 4 tests
- `AGENTS.md` — what to build, the acceptance scenario, data to develop against
- `docs/gg-notes-store/` — the store format spec (copied from gigagit), JSON Schemas, fixture
- `examples/` — example store files in gg's exact on-disk shape, with a walkthrough
- `CLAUDE.md` — working notes and platform constraints
