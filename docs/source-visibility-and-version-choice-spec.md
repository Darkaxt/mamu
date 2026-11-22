# Source visibility and version choice correction

Authorized 2026-10-05: fix unavailable uncached source entries and Amazon Shopping
source ordering; show version compatibility/counts and a choice before automatic
APK acquisition when sources target different versions. Publish complete 1.33.0.6/code 6.

R1: Non-default remote sources without a downloaded bundle are hidden from source
management and its home metadata warning. Retain their database identities, settings
and normal retry path; do not delete them or write exclusions. Cached sources remain
visible on remote failure. Default/local source diagnostics remain visible. Successful
retry makes a hidden source visible again. This covers migrated/imported entries too.

R2: Apply custom-count/version priority to the actual pre-download app patch listing
and source chooser, as well as the existing editors. Show supported version information
and custom patch counts before downloading; sources for older/different versions must
be identifiable. When more than one explicit version is offered, allow choosing the
version before Byparr/network APK acquisition or local reuse. Use existing catalogue,
version compatibility/SDK rules, UI components and selected-version download path.
Keep universal counts separate. Preserve selection/options, keys and downloader behavior.

R3: Numeric version/code increase; required host checks, committed build inputs/VCS
revision, persistent signer/alignment, latest private release metadata and independent
GitHub downloads verify. No device trials.

| Stage | Status | Requirements and acceptance |
| --- | --- | --- |
| Visibility | COMPLETE | R1 regression covers uncached imported sources, cached/default/local diagnostics and recovery; same projection drives list and home warning, raw retry/export state retained; focused host checks pass. |
| Version-aware presentation and acquisition | COMPLETE | R2 Amazon regression, preview/chooser ranking and supported version/count display, pre-acquisition choice wires selected version through real downloader path; host checks compile/pass. |
| Release | COMPLETE | R3 host/artifact/publication gates pass; all requirements reconciled and committed. |

Root cause R1: discovery validates only missing repository keys; legacy/imported database
entries skip admission validation. Raw source/error flows feed both UI surfaces.
Root cause R2: AppPatchesDialog independently orders custom sources alphabetically;
the prior fix affected only post-acquisition editors. Actual catalogue on 2026-10-05:
Debakar Amazon 32.13.2.100 has one custom patch, Doom 32.17.0.100 has eight,
De-Vanced declares an unrestricted version. These are distinct version targets.

Additional root cause: catalogue targets are grouped alphabetically by source, but
recommended() selected the first target despite documenting newest supported version.
Resolve recommendation with the newest offered/installable target across all sources.
Preview defaults to newest declared version; its menu shows per-version custom coverage
and reorders sources, marking other-version sources. Acquisition offers a version choice
only when multiple installable explicit targets remain after any chosen-source filter;
Continue enters the existing reuse/download path using the selected target. Editors
prioritize the actually selected APK version after an older version is deliberately chosen.

No blockers or tracked deferrals.

Release host verification: gate 136/6c57dcd6f9774763b221b7f3454caf1f.log passed full
current host tests and signed release assembly. Inspected XML reports have no failures
or errors. Gate acquired, two workers/no parallel, 3 GiB Gradle with Kotlin in-process;
configured test concurrency one fork/512 MiB; generated native compile/link pools 2/1
inspected for release ABIs. No memory failure or budget increase. Publication still
requires exact committed source and packaged VCS revision, then independent GitHub
asset verification; no device runtime claim.

Visibility closure: gate 134/56f1f3bbe8aa4f018369de725708c93b.log compiled both UI consumers and passed inspected source visibility and catalogue regression reports. The source/error display projections filter missing non-default remotes; underlying source map, update eligibility, exports and exclusions are untouched. Cache/default/local visibility and missing-to-present recovery checks passed. Observed gate acquired, 2 workers/no parallel/3 GiB/in-process Kotlin, one configured 512 MiB test fork; no memory failure.

Version UI closure: gate 135/3d3f6a2d498847d08f119ea9fa4e2131.log compiled preview, source chooser and pre-acquisition dialog. Inspected Amazon coverage/order, recommendation source-order independence and existing version-status reports have no failures/errors. Preview menu exposes custom counts per version and marks other-version sources. Chooser cards show custom count and source target; both chooser paths share priority. Before any reuse or redirect resolution, the flow pauses when multiple filtered explicit versions exist. Confirm stores the offered target, then the same selected version is read by retained-original reuse, redirect lookup and APK validation. Cancel/cleanup reset choice state. Theme/radio semantics and standard Cancel/Continue controls reviewed. No device UI trial. Observed gate/profile unchanged, no memory failure. Full current host suite is required for release due to cross-cutting recommendation and acquisition changes.

Release closure: latest private stable v1.33.0.6 points to 03e8d7321ccf7f9f91a9736b9aa2085c08cf2968. Gate 137/78fecb071c704bc9ab29fbba6551590e.log refreshed VCS metadata only; compiled implementation and inspected full-suite results remain unchanged. Exact committed inputs and packaged revision, original signer, alignment and recipe verify. Both GitHub assets were independently downloaded and byte-matched; downloaded APK verification repeated successfully. Evidence: docs/verification/release-1.33.0.6.json. R1/R2/R3 reconciled, all stages COMPLETE, no blockers or required deferrals; no device settings, keys or installations changed.
