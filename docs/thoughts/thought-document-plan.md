# Thought document refactor plan

## Domain model sketch
- Introduce a `ThoughtDocument` domain model composed of
  - `markdownBody`: the canonical markdown representation of the processed thoughts.
  - `outline`: a `ThoughtOutline` structure that captures the hierarchical headings
    to build navigation controls (chapter/section buttons) in the UI.
- `ThoughtOutline` is a thin wrapper around the ordered list of `ThoughtOutlineSection`
  entries. Each section exposes
  - `title`: heading text rendered in the outline.
  - `level`: markdown heading depth to keep nesting information.
  - `anchor`: slug/id generated from the heading for deep linking.
  - `children`: optional nested sections when the LLM emits sub-headings.
- The new data classes live beside the existing note models so they can be reused
  by memo/notes features and persisted independently of the raw LLM summary.

## MemoSummary integration points
- `MemoSummary` now exposes an optional `thoughtDocument` alongside the existing
  string buffers and structured items so downstream components can opt-in to the
  richer document when it becomes available.
- `MemoProcessor.initialize` and `MemoProcessor.process` continue to hydrate the
  legacy `thoughts` buffer today but will populate `thoughtDocument` once the
  LLM prompt/output is updated.
- `MainActivity.syncProcessor` and `MainActivity.processMemo` currently assemble
  the `MemoSummary` that seeds or reacts to processing; both call sites will need
  to supply or consume a `ThoughtDocument` instead of synthesizing a newline-
  delimited string when the new flow is ready.
- `NoteRepository.saveSummary`/`summaryToNotes` persist structured notes derived
  from the summary. They will eventually need to persist the markdown body and
  outline metadata in addition to individual memo notes.

## Call sites assuming the simple string buffer
To keep the follow-up implementation focused, the following code paths and tests
still operate on the newline-delimited `thoughts` string and will need updates:
- `MemoProcessor` maintains the running `thoughts` buffer, updates it through the
  `updateBuffer` helper, persists it via `thoughtPriorJson`, and exposes it in
  `MemoSummary`. (`app/src/main/java/li/crescio/penates/diana/llm/MemoProcessor.kt`)
- `MainActivity.syncProcessor` builds `thoughts` with `joinToString("\n")` and
  reads `summary.thoughts` when synchronizing processor state. It also maps the
  returned `summary.thoughtItems` back into UI notes. (`app/src/main/java/li/crescio/penates/diana/MainActivity.kt`)
- `NoteRepository.summaryToNotes` converts `summary.thoughtItems` into persisted
  memo records while ignoring richer document metadata; persistence helpers like
  `noteToMap`/`parse` expect flat text fields. (`app/src/main/java/li/crescio/penates/diana/persistence/NoteRepository.kt`)
- Tests rely on the plain string contract:
  - `MemoProcessorTest` asserts on `summary.thoughts` contents and seeds
    initialization with simple strings.
- `NoteRepositoryTest` constructs `MemoSummary` instances with raw `thoughts`
  strings when exercising persistence helpers.
  (under `app/src/test/java/li/crescio/penates/diana/...`)

## Usage guidance & next steps
- **Scripts** – `scripts/process_memo.py` now mirrors the Android pipeline for
  local or CI batch processing. Provide a service account key, a memo via
  `--memo`/`--memo-file`, and use `--update` only after confirming the dry-run
  output.
- **Caveats** – Firestore writes reuse the mobile repository semantics (notes are
  appended rather than diffed), so run housekeeping (`clearTodos`/`clearThoughts`)
  if you need to reset a session before large migrations. The OpenRouter call
  shares the production prompt/schema, so keep an eye on rate limits when
  replaying many memos.
- **Next steps** – Fold the Python processor into any automation that prepares
  demo data, then revisit the Kotlin call sites flagged above to finish the
  document-first flow once the memo/thought prompts stabilize.
