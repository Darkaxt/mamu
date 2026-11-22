# Stable 1.35.0 maintenance

Authorized by R7/R8 of morphe-extended-spec.md and the existing daily maintenance task.
Integrate published stable v1.35.0, commit df78a1ebedb6047f50f8bb6b04c25ad524e3dec6,
through a real three-way merge from the retained v1.34.0 baseline. Initial main is clean
at 571f5096. Release version 1.35.0.1, Android versionCode 9.

Preserve R1-R5/R7-R9 and all completed amendment specifications: private Extended
package/name/repository/updater/signing identity, validated community admission and explicit
exclusions, cached unavailable-source filtering, source priority by actual app-specific patch
count/version, newest installable recommendation, pre-acquisition version choice and independent
row info/Play actions, cached Play icons, native original reuse/download/cleanup, Morphe-themed
Byparr settings/downloader, recipes/queue/cancellation, and delivery recovery checks.
Adopt stable upstream changes by intent, including home-card caching/grouping, configurable
icons/themes, installer/root fixes, patch-failure detail and changelog/update-channel fixes.
Upstream generated-icon build logic is a build input and must join exact-source verification.
Do not silently restore upstream app/package/repository identity through generated resources.
No unrelated refactoring. Phone/tablet keys, settings, recovery files and apps remain untouched;
no live device/browser/download/patch/install trials.

| Stage | Status | Acceptance |
| --- | --- | --- |
| Stable merge and feature reconciliation | COMPLETE | R1-R5/R7-R9 and all amendment behavior connected; conflicts resolved by intent; baseline/numeric identity updated; exact-input gate covers newly introduced build logic; relevant host regression tests and compilation pass. |
| Release artifact verification | COMPLETE | Current exact-input snapshot, required signed assembly and host verification pass through shared gate; package/version/name/persistent signer/16 KiB alignment/recipe verified; source committed with upstream ancestry and APK embedded revision matches it. |
| Publication and independent delivery verification | COMPLETE | Push private origin, publish normal latest release at verified commit; independently download both assets and verify bytes/digests/signature/revision; record proof and update maintenance task; live inspect COMPLETE/no pending merge, clean final main. |

Follow stable-maintenance.md and standing engineering instructions. One ACTIVE stage;
no subagents. Every Gradle invocation uses the shared mutex launcher, two workers/no parallel,
initial 3 GiB Gradle, qualified Kotlin in-process, one 512 MiB test fork, native pools 2/1.
Inspect actual profile and memory failures; never raise budgets without diagnosis. The prior
verified release remains available. No blockers or tracked deferrals.

## Reconciliation evidence

- Resolved ten conflicts by intent: retain deletion of upstream-only app-release.json;
  numeric Extended version/code; combine upstream stable-only update selection with Extended
  numeric/legacy comparison and private delivery; adopt new installed-app action-list API
  while retaining original-package Play shortcut; retain upstream translated patch-option
  notices while substituting Extended's actual storage path in six locales.
- Auto-merges reviewed at native acquisition/retained-original handoff, community validation
  and visibility, newest recommendation, single/batch patch priority, independent info/Play
  row callbacks and preview/universal toggle. Original storage and key manager behavioral
  changes in this stable are copyright headers only; device key/storage contracts retained.
- Generated icon aliases target the retained Kotlin activity namespace; runtime component
  owner is context.packageName (Extended). Labels remain @string/app_name = Morphe Extended;
  applicationId is app.morphe.extended. Existing legacy icon aliases remain catalogued.
- Exact-input regression reproduced omission of buildSrc; corrected input enumeration to
  cover the new buildSrc sources/configuration. Focused maintenance tests pass, including
  interrupted-merge and unpublished-baseline recovery. XML resources parse, labels verified
  and conflict markers absent. Initial Gradle ticket 560 stopped at configuration because the invocation lacked the
  required persistent-key argument; corrected ticket 561 is running. No memory failure.

- Gate ticket 561 completed host compilation and the full unit suite. Inspected every XML
  report: no failures/errors. Relevant admission/backups, source visibility, version/ranking,
  original retention, native/Byparr/Uptodown, private release identity and new icon/catalogue
  checks passed. Generated manifest verified: 31 aliases, legacy aliases retained, one default,
  Extended label reference. Two workers/no parallel, actual Kotlin IN_PROCESS, Gradle 3 GiB,
  one configured 512 MiB test fork with fail-fast/no ignored failures. No memory failure.
  Log: e4e307a943b847eeaf9dd55edac35f8e.log. All stage-one acceptance criteria passed.

- Gate ticket 562 completed signed release assembly. Exact snapshot contains 678 inputs,
  including buildSrc. Package/name/version 1.35.0.1/code 9, persistent signer, v2 signature,
  16 KiB alignment and packaged Uptodown recipe passed verification. Release Kotlin was
  observed IN_PROCESS; both builds used two workers/no parallel and a 3 GiB Gradle heap.
  Four ABI Ninja files inspected: compile/link pools 2/1. No memory failures or budget changes.
  Log: 1aee872a94574bb684dbd7dbede6e2c1.log. Commit binding and embedded revision remain.

- Gate ticket 563 refreshed APK VCS metadata only; source inputs unchanged. APK embedded
  revision and every captured Git blob match release merge commit 4ec91565bc1d91119c35326d92cf6c0ae6a1da76.
  Signature/alignment/recipe/package/version reverified. Stage two COMPLETE.
  Log: b40c30cebd6142c5ab36185c3afb5000.log; no memory failures or budget changes.

- Published private normal/latest v1.35.0.1 at verified merge commit
  4ec91565bc1d91119c35326d92cf6c0ae6a1da76. Both assets independently downloaded, bytes
  and GitHub digests matched. Downloaded APK signature/alignment/package/version/recipe
  and embedded revision reverified. Proof: docs/verification/release-1.35.0.1.json.
- Maintenance task updated through app; persisted ACTIVE baseline, source commit, recovery
  checks and generated-icon input preservation verified. Live inspector reports COMPLETE
  delivery, no active merge, unchanged latest stable, no pending delivery.
- Final reconciliation: all three stages COMPLETE, no blockers/deferrals. Prior verified
  release retained. Device data, patching keys/settings and recovery exports untouched.
  Temporary Git test fixtures self-cleaned; retained release artifacts/input manifests
  and incremental build outputs have defined delivery/recovery/reuse purposes. Subsequent
  publication-proof commit is documentation-only and does not alter APK source inputs.
