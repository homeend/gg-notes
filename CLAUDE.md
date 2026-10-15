# GgNotes — project notes for Claude

**Start with `AGENTS.md`**: what the plugin must do, the format spec
(`docs/gg-notes-store/SPEC.md`), the example stores (`examples/`) and the
acceptance scenario. This file is the build/platform side.

IntelliJ-platform plugin scaffolded from `~/git-focus` (same Gradle/Kotlin
setup, same scripts). Purpose: surface `gg note` review notes from the
gigagit CLI (`/work/gigagit`) inside the IDE. Plugin id `dev.ggnotes` and
name `GgNotes` are still changeable; they freeze on the first Marketplace
upload.

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
- Layout: `dev.ggnotes.store` is pure Kotlin (locator, reader, link
  classifier, markdown renderer, grouping) and is where the unit tests
  live; `dev.ggnotes.git` wraps git4idea; `dev.ggnotes.overview` is the
  service, tool window, document pane, navigation, overlays and banner.
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
