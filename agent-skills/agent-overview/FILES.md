# Files — how an agent skill writes notes and overviews

The IDE's AgentNotes plugin watches one directory per worktree. Every
file you put there shows up in the IDE. An **overview** (`<id>.md`) is a row
of kind Other in the Agent Notes tool window. A **note** (`<id>.toml`) is a
row of kind File, and a read-only remark box beside its lines in the editor.
A note that an overview links (`note:<id>`) is under the row of that
overview. In the By Date view, the rows are under today's date. Nothing
else is needed: no server, no command, no running process of yours. A file
stays until the user deletes it in the IDE (tree context menu → Delete) or
you remove it.

The directory files need no tool. The store route (§9) needs a usable `gg`
(§0), never a running gg. §0 tells you which route to take.

## 0. Inside gg or not

Check first. Run this in the worktree.

sh:

```sh
[ -n "$GG_SESSION_ID" ] && echo "inside gg"
```

PowerShell:

```powershell
if ($env:GG_SESSION_ID) { 'inside gg' }
```

- `GG_SESSION_ID` set and not empty: you run inside gg. Stop here. Use the
  `gg-*` skills and the `gg session …` verbs.
- Else you run outside gg (IDE terminal, AI Assistant, Air). Find the `gg`
  binary on the IDE's side:

The IDE is on Windows when you run in WSL (`$WSL_DISTRO_NAME` is set) AND
the worktree is on a Windows drive (`/mnt/<letter>/…`), or the user says the
IDE runs on Windows. This is the same decision as §2 (the same side), so the
two never disagree. Then use `gg.exe`; otherwise use `gg`.

```sh
if [ -n "$WSL_DISTRO_NAME" ] && case "$PWD" in /mnt/[a-z]/*) true;; *) false;; esac; then
  GG=$(command -v gg.exe)   # IDE on Windows (or the user says so)
else
  GG=$(command -v gg)
fi
```

```powershell
$GG = (Get-Command gg -ErrorAction SilentlyContinue).Source
```

- `gg` is usable when `"$GG" overview list --json` exits 0 (PowerShell:
  `& $GG overview list --json`, then `$LASTEXITCODE -eq 0`). An old gg has
  no such verb and fails. No binary found: `gg` is not usable. When gg is
  not usable, say so in one line and take the files route (§1–§7).

| the work | gg usable | route |
|---|---|---|
| a commit, a branch or a PR (a PR: a review only) | yes | §9 |
| uncommitted work, or a walk through the working tree | any | §1–§7 files |
| anything | no | §1–§7 files |

Never ask the user to start gg or to set anything.

## 1. What a file can point at — the working tree only

A note sits on a file AS IT IS ON DISK in this worktree; an overview's
anchors open files of this worktree. So you can present only what is
checked out in the worktree the IDE shows: HEAD, plus uncommitted changes.

- To present another commit or branch, ask the user to check it out (or to
  open the IDE on a worktree at that tip) first. Never anchor lines of a
  commit that is not on disk: the lines will be wrong.
- A removed line has no place on disk: describe it in words, never anchor it.
- Make every edit BEFORE you write a note: a note stores the lines as you
  read them.

## 2. The directory

```
<state>/gg/sessions/<key(common dir)>/steer/<key(worktree)>/docs/
```

- `<state>` is `$XDG_STATE_HOME` when set, else `%LOCALAPPDATA%` on
  Windows, else `~/.local/state`.
- `common dir` = `git rev-parse --path-format=absolute --git-common-dir`;
  `worktree` = `git rev-parse --show-toplevel`.
- `key(p)` = the absolute path with every `/`, `\` and `:` replaced by `-`
  (no trailing separator): `/work/app/.git` → `-work-app-.git`,
  `C:/src/app/.git` → `C--src-app-.git`.
- Which side: the test of §0 (`$WSL_DISTRO_NAME` set and the worktree under
  `/mnt/<letter>/`, or the user says the IDE runs on Windows) decides it.
- Create the directory when it is missing. Put nothing in it except
  documents (and your own temp files, below).

sh (Linux, macOS, Git Bash):

```sh
key() { printf '%s' "$1" | sed 's#[/\\:]#-#g'; }
common=$(git rev-parse --path-format=absolute --git-common-dir)
top=$(git rev-parse --show-toplevel)
inbox="${XDG_STATE_HOME:-$HOME/.local/state}/gg/sessions/$(key "$common")/steer/$(key "$top")"
docs="$inbox/docs"; mkdir -p "$docs"
```

PowerShell (Windows):

```powershell
function Key($p) { $p -replace '[/\\:]', '-' }
$common = git rev-parse --path-format=absolute --git-common-dir
$top = git rev-parse --show-toplevel
$state = if ($env:XDG_STATE_HOME) { $env:XDG_STATE_HOME } else { $env:LOCALAPPDATA }
$inbox = Join-Path $state "gg\sessions\$(Key $common)\steer\$(Key $top)"
$docs = Join-Path $inbox 'docs'; New-Item -ItemType Directory -Force $docs | Out-Null
```

**You and the IDE must use the same side.** An agent in WSL whose IDE runs
on Windows writes into the WINDOWS directory, with keys made from the
Windows paths:

```sh
wkey() { wslpath -w "$1" | sed 's#[/\\:]#-#g'; }
state=$(wslpath "$(cmd.exe /c 'echo %LOCALAPPDATA%' 2>/dev/null | tr -d '\r')")
# cmd.exe not reachable: state=/mnt/c/Users/<windows user>/AppData/Local (ask the user for the name)
common=$(git rev-parse --path-format=absolute --git-common-dir)
top=$(git rev-parse --show-toplevel)
inbox="$state/gg/sessions/$(wkey "$common")/steer/$(wkey "$top")"
docs="$inbox/docs"; mkdir -p "$docs"
```

(`/mnt/t/src/app/.git` → `T:\src\app\.git` → `T--src-app-.git`.) When the
IDE runs in WSL or on Linux, use the sh recipe above.

## 3. Ids and the write

- An id is the file name without its extension and matches
  `^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$`. Mint it as `<UTC stamp>-<4 hex>`,
  lower case: `20261019T143201-9f3a`.
  sh: `id=$(date -u +%Y%m%dT%H%M%S)-$(printf %04x $((RANDOM % 65536)))`;
  PowerShell: `$id = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmss') + '-' + ('{0:x4}' -f (Get-Random -Max 65536))`.
- Write the whole file to a temp name in the directory that starts with
  `.` and ends in `.tmp` (`$docs/.$id.tmp`), then rename it to the final
  name (`mv` / `Move-Item -Force`). Never write the final name in place:
  the IDE may read a half-written file.
- Replace = write the same name again the same way. Remove = delete the
  file. Never touch a file you did not write unless the user asks.

## 4. A note — `<id>.toml`

```toml
format = 1
path = "internal/tui/scroll.go"
start = 102
end = 104
summary = "One lock around flush: the wheel handler and the tick both reach it."
rationale = """
Without it a tick between two deltas reads a half-updated offset."""
author = "claude"
created = 2026-10-19T14:32:05Z
lines = ["func (s *scroller) flush() {", "\ts.mu.Lock()", "\tdefer s.mu.Unlock()"]

[[meta]]
key = "kind"
value = "issue"
```

| key | rule |
|---|---|
| `path` | REQUIRED. The file in this worktree, relative, slash form; no leading `/`, no `.` or `..` segment, no `\`, no `:` |
| `start`, `end` | REQUIRED. 1-based, `1 ≤ start ≤ end` |
| `summary` | REQUIRED. One sentence, ≤ 500 characters |
| `rationale` | Optional, ≤ 4000 characters, may span lines |
| `author` | Your name (shown on the box) |
| `created` | Now, UTC, RFC 3339 (orders the list) |
| `lines` | REQUIRED. The text of lines `start..end` exactly as they are in the file NOW, after your last edit: one string per line, no line ending (strip `\r`), exactly `end - start + 1` entries. The IDE places the note by this text and follows it when the file changes |
| `[[meta]]` | Optional, repeatable `{key, value}`: key ≤ 64 bytes, value one line ≤ 256 bytes, ≤ 32 keys. The box shows each as `key: value` |

**Strings.** Write every string as a TOML basic string `"…"`: escape `\` as
`\\` and `"` as `\"`; a tab may stay raw or be `\t`. A multi-line
`rationale` uses `"""…"""` (the same escapes). Never use single-quoted
literal strings for `lines`: a source line with a `'` breaks them.
Read `lines` from the file, never from memory, and escape each one:

```sh
esc() { sed 's/\\/\\\\/g; s/"/\\"/g'; }
l102=$(sed -n '102p' internal/tui/scroll.go | tr -d '\r' | esc)
```

```powershell
function Esc($l) { $l.TrimEnd("`r") -replace '\\', '\\' -replace '"', '\"' }
$l102 = Esc ((Get-Content internal/tui/scroll.go)[101])
```

## 5. An overview — `<id>.md`

```markdown
+++
format = 1
title = "How scroll coalescing works"
author = "claude"
created = 2026-10-19T14:32:01Z
+++
Scroll events are coalesced per frame, so a burst of wheel ticks costs one
layout. [The wheel handler](internal/tui/scroll.go:40-58) pushes the delta;
[`flush`](internal/tui/scroll.go:102) runs once per tick, and
[why one lock](note:20261019T143205-c0d1) explains the lock.
```

- The file starts with a line that is exactly `+++`, TOML, a line that is
  exactly `+++`; the markdown body follows. `title` is REQUIRED: one line,
  ≤ 200 characters.
- The body is GitHub-flavoured markdown, ≤ 64 KiB, not blank.
- Its links are the ANCHORS, walked by the user top to bottom:
  `path` (the file), `path:40` (a line), `path:40-60` (a range), or
  `note:<id>` (opens the file at that note). Paths are relative to the
  worktree, slash form. Any other link (absolute, `..`, `\`, a URL) is a
  plain link. The first 100 anchors count.
- Write each link bare. Backticks AROUND a link make it plain text: it
  opens nothing. For a code look, put the backticks INSIDE the brackets:
  [`flush`](internal/tui/scroll.go:102).
- An anchor whose file or note does not exist shows as missing. Check each
  path and line range against the file on disk before you write.

## 6. Bring it to the front (optional)

Writing a file does not change what the IDE shows. When the IDE is live —
`$inbox/ide.json` exists and was modified less than 5 seconds ago — you can
ask it to open the overview:

```sh
cid=$(date +%s%N)-$$
printf '{"id":"%s","cmd":"file_focus","file_id":"%s","wait":true}' "$cid" "$id" > "$inbox/ide/.$cid.tmp"
mv "$inbox/ide/.$cid.tmp" "$inbox/ide/cmd-$cid.json"
```

Then read `$inbox/ide/reply-$cid.json` for at most 2 seconds
(`{"ok":true,"detail":"focused overview <id> \"<title>\""}`). No reply, or
no fresh `ide.json`: say in one line that the overview is in the Agent Notes tool window,
under today's date, and go on. Never wait longer, never retry.

## 7. Check before you report

- Each note: `lines` has `end - start + 1` entries and equals the file's
  lines now.
- Each anchor's path exists in the worktree and its lines are inside the
  file.
- Optional: parse a file you wrote (`python3 -c "import tomllib,sys;
  tomllib.load(open(sys.argv[1],'rb'))" <file>` for a note).

## 8. A link the user pastes

In the IDE, **Copy Link** on a session overview row or a session note row
of the Agent Notes tool window (or Ctrl+C on the row) and **Copy Session Note Link** on a note box (editor context
menu, caret on the note) copy ONE line:

```
Agent Notes overview <id> "<title>" (worktree <path>; file <path>)
Agent Notes note <id> at <path>:<N>[-<M>][ (outdated)] "<summary>" (worktree <path>; file <path>)
```

When the user's message holds such a line:

1. **Read the file** after `file`. It is the document: a note (§4) or an
   overview (§5). An overview's `note:<id>` anchor is `<id>.toml` in the
   same directory. From WSL, read a Windows path through `wslpath -u`:
   `cat "$(wslpath -u 'C:\Users\…\docs\<id>.toml')"`.
2. **Work in the worktree** after `worktree` (from WSL: `wslpath -u` too).
   A note's `path` and an overview's anchors are relative to it. If you
   run in another checkout, say so in one line and use the worktree's
   files.
3. **A note: `at` is where it sits NOW.** The IDE moved the note as the
   file changed, so `at` can differ from the file's `start`/`end`. Read
   those lines from the file on disk. The note's `lines` are the text it
   was written on; `(outdated)` means those lines were edited since — read
   the file around them and say what changed.
4. **Do what the user asks about it**: fix what the note names, answer a
   question about a step, go on with the work the overview describes. A
   message with ONLY the link: say in three lines what the document says
   and what you would do next, then wait.
5. **The document stays.** Do not delete or rewrite it because you acted
   on it. When your fix makes a note obsolete, say so in the reply (the
   user deletes it: tree or editor menu → Delete); delete it yourself only
   when the user asks.

## 9. Commit, branch or PR work: store it in gg

Use this route only when §0 says so. `gg` stores the record in its own
store. Never write, edit or delete a file under `<state>/gg/notes/`: only
the `gg` CLI touches the store. All commands run in the worktree. In the
examples below `$GG` is the binary from §0 (PowerShell: `& $GG …`).
**With `gg.exe` from WSL:** run it with the worktree as the current
directory (a `/mnt/<letter>/…` worktree: interop passes it as a Windows
path), and give every file argument as a Windows path:
`--file "$(wslpath -w "$f")"`.

What needs a checkout:

- A **review** (commit, branch or PR) needs no checkout. gg reads the
  commit.
- An **overview** needs the commit on disk: gg checks every anchored file
  against the working tree and refuses `<path>: differs from <sha7> on disk`.
  A commit overview: the commit is HEAD with a clean tree. A **branch
  overview**: use the tip link (`--ref <branch>`, stored on the tip); the tip
  must be checked out the same way. A **PR**: write a review, or use the
  session files (§1–§7).

Steps:

- **Target link.** A commit: `"$GG" link --rev <rev>` (`--rev HEAD`). A
  branch: `"$GG" link --ref <branch>`. A PR: first `"$GG" pr fetch <n>`
  (it needs `gh` logged in), then `"$GG" link --pr <n>`. Each prints one
  `gg://…` link; keep it as `$link`. Never give a bare `<rev>`: gg reads it
  as a file of the working tree, and the link is then "working changes".
  `$link` empty (a PR that cannot be fetched, a failed command): say so in
  one line, take the session files when the work allows it, and stop. Do not
  loop.
- **Review.** First read what to review:
  `"$GG" review save "$link" --dry-run --json`. It prints `kind`, `label`,
  `checkout`, `diff` and `hunks`. Read the change with these values. The
  document shape and the meta and display block are in the file that
  `"$GG" skill path review` prints: read its "Review document" and "Meta
  and display" sections. Do not guess them. Write the document to a temp
  file (below) and store it:
  `"$GG" review save "$link" --agent "<your name>" --file "$f" --json`.
  On `not a gg review document` or `unresolved: <dest>`, fix the document
  and save again.
- **Overview of a commit or a branch tip.** Write this JSON to a temp file:
  `{"version":1,"title":"…","overview":"<markdown, path:N-M anchors, no note:>","files":[{"path":"…","annotations":[{"newRange":[a,b],"summary":"…","rationale":"…"}]}]}`.
  The line numbers are the lines of the file AT the commit. Store it:
  `"$GG" overview save "$link" --agent "<your name>" --file "$f" --json`.
- **Line notes on a commit.** Resolve the commit first, so `--rev` never
  gets an empty value (an empty `--rev` stores a working-tree note):
  `"$GG" note add --file <path> --new-line <N> --rev "$sha" --summary "…"
  [--rationale "…"] --author "<your name>" --json`.
- **The temp file.** Write the JSON in UTF-8 to a temp file OUTSIDE the
  worktree, pass it with `--file`, delete it afterwards. Never pipe it
  through stdin: Windows PowerShell 5.1 pipes stdin as ASCII and damages
  "→" and "—".
- **Refusals.** gg lists every problem and stores nothing. On
  `<path>: uncommitted changes` or `<path>: differs from <sha7> on disk`:
  - the commit is HEAD, and the tree has uncommitted edits: present the
    working tree with the §1–§7 files, or ask the user;
  - the commit is not checked out: ask the user to check it out (or to open
    the IDE on a worktree at it). Do not check it out yourself.

  Say it once. Do not loop.
- **Result.** Read the record id in the `--json` output: `id` (a review
  prints `id` and a `link` that ends in `?review=<id>`; an overview prints
  `id` and `commit`). The IDE shows the record in the Agent Notes tool
  window, under its commit, branch or PR. Your reply names the id and where
  it shows.
- **Focus it now** (optional). The IDE must be live (§6's rule). Send
  `file_focus` first. Use `navigate` as the fallback.
  - Plugin version. Read `plugin` in `$inbox/ide.json`. Compare only the
    `MAJOR.MINOR` before any `+`. `dev` counts as new. A version older than
    0.6.0 (for example `0.5.2+abc1234`): skip "Send first" and use
    "Fallback".
  - Send first: `{"id":"<cid>","cmd":"file_focus","file_id":"store:<record id>","wait":true}`,
    sent as §6 sends it. The reply is `focused <source> <id> "<title>"`.
  - A failed reply has `"ok":false` and an `error` text. Use "Fallback"
    when `error` is `no item <id>` or starts with `no open file`. A plugin
    older than 0.6.0 sends `no open file store:<record id>`.
  - Fallback: `{"id":"<cid>","cmd":"navigate","commit":"<full sha>","hint_kind":"review","hint_id":"<record id>","wait":true}`
    (`"hint_kind":"overview"` for an overview). Write it the way §6 writes
    `file_focus`: a temp file in `$inbox/ide/`, then a rename to
    `cmd-<cid>.json`. `commit` is the full sha you linked (the `commit` of
    an overview's output; for a branch review the tip, `git rev-parse
    <branch>`). The reply is `opened review <id>` / `opened overview <id>`.
    An id the store no longer has opens the commit.
  - No fresh `ide.json`, no reply in 2 seconds, or a PR review (no commit
    to name): say in one line where the record shows, and go on. Never
    retry.
- The store is permanent. Every worktree of the repo shares it. gg's expiry
  rules apply.

sh:

```sh
link=$("$GG" link --rev HEAD)             # a PR: "$GG" pr fetch 7 && link=$("$GG" link --pr 7)
[ -n "$link" ] || echo "no link: say so, stop" >&2
sha=$(git rev-parse HEAD)
"$GG" review save "$link" --dry-run --json
f=$(mktemp)                               # outside the worktree
# write the review JSON (UTF-8) to "$f"
"$GG" review save "$link" --agent "claude" --file "$f" --json
rm -f "$f"
# an overview: the same, with: "$GG" overview save "$link" --agent "claude" --file "$f" --json
"$GG" note add --file src/app.go --new-line 42 --rev "$sha" --summary "…" --author "claude" --json
```

PowerShell:

```powershell
$link = $null
$link = & $GG link --rev HEAD             # a PR: & $GG pr fetch 7; if ($LASTEXITCODE -eq 0) { $link = & $GG link --pr 7 }
if (-not $link) { 'no link: say so, stop' }
$sha = git rev-parse HEAD
& $GG review save $link --dry-run --json
$f = (New-TemporaryFile).FullName         # outside the worktree
[IO.File]::WriteAllText($f, $json)        # $json = the review JSON; UTF-8, no BOM
& $GG review save $link --agent 'claude' --file $f --json
Remove-Item $f
# an overview: the same, with: & $GG overview save $link --agent 'claude' --file $f --json
& $GG note add --file src/app.go --new-line 42 --rev $sha --summary '…' --author 'claude' --json
```

Focus (PowerShell; `$inbox` from §2, `$id` = the record id, `$full` = the full sha). Send first:

```powershell
$cid = "$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())-$PID"
$cmd = '{"id":"' + $cid + '","cmd":"file_focus","file_id":"store:' + $id + '","wait":true}'
[IO.File]::WriteAllText("$inbox\ide\.$cid.tmp", $cmd)
Move-Item "$inbox\ide\.$cid.tmp" "$inbox\ide\cmd-$cid.json"
```

Fallback:

```powershell
$cid = "$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())-$PID"
$cmd = '{"id":"' + $cid + '","cmd":"navigate","commit":"' + $full + '","hint_kind":"review","hint_id":"' + $id + '","wait":true}'
[IO.File]::WriteAllText("$inbox\ide\.$cid.tmp", $cmd)
Move-Item "$inbox\ide\.$cid.tmp" "$inbox\ide\cmd-$cid.json"
```

## 10. Delete

Delete only what the user asked you to delete (§8.5 stays).

- **A session doc** (§1–§7 files): delete its file. The IDE drops it.
  sh: `rm "$docs/<id>.md"` (a note: `<id>.toml`). From WSL with the IDE on
  Windows, `$docs` is the Linux path of the Windows directory from §2,
  unchanged.
  PowerShell: `Remove-Item (Join-Path $docs '<id>.md')`.
- **A store record**: use the CLI, in the worktree. `"$GG" note rm <id>`
  removes a note, a thread with its replies, or a review. `"$GG" overview rm
  <id>` removes an overview. Find the ids with `"$GG" note list` and
  `"$GG" overview list --all --json` (without `--all` it lists only HEAD's
  overviews). PowerShell: `& $GG note rm <id>`, `& $GG overview rm <id>`.
- **No usable gg**: say in one line that the user deletes the record in the
  tool window (tree context menu → Delete). Never edit or delete store files.

## 11. Bring an existing document to the front

Use the live check and the command recipe of §6: the same `cid`, the same
`mv`, the same reply file, 2 seconds, no retry.

1. Send `{"id":"<cid>","cmd":"files","wait":true}` the way §6 sends
   `file_focus`.
2. Read the rows in the reply. If no row has a `kind`, the plugin is older
   than 0.6.0: go to step 5. Else pick one row by its `title`, `kind`,
   `source` or `created`.
   - `source`: `overview` (a session overview), `store-overview`,
     `store-review`, `store-thread` (a thread of a review) or
     `session-note`.
   - `kind`: `commit`, `branch`, `pull-request`, `file` or `other`.
3. Send `file_focus` with the `id` of that row. The id is `store:<id>`,
   `note:<id>` or the id of a session overview.
4. The reply is `focused <source> <id> "<title>"`. A failed reply has
   `"ok":false` and an `error`. If `error` is `no item <id>` or starts
   with `no open file`, go to step 5.
5. For a store review or overview, use the `navigate` command of §9 "Focus
   it now" (Fallback). Take its id and commit from `"$GG" note list --json`
   (a review: `id`, `rev`) or `"$GG" overview list --all --json` (an
   overview: `id`, `commit`). The reply is `opened review <id>` /
   `opened overview <id>`. A thread or a session note has no `navigate`
   route: say in one line where it is in the tool window. A thread is under
   its review. A session note is a File row under its day.
6. No fresh `ide.json`, or no reply: say in one line where the item is in
   the tool window. In By Date, it is under its day. In By Type, it is
   under its kind, then under its day.
