# Architecture

Diana captures spoken ideas and turns them into structured notes through a
pipeline that is organized around per-session environments. Each session keeps
its recordings, intermediate memos, and final notes isolated so that users can
safely switch between topics without data leaking across sessions.

## Recording and transcription

`AndroidRecorder` runs on the device and streams audio into session-specific
storage. The recorder hands the captured audio to `GroqTranscriber`, which
invokes the Groq speech model and emits textual **memos** for the active
session. These memos are appended to files such as `memos_{id}.txt`, making it
possible to inspect the raw transcription history for any given session.

## Processing inside `DianaApp`

`DianaApp` wires together the components that react to new memos. It constructs
a session-scoped environment where `MemoProcessor` interprets each memo and
updates the `NoteRepository`. The repository aggregates structured notes and
keeps them synchronized between the local file (`sessions/{id}/notes.txt`) and
Firestore (`sessions/{id}/notes`). The Compose UI that lives inside
`DianaApp` observes repository flows, displays progress, and triggers actions
such as retrying a transcription or opening a memo preview.

Per-session settings toggles (for example, whether to auto-archive processed
memos or stream partial transcripts) are also owned by `DianaApp`. The app
persists these user choices alongside the session so that the interface and the
processing pipeline stay aligned.

## Session management and isolation

Session management is coordinated by `SessionRepository`, which collaborates
with `createSessionEnvironment` to spin up an isolated set of storage
locations, processors, and configuration for each active session. When a user
switches sessions, the repository tears down the old environment and activates
another one without cross-contamination of notes or preferences.

By separating files under `sessions/{id}` and memo logs under `memos_{id}.txt`,
the application keeps the file system cleanly partitioned. Firestore mirrors
this structure by nesting documents under `sessions/{id}/notes`, ensuring that
cloud synchronization respects the same boundaries. The Compose UI uses the
session repository to route user actions and surface the correct data for the
currently selected session.

## Remote session documents

`SessionRepository` mirrors the session list into a top-level Firestore
collection named `sessions`. Each document lives at `sessions/{id}` and stores
the user-facing name, a `settings` map (containing the three processing toggles
and the selected LLM model), the ID of the currently selected session, and a
boolean `selected` flag so other clients can react to selection changes.
Documents are updated with a single `set()` call to keep the schema compact and
atomic. 【F:app/src/main/java/li/crescio/penates/diana/session/SessionRepository.kt†L330-L357】

## Importing remote sessions

The repository can hydrate a session that already exists in Firestore by
calling `importRemoteSession`. The method merges the incoming session into the
local list, persists it to disk, and then enqueues a remote sync so the local
copy stays authoritative if the user edits it. 【F:app/src/main/java/li/crescio/penates/diana/session/SessionRepository.kt†L48-L71】
`MainActivity` wires this into the UI by refreshing the visible session list and
switching the active environment to the imported session, which triggers the
per-session repositories and Compose state to reinitialize with the new ID.
【F:app/src/main/java/li/crescio/penates/diana/MainActivity.kt†L211-L247】【F:app/src/main/java/li/crescio/penates/diana/MainActivity.kt†L559-L585】

## Offline behavior

Sessions are persisted to `sessions.json` inside the app's files directory so
the entire list and the last selection survive process restarts and offline
launches. `SessionRepository` loads this file on initialization, keeps the model
in memory, and rewrites it atomically whenever the list or selection changes.
【F:app/src/main/java/li/crescio/penates/diana/session/SessionRepository.kt†L23-L124】【F:app/src/main/java/li/crescio/penates/diana/session/SessionRepository.kt†L178-L226】
Remote synchronization happens opportunistically on a background coroutine: any
create, update, import, or selection change queues a Firestore update, and the
repository will also backfill remote documents the next time it sees the
network via `fetchRemoteSessions`. This allows the UI to behave normally when
offline while catching up once connectivity returns. 【F:app/src/main/java/li/crescio/penates/diana/session/SessionRepository.kt†L125-L168】【F:app/src/main/java/li/crescio/penates/diana/session/SessionRepository.kt†L264-L318】
