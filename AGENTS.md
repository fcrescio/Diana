# AGENTS Instructions

## Getting oriented
- **app** – Android client source. The bulk of the code lives under `app/src/main/java/li/crescio/penates/diana`, including feature packages like `recorder` for audio capture, `playback` for reviewing clips, `sync` for cloud coordination, and supporting layers such as `data`, `ui`, and `di` for persistence, presentation, and dependency injection respectively.
- **docs** – Reference material, architectural decisions, deployment notes, and longer-form planning documents.
- **scripts** – Utility scripts for development and automation tasks, such as build helpers or maintenance commands.
- **samples** – Example inputs and artifacts useful for testing, demos, or exploratory work.

## Key docs
- `docs/ARCHITECTURE.md` – High-level system structure, core flows, and major components.
- `docs/NOTES.md` – Working notes that capture design discussions, caveats, and ongoing investigations.
- `docs/DEPLOYMENT.md` – Instructions for packaging and deploying the application.
- `docs/thoughts/` – Planning documents and deeper design explorations.

## Testing

- The full unit test suite can be slow. Run only the tests relevant to your changes.
- For code changes in a module, run that module's unit tests:
  - Example: `./gradlew :app:testDebugUnitTest`.
- To run a single test class or method, use Gradle's `--tests` filter. Examples:
  - `./gradlew :app:testDebugUnitTest --tests "li.crescio.penates.diana.recorder.AndroidRecorderTest"`
  - `./gradlew :app:testDebugUnitTest --tests "li.crescio.penates.diana.recorder.AndroidRecorderTest.someMethod"`
- Only run the full suite (`./gradlew test`) when changes affect multiple modules or wide areas of the codebase.
- Documentation-only or build script changes may skip tests.
