# Localized tag catalog (shipped summary)

The localized tag catalog is now live for every session. Notes persist stable tag IDs while Compose surfaces resolve locale-specific labels so UI and filtering stay in sync with the curated list.

## Catalog storage and synchronization
- Each session writes the active catalog to `sessions/<id>/tags.json` so the client can stay functional offline and reuse the data across launches. Firestore mirrors the same structure at `sessions/{id}/settings/tagCatalog`, and repository calls reconcile the two stores when loading or saving changes. 【F:app/src/main/java/li/crescio/penates/diana/tags/TagCatalogRepository.kt†L19-L112】
- Catalog saves are atomic on disk and propagate best-effort to Firestore; failed remote writes keep the local snapshot so editors never lose work. The repository exposes `TagCatalogSyncOutcome` flags so callers can surface partial failures. 【F:app/src/main/java/li/crescio/penates/diana/tags/TagCatalogRepository.kt†L41-L132】

## Editing workflow
- The **Manage tags** screen lets authorized users add, delete, and localize catalog entries with inline validation for unique IDs, required English fallbacks, duplicate locales, and empty labels. Save operations stay disabled while edits are invalid or a request is running. 【F:app/src/main/java/li/crescio/penates/diana/ui/TagCatalogScreen.kt†L39-L206】【F:app/src/main/java/li/crescio/penates/diana/tags/TagCatalogViewModel.kt†L16-L212】
- The view model materializes editable rows from the stored catalog, applies validation after every change, and persists updates through the repository. UI state also tracks optimistic success/error banners so the screen can acknowledge saves or prompt for retries. 【F:app/src/main/java/li/crescio/penates/diana/tags/TagCatalogViewModel.kt†L16-L203】【F:app/src/main/java/li/crescio/penates/diana/tags/TagCatalogViewModel.kt†L227-L338】

## Runtime enforcement and LLM integration
- `MemoProcessor` snapshots the active catalog, constrains the JSON schema to approved tag IDs, and sanitizes memo/todo tags so only recognized IDs persist. Unknown tags fall back to the catalog’s primary entry when available and otherwise drop, logging a warning for operators. 【F:app/src/main/java/li/crescio/penates/diana/llm/MemoProcessor.kt†L48-L208】【F:app/src/main/java/li/crescio/penates/diana/llm/MemoProcessor.kt†L213-L279】
- Structured notes resolve display labels from the catalog at runtime, so existing lists and filters automatically localize once translations land. 【F:app/src/main/java/li/crescio/penates/diana/notes/Models.kt†L16-L78】

## Migration and backward compatibility
- `NoteRepository` reads both the new `tagIds` arrays and legacy `tags` strings, translating free-form labels to canonical IDs where possible and preserving unresolved labels for auditing. The same logic runs for Firestore and local disk so mixed deployments stay consistent. 【F:app/src/main/java/li/crescio/penates/diana/persistence/NoteRepository.kt†L19-L373】
- Tag mapping is locale-aware: each catalog definition seeds case-insensitive lookups by ID and every localized label so historic notes map cleanly even when users entered translated terms. 【F:app/src/main/java/li/crescio/penates/diana/persistence/NoteRepository.kt†L464-L724】

## Verification
- Unit coverage: `TagCatalogRepositoryTest`, `TagCatalogViewModelTest`, `MemoProcessorTest`, and `ThoughtsSectionUtilsTest` exercise catalog parsing, validation, schema enforcement, and UI label resolution. 【F:app/src/test/java/li/crescio/penates/diana/tags/TagCatalogRepositoryTest.kt†L22-L135】【F:app/src/test/java/li/crescio/penates/diana/tags/TagCatalogViewModelTest.kt†L12-L103】【F:app/src/test/java/li/crescio/penates/diana/llm/MemoProcessorTest.kt†L25-L664】【F:app/src/test/java/li/crescio/penates/diana/ui/ThoughtsSectionUtilsTest.kt†L6-L97】
- Manual QA confirmed end-to-end editing and rendering in English, Italian, and French locales; no automated migrations were required beyond the repository-level translation of historical tags.
