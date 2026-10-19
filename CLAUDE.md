# GgNotes — project notes for Claude

**Start with `AGENTS.md`**: what the plugin must do, the format spec
(`docs/gg-notes-store/SPEC.md`), the example stores (`examples/`) and the
acceptance scenario. This file is the build/platform side.

IntelliJ-platform plugin scaffolded from `~/git-focus` (same Gradle/Kotlin
setup, same scripts). Purpose: surface `gg note` review notes from the
gigagit CLI (`/work/gigagit`) inside the IDE. The product is **AgentNotes**
(renamed from GgNotes on 2026-10-19; the zip is `agent-notes-<version>.zip`,
Gradle `rootProject.name = "agent-notes"`). The repo, the `dev.ggnotes`
packages, the bundle keys, the action ids `GgNotes.*`, the tool window id
`GgNotes` and the notification group id `GgNotes` keep the old spelling on
purpose: they are frozen ids (keymaps, saved layouts). The plugin id
`dev.ggnotes` is kept so a new zip replaces the installed plugin; id and
name freeze on the first Marketplace upload.

## Build & test

- `./test.sh` — full suite (`./test.sh --tests "dev.ggnotes.*"` passes args
  through). `./test-integration.sh` — only `*IntegrationTest` classes
  (`OverviewFlowIntegrationTest` builds a real git repo and drives the whole
  flow — store resolution through git4idea, open, anchor, editor at the
  commit, remark overlay — under a temp `XDG_STATE_HOME`).
- `./gradlew runIde --args=/home/homeend/git-focus` — the acceptance
  scenario of `AGENTS.md` §3.2 in the sandbox IDE (trust dialog suppressed).
  Add `-Pggnotes.stateHome=<dir>` to read a throwaway store at
  `<dir>/gg/notes/<key>/` instead of `~/.local/state`. Inside a running IDE
  the system property `ggnotes.env.XDG_STATE_HOME` overrides the variable.
- Layout: `dev.ggnotes.store` is pure Kotlin and is where the unit tests
  live: `StoreReader` (every part, every record classified per
  SPEC-part2 §1.1), `Reviews` (review documents, fingerprints, threads,
  counts), `Relocator` (context_hash, §5.2 relocation, blob ids),
  `StoreModel` (commits → overviews / reviews / files → threads; working
  entries per worktree), `ReviewComposer` / `NoteComposer` (a review or a
  file's threads as the document the viewer shows), `MarkdownRenderer`,
  `LinkClassifier`. `dev.ggnotes.git` wraps git4idea; `dev.ggnotes.overview`
  is the service, tool window, document pane, navigation, overlays, banner.
- One viewer for everything: a review and a note thread are turned into an
  `OverviewDocument` (markdown + per-file boxes) and opened through the
  overview machinery, so anchors, Back, the banner, missing-anchor greying
  and the editor overlays are shared. `Overview.viewKind` keeps their
  identities apart; `Overview.liveWorktree` makes anchors resolve against
  the working tree instead of a commit.
- Live notes: the working file (State 3/4) or the index copy (State 2) is
  read in the background, each note relocated (`Relocator.relocate`) and
  the anchors carry the relocated lines; a committed note is only verified.
  Never cache relocation results: gg's startup sweep rewrites the store.
- Writes go THROUGH gg, never into the store files: the only write today is
  "Remove from gg Store" (`RemovalPlan` → `StoreRemoval` → `GgCli.batch`,
  i.e. `gg batch --keep-going` with `note rm <id>` / `overview rm <id>`
  lines, children before parents). `GgCli` resolves `gg` from PATH (or
  `ggnotes.gg`) to an absolute path and passes `ggnotes.env.*` through as
  environment, so the integration test's temp `XDG_STATE_HOME` reaches gg.
  Anything that blocks the EDT while waiting on `StoreRemoval` deadlocks
  (its completion closes the document on the EDT): poll with `waitFor`.
- Windows key spelling is CONFIRMED (2026-10-15): the spec branch's gg built
  for windows/amd64 (`GOOS=windows go build ./cmd/gg` in gigagit's
  `gg-notes-store-format` worktree, run through WSL interop in
  `/mnt/t/others/test-1`) printed key `ca3f14ca89ceb043` =
  sha256(`T:\others\test-1\.git`)[:8] under `%LocalAppData%\gg\notes`;
  `StoreLocatorTest` pins it. The old `gg.exe` in test-1 (v0.3.0) has no
  `overview store` verb and its 12 Windows stores are gg's own test repos.
- Steering (`docs/gg-steer/`): `dev.ggnotes.steer` is the `gg session …
  --to ide` target. Pure and unit-tested: `SteerPaths` (gg's EncodeRepoKey
  inbox path, plus `docs/`), `Wire` (command/reply JSON), `Inbox` +
  `Presence` (drain rules, replies, sweep, ide.json), `Lines` (gg's line
  rule and landed prose), `Reanchor` (gg's agentdocs rule over a Myers line
  diff), `SessionMemory` (open files f<n> only) and `SessionAnchors` (the
  grammar plus `note:<id>`). IDE: `SteerService` (presence per git root
  from the startup activity, 1 s touch, 500 ms drain + watcher, one worker
  thread, replies only on `wait`), `SteerHandler` (verb by verb, PLUGIN.md
  §4; the `note_*`/`overview_*` verbs are retired and refused with the
  session-docs prose).
- Session docs (`docs/gg-session-docs/`, the pure layer in
  `dev.ggnotes.session`): an agent's temporary overviews and notes are files
  in `<inbox>/docs/` (`<id>.md`, `<id>.toml`). `SessionDocNames` (ids, the
  ignore rules), `SessionDocParser` (both shapes, gg's refusal prose),
  `ColdAnchor` (first placement from the note's own lines), `DocDirectory`
  + `DocIndex` (listing; reconcile by id on a (size, mtime) stamp), all
  table-tested against gg's conformance files. IDE: `SteerService` reloads
  every root's `DocFolder` every 2 s and on a watcher wake (the inbox is
  watched so a `docs/` a writer creates is seen); `sessionOverviews(root)`
  feeds the tool window's "Session" group, `viewerOverview` opens a file as
  a live document (`viewKind = session:<top>\0<id>:`), a replaced open
  overview is re-rendered on the same anchor, a removed one closes;
  `SessionNoteMarks` draws note boxes on every editor of a noted file (cold
  placement, then the debounced re-anchor; a replaced note keeps its place
  when only its text changed). Deletes (`deleteOverview`, `deleteNote`,
  `deleteNotesOn`) unlink files and let the reconcile do the rest; the
  reader never creates the directory or writes a document.
- `SteerService.start()` is a no-op in unit-test mode: tests publish
  presence only via `startSync()` under a temp `XDG_STATE_HOME` set BEFORE
  `super.setUp()`, so the suite never writes into the live state dir.
  Close every editor before `super.tearDown()` in tests that open the
  git index file (`GitIndexVirtualFile`): git4idea disposes that file
  system before the framework closes editors and an assertion fires.
- To drive the plugin by hand: find the inbox under
  `~/.local/state/gg/sessions/<Enc(common)>/steer/<Enc(worktree)>/`, check
  `ide.json`, then `mv` a JSON command into `ide/cmd-<id>.json` and read
  `ide/reply-<id>.json` (PLUGIN.md §7). gg's own `--to ide` is on
  gigagit's `feat/steer-ide` branch and was not built when this landed.
- Regex rule: never put an alternation inside a repeated group
  (`(?:a|b)*`) over user text — java.util.regex recurses per character and
  a 6 KB prose review overflowed the stack. Character classes only.
- `./dist.sh [deploy-dir]` — builds `build/distributions/gg-notes-<version>.zip`;
  with a deploy-dir the dir is created, wiped (guarded against `/` and
  `$HOME`) and the fresh zip copied in. `.bat` twins for Windows.
- JDK: Gradle needs 17–23. `java` on PATH is 25, so the scripts pick
  `~/.sdkman/candidates/java/21*` when `JAVA_HOME` is unset or 24+. Set
  `JAVA_HOME` yourself when running `./gradlew` directly.
- Test results: `build/test-results/test/TEST-*.xml`.
- Run everything from WSL; the checkout is ext4-native.

## Workflow

- Each feature gets its own git worktree: branch `feat/<name>`, per-task
  commits on the branch, merge to master only after the suite is green,
  then remove the worktree and branch. `/work` is root-owned, so a sibling
  `<repo>-worktrees/` dir cannot be created there: worktrees live under
  the project-local, git-ignored `.claude/worktrees/<name>` instead.
- Version lives in `build.gradle.kts` (`0.1.0+<sha>`); bump it and refresh
  `<change-notes>` in `plugin.xml` for every release.
- All user-visible strings go through `GgNotesBundle` /
  `messages/GgNotesBundle.properties`; actions use the convention keys
  `action.<id>.text` / `action.<id>.description` / `group.<id>.text`.

## Hard constraints (inherited platform knowledge)

- Kotlin `apiVersion` is pinned to 1.9 (`languageVersion` 2.0) in
  `build.gradle.kts`: 2024.2 bundles stdlib 1.9, and the unpinned 2.3
  compiler emits calls into 2.2+ intrinsics (`SpillingKt` in suspend
  functions → NoSuchClassError on 242, caught only by `gradlew
  verifyPlugin`, never by the suite). Raise it only together with
  since-build, and run the verifier before every upload.
- Mutually exclusive "radio" actions must NOT extend `ToggleAction` — Find
  Action renders every ToggleAction as an independent On/Off switch. Use
  plain `AnAction` + `Toggleable`, setting
  `Toggleable.setSelected(e.presentation, …)` in `update()`.
- Platform Kotlin interfaces whose default methods are `@ApiStatus.Internal`
  (e.g. `ToolWindowFactory`: `getIcon`/`getAnchor`/`manage`) must be
  implemented in Java under `src/main/java`: a Kotlin implementor gets bridge
  methods for every default, and `verifyPlugin` reports them as
  INTERNAL_API_USAGES.
- git4idea: only `GitLineHandler(Project?, VirtualFile, GitCommand)` is
  stable 242 → 262; the executable-explicit constructors and
  `GitExecutableManager.getExecutable(Project, File)` switched `File` →
  `Path` in 2025.3 (NoSuchMethodError on IU-253+).
- Marketplace validators only read the MAIN `plugin.xml`, not optional
  config-files: anything a validator must see stays in the main descriptor.
- Never run git on the EDT; identity/git lookups right after project open
  belong in the background.
