---
name: agent-cross-review
description: Use when the user asks for a cross-review — 2 or 3 reviewers on different models review one change, you settle their disagreements and show one merged review in their JetBrains IDE with the AgentNotes plugin — works with or without gg.
argument-hint: "[commit | branch | working] [2|3] [what to focus on]"
---

# agent-cross-review — several models review, you settle it

Run 2 or 3 **subagents**, each on a DIFFERENT model, over one change. Merge
their reviews, investigate every point they disagree on yourself, and write
ONE review as files the IDE shows — the same shape as the agent-review skill:
one note per remark and one overview that is the review.

**How to write the files is in the agent-overview skill's FILES.md
(`../agent-overview/FILES.md`); the review's shape is the agent-review skill
(`../agent-review/SKILL.md`), both installed beside this skill — read both
first.**

**First run FILES.md §0:** inside gg → stop and use the gg skills; outside gg,
never ask the user to start gg or to set anything.

The merged review is stored by agent-review's route: in gg when FILES.md §0
says so (§9), else as files.

**Wording.** Write every text the user reads — the merged summary, the
remarks, your rulings, your reply — in ASD-STE100 (80% compatibility when
possible): short sentences, active voice, one meaning per word, no idioms.
Code, paths, commands and identifiers stay exactly as they are. Tell each
reviewer the same rule.

## Steps

1. **What is reviewed** — agent-review step 1: on the files route the
   reviewed version must be checked out in the worktree the IDE shows;
   otherwise ask and stop. Work
   out the range and the base (agent-review step 2) once; every reviewer
   gets the same values.
2. **How many.** The number the user gave. Missing, or not 2 or 3 → ask:
   "2 or 3 reviewers?"
3. **Which models.** List the models your own subagent tool accepts (for
   example in Claude Code: `opus`, `sonnet`, `haiku` — say which one you
   run on). Ask the user to pick that many DIFFERENT ones; accept names
   they type. Never pick for them. When your subagent tool cannot choose a
   model, tell the user and stop.
4. **Run the reviewers in parallel.** Start one subagent per model, all at
   once, each with the model the user named. Each one is READ-ONLY: it
   reads the code and runs git and search commands, and it writes no file.
   Give every reviewer the same brief:

   > Review the change `<range>` in `<worktree>` (base `<base>`). Read the
   > commit messages (`git log <range>`), the patch (`git diff -M -w
   > <range>`), the old code of changed functions (`git show
   > <base>:<path>`) and the callers of every changed name. <Focus: look
   > hardest at … and list it first.> Do not modify any file. Write in
   > ASD-STE100. Your final message is ONLY this JSON, no prose:
   > `{"summary": "<before → after, 1–3 sentences>", "verdict": "approve |
   > comment | request changes", "findings": [{"path": "<worktree-relative
   > path>", "start": <first line in the file as it is now>, "end": <last
   > line>, "kind": "issue | suggestion | nitpick | question | note |
   > good", "severity": "high | medium | low", "confidence": "low | medium
   > | high", "summary": "<one sentence>", "rationale": "<why it matters /
   > how it fails>"}]}`. A finding about removed code goes on the nearest
   > new line or only in "summary".

   Remember which reviewer ran which model. A reviewer that fails or does
   not return the JSON is a failed reviewer: keep its error for the
   summary. None succeeded → report the errors and stop.
5. **Merge** — see **Merging** below.
6. **Write the files** (FILES.md): one note per kept remark, then one
   overview, `title = "Cross-review: <what was reviewed>"`, `author =
   "<you> — cross-review (<model1>, <model2>[, <model3>])"`. A walkthrough
   only when the user asked for one (agent-review step 5), written by YOU.
7. **Check** (FILES.md §7), then **show it** (FILES.md §6, optional). On the
   store route, the check is gg's output (`--dry-run`, refusals; FILES.md
   §9) and there is no §6 step: say where the record shows.
8. **Reply** with the verdict first, each reviewer and its verdict, how
   many findings were agreed, how many disputed and how you ruled, the
   review's id and the directory (store route: the record id and where it
   shows in the tool window, FILES.md §9). Do not paste the review into the chat.

## Merging

Before you rule on anything, read the change yourself: the intent (`git
log <range>`), the before (`git show <base>:<path>`), the callers, and
the mechanical files (`git diff -M -w --stat <range>`). Drop a reviewer's
finding on a mechanical file unless it names a real defect.

Two findings are the SAME issue when their lines overlap (or are a few
lines apart in the same function) and they describe the same problem.

- **Agreement** — reviewers raise the same issue: ONE note. Its meta
  `raised_by` lists the models (`"opus, sonnet"`); `kind` and `severity`
  are the most serious any of them gave (issue > suggestion > nitpick;
  high > medium > low); the rationale is the clearest one (combine them
  when they add different evidence). Do not re-investigate agreed points.
- **Discrepancy** — any of:
  - an issue only ONE reviewer raised;
  - reviewers contradicting each other on the same code ("bug" vs "fine");
  - kinds that disagree (issue vs good) or severities more than one step
    apart (high vs low);
  - different verdicts.

  For each, **investigate it yourself**: read the code and its callers,
  run read-only checks (git, search, the tests) in the worktree. Then rule:
  - **valid** → keep it as a note; the rationale carries YOUR evidence;
    meta `raised_by` = who raised it, `disputed_by` = who disagreed (when
    someone did), `ruling` = `confirmed`;
  - **not valid** → no note; list it under `## Disagreements resolved`
    with why it does not hold;
  - **undecidable** (needs runtime data, the intent is unclear) → keep it
    as a `kind` = `question` note, `ruling` = `open`, the rationale saying
    what would settle it.
- **Contracts** — check them YOURSELF after the merge, whatever the
  reviewers said: every change to what other code, users or stored data
  depend on, and what happens to old data and old callers. A breaking
  change without a migration is a finding; when no reviewer raised it,
  `raised_by` = your own name.

Every note's meta also carries `kind`, `severity`, `intent` and
`confidence` (agent-review step 3).

The overview's body:

- `## Summary` — before → after in 1–3 sentences; when there are
  mechanical files, end with one line `Mechanical: <files>`.
- `## Findings` — one bullet per kept note, most important first, the
  user's focus first; each bullet links its note (`[…](note:<id>)`);
  `- None.` when nothing is left.
- `## Disagreements resolved` — each disputed point: what each model said,
  your ruling and its evidence; `- None.` when they agreed on everything.
- `## Reviewers` — each model and its verdict; a failed reviewer and why.
- `## Verdict` — YOURS (approve, comment or request changes), with a
  one-line reason; it may differ from every reviewer's.

Write each link bare — backticks around a link make it plain text.

When the user pastes a link copied in the IDE (`Agent Notes note …` from a
remark, `Agent Notes overview …` from a review), follow
`../agent-overview/FILES.md` §8: read the file it names and act on that
remark or review — for example fix what the remark names.

**Delete.** Only when the user asks: FILES.md §10.
**Show an existing one.** FILES.md §11.

