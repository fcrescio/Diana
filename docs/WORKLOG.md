# Worklog

This log captures active initiatives and recent pushes so contributors can orient quickly.

## Active initiatives

| Date       | Status      | Summary | Further detail |
| ---------- | ----------- | ------- | -------------- |
| 2025-09-27 | Shipped     | Compose splash screen with huntress illustration and build badge gating startup. | [Splash screen concept](thoughts/splashscreen.md) — UI polish; splash shown for ~1.2s while app boots. |
| 2025-09-26 | Shipped     | Memo prompt placeholders now replace `{date}` for scripts and Android to keep LLM instructions accurate. | [Memo date placeholder notes](thoughts/memo-date-placeholder.md) — Tests: `scripts/tests/test_notes_tools.py`. |
| 2025-09-24 | In progress | Firebase notes scripting to backfill memo metadata and align Firestore exports with the memo processor. | [Firebase notes scripting plan](thoughts/firebase-notes-scripts.md) |
| 2025-09-24 | Shipped     | Delete-session dialog polished with vertically stacked Material buttons to preserve touch targets. | Verified via Compose previews on phone and tablet widths. |
| 2025-09-22 | In progress | Thought-document refactor: migrate memo processing to produce markdown bodies and navigation outlines, update persistence, and expose the richer structure in the UI. | [Thought document refactor plan](thoughts/thought-document-plan.md) |
| 2025-09-25 | Shipped | Localized, user-managed tag catalog with offline cache, Firestore sync, and in-app tag editor. | [Localized tag catalog summary](thoughts/tag-catalog-plan.md) — Tests: TagCatalogRepositoryTest, TagCatalogViewModelTest, MemoProcessorTest, ThoughtsSectionUtilsTest. Migration: legacy `tags` strings auto-resolved by repository; no manual run needed. |

## Template for new entries

Add future updates with a quick bullet snapshot to keep momentum visible:

- **Date**: YYYY-MM-DD
- **Status**: e.g., In progress / Shipped / Blocked
- **Summary**: One or two sentences describing scope and goals
- **Key files & docs**: Links to implementation areas or planning notes

- 2025-09-24 – Delete-session dialog polish shipped with stacked Material actions validated against 320dp and 600dp previews; temporary planning doc retired after implementation.

