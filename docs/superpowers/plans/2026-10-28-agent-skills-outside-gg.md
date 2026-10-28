# Agent skills outside gg (slice 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An agent outside gg (IDE terminal, AI Assistant, Air) never says it needs gg: it stores commit / branch / PR work in the gg store through the `gg` CLI when `gg` is on PATH, writes session-doc files otherwise, deletes and focuses by itself.

**Architecture:** Text-only change to the three `agent-*` skills in `agent-skills/` and their shared recipe file `agent-overview/FILES.md`. Recipes for the gg store are NOT copied: the skills point at gg's own embedded skill (`gg skill path review`), which always matches the installed binary. Every recipe is proven by running it against a scratch repo under a temp `XDG_STATE_HOME` (Task 3).

**Tech Stack:** Markdown skills; sh + PowerShell recipes; gg CLI (`gg link`, `gg review save`, `gg overview save|list|rm`, `gg note add|list|rm`, `gg skill path`).

**Spec:** `docs/superpowers/specs/2026-10-28-agent-focus-and-note-views-design.md` (§4.1, slice 1).

## Global Constraints

- Wording: every text a user reads is ASD-STE100 (the skills already say so; new text follows it): short sentences, active voice, one meaning per word.
- Inside gg = `GG_SESSION_ID` is set AND not empty. Inside gg → the `gg-*` skills. Outside → these skills.
- An agent outside gg never asks the user to start gg, to open a gg window, or to set a path, property or variable.
- No agent writes, edits or deletes a file under `<state>/gg/notes/`; only the `gg` CLI touches the store.
- Every sh snippet has a PowerShell twin (FILES.md's existing convention).
- The agent and the IDE use the same OS side: an agent in WSL whose IDE runs on Windows calls `gg.exe` (the Windows binary), never `gg`; the store and the session-docs directory are the Windows ones.
- Recipes for the store document shapes come from gg (`gg skill path review` → its "Review document" and "Meta and display" sections; `gg overview save` usage), never re-specified in these skills.
- No plugin code changes in this slice: no version bump; `CLAUDE.md` unchanged.

## Review Focus

1. **WSL agent, IDE on Windows** — `gg` resolves to the Linux binary, the review lands in `~/.local/state`, the IDE never shows it. Expected: the skill picks `gg.exe` on that side. Pinned in Task 1 (FILES.md §9 text) and Task 3 Step 4 (grep).
2. **`GG_SESSION_ID` set but empty** (an IDE terminal that inherited `GG_SESSION_ID=`). Expected: treated as outside gg. Pinned in Task 1 (the test is `[ -n "$GG_SESSION_ID" ]`) and Task 3 Step 2.
3. **An old gg on PATH** without `review save` / `overview save` (the v0.3.0 `gg.exe` in `T:\others\test-1`). Expected: the agent falls back to session-doc files, says so in one line. Pinned in Task 1 (probe = `gg overview list --json` exit 0) and Task 3 Step 3.
4. **Overview of HEAD while an anchored file has uncommitted edits** — `gg overview save` refuses (`<path>: uncommitted changes` / `differs from <sha7> on disk`). Expected: the agent either commits nothing and writes session-doc files for the working tree, or asks the user; it never retries in a loop. Pinned in Task 1 (FILES.md §9 refusal paragraph) and Task 3 Step 3.
5. **Reviewing a commit that is not checked out.** Expected: store route works (gg anchors at the commit); only the files route needs a checkout. `agent-review` step 1 must not stop the store route. Pinned in Task 2 (agent-review step 1 text) and Task 3 Step 3 (`gg review save` on `HEAD~1`).

---

### Task 1: FILES.md — where am I, store route, delete, focus

**Files:**
- Modify: `agent-skills/agent-overview/FILES.md` (new §0 before §1; new §9, §10, §11 after §8; intro paragraph)

**Interfaces:**
- Produces: section numbers the SKILL.md files cite — §0 "Inside gg or not", §9 "Commit, branch or PR work: store it in gg", §10 "Delete", §11 "Bring an existing document to the front". §1–§8 keep their numbers.

- [ ] **Step 1: Write §0 "Inside gg or not"** (before §1)
  - `[ -n "$GG_SESSION_ID" ]` (PowerShell `if ($env:GG_SESSION_ID)`) → inside gg: stop, use the `gg-*` skills / `gg session …` verbs.
  - Else find gg on the IDE's side: sh `GG=$(command -v gg)`; WSL with the IDE on Windows → `GG=$(command -v gg.exe)`; PowerShell `$GG = (Get-Command gg -ErrorAction SilentlyContinue).Source`.
  - Usable = `"$GG" overview list --json` exits 0 (an old gg without the verb fails).
  - Route table: usable gg AND the work is about a commit, a branch or a PR → §9; otherwise (uncommitted work, a walk through the working tree, or no usable gg) → §1–§7 files. One sentence: never ask the user to start gg or to set anything.

- [ ] **Step 2: Write §9 "Commit, branch or PR work: store it in gg"**
  - Target link, in the worktree: `"$GG" link <rev>` (a commit; `HEAD` default), `"$GG" link --pr <n>` (a PR); a branch review = the branch's link (`"$GG" link <branch>`), as gg's review skill says.
  - Review: `"$GG" review save <link> --dry-run --json` (prints `kind`, `label`, `checkout`, `diff`, `hunks`; read the change with those values); the document shape and the meta/display block = the "Review document" and "Meta and display" sections of the file `"$GG" skill path review` prints — read them, do not guess; store with `"$GG" review save <link> --agent "<your name>" --stdin --json`; fix and re-save on `not a gg review document` or `unresolved: <dest>`.
  - Overview of a commit: `"$GG" overview save <link> --agent "<your name>" --file <json> --json` with `{"version":1,"title":…,"overview":<markdown, path:N-M anchors, no note:>,"files":[{"path":…,"annotations":[{"newRange":[a,b],"summary":…,"rationale":…}]}]}`; lines are the file AT the commit.
  - Refusals: gg lists every problem and stores nothing. `<path>: uncommitted changes` / `differs from <sha7> on disk` → the working tree is not the commit: present the working tree with §1–§7 files instead, or ask the user; do not loop.
  - Line notes on a commit: `"$GG" note add --file <path> --new-line <N> --rev <sha> --summary "…" [--rationale "…"]`.
  - Result: the record id (from `--json`), shown in the IDE's Agent Notes tool window under its commit / branch / PR. Reply names the id and where it shows.
  - The store is permanent and shared by every worktree of the repo; gg's expiry rules apply.

- [ ] **Step 3: Write §10 "Delete"**
  - A session doc: delete its file (`rm "$docs/<id>.md"`; the IDE drops it).
  - A store record: `"$GG" note rm <id>` (a note, a thread with its replies, a review) / `"$GG" overview rm <id>`, run in the worktree. Ids: `"$GG" note list`, `"$GG" overview list --json`.
  - No usable gg → one line: the user deletes it in the tool window (**Remove from gg Store**). Never edit or delete store files.
  - Delete only what the user asked to delete (FILES.md §8.5 rule stays).

- [ ] **Step 4: Write §11 "Bring an existing document to the front"**
  - IDE live check and command recipe = §6 (reuse its `cid`/`mv`/reply rules, 2 s, no retry).
  - Find the id: send `{"id":"<cid>","cmd":"files","wait":true}`; the reply's rows with `"source":"overview"` are session overviews (`id`, `title`, `created`); pick by title / date; then `file_focus` with that id (§6).
  - A store review or overview (not in `files` yet): say in one line where it is in the tool window (under its commit / branch / PR) — focusing it comes with a later plugin version.

- [ ] **Step 5: Update the intro paragraph** — the directory files need no tool; the store route (§9) needs the `gg` CLI on PATH, never a running gg.

- [ ] **Step 6: Check numbering and links**

Run: `grep -n '^## ' agent-skills/agent-overview/FILES.md`
Expected: `## 0.` … `## 11.` in order, §1–§8 titles unchanged.

- [ ] **Step 7: Commit**

```bash
git add agent-skills/agent-overview/FILES.md
git commit -m "agent skills: inside/outside gg, store route, delete, focus (FILES.md)"
```

### Task 2: the three SKILL.md files and the README

**Files:**
- Modify: `agent-skills/agent-overview/SKILL.md`, `agent-skills/agent-review/SKILL.md`, `agent-skills/agent-cross-review/SKILL.md`, `agent-skills/README.md`

**Interfaces:**
- Consumes: FILES.md §0, §9, §10, §11 (Task 1).

- [ ] **Step 1: Same opening rule in all three SKILL.md** (right after the title paragraph): "First run FILES.md §0: inside gg → stop and use the gg skills; outside gg, never ask the user to start gg or to set anything." Plus the `description:` frontmatter of each gains "— works with or without gg".

- [ ] **Step 2: agent-review routing**
  - Step 1 ("What is reviewed"): the checkout requirement applies to the FILES route only; with usable gg (§0) a commit, a branch or a PR is reviewed through §9 whether or not it is checked out.
  - Steps 3–4 / 6: on the store route, the remarks and the review are ONE gg review document (§9), not note files + an overview file; on the files route, unchanged.
  - Reply: the record id and where it shows (§9) on the store route.

- [ ] **Step 3: agent-overview routing** — "Steps": an overview of a commit with usable gg → §9 `overview save` (anchors = lines at the commit); a walk through uncommitted work or the working tree → files, unchanged. The "user pastes a link" paragraph stays.

- [ ] **Step 4: agent-cross-review** — the merged review is stored by agent-review's route (store when §0 says so).

- [ ] **Step 5: All three** — a "Delete" line pointing at FILES.md §10 and a "Show an existing one" line pointing at §11, replacing any "needs gg" wording.

- [ ] **Step 6: README.md** — the intro says "need only git and a shell; with the `gg` CLI on PATH, commit / branch / PR work goes into gg's store (FILES.md §9)"; Limits: "To present or review another commit, check it out first" becomes files-route only; add the WSL `gg.exe` line.

- [ ] **Step 7: No "needs gg" wording left**

Run: `grep -rniE 'start gg|open gg|needs? gg|requires? gg|run gg first' agent-skills/`
Expected: no output.

- [ ] **Step 8: Commit**

```bash
git add agent-skills
git commit -m "agent skills: route by FILES.md §0; store route for commit/branch/PR work"
```

### Task 3: prove every recipe on a scratch repo, then install

**Files:**
- None committed (scratch dir under the session scratchpad).

- [ ] **Step 1: Scratch repo and temp state**

```sh
S=$(mktemp -d); export XDG_STATE_HOME=$S/state; mkdir -p $S/r && cd $S/r
git init -q && printf 'a\nb\nc\n' > f.txt && git add f.txt && git commit -qm one
printf 'a\nB\nc\nd\n' > f.txt && git commit -qam two
```

- [ ] **Step 2: §0 inside/outside test**

Run: `GG_SESSION_ID= sh -c '[ -n "$GG_SESSION_ID" ] && echo inside || echo outside'`
Expected: `outside`. And with `GG_SESSION_ID=x`: `inside`.

- [ ] **Step 3: §9 and §10 recipes, literally as written in FILES.md**
  - `gg overview list --json` → exit 0.
  - `gg link HEAD~1` → a `gg://…` link; `gg review save <that link> --dry-run --json` → JSON with `kind`.
  - Save a minimal review document (shape from `gg skill path review`: `{"version":1,"summary":"## Summary\n…","files":[{"path":"f.txt","annotations":[{"newRange":[2,2],"summary":"b became B"}]}]}`) → `--json` prints an `id`.
  - `gg overview save $(gg link HEAD) --agent test --file ov.json --json` with one anchor `f.txt:2` → an `id`; then dirty `f.txt` and save again → refusal naming `f.txt`, exit 1.
  - `gg note add --file f.txt --new-line 4 --rev HEAD --summary x --json` → an `id`.
  - `gg note list` lists the review and the note; `gg note rm <review id>` and `gg overview rm <overview id>` exit 0; `gg note list` no longer shows them.
  - Every command above must be the one FILES.md prints; a mismatch is fixed in FILES.md, not here.
  Expected: all as stated; nothing written under `~/.local/state` (`ls ~/.local/state/gg/notes | wc -l` unchanged).

- [ ] **Step 4: WSL rule present**

Run: `grep -n 'gg.exe' agent-skills/agent-overview/FILES.md agent-skills/README.md`
Expected: at least one hit in each.

- [ ] **Step 5: Session-doc route unchanged** — run FILES.md §2–§5 against `$S/r` (a note + an overview), parse the note with `python3 -c "import tomllib,sys; tomllib.load(open(sys.argv[1],'rb'))"`. Expected: parses; the files are in `$XDG_STATE_HOME/gg/sessions/…/docs/`.

- [ ] **Step 6: Merge-ready** — `git merge master` into the branch if master moved; `./test.sh` green (no code changed; proves nothing else broke).

- [ ] **Step 7: Install (after the user's merge OK)** — `cp -r agent-skills/agent-* ~/.claude/skills/` (the README's install line); then `diff -rq agent-skills/agent-overview ~/.claude/skills/agent-overview` → no output.

- [ ] **Step 8: Acceptance (the user)** — in the IDE terminal / Air, ask an agent: "review HEAD and focus it in the IDE", then "delete that review". Expected: no mention of starting gg; the review appears under its commit; the delete runs `gg note rm`.
