# App row patch information

Authorized 2026-10-05: add an info shortcut beside Google Play on each app row to
show available app-specific modifications and supported versions without APK acquisition.

Use the existing row preview callback and metadata-backed AppPatchesDialog. Cover both
installed/renamed and not-yet-patched rows, plus normal/grouped/search/hidden-search
wrappers. Each independent action has a labelled 48dp touch target and theme color.
Info must not activate the parent app click, APK resolver, downloader or local APK picker.
Suppress interactive info action in multi-select/drag-only mode. Preserve Play Store.

Open the preview with patches explicitly naming the original app package by default.
Keep universal patches accessible through a toggle; universal-only apps keep their existing
preview. Retain version menu, custom counts, ranking and source/patch descriptions.
Use metadata already in the manager; do not require an APK or add a new remote service.

| Stage | Status | Acceptance |
| --- | --- | --- |
| Row action and preview integration | COMPLETE | Both row variants/wrappers route info independently to metadata preview; named-package filtering preserves matching patches/version constraints and universal opt-in; focused checks and host assembly pass; UX/semantics reviewed and requirements reconciled. |

## Verification and reconciliation

- Both `HomeAppCard` variants forward the preview callback into independent info buttons.
  `DynamicAppCard` covers grouped/flat/search rows; `HiddenSearchAppCard` forwards it too.
  Selection and drag modes suppress the new action. Play Store keeps its own click handler.
- Callback trace: row info -> `HomeScreen` sets `patchesSheetItem` -> `HomeDialogs` obtains
  `HomeViewModel.getPatchesForPackage` -> enabled cached bundle metadata scoped to the
  original package without a version restriction. None of these paths acquires an APK.
- Named-package filtering retains original patch objects, older/current version constraints,
  source order and unrestricted app-specific patches; it does not alter input metadata.
  Generic-only sources are excluded until the universal toggle is enabled. Universal-only
  apps keep the existing applied-patch preview fallback.
- Existing version selector counts/ranks custom coverage; source/patch compatibility badges
  and descriptions remain visible. Both row actions use labelled 48dp touch targets, theme
  colors and 20dp icons. Weighted text/ellipsis preserve space for independent controls.
- Fresh focused `PatchSourceOrderTest` XML reports zero failures/errors, including the new
  mixed-source/version filtering regression. `:app:assembleDebug` completed successfully.
  `git diff --check` passed. Verification is host tests/assembly and source review; no device
  runtime test is claimed or required for this change.
- Build gate ticket 150 acquired `Local\Darka.AndroidGradleBuildGate`; preflight was clear.
  Actual Gradle profile: two workers, no parallel projects, `-Xms256m -Xmx3g`, with required
  JVM flags preserved. Kotlin 2.4.20 ran in-process inside that heap. One configured test fork
  used 512 MiB. Generated Ninja rules for all four ABIs use compile/link pools of 2/1.
  No memory failures or budget increases. Gate log: `4f78ba6721774f2d80bb6c8f7a5ce3cf.log`.

All requirements are satisfied by the integrated host-verified scope; no blockers or deferrals.
The stable-maintenance instructions preserve this shortcut and preview through upstream merges.

No blockers or tracked deferrals. No device trials.

## Authorized release

The user requested publication on 2026-10-05 after implementation commit `330bef42`.
Deliver version `1.33.0.7`, versionCode 7, as a normal latest private GitHub release.
Preserve package, persistent signer and upstream baseline. Apply the existing exact-source,
test, signature, alignment and independent asset-download gates in `stable-maintenance.md`.

| Stage | Status | Acceptance |
| --- | --- | --- |
| Release verification and publication | COMPLETE | Numeric version/code and notes are correct; signed APK passes host release gates and matches committed source/revision; private origin/tag and normal latest release are published; independent downloaded assets match; publication proof recorded. |

Release host verification: gate ticket 152 completed required unit tests and signed assembly;
all inspected suites report zero failures/errors. Signature identity, package/name,
versionCode 7/versionName 1.33.0.7, v2 signature, 16 KiB alignment and packaged recipe passed.
The gate observed two workers/no parallel, 3 GiB Gradle and in-process Kotlin, with one
configured 512 MiB test fork. All four release ABI Ninja rules retain compile/link pools 2/1.
No memory failures or budget increases. Log: `77698cd732e54ccc8d0329d2187ffae5.log`.
Gate ticket 154 refreshed only the packaged VCS metadata after the verified release commit,
using the same profile with no memory failures. All captured build inputs match committed
source `d2a35c27e1acf1a02218e6227db934df35366b0c`; the APK embeds that exact revision.

Published private latest release `v1.33.0.7` at that commit, with only the versioned APK and
public verification metadata. Independent authenticated downloads matched both files
byte-for-byte; downloaded APK signature, alignment, version and packaged revision passed.
Normal release flags, latest tag and exact Git tag identity were checked through GitHub API.
Publication proof is in `docs/verification/release-1.33.0.7.json`. The existing daily maintenance
task records this verified release and explicitly preserves the app-row info preview.
All release acceptance criteria pass; no blockers or tracked deferrals remain.
