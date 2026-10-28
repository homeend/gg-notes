# Focus anything the tool window shows — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An agent outside gg can bring ANY row of the Agent Notes tool window to the front. The IDE's `files` reply lists every item and child of the item views, and `file_focus` with an item id opens that item and selects its row.

**Architecture:**
- **Pure layer:** a new `ItemRows` turns `NoteItem`s (built by `NoteItems.build`, master `bf8fe19`) into `files` rows, finds an item by id, and judges whether an item's record is the open document.
- **IDE layer:**
  - `SteerHandler.files` appends those rows.
  - `SteerHandler.fileFocus` resolves `store:<id>` / `note:<id>`.
  - A shared `ItemOpener` opens an item the way a row click does.
  - A new `OverviewUi.revealItem` selects the row without re-opening it.
- **Skills:** the agent-overview skill's FILES.md §11 uses the new `files` rows and `file_focus` for every item.

**Tech Stack:** Kotlin (apiVersion 1.9), IntelliJ Platform 242+, JUnit 4, `OverviewFlowIntegrationTest` (drives the steer inbox with command files).

**Spec:** `docs/superpowers/specs/2026-10-28-agent-focus-and-note-views-design.md` §4.3 (with §2's anti-goals and §4.1's "Focusing something that already exists").

**Branch:** `feat/focus-items`, worktree `.claude/worktrees/focus-items`, from master `bf8fe19`. Release 0.6.0. Merge only on the user's OK.

## Global Constraints

- **Kotlin and the pure package:** Kotlin `apiVersion` 1.9 (no `Enum.entries`). `ItemRows` is pure, with no `com.intellij.*` import. `ItemRows.kt` lives in `dev.ggnotes.steer` beside `Wire.kt`.
- **Existing `files` rows stay byte-for-byte as they are:** open files, then session overviews (`source: "overview"`, ordered as today). The new rows come AFTER them. gg as shipped reads the existing rows.
- **Wire names and ids** are fixed by spec §4.3 and slice 3:
  - `source` is one of `session-note`, `store-overview`, `store-review`, `store-thread`;
  - `kind` is one of `commit`, `branch`, `pull-request`, `file`, `other` (`ItemKind.wire`);
  - ids are `store:<record id>` and `note:<id>`;
  - a session overview keeps its own id, its existing row and today's `file_focus` reply.
- **New `files` row fields:** `kind` (string) and `owner` (an object `{commit?, branch?, preview?, path?}` holding only the non-null keys, as in spec §4.3). Gson leaves out null fields, as `WireTest` already relies on.
- **`file_focus` replies:**
  - `focused <source> <id> "<title>"`, with the title quoted by `SessionDocRefs.quote` (Go quoting);
  - unknown `store:` / `note:` id → error `no item <id>`;
  - a session overview keeps `focused overview <id> "<title>"`.
- **Threads and gg:** git and the store are never read on the EDT; editor work runs inside `onEdt` (invokeAndWait). Store files are never written.
- **Strings:** user-visible strings go through `GgNotesBundle`. The wire replies are protocol text, not UI, and stay literals as the existing ones are.

## Rulings made while planning

1. **What a new row holds:**
   - `path` = `owner.path` (a thread or session note), else `""`.
   - `line` = `owner.first` (thread or note), else 0.
   - `title` = item title; `author` = item author; `created` = item time in RFC 3339 UTC (`ISO_INSTANT`), omitted when the time is null.
   - `state` = `"shown"` when the open document is the item's record (`ItemRows.isShown`), else `"background"`. A session note row is always `"background"`.
2. **Which items get rows:** every top item and every child, recursively, in By Date order: top items newest first by `ItemViews.byDate`'s ordering, each followed by its children. Session-overview items are skipped, because their existing rows already list them. Their children (session notes) still get rows.
3. **The owner object** has the spec's four keys only. A PR number travels in `preview`, as the store records it.
4. **A `file_focus` miss re-reads once, then refuses:** a store id → `OverviewService.refreshSync()` (background thread, the steer worker); a note id → `SteerService.reloadDocsNow(s)`. This is the same rule `navigate`'s `openStored` and today's session-overview focus already use.
5. **Focusing shows the tool window,** opens the item by the same route a row click uses (`ItemOpener`), then selects its row without opening it again (`revealItem`). A session note opens its lines in the editor and selects its row. For the editor, `file_focus` asks for focus, unlike the tree click, because the agent asked to bring it to the front.
6. **`navigate` + `hint_kind`/`hint_id` stays as it is** (slice 1's skills use it). FILES.md now prefers `file_focus store:<id>` and keeps `navigate` as the fallback for an older plugin.

## Review Focus

- `files` on a repository with no store and no session docs gives exactly today's reply: no new rows, and `detail: no open files` when there are no open files either (Task 2 test).
- An id with a newline or a quote in the title: the reply quotes it Go-style (Task 1 test on the reply text builder).
- `file_focus store:<id>` for a review written seconds ago (not yet in the polled snapshot) succeeds after the one re-read (Task 2 test: write the store record, then focus at once).
- `file_focus` on a child thread inside a review opens that thread's notes document and selects the child row, not the review row (Task 2 test).
- `file_focus note:<id>` for a note linked by an overview selects the child row under that overview (Task 2 test).

---

### Task 1: `files` rows and lookup, pure

**Files:**
- Modify: `src/main/kotlin/dev/ggnotes/steer/Wire.kt` (`OpenFileWire` gets `kind: String? = null`, `owner: OwnerWire? = null`; new `data class OwnerWire(val commit: String? = null, val branch: String? = null, val preview: String? = null, val path: String? = null)`)
- Create: `src/main/kotlin/dev/ggnotes/steer/ItemRows.kt`
- Create: `src/test/kotlin/dev/ggnotes/steer/ItemRowsTest.kt`; modify `src/test/kotlin/dev/ggnotes/steer/WireTest.kt`

**Interfaces:**
- Consumes:
  - `NoteItem`, `ItemKind.wire`, `ItemSource.wire`, `Payload`, `Owner`, `NoteItems.build`, `ItemViews.byDate` (`dev.ggnotes.items`);
  - `Overview.identity` / `viewKind` (`dev.ggnotes.store`);
  - `SessionDocRefs.quote`.
- Produces:
  ```kotlin
  object ItemRows {
      /** The `files` rows of [items] after today's rows: every item and child except session overviews (Rulings 1–3). */
      fun rows(items: List<NoteItem>, open: Overview?): List<OpenFileWire>
      /** The item or child with [id], depth first; null when none. */
      fun find(items: List<NoteItem>, id: String): NoteItem?
      /** Whether the open document [open] is [item]'s record: overview identity, `review:<id>:`, `notes:<root id>:`; never for a session item. */
      fun isShown(item: NoteItem, open: Overview?): Boolean
      /** `focused <source> <id> "<title>"`. */
      fun focusedReply(item: NoteItem): String
  }
  ```
- [ ] **Step 1: Write the failing tests** (`ItemRowsTest`). Use the `examples/items/` fixture loaded as `NoteItemsTest` does, plus session docs built as `DocIndexTest` builds them.
  - `rows skip session overviews and list children after their parent`: the ids in row order are each top item followed by its children. No row has the id of a session overview. The note that overview links does have a row.
  - `a row carries kind, source, owner, path, line, created`:
    - the commit review row has `source = "store-review"`, `kind = "commit"`, `owner.commit` = C1 and `path = ""`;
    - a thread row has `source = "store-thread"`, `kind = "file"`, `path = "a.go"`, `line = 3` and `owner.path = "a.go"`;
    - `created` equals the TOML `created` in `ISO_INSTANT` form;
    - the undated `e.go:2` row has `created == null`.
  - `state is shown only for the open record`: with `open` = the commit overview's `Overview`, that row is `"shown"` and every other row is `"background"`. With `open` = an Overview whose `viewKind = "notes:<thread id>:"`, only that thread is `"shown"`.
  - `find finds children`: `find(items, "store:<a.go:3 id>")` returns the thread under the overview. `find(items, "store:nope")` returns null.
  - `focused reply quotes the title`: for an item titled `Say "hi"\nnow` the reply is `focused store-review store:x "Say \"hi\"\nnow"`.
  - `WireTest`: a row with `kind` and `owner` serialises both fields. The owner holds only its non-null keys, and an old-style row has neither key.
- [ ] **Step 2: Run** `./test.sh --tests "dev.ggnotes.steer.ItemRowsTest" --tests "dev.ggnotes.steer.WireTest"`. Expected: compile FAIL (`ItemRows` is undefined).
- [ ] **Step 3: Implement** `OwnerWire`, the two fields, and `ItemRows`. Use `ItemViews.byDate(items, ZoneOffset.UTC)` for the top order; the zone does not change the within-day order.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `git add -A src && git commit -m "files rows for every note item: kind, owner, state (pure)"`

### Task 2: `files` and `file_focus` in the IDE

**Files:**
- Create: `src/main/kotlin/dev/ggnotes/overview/ItemOpener.kt`
- Modify:
  - `src/main/kotlin/dev/ggnotes/overview/OverviewToolWindowPanel.kt`: `open(n)` and `shows(n, open)` delegate to `ItemOpener` and `ItemRows.isShown`; implement `revealItem`.
  - `src/main/kotlin/dev/ggnotes/overview/OverviewService.kt`: `OverviewUi.revealItem`.
  - `src/main/kotlin/dev/ggnotes/steer/SteerHandler.kt`: `files`, `fileFocus`.
- Test: `src/test/kotlin/dev/ggnotes/overview/OverviewFlowIntegrationTest.kt`

**Interfaces:**
- Consumes: `ItemRows` (Task 1), `NoteItems.build`, `OverviewService.snapshot` / `refreshSync()` / `open` / `openReview` / `openNotes`, `SteerService.showOverview` / `showNote` / `reloadDocsNow` / `sessionIdentity`.
- Produces:
  ```kotlin
  /** Open [item] as its tool-window row's click does; [session] is the root's steer session (session items need it). EDT. */
  object ItemOpener { fun open(project: Project, item: NoteItem, repo: GitRepository, session: SteerService.RootSession?, focusEditor: Boolean = false) }
  interface OverviewUi { /* existing */ fun revealItem(repo: GitRepository, id: String): Boolean }
  ```
  - `revealItem` selects the row (through `selectSilently`, so nothing re-opens), scrolls it into view, and returns false when no row has the id.
  - `SteerService.showNote` gets `focus: Boolean = false`, which `ItemOpener` passes through. The tree keeps `false` (slice 3's fix), and `file_focus` passes `true` (Ruling 5).
  - Session-overview identity for `isShown` stays in the panel, because it needs `steer.sessionIdentity`. `ItemRows.isShown` returns false for session items, and the panel checks them itself.
- `SteerHandler.files`: after today's rows, `ItemRows.rows(items, OverviewService.open?.overview?.takeIf { it.repo == s.repo })`. Here `items = NoteItems.build(model, s.docs.index.overviews(), s.docs.index.notes())`, and `model` is the snapshot repo whose `repo.root == s.repo.root` (`RepoModel.EMPTY` when missing). The `detail: no open files` rule applies only when the WHOLE list is empty.
- `SteerHandler.fileFocus`: when `c.fileId` starts with `store:` or `note:`:
  1. Build the items and `find` the id. On a miss, re-read once (Ruling 4) and find again. A second miss → `Reply.fail(c.id, "no item <id>")`.
  2. In `onEdt`: show the tool window (`ToolWindowManager…getToolWindow(OverviewService.TOOL_WINDOW_ID)?.show()`), then `ItemOpener.open(project, item, s.repo, s, focusEditor = true)`, then `OverviewService.ui?.revealItem(s.repo, id)`.
  3. Reply `Reply.ok(c.id, ItemRows.focusedReply(item))`.

  This branch comes BEFORE today's session-overview branch; `:` is not valid in a session doc id, so the two cannot clash.

- [ ] **Step 1: Write failing integration tests** in `OverviewFlowIntegrationTest`. Use its `post(id, body)` command-file helper and its fixture repo, which has the store overview `7cf0812f` on `sha` and a session-docs directory. The tests that write session docs follow the existing session tests there.
  - `testFilesListsEveryItemAfterTheOpenFiles`:
    - `files` returns the old rows first (unchanged), then rows with `source` in the four new values;
    - `store:7cf0812f` has `kind = "commit"` and `owner.commit = sha`;
    - a session note written into the docs dir has a `note:<id>` row with `kind = "file"`.
  - `testFileFocusOpensAStoreItemAndSelectsItsRow`: `file_focus` `{"file_id":"store:7cf0812f"}` replies `focused store-overview store:7cf0812f "<title>"`. After it, `OverviewService.open?.overview?.id == "7cf0812f"` and the panel's `selectedItemId == "store:7cf0812f"`. Create the panel as the existing panel tests do.
  - `testFileFocusOnAChildThreadSelectsTheChild`: a thread contained by a review or overview in the fixture is focused by its `store:<root id>`. The open document's `viewKind` is `notes:<root id>:` and `selectedItemId` is that id. If the fixture has no contained thread, add a path-contained line note to the test's store.
  - `testFileFocusOnASessionNoteOpensItsLines`: write a note file and send `file_focus note:<id>`. The reply is `focused session-note note:<id> "<summary>"`, the working file opens with the note's line selected, and `selectedItemId == "note:<id>"`.
  - `testFileFocusOnAFreshStoreRecordReReadsOnce`: write a new overview record into the test store (as the test's existing store setup writes records), then focus `store:<new id>` before any refresh. It succeeds. `file_focus store:nope` replies the error `no item store:nope`.
  - `testFilesWithNothingKeepsTodaysReply`: with no store records and no session docs, the reply has `detail: no open files` and no `files`. Use a scratch project setup if the fixture cannot be emptied, or assert it on `ItemRows.rows(emptyList(), null)` plus the handler's empty-list rule.
- [ ] **Step 2: Run** `./test.sh --tests "dev.ggnotes.overview.OverviewFlowIntegrationTest"`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement** `ItemOpener`, `revealItem`, the panel delegation (behaviour unchanged), `showNote(focus)`, `files` and `fileFocus`.
- [ ] **Step 4: Run** the full `./test.sh`. Expected: all PASS, and the existing `files` / `file_focus` assertions at lines ~163–191 and ~248 are unchanged.
- [ ] **Step 5: Commit** `git commit -am "file_focus and files for every note item: store:<id>, note:<id>"`

### Task 3: Protocol doc, skills, release

**Files:**
- Modify: `docs/gg-steer/PLUGIN.md`
  - §1 table row for `gg session files --to ide`: add the item rows.
  - §4 `files` and `file_focus` rows: the new rows, the ids, the replies `focused <source> <id> "<title>"` and `no item <id>`.
  - §5: the row fields `kind` and `owner`, and the order.
  - Record it as an IDE-side extension that gg ignores (spec §4.3 "gg compatibility").
- Modify: `agent-skills/agent-overview/FILES.md` §11. Rewrite it in ASD-STE100 style (short sentences, active voice):
  1. Send `files` (§6 recipe).
  2. Pick the row by `title`, `kind`, `source` or `created` (the sources and kinds are listed).
  3. Send `file_focus` with its `id` (`store:<id>`, `note:<id>` or a session overview id).
  4. The reply is `focused …`. A reply `no item <id>`, or a plugin older than 0.6.0 (no row with a `kind`), means: fall back to the `navigate` + `hint_kind`/`hint_id` recipe of §9 "Focus it now" for a store review or overview.
  5. No fresh `ide.json`: one line saying where the item is in the tool window (By Date: its day; By Type: its kind, then its day).

  Also in §9 "Focus it now": prefer `file_focus` with `store:<id>`, and keep `navigate` as the fallback. In `agent-skills/agent-overview/SKILL.md`, `agent-review/SKILL.md` and `agent-cross-review/SKILL.md`, check that their "Show an existing one" pointers still say §11. Change no other skill text.
- Modify: `AGENTS.md` §6 (live steering) — one line for the item rows and ids. `CLAUDE.md` steering paragraph — `ItemRows` among the pure steer pieces.
- Modify: `build.gradle.kts` → `0.6.0+`. `plugin.xml` `<change-notes>` → a 0.6.0 entry on top: agents can focus every item the tool window shows (`files` lists them, `file_focus store:<id>` / `note:<id>`).

- [ ] **Step 1: Edit** as listed.
- [ ] **Step 2: Run** `./test.sh`. Expected: all PASS (record the counts).
- [ ] **Step 3: Run** `JAVA_HOME=$(ls -d ~/.sdkman/candidates/java/21* | head -1) ./gradlew verifyPlugin`. Expected: no missing classes and no INTERNAL_API_USAGES in new code.
- [ ] **Step 4: Commit** `git commit -am "AgentNotes 0.6.0: agents focus every note item"`

Finishing (not a task): merge gate, then `./dist.sh /mnt/t/others/gg-notes/dist` and copy the skills to `/mnt/t/others/gg-notes/skills/`.
