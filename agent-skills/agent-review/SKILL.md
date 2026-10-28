---
name: agent-review
description: Use when the user asks you to review a change (a commit, a branch or uncommitted work) and show the review in their JetBrains IDE with the AgentNotes plugin — remarks on the exact lines plus a summary with findings and a verdict — works with or without gg.
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
It also says the rule that shapes a review on the files route: a note and an
anchor point at files AS THEY ARE ON DISK in the worktree the IDE shows. On
the store route (FILES.md §9) the lines are the file at the reviewed commit.

**First run FILES.md §0:** inside gg → stop and use the gg skills; outside gg,
never ask the user to start gg or to set anything.

**Two routes** (FILES.md §0). With usable gg, a review of a commit, a
branch or a PR is stored in gg (FILES.md §9). Every other review is written
as files (FILES.md §1–§7).

**Wording.** Write every text the user reads — the summary, the remarks,
your reply — in ASD-STE100 (80% compatibility when possible): short
sentences, active voice, one meaning per word, no idioms. Code, paths,
commands and identifiers stay exactly as they are.

## Steps

1. **What is reviewed.** The user names a commit, a branch or the working
   changes; nothing named = the current branch against its trunk, or the
   uncommitted changes when the branch is the trunk. On the files route
   the reviewed version must be checked out: a branch at its tip, a commit
   as HEAD, working changes as they are. When it is not, ask the user to
   check it out (or to open the IDE on a worktree at that tip) and stop.
   Do not change the checkout yourself. With usable gg, a commit, a branch
   or a PR needs no checkout: review it through FILES.md §9, checked out or
   not.
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
3. **The remarks** — on the store route they are part of the one gg review
   document (FILES.md §9). On the files route: one note file each
   (FILES.md §4), on the lines of the file as it is now. Annotate what a reader would not spot; never a
   remark per hunk. A removed line has no place in the new file: put that finding
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
4. **The review** — on the store route it is the same gg review document as
   the remarks (FILES.md §9): no note files, no overview file; its `summary`
   keeps the sections below and the contracts rule. On the files route: one
   overview file (FILES.md §5), `title = "Review: <what was reviewed>"`.
   The body, exactly:
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
   "tour" or "walk me through", add a walkthrough, written by
   the agent-overview skill's "What the overview says" (its sections, scaled
   to the change). Files route: a second overview file. Store route: the
   walkthrough goes in the review document's "overview" key, as the file
   that `"$GG" skill path review` prints describes. The review stays the judgement; the walkthrough only
   explains. Otherwise write no walkthrough.
6. **Check** (FILES.md §7), then **show it** (FILES.md §6, optional).
   On the store route, §7 and §6 do not apply: the check is the save's
   `unresolved:` and refusal output (FILES.md §9); show it with FILES.md §9
   "Focus it now".
7. **Reply** with the verdict first, the review's title and id, the
   directory, and a three-line summary. On the store route, give the record
   id and where it shows in the tool window (FILES.md §9) in place of the
   directory. Do not paste the review into the
   chat — it is in the IDE.

A question about ONE file or line ("what does this function do?", "is this
safe?") is not a review: answer it, and write one note (FILES.md §4) if a
note helps.

When the user pastes a link copied in the IDE (`Agent Notes note …` from a
remark, `Agent Notes overview …` from a review), follow
`../agent-overview/FILES.md` §8: read the file it names and act on that
remark or review — for example fix what the remark names.

**Delete.** Only when the user asks: FILES.md §10.
**Show an existing one.** FILES.md §11.

