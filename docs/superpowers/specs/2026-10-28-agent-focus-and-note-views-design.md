# Agents without gg, focus anything, and the date/type views — design

**Date:** 2026-10-28 · **Status:** approved 2026-10-28, revised the same day
(owned items go to the gg store through the CLI; no format change) ·
**Repo:** gg-notes (AgentNotes plugin + `agent-skills/`). No change to gg or
to any shared spec.

## 1. Why

The user works with agents in three places: inside gg (an agent gg started),
and outside gg — the IDE terminal, JetBrains AI Assistant, Air. Outside gg, an
agent asked to "focus the review in the IDE" answered that it needed gg (to
delete notes, it turned out). The user wants:

1. **An agent outside gg does everything itself.** No gg instance (TUI, web)
   is needed. The `gg` CLI is a tool like git: the agent finds it on PATH
   itself and uses it wherever gg owns the data. Inside gg, it keeps using
   gg's verbs.
2. **What the IDE shows, the agent can focus.** Whatever the Agent Notes tool
   window of the IDE opened on worktree W lists, an agent working in W can
   bring to the front.
3. **Notes belong to what they address — as gg already does.** A gg-store
   record's address says whether it belongs to a commit, a branch, a pull
   request / merge preview, a file or a worktree. An agent that writes about
   a commit, a branch or a PR stores it in gg through the CLI, so it gets
   that address; the IDE already reads the store. Nothing is redesigned.
4. **Two views, date first.** The tool window shows a **Date** view (default:
   day → everything added that day, an icon per kind) or a **Type** view
   (Branch / Commit / Pull request / File / Other → day → items). Goal: "find
   today's review" in one glance. The user's own sketch:

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

**Non-goals:** any change to gg, the notes-store format or the session-docs
format; focusing the IDE's new rows from INSIDE gg (user, 2026-10-28: later,
a gigagit task); PR comments; a search box; shelf notes.

**Anti-goals (a failure even if it "works"):**
- an agent outside gg telling the user to start gg, or to set a path/property;
- an agent writing or deleting gg-store files directly (only the `gg` CLI);
- the views listing a review AND, again as separate rows, the line notes it
  already contains (user: "just top addressable notes");
- a new address/owner mechanism next to gg's (user, 2026-10-28: "this is
  already set and works in gg").

## 3. Facts this rests on (checked 2026-10-28)

- **gg's address model** (`internal/model/note.go:264-316`, `model.go:659`):
  `address {Worktree, Branch, Commit, ShelfID, Path, State}` + tags +
  `scope`/`preview`/`preview_branch`. Commit + path = a line note under that
  commit; commit, no path, tag `review` / `overview` = a commit review /
  permanent overview; worktree, no path, tag `review` = a working review.
  gg sets `Address.Branch` on a REVIEW only when it reviewed a branch
  (`domain/review.go:224`); on a permanent OVERVIEW it is the save-time
  branch, a label only (`overview_save.go:70`).
- The plugin already reads all of it and classifies reviews: `ReviewKind` =
  COMMIT, RANGE, BRANCH, PULL_REQUEST, MERGE_PREVIEW, PAIR, WORKING
  (`store/Reviews.kt:52, 129-138`, gg's rule); a review carries
  `scopedNotes`/`scopedThreads` — the line notes gg shows under it.
- **The gg CLI writes the store with no gg instance running:**
  `gg review save <gg-link> --agent <name> --stdin [--json] [--dry-run]`
  (a commit, branch, PR/merge-preview, pair or working-changes review),
  `gg overview save [<gg-link>] --agent <name> (--stdin|--file) [--title]`
  (a permanent overview on a commit), `gg note add <gg-link> --summary …`
  (a line note), `gg note list`, `gg note rm <id>`, `gg overview rm <id>`.
  Links: `gg link --rev <rev>` / `--ref <branch>` / `--pr <n>` (a bare argument is read as a working-tree file), `--pair`,
  `--cached`. The store's cross-process lock and cascades stay gg's.
- **Inside gg** = `GG_SESSION_ID` is set (gg sets it for every agent it
  starts and strips it from a child started outside:
  `internal/agentsession/session.go:166`, `internal/cli/agent.go:42`).
- Session docs (`docs/gg-session-docs/SPEC.md`) need no gg at all; they are
  per worktree and on the working tree only. They stay as they are.
- The plugin finds `gg` without a setting since 0.4.6 (`GgLocator`, master
  7622bb8). Slices branch from master.

## 4. The parts

### 4.1 Agent skills: in or out of gg (`agent-skills/`)

Every `agent-*` skill gets one short rule at the top, and `FILES.md` gets the
recipes:

- **Where am I.** `GG_SESSION_ID` set → inside gg: use the `gg-*` skills and
  `gg session …` verbs. Not set → this skill; never ask the user to start
  gg or to configure anything.
- **Where it goes.** `command -v gg` (PowerShell `Get-Command gg`):
  - **gg on PATH, the work is about a commit, a branch or a PR** (a review
    of a commit/branch/PR; an overview of a commit) → the gg store through
    the CLI: `gg link …` for the target, `gg review save <link> --agent …
    --stdin` / `gg overview save <link> --agent … --file …`, line notes with
    `gg note add --file <path> --new-line <N> --rev <sha> --summary …`. The document shapes are what `--dry-run
    --json` prints and the notes-store spec defines (the recipe follows
    gg's own `gg-review` / `gg-overview` store steps). The IDE lists it
    under its commit/branch/PR; the reply names the record id.
  - **Otherwise** (uncommitted work, a walkthrough of the working tree, or
    no gg) → session-doc files, exactly as today.
- **Deleting.** A session doc: delete its file. A store record:
  `gg note rm <id>` / `gg overview rm <id>` in the worktree (ids from
  `gg note list` / `gg overview list`, or the IDE's `files`, §4.3); no gg on
  PATH → one line: use the tool window's **Delete** (tree context menu). Never
  edit store files.
- **Focusing something that already exists** (new `FILES.md` section): when
  the IDE is live, send `files`, pick the item by title / kind / date, send
  `file_focus` with its id (§4.3). Store items: the plugin's existing `navigate` command with
  `hint_kind` / `hint_id` (`SteerHandler.openStored`) focuses a stored review
  or overview now (FILES.md §9 "Focus it now", §11).

The gg skills (`gg-review`, `gg-overview`, `reviewing-with-gg`, `using-gg`)
are gigagit's and are not changed by this design.

### 4.2 The item model (pure, unit-tested)

One list of **items** per repository, built from `RepoModel` (store) and the
session docs, each item:
`{ id, source, kind, title, owner label, author, created, children }`.

**Kind** (icon + Type-view group), from gg's address:

| kind | store | session doc |
|---|---|---|
| Commit | permanent overview (its `branch` is a label); review with ReviewKind COMMIT, RANGE or PAIR | — |
| Branch | review with ReviewKind BRANCH (still at the reviewed commit or not — gg's "was tip" is a badge) | — |
| Pull request | review with PULL_REQUEST or MERGE_PREVIEW; a `previews.toml` overview | — |
| File | a line-note thread (commit or working tree) not contained in a top item | a note not linked by any overview |
| Other | working review | overview |

Shelf notes stay out (SPEC-part2 §7: no reader spec yet; the plugin does not
read `shelf.toml`).

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

### 4.3 Focus anything shown (steer, `PLUGIN.md`)

- `files` reply: today's rows unchanged (open files, then session overviews
  with `source: "overview"` — gg as shipped reads them), then one entry per
  other **top item and child** of §4.2: `{id, source, kind, title, author,
  created, owner, state}`; `source` = `session-note` | `store-overview` |
  `store-review` | `store-thread`; `kind` = `commit` | `branch` |
  `pull-request` | `file` | `other`; `owner` = `{commit?, branch?,
  preview?, path?}` from the record's address.
- Ids: session overview = its file id (unchanged); session note =
  `note:<id>` (the anchor grammar); store = `store:<record id>`.
- `file_focus` with such an id: open it in the viewer (an overview/review in
  the document pane, a note/thread at its lines) and select its row in the
  tool window. Reply `focused <source> <id> "<title>"`; a session overview
  keeps today's `focused overview <id> "<title>"`. Unknown id →
  `no item <id>`.
- gg compatibility (checked): gg's JSON decoding drops unknown fields, its
  text printer prints unknown `source` values verbatim, and gg never sends
  the new ids. An IDE-side extension, recorded in `PLUGIN.md`.

### 4.4 The tool window

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
- Everything else stays: opening, Copy Link, `on screen`, `⚠ N unresolved`,
  missing commits.

## 5. Slices (each ships alone, in order)

1. **Skills** (§4.1): in/out-of-gg rule, store-through-CLI for commit /
   branch / PR work, deletion recipe, focusing a session overview. Fixes the
   AIR failure. No plugin change.
2. **Item model** (§4.2), pure, with tests. No UI change yet.
3. **Views** (§4.4) on the item model; default Date.
4. **Focus anything** (§4.3) + the skill section using it.

Each slice: its own `feat/<name>` worktree, version bump + change notes,
full suite + `verifyPlugin`, merged only on the user's OK.

## 6. Left to the builder

Icons (platform `AllIcons` picks), exact row text layout, the owner label
format, how a child row looks, the `files` entry order, test fixture data,
the exact store-document recipe in `FILES.md` (copied from gg's own skills
and `--dry-run --json` output, not invented).

## 7. Calls made without the user (check these)

- Pull request and merge preview share the **Pull request** kind; RANGE and
  PAIR are **Commit**; a store overview's `branch` is a label (→ Commit).
- Working reviews and session overviews are **Other**; shelf notes are left
  out (no reader spec).
- A note contained twice has one parent: review before overview, then newest.
- No branch/reachability filter: what is shown today stays shown.
- "Contained in the review" includes, for a prose review, every line note on
  its commit.
- Contained notes stay reachable as children of their item.
- Item ids are namespaced (`store:`, `note:`) so they cannot collide.
- Items within a day are newest first.
- Delete: per item with children, plus "Remove Everything on This Commit"
  on commit-owned store items; nothing on day/type nodes.
- An agent outside gg with gg on PATH stores commit / branch / PR work in
  the gg store (permanent, shared by every worktree of the repo, subject to
  gg's expiry); without gg it writes session docs (working tree only).
