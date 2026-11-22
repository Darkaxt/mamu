# Next update: source admission and supported-app presentation

Requested on 2026-10-05 while device deployment was in progress. These changes belong to
**the next update**, after the current deployment. Status: COMPLETE. Do not claim these
features are included in v1.33.0-extended.1 or the small settings-group commit.

## Required behavior

1. Do not create a visible source entry for a newly discovered catalogue source until its
   metadata/bundle can actually be downloaded and validated. First-time failures are not
   permanent exclusions: a later normal check can admit the source once fixed. Previously
   working sources retain their cached patches and saved selections during failures.
2. Removing a source and permanently excluding it from automatic discovery must be explicit
   choices. Released v1.33.0-extended.1 automatically excludes on deletion; avoid an obligatory extra
   popup when the existing delete confirmation can present the choice clearly.
3. Adapt native downloader and Byparr configuration surfaces to Morphe's existing dialog
   components, spacing, typography, buttons and active color/theme preferences. Keep accurate
   progress, queue feedback, cancellation, Retry/Open in Browser and Cancel/Save semantics.
4. Resolve and cache the official Play Store icon for supported packages without an installed
   APK/local icon. Identify the listing by the original package identity, including renamed
   patched apps. Keep a placeholder for missing/unavailable listings; do not block list display.
   User example: com.cloudflare.onedotonedotonedotone and its play-lh.googleusercontent.com icon.

## Compact execution plan

| Stage | Status | Acceptance |
| --- | --- | --- |
| 1. Source admission and explicit exclusion | COMPLETE | Failed initial source stays out of list, later healthy check admits it, existing cache survives failure, deletion/exclusion intent is explicit; focused regression checks and real metadata boundary verification. |
| 2. Morphe downloader presentation | COMPLETE | Existing Morphe components and theme applied; unchanged behavior contracts compile and relevant host checks pass; no live product trial is required. |
| 3. Official icon resolution | COMPLETE | Exact package listing yields icon, cache works, unavailable listing preserves placeholder; bounded network/cache regression and actual public listing verification. |
| 4. Integrated verification and commit | COMPLETE | Reconcile all requirements, inspect focused current evidence, commit verified changes; no device delivery or external release unless explicitly requested for this update; never publish a partial stage. |

Follow the standing single-ACTIVE-stage and Gradle gate requirements. Preserve each device's
key/preferences and the last verified release. No device patch/download/install trial.

Authorization: implementation requested; normal investigation, verification and commit proceed.
Current release remains v1.33.0-extended.1. This update is host implementation only until
new delivery is requested. No blockers or tracked deferrals at stage activation.

Stage 1 verified: gate ticket 98/e0ef8f7f38224f97b5ebeee7f263bdeb.log, focused catalogue
filtering and invalid HTML/missing/truncated dex regression checks pass with host compilation.
Fresh AliExpress manifest and 605358-byte 1.0.7 archive returned HTTP 200; manifest, dex
header and ZIP CRC verified. Candidate download, archive validation and guarded metadata load
precede the single database upsert; no exclusion is written on failure. Existing update/cache
paths are unchanged. Delete confirmation explicitly offers exclusion, off by default.
Candidate validation avoids enqueuing per-candidate failures inside the bounded store action.
Observed gate/two workers/no parallel/3 GiB/in-process compiler, one test fork configured at
512 MiB, no memory failure. Stage 2 is now the only ACTIVE stage.

Stage 2 verified: gate ticket 99/0f8a407b98954967a4a260124144fbed.log compiled the compact
Morphe-themed surfaces and passed queue/cancellation/acquisition regressions. Dialogs retain
compact Material layout, use theme background/text colors, existing AppDialog button/action
layout and text fields; status labels now use string resources. Adaptive button layout handles
narrow screens/long labels; progress semantics and labelled cog remain. No device trial.
Observed profile unchanged; no memory failure. Stage 3 is the only ACTIVE stage.

Stage 3 verified: gate ticket 102/00fd511b07b34a7c93eea48335ea666a.log passed icon
identity/host validation, concurrent lookup, persistent positive cache, nonpersistent misses
and cancellation regressions, plus parsing the captured live WARP listing. Official CDN
HEAD returned HTTP 200/image-webp for the exact resolved 512px icon. Original tracked-package
identity is used; eligibility reacts to bundle metadata after cached home cards appear.
Installed/original/patched APK icons stay first; Coil supplies image-byte caching and the
existing placeholder remains during lookup or image failure. Lookup is serialized, bounded
to 4 MiB of HTML and uses existing HTTP policy. Earlier ticket 100 exposed a JUnit non-void
method; corrected its Unit return and fresh focused checks pass. No memory failure.
Stage 4 is the only ACTIVE stage; no blockers or tracked deferrals. Final host packaging and
full unit suite are justified by shared AppIcon and source repository integration changes.

Stage 4 reconciliation: ticket 106/fb7b1e80fd0343f3aa203554da9d0496.log passed debug
assembly and the full current host unit suite; report failures/errors inspected as zero.
Final comparison with upstream PatchBundle showed that manifest attributes are optional for
older bundles. Added a regression and removed that accidental admission restriction; this
localized validation change requires focused follow-up and refreshed packaging, while the
unchanged icon/queue/acquisition/backup test results remain applicable. Native generated
rules for all four ABIs enforce compile depth 2/link depth 1. No memory-related failure.

Final closure: focused ticket 107/16665230a2824f9ea39049d959ebdb5a.log passed the optional-
manifest regression, catalogue checks and refreshed APK assembly. The full suite inspected
in ticket 106 remains applicable to unchanged icon, acquisition, backup and other behavior;
only the directly affected archive validator was changed and rechecked. Packaged DEX contains
the icon resolver and admission validator. docs/verification/next-update-host.json retains
concise current evidence. All four requirements are integrated and host verified, all stages
COMPLETE, no blockers/required deferrals. No live device trials or external release for this
update. Temporary public page/archive captures removed through reviewed transaction
a750bd8c88e50a8a783818941aa874a6; device recovery, signing identity and published release kept.
The completed code is committed for the next release.

## Authorized release delivery (2026-10-05)

User explicitly requested publication. Stage 5: COMPLETE. Publish the complete update as
v1.33.0-extended.2, retaining the existing signing identity and stable v1.33.0 baseline.
Acceptance: signed release and current host tests pass; captured inputs match committed
source and packaged revision; private latest GitHub tag/assets match that commit; an
independent asset download matches its hash, identity, signature and alignment.
No device changes. No blockers or tracked deferrals.

Release host verification: gate ticket 108 / 222c445898694609b85f02a6b9a8c6f4.log
passed the current unit suite and signed release assembly. Exact input snapshot,
persistent signer, package/version, v2 signature, 16 KiB alignment and recipe checked.
Observed two workers/no parallel/3 GiB, Kotlin IN_PROCESS, configured one test fork
512 MiB; actual native compile/link pools 2/1 for all ABIs. No memory failure.
Initial artifact check used a filename without Gradle's -release suffix; corrected
the path and verification passed. Publication/provenance checks remain in stage 5.

Release closure: gate ticket 110 / 6cb19701ac324ab887d439d154104995.log refreshed
packaged VCS metadata without changing verified source. Released tag points to
4cc5fcc7447b04f8e9cdd45ec50390c15386c06b and matches the APK packaged revision.
GitHub latest private release is published, non-draft/non-prerelease; independently
downloaded assets match all local bytes. Downloaded APK signature, identity, alignment,
committed inputs and SHA-256 reverified. Evidence: docs/verification/release-1.33.0-extended.2.json.
All five stages COMPLETE, no blockers or required deferrals; no device changes.
