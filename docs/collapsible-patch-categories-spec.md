# Collapsible patch-panel categories

Authority: user request on 2026-10-13. Implementation is already authorized.
This extends the completed unified panel and universal category specifications.

## Acceptance

- F1: Every nonempty category in the shared single-app/batch expert panel is collapsible,
  including a panel with just one category. Universal categories start collapsed; custom
  patches and the opt-in other-version custom group start expanded.
- F2: Reuse Morphe's PatchGroupHeader, chevron, patch count and selected-count badge.
  Preserve the existing theme, 48 dp minimum header target, weighted/ellipsized label and
  translated expand/collapse announcement. Collapsed rows are removed from the lazy list.
- F3: Folding changes presentation only: patch selection, options, compatibility, source
  identity/visibility, order and execution remain unchanged. Keep explicit folds for the
  lifetime of the current app/version panel, including temporary source/filter changes.
  Stable category keys must not depend on translated headings or a patch's selection.
- F4: Search and selected-only mode temporarily expose their matching rows without changing
  explicit folds; clearing either restores the ordinary defaults/overrides. Compatibility
  filters alone must not expand all universal categories.
- V1: Focused host tests verify defaults, single-category behavior, stable independent folds,
  restored folds and retained patch identities/selection. Compile the shared UI and rerun
  relevant grouping/panel/batch regressions through the host-wide Gradle gate. No device trials.
  Commit and synchronize the verified change; this request does not include a new release.

## Stages

1. Category behavior — COMPLETE. F1-F4: use existing grouping/fold/header components in the
   unified panel; focused tests describe the contract.
2. Verification and integration — COMPLETE. V1: focused host verification, design/accessibility
   review, final reconciliation, commit and remote synchronization.

## Ledger

Stage 1 F1-F4 passed focused host verification through gate 894. The shared panel compiled;
actual default/fold identity/order/selection tests and existing grouping, panel compatibility
and batch source-selection regressions passed. Gate/profile/test XML evidence is retained in
`verification/collapsible-patch-categories.json`. Search/selected-only rendering and unchanged
patch callbacks were reviewed in the integrated lazy-list code. Gate 893 stopped during
configuration for the missing persistent key path; gate 894 supplies it. Both daemons exited;
no memory failure occurred.

Stage 2 is COMPLETE. Final design/accessibility review confirms the existing full-width header,
48 dp minimum target, ellipsized weighted title, patch count, selected badge and translated
expand/collapse chevron. Collapsed patch items are absent from the lazy list; search/selected-only
only override rendering and never write folds. Compatibility filters still use stored folds.
The same original PatchCard callbacks/keys/options/warnings remain in the expanded list, and
single-app/batch callers share this dialog. This is a source/design-contract review, not rendered
screenshot or live device evidence. F1-F4 and V1's host verification reconcile without gaps.
Transactional cleanup b2987e0127ae61d9f8e290ac7f92c1e9 is applied: all five reviewed
TEMP members removed (246,784 bytes); canonical verification logs are retained. Source commit
`f772d58966cb4941f478b6f2a89b03c4ebb5b514` was pushed and origin/main equality verified; both
implementation/test Git blobs match the verified SHA-256 inputs. All acceptance criteria F1-F4/V1
are satisfied at the stated host-verification boundary. All stages COMPLETE; no blockers or
required deferrals remain. No blockers or required deferrals.
No changes to downloaders, patch execution, persisted
settings, APK identity, signing keys or version numbers are required.
