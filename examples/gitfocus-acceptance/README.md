# gitfocus-acceptance — a frozen copy of the acceptance scenario

`GitFocusAcceptanceIntegrationTest` runs on these files, never on the live
`~/git-focus` store: gg prunes and rewrites that store (on 2026-10-28 it held
2 of these 8 records), so a test on it fails for reasons outside the plugin.

| file | what it is |
|---|---|
| `git-focus.bundle` | `git bundle create … master` of `~/git-focus` (master at `ade4156`, 2026-10-05). The test clones it into a temp dir; the commits keep their real ids |
| `commits.toml` | the store gg wrote about that history, `format = 3`, 8 records: the overview `7cf0812f` "GitFocus tab: branch-first rows" (commit `d5efa035`), the prose review `fd1eaedb` (commit `4e55504b`), and six line notes on commit `b434be1e` (`RowTextFit.kt` 63, 67; `WorktreesPanel.kt` 419, 427; `RowTextFitTest.kt` 79, 89) |

Provenance (2026-10-28): the overview and the review are the live
`commits.toml` byte for byte; the six line notes are byte for byte from
`notes.toml.migrated-1791935419` beside it (gg kept the pre-upgrade file),
whose record shape format 3 reads unchanged. The test puts `commits.toml`
under a temp `XDG_STATE_HOME` at the key of the clone's common dir.
