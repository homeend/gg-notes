# GgNotes as a `gg session` target — plugin implementation spec

Derived from `SPEC.md` (the protocol; binding where they differ). This file
says what the PLUGIN builds, in the order to build it, with the exact
on-disk behaviour an agent's `gg session … --to ide` relies on.

The gg side (`--to ide`, `GG_SESSION_TO`) is in gigagit main (06860f0f) and
the installed `gg`. Hand-written command files (§7) still work for tests —
the protocol is files.

## 1. What the user gets

With the project open, an agent in a terminal can say:

| the agent runs | the IDE does |
|---|---|
| `gg session navigate gg://gate/src/a.go@<sha>:40-60 --to ide` | opens `src/a.go` at that commit (read-only revision tab), caret on 40, 40–60 selected; replies `opened src/a.go:40-60` |
| `gg session navigate --file src/a.go --new-line 12 --to ide` | opens the working file at line 12 |
| `gg session highlight add src/a.go:3-4 --tone warn --to ide` | a persistent gutter/background marker on 3–4 |
| `gg session note add src/a.go:3-4 --summary "…" --rationale "…"` | gg writes `<id>.toml` into the worktree's session docs directory (§5); the IDE shows a read-only remark box beside lines 3–4 (same overlay the overview remarks use) |
| `gg session files --to ide` | the open-files list with ids `f<n>`, then the session overviews under their document ids, then one row for every other item of the tool window (`store:<id>`, `note:<id>`; IDE-side extension, §5) |
| `gg session overview add --title "Tour" --file tour.md` | gg writes `<id>.md` into the session docs directory; the overview is an item of the tool window (kind Other, under its day in By Date) and opens there (gg steers a `file_focus` for the id when a window is live) |
| `gg session status` | an `ide: pid …` line (the plugin is live) |

Open files and highlights are the session's; closing the project forgets
them. Session documents are FILES (§5) that stay until a user or their
writer deletes them.

## 2. Presence — be discoverable

Per git root of the project (one inbox per worktree):

```
inbox = <state>/gg/sessions/<Enc(commonDir)>/steer/<Enc(worktree)>/
state = $XDG_STATE_HOME ?: (Windows ? %LocalAppData% : ~/.local/state)
Enc(p) = filepath.Clean(p) with '/', '\' and ':' → '-'
```

- `commonDir` = `git rev-parse --path-format=absolute --git-common-dir`
  (`GitAccess.commonDir` already does this), `worktree` = `git rev-parse
  --show-toplevel` (the project's git root path, cleaned). Go's
  `filepath.Clean`: collapse `//`, drop `.`, resolve `..` lexically, drop a
  trailing separator; on Windows `/` → `\` FIRST, then the replacement, so
  `C:\src\x\.git` and `C:/src/x/.git` both encode to `C--src-x-.git`.
- Real Linux example: common dir
  `/home/homeend/.claude/jobs/2620a6cb/tmp/cmprepo/.git`, worktree
  `/home/homeend/.claude/jobs/2620a6cb/tmp/cmprepo` →
  `~/.local/state/gg/sessions/-home-homeend-.claude-jobs-2620a6cb-tmp-cmprepo-.git/steer/-home-homeend-.claude-jobs-2620a6cb-tmp-cmprepo/`.
- Create `inbox/` and `inbox/ide/` (0755) if absent.
- Write `inbox/ide.json` by temp-file + atomic rename:

  ```json
  {"pid":12345,"worktree":"/home/dev/src/gate","started":"2026-10-15T12:00:00Z","ide":"IntelliJ IDEA 2026.2","plugin":"0.2.0"}
  ```

  `worktree` is the exact string you encoded; `started` RFC 3339 UTC.
- **Every 1 s** set the file's mtime to now (`Files.setLastModifiedTime`);
  if that throws, rewrite the file. gg treats it live while the mtime is
  **< 5 s** old. Stop the timer and delete the file on project close
  (`Disposable`). A crash leaves a stale file: harmless.
- Never write `tui.json`/`web.json`; never create or delete top-level
  `cmd-*.json`/`reply-*.json` — those are the TUI's.

## 3. The sub-inbox — receive commands

Watch `inbox/ide/` (a `WatchService` on the directory; also poll every
500 ms — some mounts deliver no events). On each wake:

1. list `cmd-*.json` sorted by name (the id sorts by post time);
2. for each file: if size > 512 KiB → delete unread; else read bytes, then
   **delete the file**, then parse; drop it when `id` is missing or
   contains a path separator / is `.`/`..` (its reply name would escape the
   directory); unknown JSON keys are ignored;
3. hand the command to the handler (§4) **off the EDT** (a pooled thread;
   editor work inside goes through `invokeLater` / `WriteAction` as the
   platform demands); when `wait` is true, write `inbox/ide/reply-<id>.json`
   (temp + rename) with the handler's reply. Without `wait`, write nothing.
4. At project open, delete every `cmd-*.json` and `reply-*.json` in
   `inbox/ide/` (a crashed session's leftovers) — never the top-level ones.

The sender waits **2 s** for the reply, then prints "queued" and moves on:
a command that arrives late must still be applied; its reply is simply
never read (the sender deletes replies it reads; your open-time sweep
removes the rest).

Wire shapes — `Command` and `Reply` — are SPEC §3.1/§3.2 verbatim. Model
them as data classes with every field optional except `id`, `cmd`.

## 4. The handler — verb by verb

Reply prose is English and parsed by agents: use these exact forms.

| `cmd` | do | reply `detail` / `error` |
|---|---|---|
| `navigate` + `file` | resolve the file by `target.state`: absent/`unstaged`/`untracked` → the working file (VFS); `staged` → `GitAccess.indexContent` in a read-only light virtual file; `commit` → `GitAccess.fileContent(sha)` in a read-only revision tab (what v1 already opens for overview anchors). Open it (`FileEditorManager.openTextEditor`), unless `background`: open the tab without activating (`openFile(file, false)`). With `line`: caret at `line.no` (clamped to the last line), `no..end` selected. Register the file in the open-files list (§5). | `opened <file>:<no>` / `opened <file>:<no>-<end>`; past the end: `opened <file>:<asked>; clamped to line <last>`; no `line`: `opened <file>` |
| `navigate` + `commit` only | select the commit in the Git log tool window (`VcsLogContentProvider`/`VcsProjectLog.jumpToCommit`) | `revealed commit <sha7>` |
| `navigate` + `step` | from the file in front, go to the next/previous temporary note (§5) by line order | `opened <file>:<n>` / `no next note in this diff` · `no previous note in this diff` |
| `navigate` with `line.side == "old"` | refuse (v1 has no diff view) | error `the IDE shows no old side` |
| `navigate` with `target.state` ∈ `preview`,`ref`,`pair` | refuse | error `the IDE cannot show a <state> target` |
| `highlight` | a `RangeHighlighter` (gutter mark + line background in the `tone` colour: info/warn/error) on `start..end` of `file` (`side` `new` or empty = new); kept until `highlight_clear` or the file closes | `marked <file> new:<start>-<end> as <tone>` |
| `highlight_clear` | remove all; with `file`, only that file's | `cleared every mark` / `cleared the marks on <file>` |
| `files` | the open-files list, then the session overviews, then one row per note item and child (§5) | `files: [...]` (§5), `detail: no open files` only when the whole list is empty |
| `file_focus` | by `file_id` or `file`; `line` → caret; a `file_id` that is a session document id shows that overview (§5) — an id not listed yet makes the IDE re-read the directory once before refusing (gg posts the focus right after its rename) | `focused <path>` — ` at line <n>` appended only when the command named a line; an overview: `focused overview <id> "<title>"` (gg's lead, the title Go-quoted) / `no open file <name>`. A `file_id` `store:<record id>` or `note:<id>` (IDE-side extension, §5) shows that item as a click on its row does (a store record: its document in the viewer; a session note: its lines in the editor, with focus), selects its row and replies `focused <source> <id> "<title>"`; a miss re-reads the store or the docs directory once, then error `no item <id>` |
| `note_add`, `note_list`, `note_show`, `note_rm` | RETIRED (session-docs spec §6.3): refused BEFORE any other check, the worktree guard included; a gg that still sends them is older than the files; one notice per root | error `<verb> is retired: write the file (gg session note add)` |
| `overview_add` / `overview_set` / `overview_list` / `overview_show` / `overview_rm` | RETIRED, as above | error `<verb> is retired: write the file (gg session overview add)` |
| `focus`, `reload`, `serve` | refuse | error `<cmd> is a gg TUI command` |
| anything else | refuse | error `unknown command "<cmd>"` (the verb quoted, as the TUI says it) |

Before any of the above except a retired verb: if `worktree` is set and is
not one of the project's git roots (compare cleaned, slash-form, case-folded
on Windows/macOS), reply error `the IDE shows <root>, not <worktree>` and do
nothing.

## 5. The session: open files and the session docs directory

**Open files** (per git root; `f<n>` counters start at 1 and never reuse an
id during the session) — every file a `navigate`, `file_focus` or
`highlight` touched: `{id, path, source: worktree|commit, title?, rev?,
line, state: shown|background, notes}`; `line` = the caret's line now (0
when the editor is not loaded); `state` `shown` when its tab is the selected
editor; `notes` = count of session notes on that path (§5.2). Order: most
recently shown first. A file the user closes leaves the list; its highlights
go with it. After the files, `files` lists every session overview of the
worktree as `{id: <document id>, path: <id>.md, source: overview, title,
state, author, created (RFC 3339 UTC, when the file has one), anchors}`
(the shown one first).

**Item rows** (IDE-side extension, plugin 0.6.0). After the overview rows,
`files` lists one row for every item of the tool window and for each of its
children, recursively: the top items in By Date order, each followed by its
children. A session overview has its row above and gets no second one; the
notes it links do get rows. Row: `{id, source, kind, title, author, created
(RFC 3339 UTC, left out when unknown), path, line, state, owner}`.
- `id`: `store:<record id>` or `note:<id>`.
- `source`: `store-overview`, `store-review`, `store-thread` or `session-note`.
- `kind`: `commit`, `branch`, `pull-request`, `file` or `other`.
- `path`, `line`: the file and first line of a thread or note; else `path` is `""` and `line` is left out (0) when there is no line.
- `state`: `shown` when the tool window's open document is that record, else `background`. A session note is always `background`.
- `owner`: `{commit?, branch?, preview?, path?}`, only the keys that have a value.

gg compatibility: gg's JSON decoding drops fields it does not know, and its
text printer prints an unknown `source` as it is. gg never sends the new
ids. The rows and the ids are an extension of the IDE only.

### 5.1 The directory (session-docs spec §2, §4)

`<inbox>/docs/` — the steer inbox the plugin already resolves, plus
`docs`. Flat, no sub-directories. A writer (gg's CLI, gg's TUI for the
agent tours, an agent by hand) creates it; the plugin NEVER creates it,
never writes a document, and never deletes anything on its own. `<id>.md`
is an overview (`+++` TOML front matter: `format`, `title` required,
`author` default `agent`, `created`; then a markdown body whose links are
anchors — `path`, `path:N`, `path:N-M`, `note:<id>`, the first 100 count);
`<id>.toml` is a note (`path`, `start`, `end`, `summary`, `rationale`,
`author`, `created`, `lines` = the text of `start..end` as the writer saw
it, `[[meta]]`, `[display]`). The id grammar is
`^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$`; the file name IS the id.

Ignored unread: names starting with `.`, ending `.tmp`, any other
extension, directories, files over 512 KiB; ignored after a read: a file
that does not parse or misses a required key (title; path, start, end,
summary, lines with exactly `end-start+1` entries). `dev.ggnotes.session`
holds all of it, table-tested against `docs/gg-session-docs/` (ids, the
examples, the cold-anchor cases, the schemas).

**Reconcile** (every 2 s and on every watcher wake of the inbox or the
directory), by id: a new id is a new document; a known id whose (size,
mtime) changed is re-read and REPLACED in place (an open overview is
re-rendered on the same anchor, a note keeps its on-screen state when its
path, numbers and lines did not change); an id that is gone is dropped (an
open overview closes, a note's box goes). Unchanged stamps are not re-read.
Ids are unique per worktree only: the viewer's identity of a session
overview carries the worktree (`viewKind = session:<top>\0<id>:`).

### 5.2 Notes on the editor (spec §4.3, §4.4)

A note file is a box on every editor of its path (`RemarkOverlay.box`):
summary first (`⚠ outdated · ` prefixed while it is), rationale under it,
author dimmed, `key: value` lines for its meta. The first 50 notes of a path
by `created` then id are shown.

Cold anchoring — the first placement against the text the IDE has: (1) if
`lines` matches the file at `start` → current at `start..end`; (2) else if
`lines` occurs at exactly one place → current there; (3) else OUTDATED,
kept at `start..end` clamped into the file (≥ 1, ≤ len). A file with no
lines makes its notes outdated at 1..1 (listed, nothing marked). After
that, re-anchoring on edits is the rule of the previous version (gg's
`reanchor`: a debounced document listener, a Myers line diff, an outdated
note comes back when its lines are at its old place or at exactly one
place). A file's live state exists while an editor of it is open; a closed
file is placed cold again when it opens next. `navigate --step
next_note|prev_note` walks these placements.

### 5.3 What the tool window shows

The tool window lists each overview file as an item of kind Other and
each note file as an item of kind File, beside the store's items; a note
that an overview links by `note:<id>` is that overview's child (the newest
overview when several link it). Their time is `created` (the file's mtime
when absent): By Date puts them under that local day, newest first, then
id; By Type under Other and File.

An overview row: title · `session` · author · time, `on screen` while the
viewer shows it, `⚠ N unresolved` for anchors whose file is not in the
worktree or whose note is not in the directory (re-checked on every
rebuild). Opening one is the overview viewer: anchors are live (the
working tree), a `note:<id>` anchor points at the note's lines now, an
unresolved anchor is plain text. gg's tours (`tour-brief-…`,
`tour-report-…`, author `gg`) are ordinary rows. A note row: summary ·
path:lines · author · time; a click opens the working file with the
note's lines selected. Copy Link (or Ctrl+C) on either row copies its
link line.

**Close vs delete.** Close (the toolbar's X, another row) is local: the
file and the row stay. **Delete** on a session row confirms, then unlinks
the file; the editor's menu on a file with session notes offers **Delete
Session Note** (the note under the caret) and **Delete All Session Notes on
This File** (every note file naming that path). The reconcile that follows
does the rest. Never delete on close, on project close, or because a note
is outdated.

### 5.4 Migration from the in-memory session (0.2.x → 0.3.0)

Dropped: `SessionMemory`'s notes and overviews (`t<n>`, overview `f<n>`
ids), `TempNotes`, the `note_*`/`overview_*` handlers and their wire
shapes (`NoteWire`, `OverviewWire`), the 20-overviews cap. Kept: the
open-files list, `navigate`, `highlight`, `files`, `file_focus`,
re-anchoring. Added: `dev.ggnotes.session` (names, parser, cold anchoring,
listing, reconcile), `DocFolder` per root on `SteerService.RootSession`,
`SessionNoteMarks`, the delete actions. A gg older than the contract that
sends a retired verb gets the refusal of §4 and the IDE shows one notice
per root naming the directory it reads instead; with no `docs/` directory
the tool window simply lists no session items.

## 6. Threading and failure rules

- File I/O (presence, drain, reply) on a pooled thread; never on the EDT.
- Editor changes in `invokeLater` under the right action; replies written
  AFTER the change is applied (the agent reads the reply as "done").
- A handler exception → reply `ok:false`, `error: <message>` and log; never
  a lost reply when `wait` was true.
- One command at a time per inbox, in order.
- A watcher that dies restarts; the poll covers the gap.

## 7. Developing without the gg change

The protocol is files. To simulate an agent:

```sh
inbox=~/.local/state/gg/sessions/<Enc(commonDir)>/steer/<Enc(worktree)>
cat $inbox/ide.json                                   # your presence, mtime < 5 s old
printf '%s' '{"id":"1-1","cmd":"navigate","file":"src/a.go","line":{"side":"new","no":3,"end":4},"wait":true}' > $inbox/ide/.tmp && mv $inbox/ide/.tmp $inbox/ide/cmd-1-1.json
cat $inbox/ide/reply-1-1.json                         # {"id":"1-1","ok":true,"detail":"opened src/a.go:3-4 at line 3"}
```

`gg session … --to ide` does the same with the installed `gg` (gigagit main
06860f0f or later). Validate incoming
files leniently: the schemas leave the verb/state/side vocabularies open on
purpose — dispatch on them and refuse unknown values with the prose above.

## 8. Tests the plugin should have

- Unit (no IDE): `Enc`/inbox path for the real example above; presence
  JSON round-trip; drain rules (size cap, bad id, order, delete-before-parse);
  reply-only-when-wait; the re-anchoring rule (`reanchor-cases.json`);
  every refusal string of §4. Session docs: `id-vectors.json`, the parser
  against `docs/gg-session-docs/examples/` (CRLF, meta/display, the
  outdated note, the unresolved anchor) and gg's refusal prose,
  `cold-anchor-cases.json`, the two schemas against the examples, and a
  directory test that drops, replaces and removes files by hand.
- Integration (sandbox IDE, as the v1 acceptance test does): post a
  `navigate` file and assert the editor state + the reply file; drop a note
  file and an overview file into the worktree's `docs/` by hand and assert
  the box, the row, `files`, `file_focus`, an edit above the note moving
  it, a replaced file replaced in place, a removed file gone, and the
  deletes unlinking only what they name.
- Acceptance (SPEC §8) once the gg binary has `--to ide`.

## 9. Out of scope

Serving HTTP; multiple IDE windows on one worktree; `focus`/`reload`/
`serve`; preview/ref/pair targets; old-side lines; writing to the notes
store; writing session documents (the writer side is session-docs spec §5);
anything that needs gg running.
