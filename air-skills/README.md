# air skills — notes, overviews and reviews for an agent in the IDE

Three agent skills for an AI agent that works beside a JetBrains IDE with
this plugin installed. The agent writes plain files into the worktree's
session-docs directory; the plugin shows them — overviews in the tool
window's "This session" group, notes as remark boxes in the editor. The
skills need only git and a shell: no other tool, server or process.

| skill | what the agent does |
|---|---|
| `air-overview` | presents a result or a change: notes on the exact lines plus an overview whose links open the code. `FILES.md` beside it is the file recipe all three use |
| `air-review` | reviews a commit, a branch or the working changes: one note per remark (meta `kind`/`severity`/`intent`/`confidence`) plus an overview that is the review (summary, findings, verdict) |
| `air-cross-review` | runs 2–3 subagents on models the user picks, merges their reviews, settles their disagreements, writes one review |

## Install

Copy all three directories into your agent's skills directory, side by
side (the review skills read `../air-overview/FILES.md`):

```sh
cp -r air-skills/air-* ~/.claude/skills/          # Claude Code, every project
cp -r air-skills/air-* <project>/.claude/skills/  # Claude Code, one project
```

Other agents: put them where the agent loads skills, or point its
instructions file at each `SKILL.md`.

## Limits

- Notes and anchors point at files as they are on disk in the worktree
  the IDE shows. To present or review another commit, check it out first.
- The agent and the IDE must use the same OS side: an agent in WSL with
  the IDE on Windows writes into the Windows directory (`FILES.md` §2).
- The file format is `docs/gg-session-docs/SPEC.md` in this repository.
