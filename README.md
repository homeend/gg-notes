# GgNotes

IntelliJ-platform plugin that surfaces [gigagit](https://gigagit.code-drill.eu/)
`gg note` review notes inside the IDE. Early scaffold.

## Build

```bash
./test.sh                 # unit + integration tests
./dist.sh                 # build/distributions/gg-notes-<version>.zip
./dist.sh /some/deploy/dir
```

Needs a JDK 17–23 (`JAVA_HOME`, or the sdkman fallback in the scripts).
Install the zip via Settings ▸ Plugins ▸ gear ▸ *Install Plugin from Disk…*.

## Layout

- `src/main/kotlin/dev/ggnotes` — plugin sources (`GgNotesBundle`, `GgNotesIcons`, startup activity)
- `src/main/resources/META-INF/plugin.xml` — descriptor
- `src/main/resources/messages/GgNotesBundle.properties` — all user-visible strings
- `src/test/kotlin` — JUnit 4 tests
- `AGENTS.md` — what to build, the acceptance scenario, data to develop against
- `docs/gg-notes-store/` — the store format spec (copied from gigagit), JSON Schemas, fixture
- `examples/` — example store files in gg's exact on-disk shape, with a walkthrough
- `CLAUDE.md` — working notes and platform constraints
