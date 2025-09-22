# Delete-session dialog remediation plan

## Context

Recent exploratory testing uncovered a UI overlap within the delete-session confirmation dialog on medium-density devices. The destructive action button and explanatory copy are crowding each other, and in certain locales the red "Delete" button partially overlays the message body. This document captures the current behavior, supporting evidence, and the plan for aligning the dialog with Material spacing and typography guidance before development work begins.

## Current layout behavior

- Dialog is implemented as a `MaterialAlertDialogBuilder` hosting a custom layout that mixes `ConstraintLayout` with nested `LinearLayout` blocks.
- Title and supporting body text share the same text style, causing poor hierarchy and limited whitespace between elements.
- Primary and secondary actions are arranged horizontally with fixed margins. On narrow widths this shrinks the text container, letting translated strings wrap into the button area.
- Screenshot to capture: `docs/assets/delete-session-dialog/overlap-medium-density.png` (to be taken on Pixel 5 @ 100% font scale).

## Issues observed

1. **Overlapping controls** – Action buttons render over body text when content height expands beyond design expectation.
2. **Typography hierarchy** – Title shares body style, reducing emphasis and readability. Body text uses 12sp instead of Material's recommended 14sp.
3. **Inconsistent spacing** – Top and bottom padding do not match the 24dp dialog content padding guideline. Vertical rhythm collapses when the error banner appears.
4. **Edge alignment** – Custom layout hard-codes 12dp side padding, misaligning actions with the dialog title baseline compared with Material defaults (24dp start/end).

## Proposed adjustments

### Layout & component changes

- Replace the custom mixed layout with a single `MaterialAlertDialogBuilder` configured via `setView` pointing to a simplified `ConstraintLayout` (or switch fully to Material's default dialog layout if custom header not required).
- Use a vertical `LinearLayout` for the content area with `android:paddingHorizontal="24dp"` and `android:paddingVertical="20dp"` to align with Material dialog specs.
- Position action buttons within a `MaterialButtonToggleGroup` only if multi-select is required; otherwise rely on the default dialog button bar, ensuring buttons stack vertically when width is constrained.

### Spacing & padding

- Apply `marginBottom="24dp"` between the body copy and the button container to maintain separation when button stacking occurs.
- Ensure top padding of 24dp beneath the title and 16dp between title and body copy.
- Confirm that when additional warning text appears (e.g., "This cannot be undone"), it uses `marginTop="12dp"` to preserve readable grouping.

### Typography

- Title: `TextAppearance.Material3.TitleLarge`, color `onSurface`.
- Body: `TextAppearance.Material3.BodyMedium` (14sp), `android:lineSpacingExtra="4dp"` for improved readability.
- Warning message: `TextAppearance.Material3.BodySmall` with `colorError` tint.

### Alignment & behavior

- Set dialog width to `wrap_content` with max width 560dp; rely on Material components to handle smaller breakpoints.
- For RTL support, replace manual `paddingStart`/`paddingEnd` with `paddingHorizontal` so that mirrored layouts align naturally.
- Enable button stacking via `MaterialAlertDialogBuilder` configuration (`setOnShowListener` to call `MaterialDialogsHelper.stackButtonsIfNeeded(dialog)` once utility exists).

## Implementation steps

1. Capture baseline screenshot and add to `docs/assets/delete-session-dialog/` directory.
2. Update layout resource (tentatively `app/src/main/res/layout/dialog_delete_session.xml`) to apply the spacing and typography changes above.
3. Wire up dialog builder to remove custom button container logic so the Material dialog can manage stacking.
4. Verify behavior on small-width device (Pixel 4a, font scale 1.15) and large-width tablet (Pixel Tablet) to confirm layout resilience.
5. Capture post-fix screenshots for comparison and embed them back into this document.

## Open questions

- Should we expose a warning icon or color accent in the title to emphasize destructive action?
- Does analytics need to capture dialog cancellations vs. confirmations after the UI change?

## Next steps

- Align with design on whether the destructive button should adopt full-width style when stacked.
- Prepare engineering tasks for layout refactor, typography styles definition, and QA verification checklist.
