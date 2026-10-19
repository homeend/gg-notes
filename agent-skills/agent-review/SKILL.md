---
name: agent-review
description: Use when the user asks you to review a change (a commit, a branch or uncommitted work) and show the review in their JetBrains IDE with the AgentNotes plugin — remarks on the exact lines plus a summary with findings and a verdict.
argument-hint: "[commit | branch | working] [what to focus on]"
---

# agent-review — a full review shown in the IDE

Review one change and write it as files the IDE shows: one **note** per
remark, on the exact lines, and one **overview** that is the review itself —
summary, findings (each one a link to its remark), verdict. The user opens
the review in the IDE's Agent Notes tool window ("Session" group) and walks the
findings.

**How to write the files is in the agent-overview skill's FILES.md
(`../agent-overview/FILES.md`, installed beside this skill) — read it first.**
It also says the rule that shapes every review here: a note and an anchor
point at files AS THEY ARE ON DISK in the worktree the IDE shows.

**Wording.** Write every text the user reads — the summary, the remarks,
your reply — in ASD-STE100 (80% compatibility when possible): short
sentences, active voice, one meaning per word, no idioms. Code, paths,
commands and identifiers stay exactly as they are.

## Steps

1. **What is reviewed.** The user names a commit, a branch or the working
   changes; nothing named = the current branch against its trunk, or the
   uncommitted changes when the branch is the trunk. The reviewed version
   must be checked out: a branch at its tip, a commit as HEAD, working
   changes as they are. When it is not, ask the user to check it out (or
   to open the IDE on a worktree at that tip) and stop. Do not change the
   checkout yourself.
2. **Read it**, with git, in the worktree. Read in this order:
   - **The range.** A commit: `git show --stat <sha>`; its range is
     `<sha>^..<sha>`, its base `<sha>^`. A branch: the trunk
     is `git symbolic-ref --short refs/remotes/origin/HEAD` (strip
     `origin/`), else `main`, else `master`; `base=$(git merge-base
     <trunk> HEAD)`, range `$base..HEAD`. Working changes: `git status`
     (untracked files are part of the review) and `git diff HEAD`.
   - **The intent first.** `git log <range>` (full messages). Judge the
     change against what it is FOR.
   - **The change.** `git diff -M -w --stat <range>`, then `git diff -M -w
     <range> -- <file>` for each file that matters.
   - **The before.** The removed lines are in the patch; `git show
     <base>:<path>` gives the old file whole. You cannot call a change
     wrong without the code it replaced.
   - **The callers.** Search the worktree for each new or changed name.
     Find where execution enters the changed code from code the change did
     not touch — a bug often sits at that boundary.
   - **Mechanical files.** Note renames, formatting-only edits and
     generated files. Do not annotate them.
3. **The remarks** — one note file each (FILES.md §4), on the lines of the
   file as it is now. Annotate what a reader would not spot; never a
   remark per hunk. A removed line has no place on disk: put that finding
   on the nearest new line that replaced it, or only in the summary.
   Every note carries `[[meta]]`:
   - `kind` — `issue` (wrong now), `suggestion` (fine now, could be
     better), `nitpick` (style, trivial), `question` (you need the
     author's answer), `note` (context or impact, nothing to fix), `good`
     (you checked it and it holds);
   - `severity` — `high` | `medium` | `low` (on issue, suggestion, note);
   - `intent` — one line: what made you write this remark;
   - `confidence` — `low` | `medium` | `high`.

   `summary` is the finding in one sentence; `rationale` says why it
   matters and how it fails.
4. **The review** — one overview file (FILES.md §5), `title = "Review:
   <what was reviewed>"`, the body exactly:
   - `## Summary` — 1–3 sentences: **before → after** (what the code did,
     what it does now). When there are mechanical files, end with one line
     `Mechanical: <files>`.
   - `## Findings` — one bullet per finding, most important first, the
     user's focus first when they gave one; each bullet links its remark —
     `- [Unbounded retry](note:<id>): …` — or `path:N` when it has no
     note; `- None.` when there is nothing. Always cover **contracts**:
     every change to what other code, users or stored data depend on (a
     CLI flag, a config key, a stored format, a public API, a wire shape)
     and what happens to old data and old callers. A breaking change
     without a migration is a finding.
   - `## Verdict` — approve, comment or request changes, with a one-line
     reason.

   Write each link bare — backticks around a link make it plain text.
5. **A walkthrough (only when asked).** When the user says "overview",
   "tour" or "walk me through", add a second overview file, written by
   the agent-overview skill's "What the overview says" (its sections, scaled
   to the change). The review stays the judgement; the walkthrough only
   explains. Otherwise write no walkthrough.
6. **Check** (FILES.md §7), then **show it** (FILES.md §6, optional).
7. **Reply** with the verdict first, the review's title and id, the
   directory, and a three-line summary. Do not paste the review into the
   chat — it is in the IDE.

A question about ONE file or line ("what does this function do?", "is this
safe?") is not a review: answer it, and write one note (FILES.md §4) if a
note helps.
