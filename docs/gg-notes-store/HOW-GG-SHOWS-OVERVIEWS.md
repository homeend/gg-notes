# How overviews work in gg (the behaviour the plugin mirrors)

This is the user-facing behaviour of gg's terminal UI (and gg web) for
overviews, as of gigagit `spec/gg-notes-store-format` (2026-10-15). The
plugin copies the *experience*, not the keys.

## 1. Where an overview comes from

An AI agent, after finishing a change and committing it, presents the result
as a **gg-overview**: it writes a JSON document (`{version, title,
overview:<markdown>, files[].annotations[]}`) and runs
`gg overview save --agent <name> --stdin`. gg checks the document against
the commit (every anchored path must exist at the commit, every line must be
inside the file, no `note:` links, no directory links; for a commit on the
current branch the anchored files must have no uncommitted changes) and
refuses the whole document, storing nothing, if anything is wrong. On
success it stores the document as one record in its machine-local notes
store, on the commit HEAD points at (or on a commit / branch tip / pull
request named by a `gg://` link), and prints the overview's
`gg://<repo>@<sha>?overview=<id>` link. Saving again with the same title on
the same commit (or the same PR) replaces the earlier overview in place.

Two kinds: a **commit overview** (on a commit; the branch is recorded when
HEAD was that branch's tip at save time) and a **PR overview** (on a pull
request's head commit, scoped `<base>...refs/gg/pr/<n>`). There is also a
temporary, in-memory kind (`gg session overview add`) that exists only
inside a running gg — the plugin cannot see those and must not try.

## 2. Where the user finds it

- **Commits list:** a commit that carries an overview shows a `≡` marker on
  its row; the commit's `.` (action) menu has an "Overview" entry (a chooser
  when the commit has several, by title).
- **Branches list:** while a branch's tip is the overview's recorded
  commit, a `└ Overview` sub-row appears under the branch. When the branch
  moves on, the overview stays on its commit ("was tip").
- **Pull-request view:** a PR overview is listed under its PR, not in the
  commit's own list.
- **View all notes** (`ctrl+e`): every overview of the repository as a row
  (title, agent, date, commit), alongside review notes.
- **A link:** pasting a `gg://…?overview=<id>` link (`#` in the TUI, or
  `gg open <link>`) opens that overview directly. gg refuses the link when
  the overview is gone or no longer on that commit.
- **CLI:** `gg overview list [--all]` (HEAD's / the repository's),
  `gg overview show <id|link>` (the markdown and each remark),
  `gg overview export <id|link>` (a self-contained HTML page: the document,
  every anchored file at the commit with its diff, works from disk with no
  gg), `gg overview rm <id>`.

## 3. The overview window (the "split")

Opening an overview shows a **two-pane window**: the rendered document on
the left, a file pane on the right.

- The document is rendered markdown (headings, lists, code spans, quotes,
  tables). Links that are **anchors** are drawn as links and numbered in
  document order; a link gg cannot classify shows as its label with the
  destination in dim text; http(s) links stay links.
- **Walking anchors:** `tab` / `shift+tab` (and `n` / `p`) move the
  cursor from anchor to anchor. The right pane follows: it shows the
  anchored file AT THE OVERVIEW'S COMMIT (not the working tree), scrolled to
  the anchored line, the range highlighted. So reading the overview top to
  bottom is a guided tour: each anchor the author placed brings the matching
  code into view beside the text.
- **Anchor bands:** while an overview is open, every line/range anchor of
  the open file is drawn in that file's gutter as a band (`┃`); the current
  anchor's band is emphasised. `n` / `p` inside the file step between the
  bands, so the user can walk a file's anchors without going back to the
  text.
- **Enter** on an anchor opens the real file view full-size at that place
  (the same place the right pane showed). **Backspace** (gg web: the
  browser's Back) returns to the overview, on the anchor the user left.
- **Remarks** (`files[].annotations`): drawn as read-only boxes beside the
  exact lines of the file, only while this overview is open — a one-line
  `summary`, the `rationale` below it. They are not notes the user can
  reply to; they are part of the document.
- An anchor whose file or lines no longer exist (the commit was rewritten,
  the file moved) is greyed and does not open; the rest of the overview
  still works. A stored document that no longer parses is listed by title
  with a "cannot render" state, never hidden.
- `esc` closes the window and returns to where the user came from.

## 4. What the plugin should take from this

- The unit of display is **one overview = one document + one commit**. The
  list is per repository, grouped/ordered by commit (HEAD's first, newest
  first).
- The reading model is **text on one side, code on the other, anchors as
  the bridge**: moving through anchors moves the code pane; opening an
  anchor goes to the full editor at the commit's content; Back returns to
  the anchor.
- Remarks are **document-scoped overlays** on the file (inlay/gutter hints
  shown while the overview is open), not persistent editor annotations.
- A PR overview is shown with its PR number; the plugin may group it under
  its commit since it has no PR view.
- Nothing here needs gg to run: the store files, the git object store and
  the document are all the plugin needs.
