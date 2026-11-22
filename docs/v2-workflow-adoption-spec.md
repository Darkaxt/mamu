# v2 prepared queues and patch-flow ownership

Authority: the user's request to borrow v2's prepared batch queue, PatchFlow and ExpertModeSelection, with standing final-release authorization. Borrow from stable v2.0.0 (445b31ad), adapting the existing Extended behavior rather than replacing the approved interface. No device trials.

## Required outcome

R1 Prepared batch queues persist across process recreation: retain target/clone identities, exclusions, install policy and mount choice; add targets over multiple sessions without duplicates or replacing existing edits. Re-resolve sources/APKs on restoration; do not restore stale runtime objects, unsupported-version acknowledgements, file leases or automatically resume a running patch. Clearing/start removes the saved preparation deterministically. Replanning cannot overwrite a newer user decision or an already-started item. Keep the independent durable original-download queue unchanged.

R2 Adopt the v2 PatchFlow/ExpertModeSelection responsibility split: HomeViewModel owns the library/source management, PatchFlow owns acquisition/pre-patch/run preparation, ExpertModeSelection owns exact source-specific selections/options/visibility/copy and section actions. Each state has one owner; UI callers use those owners directly where practical. Preserve current interface, original-package clone association, ABI/build/signature validation, Play metadata, downloader, category folds and scoped toolbar behavior.

R3 Preserve actual-APK compatibility, named source-release acknowledgements, known failure default deselection/deliberate retry, source caution and release-age warnings in single/simple/batch paths. Cancellation and late results must not mutate a newer session; all originals, merge/output staging and consumer leases retain the released cleanup/retention contracts. Navigation Home/review/dismiss and options/copy cancellation remain mutation-safe.

R4 Verify prepared queue restore/add/clear/start and flow/selection integration using meaningful host regressions, current UI fixtures and applicable app/core suites. No heavy work added to the UI thread; serialization and replanning use existing background dispatchers. Preserve JDK25, installed Gradle gate, two workers/no parallel/3GiB Kotlin in-process and one test fork. Inspect actual build/lifecycle results.

R5 Publish one normal private latest release 2.0.0.2/code29, matching numeric tag/version/asset with the existing signer and two canonical assets. Commit verified source, refresh packaged VCS, independently verify downloaded bytes/signature/alignment/source/runtime assets, reconcile documentation and daily maintenance baseline, and clean expendable task artifacts through reviewed helper tickets. Do not replace 2.0.0.1.

## Stages and reconciliation

1. Integrated workflow adoption — COMPLETE. R1-R4. Production restore/append/clear and input ownership regressions, source/section selection controls and preserved host/native contracts passed. Final affected checks and signed artifact are recorded in docs/verification/v2-workflow-adoption.json. The scoped refactoring assessment found no remaining required consolidation. Blockers: none. Deferrals: none.
2. Final delivery — COMPLETE. R5. Source cb40f6d830b309935f11c5b0b16143796a87bebd is published as normal private latest v2.0.0.2/code29 with both canonical assets; independent downloads, exact bytes/digests, persistent signer, alignment, embedded assets and packaged VCS passed. The existing daily automation was read back with its schedule/target preserved and the new workflow baseline. Transaction ce41437809591511a970e243432eb046 applied with no residual scratch. docs/verification/release-2.0.0.2.json records delivery. Blockers: none. Deferrals: none.

## Decisions

Prepared queue persistence is preparation only, not crash recovery for an executing patch. Restore target intent and replan against current sources; do not fabricate compatibility or preserve stale capabilities. The separate v2 dialog-stack redesign is outside this request; keep existing visual/navigation behavior while moving its ownership into PatchFlow.
