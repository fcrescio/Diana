# Project Structure

## Top-level layout

- `app/` – Android application module containing Kotlin source, resources, and Gradle configuration for the mobile client.
- `docs/` – Architectural references, design notes, deployment guides, and other long-form documentation.
- `scripts/` – Developer utilities and automation helpers (for example, LLM resource upload tooling).
- `samples/` – Example payloads and fixtures that support manual testing or demonstrations.
- `gradle/`, `gradlew`, `gradlew.bat` – Wrapper files used to execute the Gradle build without requiring a local installation.
- `build.gradle`, `settings.gradle`, `gradle.properties` – Root-level build configuration shared by the project.

## Android module layout

The main source set lives under `app/src/main/java/li/crescio/penates/diana`. Key packages include:

- `llm/` – Integrations for language-model processing, prompt management, resource loading, and structured memo summarization.
- `notes/` – Domain models describing recordings, transcripts, memos, and other note abstractions shared across features.
- `persistence/` – Repository classes that coordinate reads and writes of memos, notes, and session metadata.
- `player/` – Audio playback interfaces and the Android-specific implementation.
- `recorder/` – Audio capture interfaces and Android recorder implementation details.
- `session/` – Session lifecycle models and repositories that persist user session state and settings.
- `transcriber/` – Abstractions and implementations for turning recorded audio into transcripts via external services.
- `ui/` – Jetpack Compose screens, theming primitives, and presentation-layer helpers used by the main activity.
- `MainActivity.kt` – Android entry point that wires repositories, Firebase services, and Compose navigation together.

## Tests and resources

- Unit tests reside in `app/src/test/java/li/crescio/penates/diana`, organized to mirror the production package structure.
- Android UI resources (layouts, strings, icons, themes) are under `app/src/main/res`.
- Non-Android assets such as LLM prompts, schemas, and configuration files live in `app/src/main/resources`.

## Where to read next

- [ARCHITECTURE.md](ARCHITECTURE.md) – System overview, major flows, and component responsibilities.
- [NOTES.md](NOTES.md) – Ongoing design discussions, open questions, and decision history.
- [DEPLOYMENT.md](DEPLOYMENT.md) – Build, packaging, and release guidance.
- [WORKLOG.md](WORKLOG.md) – Snapshot of active initiatives with links to deeper plans.
- [`docs/thoughts/`](thoughts/) – Exploratory planning documents and deeper design dives.
