# Tag catalog planning notes

## Current behavior snapshot
- **Data model** – Tags are stored as raw `List<String>` values on structured notes without validation or canonical IDs, and they are persisted verbatim to local JSON and Firestore documents. 【F:app/src/main/java/li/crescio/penates/diana/notes/Models.kt†L15-L44】【F:app/src/main/java/li/crescio/penates/diana/persistence/NoteRepository.kt†L54-L152】【F:app/src/main/java/li/crescio/penates/diana/persistence/NoteRepository.kt†L322-L398】
- **LLM prompts & schema** – The todo and thought schemas expect free-text tags and the English prompt instructs the model to invent 1–3 lowercase tags in the memo’s language, with no notion of a shared catalog. 【F:app/src/main/resources/llm/schema/todo.json†L1-L50】【F:app/src/main/resources/llm/schema/thought.json†L1-L44】【F:app/src/main/resources/llm/prompts/en/user.txt†L1-L49】
- **UI surface** – Notes list renders whatever tags arrive by showing AssistChips, using simple string filtering for the thoughts outline without deduplication or localization awareness. 【F:app/src/main/java/li/crescio/penates/diana/ui/NotesListScreen.kt†L47-L212】【F:app/src/main/java/li/crescio/penates/diana/ui/NotesListScreen.kt†L212-L386】
- **Settings & admin flows** – There is currently no surface for managing tags, only toggles for which summaries the LLM processes. 【F:app/src/main/java/li/crescio/penates/diana/ui/SettingsScreen.kt†L1-L100】

### Pain points
- Users and the LLM can create near-duplicate or untranslated tags, fragmenting search/filter results and creating cluttered chips.
- No authoritative catalog exists to drive localization, analytics, or governance; everything is inferred from memo wording.
- The LLM has no mechanism to align free-form tags with any future curated list, complicating backwards compatibility.
- We lack migration paths for historic notes whose tags may not map cleanly to a future canonical set.

## Functional requirements
### Fixed catalog lifecycle
- Maintain a deterministic catalog of tag records with stable IDs, activation state, and optional grouping/ordering metadata.
- Provide tooling for authorized users to request additions, edits, or deprecations with audit history.
- Enforce catalog membership when saving or editing notes, including LLM-generated items.

### Localization rules
- Support at minimum English (fallback), Italian, and French labels per tag with room for future locales.
- Require localized display strings before a tag can be activated; fall back to English only when a locale string is absent.
- Track revision timestamps per locale to coordinate translation updates.

### User management & UX
- Limit catalog editing to explicit roles (e.g., admins, editors) surfaced via settings or a dedicated management screen.
- Offer memo authors lightweight tag selection from the catalog, including search, recently used tags, and favorites.
- Surface deprecation warnings for notes that reference retired tags and suggest replacements.

### LLM constraints
- Supply the LLM with the active catalog (ID + localized label) for the user’s locale and require it to emit tag IDs instead of free text.
- Validate LLM output against the schema and catalog, rejecting or correcting tags that are inactive or missing translations.
- Provide deterministic mapping from historic free-form tags to catalog IDs during ingestion/migration.

### Migration expectations
- Audit existing notes to build a mapping table from free-form tags to proposed catalog entries, including “unknown” buckets.
- Support batch updates of historical notes, with rollback, to replace legacy tags with catalog IDs.
- Communicate changes to users (release notes, in-app notices) before and after migration windows.

## Design options
### Storage strategy
1. **Local JSON seed + Firestore catalog** (preferred): Ship a versioned JSON catalog with the app for offline defaults, then sync updates and overrides from a Firestore collection to keep clients current.
2. **Firestore-only source**: Store the entire catalog centrally; clients must fetch before enforcing tags (simpler pipeline but offline-hostile).

### Data modeling
- Represent each tag as an object: `{ id: String, locale_labels: Map<Locale, String>, status: Active|Deprecated|Hidden, synonyms: [String], createdAt, updatedAt }`.
- Keep localized labels separate from display formatting in UI, enabling locale-specific sorting and search.
- Preserve legacy tag strings in a lookup table for migration analytics and auto-suggestions.

### Integration points
- **MemoProcessor**: Inject catalog context into prompts/schemas so the LLM can only output tag IDs; post-process results to attach localized labels.
- **NoteRepository**: Persist tag IDs while denormalizing localized labels when writing exports or backward-compatible text fields.
- **UI (Notes & Settings)**: Update filtering, chips, and management surfaces to work off IDs and localized labels, including offline cache refresh cues.
- **Telemetry**: Emit events when tags are applied, replaced, or rejected to monitor catalog health.

## Prioritized acceptance criteria
1. **P0** – Client persists and renders notes using catalog tag IDs mapped to localized labels; free-form tags are rejected at entry and during LLM processing.
2. **P0** – Firestore exposes a versioned catalog endpoint and clients reconcile updates (including activation/deprecation) within one sync cycle.
3. **P1** – Migration job converts ≥95% of historic free-form tags to catalog IDs with audit logging and retry support; remaining items are flagged for manual review.
4. **P1** – Settings or admin screen allows authorized users to view catalog status, trigger sync, and manage localization completeness indicators.
5. **P2** – UI exposes suggestions, favorites, and deprecation guidance to end users during note editing and review workflows.
