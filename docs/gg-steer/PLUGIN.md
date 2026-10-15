# GgNotes as a `gg session` target — plugin implementation spec

Derived from `SPEC.md` (the protocol; binding where they differ). This file
says what the PLUGIN builds, in the order to build it, with the exact
on-disk behaviour an agent's `gg session … --to ide` relies on.

The gg side (`--to ide`, `GG_SESSION_TO`) is being added on gigagit's
`feat/steer-ide` branch. Until a gg with it is installed, develop against
hand-written command files (§7) — the protocol is files, so nothing else is
needed.

## 1. What the user gets

With the project open, an agent in a terminal can say:

| the agent runs | the IDE does |
|---|---|
| `gg session navigate gg://gate/src/a.go@<sha>:40-60 --to ide` | opens `src/a.go` at that commit (read-only revision tab), caret on 40, 40–60 selected; replies `opened src/a.go:40-60 at line 40` |
| `gg session navigate --file src/a.go --new-line 12 --to ide` | opens the working file at line 12 |
| `gg session highlight add src/a.go:3-4 --tone warn --to ide` | a persistent gutter/background marker on 3–4 |
| `gg session note add src/a.go:3-4 --summary "…" --rationale "…" --to ide` | a read-only remark box beside lines 3–4 (same overlay the overview remarks use); the file counts as "open" |
| `gg session files --to ide` | the open-files list with ids `f<n>` |
| `gg session overview add --title "Tour" --file tour.md --to ide` | the overview in the existing tool window, anchors walking the editor |
| `gg session status` | an `ide: pid …` line (the plugin is live) |

Everything is in the plugin's memory; closing the project forgets it.

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
| `navigate` + `file` | resolve the file by `target.state`: absent/`unstaged`/`untracked` → the working file (VFS); `staged` → `GitAccess.indexContent` in a read-only light virtual file; `commit` → `GitAccess.fileContent(sha)` in a read-only revision tab (what v1 already opens for overview anchors). Open it (`FileEditorManager.openTextEditor`), unless `background`: open the tab without activating (`openFile(file, false)`). With `line`: caret at `line.no` (clamped to the last line), `no..end` selected. Register the file in the open-files list (§5). | `opened <file>[:<no>[-<end>]] at line <n>`; past the end: `opened <file>:<asked> at line <last> (line <asked> is past the end, <last> lines)`; no `line`: `opened <file>` |
| `navigate` + `commit` only | select the commit in the Git log tool window (`VcsLogContentProvider`/`VcsProjectLog.jumpToCommit`) | `showing commit <sha7>` |
| `navigate` + `step` | from the file in front, go to the next/previous temporary note (§5) by line order | `opened <file>:<n> at line <n>` / `no next note in this diff` · `no previous note in this diff` |
| `navigate` with `line.side == "old"` | refuse (v1 has no diff view) | error `the IDE shows no old side` |
| `navigate` with `target.state` ∈ `preview`,`ref`,`pair` | refuse | error `the IDE cannot show a <state> target` |
| `highlight` | a `RangeHighlighter` (gutter mark + line background in the `tone` colour: info/warn/error) on `start..end` of `file` (`side` must be `new`); kept until `highlight_clear` or the file closes | `highlighted <file>:<start>-<end>` |
| `highlight_clear` | remove all | `cleared` |
| `files` | the open-files list | `files: [...]` (§5), `detail: no open files` when empty |
| `file_focus` | by `file_id` or `file`; `line` → caret | `focused <path>[ at line <n>]` (an overview: `focused <title>`) / `no open file <name>` |
| `note_add` | §5 | the note in `notes: [..]` |
| `note_list` | §5; `file`/`file_id` restricts | `notes: [...]`, `detail: no notes` when empty |
| `note_show` | §5 | `notes: [the one]` with `text` |
| `note_rm` | §5 | `removed t<n>` / `no note t<n>` |
| `overview_add` / `overview_set` / `overview_list` / `overview_show` / `overview_rm` | §5 | §5 |
| `focus`, `reload`, `serve` | refuse | error `<cmd> is a gg TUI command` |
| anything else | refuse | error `unknown command <cmd>` |

Before any of the above: if `worktree` is set and is not one of the
project's git roots (compare cleaned, slash-form, case-folded on
Windows/macOS), reply error `the IDE shows <root>, not <worktree>` and do
nothing.

## 5. The session memory

Per git root; `f<n>` / `t<n>` counters start at 1 and never reuse an id
during the session.

**Open files** — every file a `navigate`, `file_focus`, `note_add` or
`overview_add` touched: `{id, path, source: worktree|commit|overview,
title?, rev?, line, state: shown|background, notes}`; `line` = the caret's
line now (0 when the editor is not loaded); `state` `shown` when its tab is
the selected editor; `notes` = count of live notes on it. Order: most
recently shown first. A file the user closes leaves the list; its notes and
highlights go with it.

**Temporary notes** (`note_add`): resolve the file as `navigate` would
(working file unless `target` says otherwise), open it in the background,
then place the note on `start..end` of the editor's CURRENT `Document` text
(not the disk). Refuse, in this order, with these strings: `<path> has no
lines to note` (empty document), `a line number is 1-based`, `the range
ends before it starts`, `line <n> is past the end of <path> (<len>
lines)`, `a note needs a summary`, `the summary is longer than 500
characters`, `the rationale is longer than 4000 characters`, `<path>
already carries 50 notes`. Store the note's lines' text (its anchor).

Draw it like an overview remark (`RemarkOverlay`): a read-only box beside
the lines, `summary` first, `rationale` under it, `author` dimmed; meta
badges per `display` when given (store-spec part 2 §6; `key: value` lines
are acceptable first).

Re-anchoring on edits — gg's rule, port it exactly:
- keep, per note, the text it was aligned to (`old`) and its lines;
- when the document changes (debounce ~200 ms on `DocumentListener`),
  diff `old` against the current lines (a line-level Myers diff; IntelliJ's
  `com.intellij.diff.comparison.ComparisonManager` line mode is fine) →
  a map old-line → current-line for lines that survived unchanged;
- a NOT-outdated note moves when every line of `start..end` maps and the
  images are contiguous; otherwise it becomes `outdated` and keeps its
  numbers clamped into the file;
- an OUTDATED note comes back when its anchor text is at its old `start`,
  or at exactly ONE place in the file (two places → stays outdated);
- then `old` := current lines.
- `note_show` returns `text` = the lines it sits on now; `outdated: true`
  in every listing while it is.

**Overviews** (`overview_add --title … [--file md] [--background]`):
an entry of the open-files list with `source: overview` (id `f<n>`); limits
and refusals: `an overview needs text`, `the text is over 64 KiB`, `an
overview needs a title`, `the title must be one line of at most 200
characters`, at most 20 per root (`error: 20 overviews open`); anchors by
the store-spec part 1 §6 grammar plus `note:t<n>` (an anchor to a
temporary note → that note's file at its lines), the first 100 count;
`unresolved` = destinations whose file or note is not found (listed, drawn
plain). Show it in the overview tool window with the SAME renderer as a
stored overview (`OverviewService.open` path) unless `background`;
`overview_set` replaces title/text in place, keeping the walk on the same
anchor index; `overview_show` returns `text`; `overview_rm` closes it.
Replies: `showing f<n>` / `added f<n> in the background` /
`no overview <id>`. `overview_list` → `overviews: [{id, title, state,
anchors, unresolved?}]`.

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

Once gigagit's `feat/steer-ide` is built (`/work/gigagit/.claude/worktrees/steer-ide/bin/gg` after its Task 2), `gg session … --to ide` does the same.

## 8. Tests the plugin should have

- Unit (no IDE): `Enc`/inbox path for the real example above; presence
  JSON round-trip; drain rules (size cap, bad id, order, delete-before-parse);
  reply-only-when-wait; the re-anchoring rule with the cases of SPEC §7
  (`reanchor-cases.json` when gg ships it — until then write the eight
  cases from PLUGIN §5 yourself); every refusal string of §4/§5.
- Integration (sandbox IDE, as the v1 acceptance test does): post a
  `navigate` file and assert the editor state + the reply file; a `note_add`
  then an edit above the note moves it; `overview_add` shows in the tool
  window.
- Acceptance (SPEC §8) once the gg binary has `--to ide`.

## 9. Out of scope

Serving HTTP; multiple IDE windows on one worktree; `focus`/`reload`/
`serve`; preview/ref/pair targets; old-side lines; writing to the notes
store; anything that needs gg running.
