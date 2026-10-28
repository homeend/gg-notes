# Note items and the By Date / By Type views — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Agent Notes tool window lists every top item of the repository (store overviews, store reviews, uncontained line-note threads, session overviews, unlinked session notes) by day (default) or by type → day, with a kind icon per row. The notes a review or overview contains become children of that item, not separate rows.

**Architecture:** A new pure package `dev.ggnotes.items` turns the existing `RepoModel` plus the session-doc index entries into `NoteItem`s (slice 2: kind, owner, date, containment, the two groupings), unit-tested with no IDE. Slice 3 rebuilds `OverviewToolWindowPanel` on those groupings, adds the View selector (two radio actions in a popup group), and changes Delete to work per item, plus "Remove Everything on This Commit". The open/overlay/anchor machinery is not changed.

**Tech Stack:** Kotlin (apiVersion 1.9), IntelliJ Platform 242+, JUnit 4 (pure tests), `BasePlatformTestCase`-style integration tests (`OverviewFlowIntegrationTest`).

**Spec:** `docs/superpowers/specs/2026-10-28-agent-focus-and-note-views-design.md` (§4.2 item model, §4.4 tool window, §7 calls). Slice 4 (§4.3 focus anything) is NOT in this plan; it consumes the ids and wire names fixed here.

**Branch:** `feat/note-views`, worktree `.claude/worktrees/note-views`, from master `aaa8843`. Slice 2 (Tasks 1–3) shows nothing to the user, so it merges together with slice 3 (Tasks 4–7) as one release, 0.5.0. Merge only on the user's OK.

## Global Constraints

- Kotlin `apiVersion` 1.9: no stdlib API newer than 1.9 (e.g. no `Enum.entries`, use `values()`). Run `./gradlew verifyPlugin` before release (Task 7).
- `dev.ggnotes.items` is pure Kotlin. It has no `com.intellij.*` import, and its tests are plain JUnit 4 classes like `RemovalPlanTest`.
- Radio actions are `AnAction` + `Toggleable` with `Toggleable.setSelected(e.presentation, …)` in `update()`, never `ToggleAction`. `getActionUpdateThread() = EDT`.
- Every user-visible string goes through `GgNotesBundle` (`messages/GgNotesBundle.properties`). Actions use `action.<id>.text` / `action.<id>.description`, and groups use `group.<id>.text`.
- No git on the EDT. No direct write to store files: deletes go through `StoreRemoval` (gg batch) or `SteerService.delete*` (session files).
- Frozen ids stay as they are: tool window `GgNotes`, action ids `GgNotes.*`, package `dev.ggnotes`.
- Wire names (slice 4 reads them):
  - `ItemKind`: `branch`, `commit`, `pull-request`, `file`, `other`.
  - `ItemSource`: `overview` (session overview, as today's `files` reply), `session-note`, `store-overview`, `store-review`, `store-thread`.
  - Ids: `store:<record id>`, `note:<session note id>`, and the session overview's own file id.
- Tests run with `./test.sh` (full) or `./test.sh --tests "dev.ggnotes.items.*"`; results are in `build/test-results/test/TEST-*.xml`.

## Rulings made while planning (the user can overturn these)

1. **Store date** = `created ?: updated`. A store record with neither is still listed, in a last day group with a null date ("No date"). Session date = `DocIndex.Entry.time` (created, else file mtime), as the spec says.
2. **File items are per thread**, as spec §4.2 says, not per file: 6 uncontained notes on one commit = 6 rows that day.
3. **Containment by document files:**
   - A store review contains the uncontained threads on its commit whose path is in `review.document.files[].path`. A `ReviewContent.Prose` review contains every thread on its commit. A `CannotRender` review contains nothing.
   - The same rule applies to a store overview with `DocumentResult.Ok`.
   - A working review applies it to the live threads of its own worktree.
   - `NoteRecord.files` (reviewed blobs) is not used.
4. **One parent:** first the reviews, then the overviews. Inside each group the newest wins (by item time), then the smallest id. A session note linked by several session overviews goes to the newest overview.
5. **Kinds:** a store overview from `previews.toml`, or one with a `prNumber`, is Pull request. Every other store overview is Commit.
6. **Delete removes the item with its children** (spec §4.4). This applies to session overviews too: their linked notes are unlinked with them, and the confirmation names the count. "Remove Everything on This Commit" = today's `RemovalPlan.forCommit`.
7. **Dead code:** `RemovalPlan.forFile` / `forWorking`, `Kind.FILE` / `Kind.WORKING`, their tests and the `remove.confirm.FILE` / `remove.confirm.WORKING` keys go. `OverviewGrouping.rowText` stays (tooltips). `OverviewGrouping.group` / `CommitGroup` stay only if something still calls them; otherwise they go too.

## Review Focus

- A store record with no `created` and no `updated` is still listed, in the "No date" group, last (Task 3 test).
- A record created at `2026-10-27T23:30Z` is listed under 2026-10-28 for a `+02:00` zone (Task 3 test): days are local days.
- Every thread id and every session note id appears exactly once in the flattened item tree, either as a top item or as a child (Task 2 invariant test). The same check covers scoped threads and path-contained threads.
- A session note linked by an overview is a child. When that overview is removed, the note is a top File item again (Task 2 test: build twice).
- After a refresh, and after switching the view, the row of the open document is selected again (Task 6 integration test).

---

## Slice 2 — the item model (pure)

### Task 1: Item types, kinds, owners and dates

**Files:**
- Create: `src/main/kotlin/dev/ggnotes/items/NoteItem.kt`
- Create: `src/main/kotlin/dev/ggnotes/items/NoteItems.kt`
- Create: `src/test/kotlin/dev/ggnotes/items/NoteItemsTest.kt`
- Create (fixture): `examples/items/commits.toml`, `examples/items/previews.toml`, `examples/items/worktrees/<key>.toml`. Build them by hand. Copy the record shapes from `examples/commits.toml` (overview record lines 3–23, review record 87–108, line note 109–147) and `examples/worktrees/cc36f09eabab0c81.toml`.

**Interfaces:**
- Produces (`NoteItem.kt`):
  ```kotlin
  enum class ItemKind(val wire: String) { BRANCH("branch"), COMMIT("commit"), PULL_REQUEST("pull-request"), FILE("file"), OTHER("other") } // declaration order = By Type order
  enum class ItemSource(val wire: String) { SESSION_OVERVIEW("overview"), SESSION_NOTE("session-note"), STORE_OVERVIEW("store-overview"), STORE_REVIEW("store-review"), STORE_THREAD("store-thread") }
  data class Owner(val commit: String? = null, val branch: String? = null, val pr: Int? = null, val preview: String? = null, val path: String? = null, val first: Int? = null, val last: Int? = null, val worktree: String? = null)
  sealed class Payload {
      data class StoreOverview(val overview: Overview, val replies: List<NoteRecord>) : Payload()
      data class StoreReview(val review: Review) : Payload()
      /** commit = "" and worktree != null for a live thread (the openNotes arguments). */
      data class StoreThread(val thread: NoteThread, val commit: String, val worktree: String?) : Payload()
      data class SessionOverview(val entry: DocIndex.Entry<SessionOverviewDoc>) : Payload()
      data class SessionNote(val entry: DocIndex.Entry<SessionNoteDoc>) : Payload()
  }
  data class NoteItem(val id: String, val kind: ItemKind, val source: ItemSource, val title: String, val owner: Owner,
                      val author: String, val time: java.time.Instant?, val payload: Payload, val children: List<NoteItem>)
  ```
- Produces (`NoteItems.kt`): `object NoteItems { fun build(model: RepoModel, sessionOverviews: List<DocIndex.Entry<SessionOverviewDoc>>, sessionNotes: List<DocIndex.Entry<SessionNoteDoc>>): List<NoteItem> }`. In this task the result is every top item with `children` = the review's `scopedThreads` only (a review's scoped threads as STORE_THREAD items). Task 2 adds the other containment.

Mapping (spec §4.2 table plus Ruling 5):

| payload | kind | id | title | owner | time |
|---|---|---|---|---|---|
| StoreOverview | PULL_REQUEST if `prNumber != null` or `sourceFile.fileName == "previews.toml"`, else COMMIT | `store:<o.id>` | `o.title` | commit, branch (label), pr, preview | `created ?: updated` |
| StoreReview | COMMIT/RANGE/PAIR → COMMIT; BRANCH → BRANCH; PULL_REQUEST/MERGE_PREVIEW → PULL_REQUEST; WORKING → OTHER | `store:<r.id>` | `r.title` | commit (null when empty), branch only for BRANCH, pr, preview, worktree only for WORKING | `record.created ?: record.updated` |
| StoreThread (from `CommitEntry.files`, `WorkingEntry.files`, review `scopedThreads`) | FILE | `store:<root.id>` | `root.summary` | commit (null for live), path, first, last, worktree (live only) | `root.created ?: root.updated` |
| SessionOverview | OTHER | `doc.id` | `doc.title` | (empty) | `Instant.ofEpochMilli(entry.time)` |
| SessionNote | FILE | `note:<doc.id>` | `doc.summary` | path, first = start, last = end | `Instant.ofEpochMilli(entry.time)` |

Fixture cases the TOML must hold (one commit `C1`, one other commit `C2`, one worktree):
- a commit overview on `C1` whose document lists `a.go`;
- a commit review on `C1` (scope `C1^..C1`) whose document lists `b.go`;
- a branch review on `C2` (`address.branch = "feat/x"`);
- a PR overview in `previews.toml`;
- a prose review on `C2`: its rationale is not a review document, so it decodes as `ReviewContent.Prose`; see `ReviewsTest` for how;
- line notes on `C1`: `a.go:3`, `b.go:5`, `c.go:7`, and `a.go:9` with a reply;
- a line note on `C2` (`d.go:1`);
- one record with no `created` and no `updated` (a line note on `C1`, `e.go:2`);
- in the worktree: a working review listing `w.go`, plus live notes `w.go:1` and `x.go:4`.

The model under test is `StoreModel.build(StoreReader().read(dir), headSha = C1, subjects = emptyMap())` with `dir` a temp copy of `examples/items/`, as in `RemovalPlanTest.model()`.

- [ ] **Step 1: Write the fixture and the failing tests** in `NoteItemsTest`:
  - `kinds follow gg's address`: the item kinds by id are `COMMIT` (overview, commit review), `BRANCH` (branch review), `PULL_REQUEST` (preview overview), `OTHER` (working review), `FILE` (every thread).
  - `ids are namespaced`: every store item id starts with `store:`; a session note id is `note:<id>`; a session overview id is its file id. The session entries are built with `DocIndex.Entry(doc, size = 0, mtime = 0, time = <millis>)` and `SessionDocParser.overview/note(id, bytes)`, as `DocIndexTest` builds them.
  - `owners`: the branch review has `owner.branch == "feat/x"` and `owner.commit == C2`; the working review has `owner.worktree` set and `owner.commit == null`; a live thread has `owner.commit == null`, `path == "x.go"`, `first == 4`.
  - `the time is created, else updated, else null`: the `e.go:2` item has `time == null`, and the others equal the TOML `created`.
  - `a review's scoped threads are its children`: build a scoped case from `examples/` (the review `c0ffee01` and its scoped notes). Its children ids are `store:` + `scopedThreads` root ids, and those ids are not top items.
- [ ] **Step 2: Run** `./test.sh --tests "dev.ggnotes.items.*"`. Expected: compilation FAIL (`NoteItems` is undefined).
- [ ] **Step 3: Implement** `NoteItem.kt` and `NoteItems.build` as in the table. Top-item order is not fixed here; `ItemViews` (Task 3) sorts.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `git add examples/items src/main/kotlin/dev/ggnotes/items src/test/kotlin/dev/ggnotes/items && git commit -m "Note items: kinds, owners, dates from the store and session docs"`

### Task 2: Containment — only top items are rows

**Files:**
- Modify: `src/main/kotlin/dev/ggnotes/items/NoteItems.kt`
- Test: `src/test/kotlin/dev/ggnotes/items/NoteItemsTest.kt`

**Interfaces:**
- Consumes: Task 1 types.
- Produces: `NoteItems.build` returns only top items. Children are ordered by `owner.path`, then `owner.first`, then id. Store children are STORE_THREAD items; session children are SESSION_NOTE items.

Rules (Rulings 3 and 4):
1. Store, per `CommitEntry`, for each thread in `entry.files`:
   - The candidate reviews are the reviews on this commit whose content is `Prose`, or whose `document.files` contain the thread's path.
   - The candidate overviews are those with `DocumentResult.Ok` whose `document.files` contain the path.
   - The parent is the first review by (time desc, id asc); else the first overview by the same order; else the thread is a top FILE item.
2. Store, per `WorkingEntry`: the same, with the worktree's working reviews and its `files`.
3. Session: a note whose id is in some session overview's `doc.anchors.mapNotNull { it.note }` is a child of the newest such overview (time desc, id asc). Otherwise it is a top FILE item. An anchor naming a missing note adds nothing.

- [ ] **Step 1: Write failing tests:**
  - `a note on a file the review lists is the review's child`: `b.go:5` is under the commit review.
  - `a note on a file only the overview lists is the overview's child`: `a.go:3` and `a.go:9` are under the overview.
  - `review before overview`: add `a.go` to the commit review's file list in a second fixture variant (or build the variant in the test by copying the TOML and editing the line). `a.go:3` is then under the review.
  - `a prose review contains every note on its commit`: `d.go:1` is under the prose review on `C2`.
  - `an uncontained note is a top file item`: `c.go:7` and `e.go:2` are top items.
  - `a working review contains live notes on its files`: `w.go:1` is a child; `x.go:4` is a top item.
  - `a session note linked by an overview is its child, and a top item again when the overview goes`: build with overviews `[O1 linking n1]` and notes `[n1, n2]`: `n1` is a child of `O1`, `n2` is a top item. Build again with no overviews: both are top items. When two overviews link `n1`, it sits under the newer one.
  - `every thread and session note appears exactly once`: flatten the items recursively. The multiset of `store:` thread ids equals every root id in `model.commits[*].files`, `model.working[*].files` and `reviews[*].scopedThreads`, each once. The same check covers `note:` ids against the session notes passed in.
- [ ] **Step 2: Run** `./test.sh --tests "dev.ggnotes.items.*"`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement** the three rules in `NoteItems.build`.
- [ ] **Step 4: Run.** Expected: PASS (Task 1 tests too).
- [ ] **Step 5: Commit** `git commit -am "Note items: a contained note is a child of one review or overview"`

### Task 3: The two groupings

**Files:**
- Create: `src/main/kotlin/dev/ggnotes/items/ItemViews.kt`
- Create: `src/test/kotlin/dev/ggnotes/items/ItemViewsTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class DayGroup(val day: java.time.LocalDate?, val items: List<NoteItem>)   // day == null: "No date"
  data class TypeGroup(val kind: ItemKind, val days: List<DayGroup>)
  object ItemViews {
      fun byDate(items: List<NoteItem>, zone: java.time.ZoneId): List<DayGroup>
      fun byType(items: List<NoteItem>, zone: java.time.ZoneId): List<TypeGroup>
      /** The grey label after a row's title (spec §4.4 owner label). */
      fun ownerLabel(item: NoteItem): String
  }
  ```
- Order:
  - Days are newest first, and the null day comes last.
  - Within a day, items are newest first, then by id.
  - Type groups follow the `ItemKind` declaration order (Branch, Commit, Pull request, File, Other), and empty groups are left out.
  - Children are not regrouped.
- `ownerLabel`:
  - PULL_REQUEST: `PR #<n>`, else `preview`.
  - BRANCH: the branch name.
  - COMMIT: the 7-character sha.
  - FILE: `path:N` or `path:N-M`, then ` · <sha7>` when `owner.commit` is set.
  - OTHER: `working` for a store item with `owner.worktree`, else `session`.

- [ ] **Step 1: Write failing tests** with items built directly (a small `item(id, kind, time)` helper; the payload is a dummy `SessionOverview` entry):
  - `days are local days, newest first`: `2026-10-27T23:30:00Z` under `ZoneId.of("+02:00")` falls on `2026-10-28`, and under `UTC` on `2026-10-27`.
  - `items in a day are newest first, then id`.
  - `undated items come last under a null day`.
  - `by type: kinds in fixed order, empty ones omitted, days inside`.
  - `owner labels`: one assertion per kind as listed above.
- [ ] **Step 2: Run** `./test.sh --tests "dev.ggnotes.items.*"`. Expected: FAIL.
- [ ] **Step 3: Implement** `ItemViews`.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `git add -A src && git commit -m "Note items: By Date and By Type groupings, owner labels"`

---

## Slice 3 — the views

### Task 4: Delete per item

**Files:**
- Modify: `src/main/kotlin/dev/ggnotes/store/RemovalPlan.kt` (add `forItem`; drop `forFile`, `forWorking`, `Kind.FILE`, `Kind.WORKING`; Ruling 7)
- Create: `src/main/kotlin/dev/ggnotes/items/SessionRemoval.kt`
- Modify: `src/test/kotlin/dev/ggnotes/store/RemovalPlanTest.kt`; create `src/test/kotlin/dev/ggnotes/items/SessionRemovalTest.kt`
- Modify: `src/main/resources/messages/GgNotesBundle.properties`

**Interfaces:**
- Produces:
  - `RemovalPlan.forItem(item: NoteItem): RemovalPlan?`. It returns null for a session item. The lines are each store child's `note rm <root id>` (children first, recursively), then the item's own line:
    - `overview rm <id>` for an overview, Kind OVERVIEW;
    - `note rm <id>` for a review, Kind REVIEW;
    - `note rm <root id>` for a thread, Kind THREAD.
    - The label is `item.title`, or for a thread the existing `path:N` form.
  - `data class SessionRemoval(val overviews: List<String>, val notes: List<String>) { companion object { fun of(item: NoteItem): SessionRemoval? } }`. It returns null for a store item. A session overview gives its id plus its children's note ids; a session note gives its own id.
- Bundle:
  - `remove.confirm.OVERVIEW` becomes `Delete the overview "{0}", its replies and the notes shown under it ({1} records)?`
  - New key `remove.confirm.SESSION_WITH_NOTES=Delete the session overview "{0}" ({1}) and the {2} session notes it links? Their files are removed from the worktree's session docs; every gg reader of this worktree loses them.`
  - New key `action.GgNotes.RemoveCommit.text=Remove Everything on This Commit`
  - New key `action.GgNotes.RemoveCommit.description=AgentNotes: delete every overview, review and note of this item's commit from gg's notes store (gg does it)`
  - Remove `remove.confirm.FILE` and `remove.confirm.WORKING`.

- [ ] **Step 1: Write failing tests:**
  - `RemovalPlanTest`: `an item goes with its children, children first`. Use Task 1's fixture: the overview item gives `["note rm <a.go:3 id>", "note rm <a.go:9 id>", "overview rm <id>"]`; the review item gives its children, then `note rm <review id>`; a session item gives `null`.
  - Delete the `forFile` / `forWorking` tests.
  - `SessionRemovalTest`: an overview with two linked notes gives `SessionRemoval([O1], [n1, n2])`; a lone note gives `([], [n2])`; a store item gives `null`.
- [ ] **Step 2: Run** `./test.sh --tests "dev.ggnotes.store.RemovalPlanTest" --tests "dev.ggnotes.items.*"`. Expected: FAIL.
- [ ] **Step 3: Implement.** Then `grep -rn "forFile\|forWorking\|Kind.FILE\|Kind.WORKING" src` must print nothing except the panel lines that Task 6 replaces. Leave those for Task 6, and note this in the report.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `git commit -am "Delete per item: store children first, session overview with its notes"`

### Task 5: The View selector

**Files:**
- Create: `src/main/kotlin/dev/ggnotes/overview/ViewModeActions.kt`
- Modify: `src/main/kotlin/dev/ggnotes/overview/OverviewService.kt` (add the view state)
- Modify: `src/main/resources/META-INF/plugin.xml`, `src/main/resources/messages/GgNotesBundle.properties`
- Test: `src/test/kotlin/dev/ggnotes/overview/OverviewFlowIntegrationTest.kt` (a new test method)

**Interfaces:**
- Produces:
  - `enum class NoteView { DATE, TYPE }` in `dev.ggnotes.overview`.
  - `OverviewService.view: NoteView`. Its getter reads `PropertiesComponent.getInstance(project).getValue("ggnotes.view")` (missing or unknown → DATE). Its setter writes the property and then notifies the snapshot listeners, so the panel rebuilds. Mirror how `listHidden` notifies.
  - `abstract class ViewModeAction(private val mode: NoteView) : AnAction(), Toggleable`, with `update` → `Toggleable.setSelected(e.presentation, service.view == mode)` and `actionPerformed` → `service.view = mode`. The subclasses are `ViewByDateAction` (DATE) and `ViewByTypeAction` (TYPE).
- `plugin.xml`:
  - Actions `GgNotes.ViewByDate` and `GgNotes.ViewByType`.
  - `<group id="GgNotes.ViewMode" popup="true" icon="AllIcons.Actions.GroupBy">` holding both actions.
  - In `GgNotes.Toolbar`, reference the group after `GgNotes.Refresh`, with a separator.
- Bundle:
  - `group.GgNotes.ViewMode.text=View`
  - `action.GgNotes.ViewByDate.text=By Date`
  - `action.GgNotes.ViewByDate.description=AgentNotes: list notes by the day they were added`
  - `action.GgNotes.ViewByType.text=By Type`
  - `action.GgNotes.ViewByType.description=AgentNotes: list notes by what they belong to (branch, commit, pull request, file, other), then by day`

- [ ] **Step 1: Write the failing test** `testViewSelectorDefaultsToDateAndIsRemembered`:
  - A fresh project property gives `service.view == DATE`.
  - `ViewByTypeAction.update` on a `TestActionEvent` shows not selected (`Toggleable.isSelected(presentation)` false). `actionPerformed` then sets TYPE, and `PropertiesComponent…getValue("ggnotes.view") == "TYPE"`.
  - `ViewByDateAction.update` now shows not selected.
  - Reset to DATE in `finally`.
- [ ] **Step 2: Run** `./test.sh --tests "dev.ggnotes.overview.OverviewFlowIntegrationTest"`. Expected: FAIL (compilation).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run.** Expected: PASS. Also run `./test.sh --tests "dev.ggnotes.GgNotesBundleTest"`: PASS.
- [ ] **Step 5: Commit** `git commit -am "View selector: By Date (default) / By Type, remembered per project"`

### Task 6: The tool window on items

**Files:**
- Modify: `src/main/kotlin/dev/ggnotes/overview/OverviewToolWindowPanel.kt` (rebuild, nodes, renderer, selection, actions)
- Modify: `src/main/kotlin/dev/ggnotes/steer/SteerService.kt` (add `showNote`)
- Modify: `src/main/resources/messages/GgNotesBundle.properties`
- Test: `src/test/kotlin/dev/ggnotes/overview/OverviewFlowIntegrationTest.kt`

**Interfaces:**
- Consumes: `NoteItems.build`, `ItemViews.byDate/byType/ownerLabel`, `RemovalPlan.forItem`, `SessionRemoval.of`, `OverviewService.view`.
- Produces:
  - `SteerService.showNote(s: RootSession, entry: DocIndex.Entry<SessionNoteDoc>)` (EDT). It opens `<s.topLevel>/<doc.path>` at `s.marks.placementOf(doc)` with `OpenFileDescriptor` and selects those lines. The precedent is `SteerHandler.kt:224`.
  - For tests: `internal fun rowTexts(depth: Int): List<String>` (the rendered first text fragment of every row at that tree depth) and `internal val selectedItemId: String?`.

Node classes (replace `GroupNode`, `WorkingNode`, `FileNode`, `ThreadNode`, `SessionGroupNode`, `SessionOverviewNode`, `OverviewNode`, `ReviewNode`):
- `RepoNode(repo)`: kept; on top only when there are several roots.
- `TypeNode(kind)`, `DayNode(day: LocalDate?)`: no open, no delete, no identity.
- `ItemNode(item, repo, session: RootSession?, tips: Map<String, String?>, treeChanged: List<String>, subject: String?, unresolved: List<String>)`.
- `ReplyNode(reply)`: kept, under a thread item or an overview item.

`rebuild()`:
- Per repo, `items = NoteItems.build(repo.model, s?.docs?.index?.overviews().orEmpty(), s?.docs?.index?.notes().orEmpty())` with `s = steer.sessionFor(repo.repo.root)`.
- The zone is `ZoneId.systemDefault()`.
- By `service.view`:
  - DATE: DayNode → ItemNode.
  - TYPE: TypeNode → DayNode → ItemNode.
- Each ItemNode gets its children recursively. A thread item's `thread.replies` and an overview item's `replies` become ReplyNodes.
- The `subject` is `repo.model.commits.firstOrNull { it.commit == item.owner.commit }?.subject`.
- Status line, `expandAll`, `fitTree` and re-selection stay as today.

Row renderer:
- The kind icon:
  - BRANCH: `AllIcons.Vcs.Branch`
  - COMMIT: `AllIcons.Vcs.CommitNode`
  - PULL_REQUEST: `AllIcons.Vcs.Merge`
  - FILE: `AllIcons.FileTypes.Text`
  - OTHER: `AllIcons.Actions.Preview`

  If one does not compile against 242, use another `AllIcons` constant and say which in the report.
- Then the title, and in grey: `ownerLabel · author · HH:mm`. For a DATE-view child or a TYPE-view item the day is the parent, so only the time is shown. An item in the null day shows no time.
- Keep today's badges by payload:
  - `RowBadge.of` / `ofReview` (tips);
  - counts, prose, cannot render, tree changed;
  - replies count;
  - resolved / draft / old side / meta on threads;
  - `on screen` and `⚠ N unresolved` on session overviews.
- When `owner.commit != null && subject == null`, append `toolwindow.unknownCommit` in error style. The tooltip is the subject or the commit.
- DayNode text: `ISO_LOCAL_DATE`, or `toolwindow.noDate` (new key `toolwindow.noDate=No date`).
- TypeNode text uses new keys: `toolwindow.kind.BRANCH=Branch`, `toolwindow.kind.COMMIT=Commit`, `toolwindow.kind.PULL_REQUEST=Pull request`, `toolwindow.kind.FILE=File`, `toolwindow.kind.OTHER=Other`.
- Delete the now-unused `toolwindow.session`, `toolwindow.session.tooltip` and `toolwindow.working`, but first grep for other users.

Selection opens by payload:
- StoreOverview → `service.open`
- StoreReview → `service.openReview`
- StoreThread → `service.openNotes(listOf(thread), path, commit, worktree, repo)`
- SessionOverview → `steer.showOverview(s, entry, keepAnchor = false)`
- SessionNote → `steer.showNote(s, entry)`
- ReplyNode → its parent item's open

Re-selection replaces identity equality with `fun shows(item, open: Overview): Boolean`:
- StoreOverview: `overview.identity == open.identity`
- StoreReview: `open.viewKind == "review:${id}:"`
- StoreThread: `open.viewKind == "notes:${root.id}:"` (`OverviewService.openNotes` sets `viewKind = "notes:$ids:"`)
- SessionOverview: `steer.sessionIdentity(s, doc) == open.identity`
- SessionNote: never (it opens an editor, not a document)

`openSelectedFullArea` applies to every ItemNode except SessionNote, and to ReplyNode.

Actions on the tree popup (Delete key and Ctrl+C bindings unchanged):
- **Delete** (`RemoveFromStoreAction`):
  - Store item: `RemovalPlan.forItem`, confirm with `remove.confirm.<kind>` + `remove.confirm.note`, then `StoreRemoval.perform`.
  - ReplyNode: `forReply`.
  - Session item: `SessionRemoval.of`. Confirm with `remove.confirm.SESSION` when there are no linked notes, `remove.confirm.SESSION_WITH_NOTES` (title, id, count) when there are, and `remove.confirm.SESSION_NOTE` for a note. Then `steer.deleteNote` for each note and `steer.deleteOverview`.
  - The label is `RemoveFromSession` for session items, as today.
- **Remove Everything on This Commit** (new inner action, bundle keys from Task 4): visible on a store ItemNode whose `owner.commit` matches a `CommitEntry` in its repo's model. It runs `RemovalPlan.forCommit(entry)`, confirms with `remove.confirm.COMMIT`, then calls `StoreRemoval.perform`.
- **Copy Link**:
  - session overview item: `steer.overviewLink`;
  - session note item: `steer.noteLink`;
  - otherwise hidden.

- [ ] **Step 1: Write failing integration tests** in `OverviewFlowIntegrationTest`. Use its existing fixture repo (overview `7cf0812f` on `sha`) and the `panel`/`refreshSync` pattern of `testCloseClearsTheSelectionHidesTheDocumentHalfAndDisablesDocumentOnly`.
  - `testDateViewListsDaysThenItems`: with DATE, `rowTexts(0)` is all ISO dates (or "No date"), newest first. `rowTexts(1)` contains the overview's title.
  - `testTypeViewListsKindsThenDays`: set TYPE. `rowTexts(0)` is a subsequence of `[Branch, Commit, Pull request, File, Other]` and contains `Commit`; `rowTexts(1)` is dates. Reset to DATE in `finally`.
  - `testOpenDocumentStaysSelectedAcrossRefreshAndViewSwitch`:
    - `service.open(overview, repo)` → `selectedItemId == "store:7cf0812f"`.
    - `refreshSync()` + dispatch: the same id stays selected.
    - Switch to TYPE: the same id stays selected.
    - Open a thread via `service.openNotes(listOf(thread), …)` → `selectedItemId == "store:<thread id>"`.
  - `testRemoveEverythingOnThisCommitIsOfferedOnACommitItem`: select the overview's row (through `service.open`). The popup group of the tree holds an enabled action whose text is `Remove Everything on This Commit`. Use `ActionUtil.getActions`/the popup group the panel installs; expose `internal val popupActions` if needed.
- [ ] **Step 2: Run** `./test.sh --tests "dev.ggnotes.overview.OverviewFlowIntegrationTest"`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement** `showNote`, then the panel as described. Keep `fitTree`, `TreeShare`, the splitter, the double-click behaviour and the status messages unchanged.
- [ ] **Step 4: Run** the full suite `./test.sh`. Expected: all PASS, including the existing Close / double-click / Delete-key / Copy Link tests. If an existing test asserts today's tree layout, adapt the assertion to the new layout and list each such change in the report.
- [ ] **Step 5: Commit** `git commit -am "Tool window: By Date / By Type views on note items, delete per item"`

### Task 7: Docs, version, verifier

**Files:**
- Modify: `AGENTS.md` §4 ("Suggested shape"): rewrite the "group by commit" contract to the two views, the kinds and the containment.
- Modify: `AGENTS.md` §3.2, if its acceptance prose names the commit groups or the Session group.
- Modify: `CLAUDE.md`, the layout paragraph: add `dev.ggnotes.items` (NoteItems, ItemViews, SessionRemoval) to the pure layers. Replace "feeds the tool window's "Session" group" with the item views.
- Modify: `examples/README.md`, if it describes the tree layout. Add one line for `examples/items/`.
- Modify: `build.gradle.kts`: version `0.5.0+`.
- Modify: `src/main/resources/META-INF/plugin.xml` `<change-notes>`: add a 0.5.0 entry on top, in the style of the 0.4.6 entry. It says:
  - the By Date (default) / By Type views with kind icons;
  - contained notes nest under their review or overview;
  - session notes are listed;
  - Delete removes an item with its children;
  - Remove Everything on This Commit.

- [ ] **Step 1: Edit the docs, version and change notes** as listed.
- [ ] **Step 2: Run** `./test.sh`. Expected: all PASS (record the counts from `build/test-results/test/TEST-*.xml`).
- [ ] **Step 3: Run** `JAVA_HOME=$(ls -d ~/.sdkman/candidates/java/21* | head -1) ./gradlew verifyPlugin`. Expected: no compatibility problems and no INTERNAL_API_USAGES for the new code.
- [ ] **Step 4: Commit** `git commit -am "AgentNotes 0.5.0: note views by date and by type"`

Finishing (not a task): show the user, run the merge gate, then `./dist.sh /mnt/t/others/gg-notes/dist` and copy the skills to `/mnt/t/others/gg-notes/skills/` (memory: copy-skills-after-build).
