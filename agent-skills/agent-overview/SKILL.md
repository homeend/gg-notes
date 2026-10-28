---
name: agent-overview
description: Use when the user asks to present, visualise or walk them through a result or a change in their JetBrains IDE (with the AgentNotes plugin) — notes on the exact lines plus an overview whose links open the code — works with or without gg.
argument-hint: "[what to present]"
---

# agent-overview — present a result in the IDE

An **overview** is a short markdown document whose links are anchors into
the code, plus your **notes** on the exact lines. The user opens the
overview in the IDE's Agent Notes tool window (By Date view: under today's
date), walks the anchors top to bottom, and each one opens the file at the place you named, with your
note beside it. Present the result this way instead of a long chat answer.

You write plain files; the IDE plugin shows them. **How to write them is in
[FILES.md](FILES.md) beside this skill — read it first.** It also says what
an anchor can point at: on the files route, only files as they are on disk
in this worktree.

**First run FILES.md §0:** inside gg → stop and use the gg skills; outside gg,
never ask the user to start gg or to set anything.

**Wording.** Write every text the user reads — the overview, the notes,
your reply — in ASD-STE100 (80% compatibility when possible): short
sentences, active voice, one meaning per word, no idioms. Code, paths,
commands and identifiers stay exactly as they are.

**The user says only WHAT to present.** Find out the rest yourself: the
worktree, the directory (files route), what changed. Never ask the user for a path or a
flag that you can compute.

## Read before you write

When you present a CHANGE (a commit, a branch, uncommitted work, your own
work), collect these facts first, with git, in the worktree:

1. **The range.**
   - A commit: `git show --stat <sha>`, then `git show <sha>`.
   - A branch: the trunk is `git symbolic-ref --short refs/remotes/origin/HEAD`
     (strip `origin/`), else `main`, else `master`;
     `base=$(git merge-base <trunk> HEAD)`; the range is `$base..HEAD`.
   - Uncommitted work: `git status`, `git diff HEAD`.
   - Size and noise: `git diff -M -w --stat <range>` (`-M` shows a rename
     as one entry, `-w` hides whitespace-only edits).
2. **The intent.** `git log <range>` (full messages) and what the user
   said. Read them before the code: they tell you what the change is FOR.
3. **The before.** `git diff -M -w <range> -- <file>` shows the removed
   lines; `git show <base>:<path>` shows the old file whole. Without the
   old code you cannot say what changed.
4. **New terms.** In the added lines, find each new type, function,
   constant, config key, CLI flag, endpoint, table or stored field. In the
   removed lines, find each name that is gone or renamed.
5. **The callers.** Search the worktree for each new name. Find where
   execution enters the new code from code the change did not touch.
6. **Mechanical files.** Note renames, formatting-only edits and generated
   files. They go in one line of "Not covered", not in the walk.

On the files route the worktree must hold the version you present
(FILES.md §1). If the user names a commit that is not checked out and gg is
not usable, ask them to check it out first. With usable gg (§0), a
REVIEW of a commit needs no checkout; an OVERVIEW needs the commit on disk
(FILES.md §9): when it is not, ask the user to check it out.

## What the overview says — a walkthrough, never a file list

An overview explains how something WORKS. Write it in the order you would
explain it to a colleague at a whiteboard; the anchors follow that order,
so the walk IS the explanation. The sections, in this order:

1. First line: what the change (or the code) makes possible, in one
   sentence.
2. **Before → after** (a change only): what the code did before, what was
   wrong or missing, what it does now. One short paragraph. Anchor the new
   lines; the old lines stay in words.
3. **New terms** (only when the change adds them): one line per term —
   `name` (its kind), what it IS in the words of the product, not of the
   code ("a claim holds a worktree for one agent", not "a struct with
   three fields"), with ONE anchor on its definition line. Put a term
   before every term and section that uses it. Then **Changed meaning**:
   each existing term whose behaviour changed, anchored. These cause the
   most confusion — never skip one.
4. **Core idea**: one paragraph that names the shape ("a file inbox plus a
   watch", "one gate, writer first"). Add a small diagram in a fenced text
   block only when it makes the shape clearer than the words.
5. **How it works**: one real run through the code, step by step, in
   execution order, crossing files as the control flow does — the trigger
   (key, command, event, call; start at the caller in unchanged code),
   each decision point, the data handed on, where the result lands. One
   sentence per step, with ONE anchor on the lines where that step
   happens.
6. **Contracts** (only when the change touches one): every change to what
   other code, users or stored data depend on — a CLI flag, a config key,
   a stored format, a public API, a wire shape — and what happens to old
   data and old callers. Say "breaking" when it is.
7. **Why this shape**: the two or three design decisions and what each
   avoids, anchored. A reason the commit or the user states needs no mark;
   write `(inferred)` after a reason you concluded yourself.
8. **Edges**: failure and the unusual cases, anchored to their branches.
9. **Not covered**: what you skipped, what you could not confirm (claims of
   a commit message you did not find in the code), open questions, what
   the user should do next, and one line naming the mechanical files.

**Scale it to the change.** A small change (one commit, no new term) gets
sections 1, 2 (one or two sentences), 5, 8 and 9. A branch that adds
concepts gets every section that applies. Code that is not a change (the
user asked how something works) skips 2 and 6.

Never group by file: a file may appear five times, in the order the flow
reaches it. Do not narrate the diff line by line: say what each step does
and why; the reader sees the lines. Prefer several precise anchors
(`path:N`, `path:N-M`) over one wide range; anchor the lines that SHOW the
point, not a neighbour of them. Write each link bare — backticks around a
link make it plain text (FILES.md §5). ≤ 64 KiB, ≤ 100 anchors.

## Steps

0. **Route** (FILES.md §0). An overview of a commit, with usable gg and
   the commit on disk: store it with FILES.md §9 `overview save`. A branch
   overview uses the tip link (`--ref <branch>`); the tip must be on disk
   too. The anchors are lines at the commit. Steps 2–4 are then FILES.md §9. Step 5: check each anchor against the
   file AT the commit (gg's `unresolved:` and refusal output is the check).
   Step 6: focus it with FILES.md §9 "Focus it now" / §11.
   Step 7: reply with the record id and where it shows, not the
   directory. A walk through uncommitted work or the working tree: steps
   1–7 below, as written.
1. **Finish first.** Make every edit before you write a single note.
2. **Find the directory** (FILES.md §2) for the worktree the IDE shows.
3. **Notes on the lines.** One note file per remark, on the exact lines it
   is about (FILES.md §4): `summary` one sentence, the reasoning in
   `rationale`. Several short notes beat one long one. Each anchored step
   of the walk gets a note that says what those lines contribute. Keep the
   ids.
4. **The overview.** One overview file (FILES.md §5) with the walkthrough
   above. Link a step to its note with `note:<id>` when the note says more
   than the sentence; link it to `path:N-M` otherwise.
5. **Check** (FILES.md §7): every `lines` equals the file now; every anchor
   names an existing file and lines inside it.
6. **Show it** (optional, FILES.md §6) when the IDE is live.
7. **Reply** in chat with the overview's title, its id, the directory, and
   a three-line summary. Do not paste the overview into the chat — it is
   in the IDE.

When the user later pastes a link copied in the IDE (`Agent Notes overview
…` or `Agent Notes note …`), follow FILES.md §8: read the file it names —
that is the document or the step they ask about — and work in its worktree.

**Delete.** Only when the user asks: FILES.md §10.
**Show an existing one.** FILES.md §11.
