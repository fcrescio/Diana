# Firebase notes scripting plan

## Goals
- Automate export and normalization of Firestore memo notes into the markdown format consumed by the memo processor.
- Provide repeatable scripts to backfill historical note documents and reconcile missing memo metadata.
- Enable scheduled execution so that the memo processor and Firestore stay in sync without manual intervention.

## Assumptions
- Service account credentials with read/write access to the `notes` and `memoMetadata` collections will be available via a CI secret.
- Target collections include `notes`, `noteDrafts`, and `memoMetadata`, with scripts expected to operate primarily on `notes` and mirror updates into the other collections when necessary.
- Memo-processing parity requires the scripts to reuse the same parsing rules and markdown emitters already defined in the memo processor service, either by importing shared libraries or by running in the same container image.

## Risks
- Divergent schema versions between historical documents and current memo processor expectations could cause data loss or require complex migration logic.
- Service account quotas or permission misconfiguration may block bulk backfills and require batching strategies.
- Running scripts without dry-run safeguards could overwrite user edits or cause duplicate memo metadata.

## Open questions
- Should the scripts live alongside existing memo processor tooling or as standalone Firestore utilities?
- What telemetry or logging is required to verify each run and integrate with existing observability dashboards?
- Do we need throttling or exponential backoff to comply with Firestore rate limits during large backfills?

## Outcomes
- Implementation planning is complete; scripting work is queued behind memo processor refactors and has not been executed yet.

## Follow-ups
- Build Firestore automation scripts that satisfy the parity goals outlined above and validate against staging data sets.
- Define dry-run and logging strategies before the first production execution.

## Deviations
- No deviations from the planning assumptions so far; service account provisioning and schema verification still need confirmation during implementation.
