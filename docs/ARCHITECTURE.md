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
