# examples/ — what gg writes, and what the plugin shows for it

Every file here is in the exact on-disk shape of gg's notes store
(`docs/gg-notes-store/SPEC.md`). Use them as test data: copy
`commits.toml` + `previews.toml` into `<tmp>/gg/notes/<key>/` and point the
reader at `<tmp>` as `XDG_STATE_HOME`.

## `commits.toml` + `previews.toml` — the spec's fixture store

A two-file store (both files share one envelope). Reading both with the
spec's §4.3 predicate yields exactly TWO overviews:

| id | file | title | why it is an overview |
|----|------|-------|-----------------------|
| `7cf0812f` | `commits.toml` | How the gate works | `tags=['overview']`, `address.Path=''`, `State=0`, no `parent_id` |
| `3a9e1c20` | `previews.toml` | PR 7: the gate, reviewed | same, plus `preview='main...refs/gg/pr/7'` → a PULL REQUEST overview (PR number 7, from the right half) |

and three records that must be SKIPPED, one per clause of the predicate:

| id | why skipped |
|----|-------------|
| `91f0aa07` | `tags=['review']` and a `Path` — a review remark on lines 3–4 |
| `b2c3d4e5` | `tags=['overview']` BUT a `Path` — an overview-tagged FILE note, not commit-level |
| `c3d4e5f6` | `parent_id='7cf0812f'` — a reply, whatever its tags say |

The `rationale` of an overview record is a TOML **basic** string holding
JSON text (two decodes: TOML → string, string → JSON). Decoded, the first
one is:

```json
{
  "version": 1,
  "title": "How the gate works",
  "overview": "The gate is one function.\n\n## 1. The check\n\n- [`gate`](internal/x/gate.go:3-4) refuses a closed door.\n- [this line](internal/x/gate.go:2) opens it.\n\n## 2. The caller\n\n- [the call](internal/y/call.go:1) runs the gate first. See [the config](config.toml).\n\nSkipped: nothing. Next: none.",
  "files": [
    { "path": "internal/x/gate.go",
      "annotations": [ { "newRange": [3, 4], "summary": "The refusal.", "rationale": "A closed door must not pass." } ] },
    { "path": "internal/y/call.go",
      "annotations": [ { "newRange": [1, 1], "summary": "The only caller." } ] }
  ]
}
```

What the plugin shows for it:

- **Row** in the tool window: `How the gate works · Claude Opus 5.5 ·
  2026-10-13 · d5efa03 · main`.
- **Document**: a paragraph, two `##` headings, two bullet lists. The four
  links are ANCHORS (numbered 1–4 in document order):
  1. `` `gate` `` → `internal/x/gate.go` lines 3–4 (range selected);
  2. `this line` → `internal/x/gate.go` line 2;
  3. `the call` → `internal/y/call.go` line 1;
  4. `the config` → `config.toml`, whole file (no line).
  The files are read AT commit `d5efa035…` (`git show <sha>:<path>`); in a
  checkout that does not have that commit, the row still lists and the
  anchors are marked unresolvable.
- **Remarks** beside the opened file: on `gate.go` lines 3–4 a box "The
  refusal." (hover: "A closed door must not pass."); on `call.go` line 1
  "The only caller." (no rationale).

The PR overview's document has one anchor (`the gate` →
`internal/x/gate.go:1-4`) and no remarks (`files` absent).

## `gitfocus-real/` — a record gg actually wrote

`commits.toml` is the one overview record of the GitFocus plugin's store on
this machine (`~/.local/state/gg/notes/4acf7db7161a42e0/commits.toml`,
written 2026-10-13 by "Claude Opus 5.5"), unchanged except that the seven
other records of that file are left out. `overview-document.json` is its
document, pretty-printed for reading.

It shows the real thing at full size: a 7 KB document, 27 anchors into
three Kotlin files (`WorktreesList.kt`, `WorktreesPanel.kt`,
`WorktreesListTest.kt`), inline code in labels
(``[`rowText`](src/main/kotlin/dev/gitfocus/worktrees/WorktreesList.kt:20-28)``),
`##` sections in explanation order, a closing "what you should do next"
paragraph, and `files[].annotations` on the exact lines. The anchored
commit `d5efa035df1f3c4c949040bebb924a35d0ea61a0` exists in `~/git-focus`,
so this record is the acceptance scenario of `AGENTS.md` §3.2: opened on
that checkout, the plugin must show this overview and open these files at
these lines.

## Link forms you must handle (spec §6–§7)

| In the markdown | Rendered as |
|-----------------|-------------|
| `[gate](internal/x/gate.go:3-4)` | anchor → file, lines 3–4 |
| `[x](./a.go:7)` | anchor → `a.go` line 7 (`./` stripped) |
| `[cfg](config.toml)` | anchor → whole file |
| `[x](a.go:9-3)` | text `x (a.go:9-3)` — end < start is not an anchor |
| `[x](../b.go:1)`, `[x](/abs/a.go)`, `[x](C:/a.go)` | text `x (…)` — leaves the repo |
| `[x](https://example.com/p)` | an ordinary link |
| `[x](javascript:alert(1))` | text `x` — the destination is dropped |
| `[x](note:t12)` | never in a stored overview; text |
| the 101st anchor and later | the label alone, as text |
| a newline inside a paragraph | a hard line break |
| `<b>raw html</b>` | literal text |
