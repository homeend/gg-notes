# Files — how an agent skill writes notes and overviews

The IDE's AgentNotes plugin watches one directory per worktree. Every
file you put there shows up in the IDE: an **overview** (`<id>.md`) as a row
of the Agent Notes tool window's "Session" group, a **note** (`<id>.toml`) as a
read-only remark box beside its lines in the editor. Nothing else is needed:
no server, no command, no running process of yours. A file stays until the
user deletes it in the IDE (tree context menu → Delete) or you remove it.

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
no fresh `ide.json`: say in one line that the overview is in the Agent Notes tool window's
"Session" group, and go on. Never wait longer, never retry.

## 7. Check before you report

- Each note: `lines` has `end - start + 1` entries and equals the file's
  lines now.
- Each anchor's path exists in the worktree and its lines are inside the
  file.
- Optional: parse a file you wrote (`python3 -c "import tomllib,sys;
  tomllib.load(open(sys.argv[1],'rb'))" <file>` for a note).

## 8. A link the user pastes

In the IDE, **Copy Link** on a row of the "Session" group (or Ctrl+C on
the row) and **Copy Session Note Link** on a note box (editor context
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

