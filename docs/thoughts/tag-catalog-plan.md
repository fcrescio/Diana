# Tag catalog planning notes

## Background
- Capture the need for a structured, multilingual tag system that can support search, filtering, and future analytics.

## Goals
- Define a sustainable approach for maintaining a curated catalog of tags managed directly by users or administrators.
- Ensure localization support for Italian, French, and English audiences with graceful fallback behavior.
- Outline integration touchpoints with existing note-taking and classification flows.

## Requirements
- The tag list must be user-curated with clear tooling for proposing, approving, and pruning entries to keep the catalog intentionally limited.
- Tags must be localized for Italian, French, and English, with English strings serving as the fallback when a translation is missing.
- Provide metadata hooks so other features can reference tag identifiers without duplicating localized labels.

## Open questions
- What constraints should we enforce on the maximum number of active tags per locale to balance flexibility against sprawl?
- How will moderation rights be granted or revoked across different user roles?

## Risks
- Overly broad tag sets could dilute usefulness and introduce inconsistent translation quality.
- Localization updates might lag behind new tag proposals, creating a fragmented user experience.

## Next steps
- Draft workflow diagrams for creating, editing, and deprecating tags across locales.
- Identify instrumentation needed to monitor tag usage and inform future pruning.
- Validate localization requirements with the content team and establish translation SLAs.
