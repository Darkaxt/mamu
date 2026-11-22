# Stable 2.1.0 integration

Authority: R7 of morphe-extended-spec.md, stable-maintenance.md and the user's standing implementation/release instructions. Published stable v2.1.0 at fb8d86aee8c5ae90038f4a062bc36975b1bd217c is the only new upstream input. Extended release: 2.1.0.1, Android code 30. No device trials or new toolchain installation.

## Required outcome

R1. Complete the real three-way merge from the verified 2.0.0.2 baseline; preserve private identity, signer, all Extended acquisition, library, compatibility, source visibility, release-age and known-failure contracts, and the adopted PatchFlow/ExpertModeSelection ownership and durable preparation queue.

R2. Adopt upstream's embedded Java 17 runtime and 32-bit client/serial-GC tuning, source-bound native/host assets, diagnostics and legal notices. Build Gradle on existing JBR25; use existing Temurin17 only for same-feature runtime linking/shrinking. Adopt the stable wrapper update. Preserve per-run scratch and exact outcome/input ownership. Verify every packaged runtime/host byte and the actual embedded feature version.

R3. Integrate stable batch mount targeting per app, including root availability, tracked-install/default target, availability filtering, editor/worker agreement, retry, deliberate target selection and correct mount installation. Persist per-target intent across preparation restore, accept previous queue format, re-resolve current data, and preserve live edits, leases and stale-result guards.

R4. Accept APK-family shares based on parsed contents instead of file extension, preserve cancellation-safe input copies, and carry the imported archive label/icon into the Extended picker. Adopt source-named export filenames with off-main metadata parsing. Retain approved Extended UI/navigation/settings; integrate useful tour additions into its existing targets, without importing the upstream replacement dialog/pager stack.

R5. Pass app and patcher-core host regressions, affected integration/native fixtures and a signed aligned APK with exact build-input snapshot (including buildSrc), persistent signer and packaged runtime/recipe/VCS identity. Inspect actual gate profile and daemon exits. Reconcile every R1-R4 requirement before stage closure.

R6. Commit verified integration, refresh packaged VCS when required, publish a normal private latest v2.1.0.1 with canonical APK and verification JSON, independently download both assets and verify byte/digest/package/signature/alignment/runtime/source/tag/latest identity. Complete reviewed scratch cleanup and update the existing maintenance baseline without changing schedule/target. No intermediate release.

## Stages and reconciliation

1. Integrated stable update — COMPLETE. R1-R5 reconciled and verified under docs/verification/stable-2.1.0.json. Full app/core host regression evidence retained; final affected queue/editor/native fixtures and source-bound signed candidate passed gate1739, including parser leases and imported-label wiring. No blockers or tracked deferrals.
2. Final delivery — COMPLETE. R6 verified under docs/verification/release-2.1.0.1.json: normal private latest v2.1.0.1/code30 at 39b36d75f95c5b49908a77e463688fd3cf774034, packaged VCS refresh gate1740, both independently downloaded assets with exact byte/digest/package/signer/alignment/runtime identity, and unchanged daily automation schedule/target at the new baseline. Both reviewed cleanup transactions are applied without residuals; release outputs, test/native evidence and signing/recovery files remain preserved. No blockers or tracked deferrals.

## Decisions

The upstream UI was already deliberately superseded by Extended's approved UI in 2.0.0. Resolve those presentation conflicts against the actual stable delta, retaining Extended presentation and applying functional changes at its current integration points. Do not resurrect the excluded dialog stack or tab pager. Host JDK25 remains installed/selected; runtime17 linking uses the existing local JDK17. Upstream diagnostic stall intervals only emit logs; they never cancel or classify a run as failed.

Integration review retained the .2 coordinator's serialized preference writes, parser/import leases, workspace retirement and stale-result checks. Per-app target changes use the same guarded re-resolution path, with a read lease released on actual job completion (including cancellation before launch). Legacy whole-queue mount intent migrates to per-target choices; preparation stores no executable state or acknowledgements. Upstream pager transition changes do not apply to Extended's separate navigation. The existing tour gains its Patch any app spotlight. Source-named exports use cached/IO metadata. No new framework, device trial, optional cleanup or speculative refactor was introduced.
