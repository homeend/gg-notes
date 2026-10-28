# Agents without gg, owned session docs, and the date/type views — design

**Date:** 2026-10-28 · **Status:** draft for the user's approval ·
**Repo:** gg-notes (AgentNotes plugin + `agent-skills/`), with one shared-spec
change in gigagit.

## 1. Why

The user works with agents in three places: inside gg (an agent gg started),
and outside gg — the IDE terminal, JetBrains AI Assistant, Air. Outside gg, an
agent asked to "focus the review in the IDE" answered that it needed gg (to
delete notes, it turned out). The user wants:

1. **An agent outside gg does everything itself.** It writes the files, steers
   the IDE through the inbox, and calls the `gg` CLI only where gg owns the
   data (deleting a gg-store record) — and finds `gg` on PATH itself. Inside
   gg, it keeps using gg's verbs.
2. **What the IDE shows, the agent can focus.** Whatever the Agent Notes tool
   window of the IDE opened on worktree W lists, an agent working in W can
   bring to the front.
3. **Notes belong to what they address.** As in gg, a note or document has an
   owner — a commit, a branch, a pull request, a file, or nothing — and the
   IDE on worktree W shows the documents whose owner W can see. An agent's
   session docs get the same kind of address gg's store records have.
4. **Two views, date first.** The tool window shows a **Date** view (default:
   day → everything added that day, newest first, an icon per kind) or a
   **Type** view (Branch / Commit / Pull request / File / Other → day → items).
   Goal: "find today's review" in one glance. The user's own sketch:

   ```text
   By Date                          By Type
   2026-10-10                       branch
       commit review                    2026-10-10
       branch A review                      branch A review
       file overview                    2026-10-01
       some other overview                  branch B review
       branch B review                      branch C review
   2026-10-01                       commit
       some other overview              2026-10-10
       branch B review                      commit review
                                    file
                                        2026-10-10
                                            file overview
                                    others
                                        2026-10-01
                                            some other overview
   ```

## 2. Goals, non-goals, anti-goals

**Goals:** the four points above; every slice unit-tested in the pure layer
(`dev.ggnotes.store`, `dev.ggnotes.session`, `dev.ggnotes.steer`) the way the
repo already does it.

**Non-goals:** gg's own reader (TUI/web) honouring the new address keys — that
is a gigagit task this design only specifies; writing into the gg store from
the IDE or an agent other than through gg; PR comments; a search box.

**Anti-goals (a failure even if it "works"):**
- an agent outside gg telling the user to start gg, or to set a path/property;
- an agent writing or deleting gg-store files directly;
- the views listing a review AND, again as separate rows, the line notes it
  already contains (user: "just top addressable notes");
- an agent anchoring lines it did not read from the commit it names.

## 3. Facts this rests on (checked 2026-10-28)

- **gg's owner model** (`internal/model/note.go:264-316`, `model.go:659`):
  a record's `address {Worktree, Branch, Commit, ShelfID, Path, State}` says
  what owns it. Commit + path = a line note under that commit; commit, no
  path, tag `review` / `overview` = a commit review / permanent overview;
  worktree, no path, tag `review` = a working review; shelf id = a shelf
  note. `Address.Branch` alone "proves nothing" (a label). Branch ownership
  exists only for a range review (`preview_branch`, `domain.ReviewShownOn`);
  a `preview` scope belongs to a pull request or merge preview.
- The plugin already classifies reviews: `ReviewKind` = COMMIT, RANGE,
  BRANCH, PULL_REQUEST, MERGE_PREVIEW, PAIR, WORKING (`store/Reviews.kt:52,
  129-138`), and a review carries `scopedNotes`/`scopedThreads` — the line
  notes gg shows under it.
- **Inside gg** = the environment has `GG_SESSION_ID` (gg sets it, plus
  `GG_MCP_URL`/`GG_SESSION_TOKEN`, for every agent it starts, and strips them
  from a child started outside: `internal/agentsession/session.go:166`;
  `internal/cli/agent.go:42` uses the same test).
- Session docs (`docs/gg-session-docs/SPEC.md`, source of truth gigagit
  `docs/superpowers/specs/2026-10-18-gg-session-docs-design.md`): readers
  ignore unknown keys (§3.2, §3.3), so new optional keys are compatible.
- Deleting a gg-store record needs gg (cross-process O_EXCL lock, cascade of
  replies/resolutions); the `gg` CLI does it with no gg process running
  (`gg note rm <id>`, `gg overview rm <id>`, or `gg batch`). Deleting a
  session doc is `rm` of its file.
- The plugin finds `gg` without a setting since 0.4.6 (`GgLocator`, merged
  to master as 7622bb8). Slices branch from master after it.
- gg sets `Address.Branch` on a REVIEW only when it reviewed a branch
  (`domain/review.go:224`), so the plugin's `kindOf` (branch set → BRANCH) is
  gg's rule; gg's "was tip" (`ReviewKindOf`) is the same kind with a
  different badge. On a permanent OVERVIEW gg stores the save-time branch as
  a label only (`overview_save.go:70`).
- An agent outside gg lists store ids with the CLI: `gg note list`
  (gg on PATH; no gg process needed).

## 4. The parts

### 4.1 Agent skills: in or out of gg (`agent-skills/`)

Every `agent-*` skill gets one short rule at the top, and `FILES.md` gets the
recipes:

- **Where am I.** `GG_SESSION_ID` set → inside gg: use the `gg-*` skills and
  `gg session …` verbs. Not set → use these files; never ask the user to
  start gg.
- **Deleting.** A session doc: delete its file. A gg-store record (an id the
  IDE lists with `source` `store-*`, §4.4): `command -v gg` (PowerShell
  `Get-Command gg`) → `gg note rm <id>` / `gg overview rm <id>` in the
  worktree (ids: `gg note list`, or the IDE's `files` once §4.4 ships); no gg on PATH → tell the user in one line to use the tool
  window's **Remove from gg Store**. Never edit store files.
- **Focusing something that already exists** (new §6.1): when the IDE is
  live, send `files` (§4.4), pick the item by title / kind / date, send
  `file_focus` with its id. Not live → say where it is in the tool window.
- **Owner.** A note or overview the agent writes about a commit carries that
  commit (§4.2): `commit = $(git rev-parse <rev>)`; for the work on a branch,
  also `branch`; for a pull request, `preview`. Uncommitted work: no owner
  key (today's behaviour). The `lines` of a commit-owned note come from
  `git show <commit>:<path>`, not from the working file.

The gg skills (`gg-review`, `gg-overview`, `reviewing-with-gg`, `using-gg`)
are gigagit's; they get the mirror sentence ("`GG_SESSION_ID` unset and the
AgentNotes plugin in use → the `agent-*` skills") in gigagit, on a branch,
not merged without the user.

### 4.2 Session docs get an owner (shared spec)

New optional keys, same names in a note's TOML and an overview's front
matter:

| key | meaning |
|---|---|
| `commit` | full sha. The document is about this commit: a note's `start/end/lines` are lines of `<commit>:<path>`; an overview's anchors open files at this commit |
| `branch` | the branch the work was on (owner when `commit` is a range tip, like gg's `preview_branch`) |
| `preview` | a pull request or merge preview scope, gg's spelling (`<base>...refs/gg/pr/<n>`, `<target>...<source>`) |

Absent = today: the working tree of the directory's worktree.

Reader rules (plugin): a commit-owned overview opens like a store overview
(revision tab at the commit, banner with **Open current version**); a
commit-owned note is a box on that revision tab, and is cold-anchored on the
working file by its `lines` like today. A commit not in the repository: the
row is shown, marked missing (as store commits already are). Shown in the
IDE of worktree W: every session doc in W's directory (the directory is
already per worktree), whatever commit it names — answer 3 of the user: the
commit need not be checked out.

Spec work: a dated addendum in gigagit
(`docs/superpowers/specs/2026-10-28-session-docs-owner-design.md`) plus the
conformance examples, then refresh this repo's copy `docs/gg-session-docs/`.
`SessionDocParser` gains the keys (table tests from the examples).

### 4.3 The item model (pure, unit-tested)

One list of **items** per repository, built from `RepoModel` (store) and the
session docs, each item:
`{ id, source, kind, title, owner label, author, created, children }`.

**Kind** (icon + Type-view group):

| kind | store | session doc |
|---|---|---|
| Commit | permanent overview (its `branch` is a label, not an owner); review with ReviewKind COMMIT, RANGE or PAIR | overview with `commit` only |
| Branch | review with ReviewKind BRANCH (whether the branch is still at the reviewed commit or not — "was tip" is a badge) | overview with `branch` (the agent declares it) |
| Pull request | review with PULL_REQUEST or MERGE_PREVIEW; a `previews.toml` overview | overview with `preview` |
| File | a line-note thread not contained in a top item | a note not linked by any overview |
| Other | working review; anything unclassified | overview with no owner |

Shelf notes stay out: SPEC-part2 §7 keeps them out until the shelf store has
a reader spec, and the plugin does not read `shelf.toml`.

**Top items only.** A line note that a review or overview contains is NOT a
row of its own; it is a child of that item (reachable, not repeated).
Contained =
- store: in the review's `scopedThreads`; or on the same commit with its path
  among the review document's / overview document's files; for a prose review
  (no file list), any line note on the reviewed commit;
- session: linked by `note:<id>` from an overview.

A contained note has ONE parent: a review before an overview, then the
newest. Visibility is today's: every commit's records in the store (missing
commits marked) plus W's own working entries and W's session directory; no
branch filter.

**Date** = `created` (store record; session doc front matter), else the
file's mtime; shown and grouped in the IDE's local time zone.

### 4.4 Focus anything shown (steer, `PLUGIN.md`)

- `files` reply: after the open files and session overviews (today's rows,
  unchanged — gg as shipped reads them), one entry per other **top item and
  child** of §4.3: `{id, source, kind, title, author, created, owner,
  state}`, `source` = `session-note` | `store-overview` | `store-review` |
  `store-thread`, `kind` = `commit` | `branch` | `pull-request` | `file` |
  `other`, `owner` = `{commit?, branch?, preview?, path?}`. The exact shape
  is gigagit's owner addendum §4
  (`docs/superpowers/specs/2026-10-28-session-docs-owner-design.md`, branch
  `spec/session-docs-owner`).
- Ids: session overview = its file id (unchanged); session note =
  `note:<id>` (the anchor grammar); store = `store:<record id>`.
- `file_focus` with such an id: open it in the viewer (an overview/review in
  the document pane, a note/thread at its lines) and select its row in the
  tool window. Reply `focused <source> <id> "<title>"`. Unknown id →
  `no item <id>`.
- These are IDE-side extensions of the steer protocol; recorded in
  `PLUGIN.md` and sent to gigagit's steer spec as an addendum.

### 4.5 The tool window

- A selector in the toolbar: **By Date** (default) | **By Type**, a radio pair
  (`AnAction` + `Toggleable`, not `ToggleAction` — CLAUDE.md), remembered per
  project (`PropertiesComponent`).
- Replaces today's layout (Session group, commit groups, working entries).
- **By Date:** day nodes (`2026-10-28`), newest day first → the top items
  added that day, newest first, flat — no owner level between day and item.
  Each row: kind icon, title, owner label (`a1b2c3d`, `feat/x`, `PR 7`,
  `path:N`), author, time. Children (contained notes) expand under their item.
- **By Type:** Branch, Commit, Pull request, File, Other (empty groups
  hidden) → day nodes, newest first → items newest first (the same rows as
  By Date).
- **Delete** (replaces today's commit-group / worktree-group / file-node
  removal, which have no node now): **Remove from gg Store** / **Delete** on
  an item removes it with its children (a review with its contained notes);
  a commit-owned store item also offers **Remove Everything on This Commit**
  (today's `RemovalPlan.forCommit`). No delete on day or type nodes.
  AGENTS.md §4's "group by commit" contract is rewritten to these views.
- Several git roots: one repo node on top of either view, as today.
- Everything else stays: opening, Delete / Remove from gg Store, Copy Link,
  `on screen`, `⚠ N unresolved`, missing commits.

## 5. Slices (each ships alone, in order)

1. **Skills** (§4.1, minus the parts that need 2–4): in/out-of-gg rule,
   deletion recipe, "focus an existing session overview" via the directory.
   Fixes the AIR failure. No plugin change.
2. **Item model** (§4.3) for store records + today's session docs, pure, with
   tests. No UI change yet.
3. **Views** (§4.5) on the item model; default Date.
4. **Focus anything** (§4.4) + the skill section using it.
5. **Session-doc owner** (§4.2): gigagit addendum + conformance, plugin
   parser + rendering, skill "Owner" paragraph, the kind column for owned
   session docs.

Each slice: its own `feat/<name>` worktree, version bump + change notes,
full suite + `verifyPlugin`, merged only on the user's OK.

## 6. Left to the builder

Icons (platform `AllIcons` picks), exact row text layout, the owner label
format, how a child row looks, the `files` entry order, test fixture data.

## 7. Calls made without the user (check these)

- Pull request and merge preview share the **Pull request** kind; RANGE and
  PAIR are **Commit**; a store overview's `branch` is a label (→ Commit).
- Working reviews are **Other**; shelf notes are left out (no reader spec).
- A note contained twice has one parent: review before overview, then newest.
- No branch/reachability filter: what is shown today stays shown.
- "Contained in the review" (§4.3) includes, for a prose review, every line
  note on its commit.
- Contained notes stay reachable as children of their item.
- A commit-owned session note also shows on the working file (cold anchored),
  not only on the revision tab.
- Item ids are namespaced (`store:`, `note:`) so they cannot collide.
- Items within a day are newest first.
- Delete as in §4.5: per item with children, plus "Remove Everything on This
  Commit" on commit-owned store items; nothing on day/type nodes.
- Focusing the new items from INSIDE gg is out of scope (user, 2026-10-28):
  the plugin keeps today's `files` entries and `focused overview <id>
  "<title>"` reply for session overviews unchanged so gg as shipped keeps
  working; the new entries are added after them.
