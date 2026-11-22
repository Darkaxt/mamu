# Numeric versions and direct store shortcuts

Authorized 2026-10-05: implement numeric version naming and requested downloader/row UI changes. The user accepted the proposed 1.33.0.3 correction release with “Go with that”, adding the UI changes to the same complete update. No device trials.

Requirements:
- VersionName 1.33.0.3, code 3; future MAJOR.MINOR.PATCH.REVISION, tags v plus identical numeric version, matching asset version. Preserve old releases and signer/package. Global Android code increases independently.
- Native updater compares all four numeric components, preserves ordering from legacy -extended.N versions, handles numeric revisions without fixed-width arithmetic collisions. Maintenance validator/docs/scheduled instructions use numeric format.
- Remove acquisition-dialog cog; server settings remain in Downloader settings.
- Add subtle narrow outline to downloader matching existing Morphe white gradient outlines, with theme-appropriate contrast.
- Add labelled Play Store icon at end of home app rows, including undownloaded supported apps, installed/renamed records. It uses original package identity, requires no binary/Byparr lookup and does not activate the row's acquisition action. Keep text/badges usable and 48dp touch target.

| Stage | Status | Acceptance |
| --- | --- | --- |
| 1. Numeric version contract | COMPLETE | Focused native updater regression verifies legacy transition, revisions, upstream increments and large components; maintenance validator accepts numeric and rejects old suffix/new mismatches; focused host tests pass. |
| 2. Downloader and row UI integration | COMPLETE | Cog removed, outline uses theme, independent trailing store action available in both row types; host assembly and affected regressions pass, final reconciliation and commit. |
| 3. Correction release delivery | COMPLETE | Signed 1.33.0.3/code 3 passes release host tests, exact input/source/revision/signature/alignment verification; matching private latest release tag/assets independently downloaded and checked. |

No blockers or tracked deferrals. Runtime device testing remains excluded by user's standing preference. Publish the complete correction release after signed-artifact/source gates pass.

Stage 1 closure: Python maintenance contracts pass; gate 116/a85115bf2f2a4e929f530ea7bee5795f.log compiled and passed numeric/legacy/large-component updater and existing version/identity regressions. Reports inspected. Gate 115 stopped on missing signing path before compilation; corrected invocation uses persistent key. No memory failure. Numeric naming applied to build/validator/maintenance and existing daily task.

Stage 2 closure: gate 117/a1604ece02b04df881c90def923186f8.log passed assembly and affected updater/identity/acquisition/icon regressions. Inspected XML reports have no failures/errors. UI review: both home row branches have independent labelled 48dp store target; original package selected before file access, weighted text preserves trailing space. No parent acquisition callback is called. Downloader cog state/action removed; settings route kept. Border matches existing light-to-tint outline, using onBackground for light-theme contrast and standard dialog shape. No device visual trial claimed. Profile remains 2 workers/no parallel/3 GiB/in-process, one configured 512 MiB test fork, no memory failures.

Stage 3 closure: gate 118/dfa8dc28b41148b99a05a99ce44b58ec.log passed release assembly/full release host suite. Signed artifact verifies captured committed inputs, original signer, numeric version/code, packaged source revision, v2 signature, 16 KiB alignment and recipe. GitHub private latest non-draft/non-prerelease v1.33.0.3 points to b34f2b803eaa969c3637b53ecfc5250b151f94ea. Independent authenticated downloads match all assets; downloaded APK verification passed again. Evidence: docs/verification/release-1.33.0.3.json. All requirements/stages COMPLETE, no blockers or tracked deferrals; no device changes or runtime visual trial.

## Byparr settings icon release (2026-10-05)

The user explicitly requested publication of the independently compiled icon correction,
including an opportunity to test numeric version detection. Stage 4: COMPLETE.
Acceptance: publish complete 1.33.0.4/code 4 retaining identity/signer and all previous
features; host release checks pass; exact committed inputs and packaged revision match;
private latest GitHub tag/assets match; independent download hash/signature/alignment
verification passes. Follow docs/stable-maintenance.md and shared Gradle gate profile.
No device changes, blockers or tracked deferrals. Icon implementation already verified
by compileDebugKotlin gate 122/b1277d853dd847ec9f342f622bdd4984.log.

Stage 4 closure: gate 123/3d826b08996e4f12b5d5df768b67d4cf.log passed release host tests and signed assembly. APK 1.33.0.4/code 4 passed exact committed inputs/revision, signer, v2 signature, alignment and recipe checks. GitHub latest private non-draft/non-prerelease tag matches source 4a07f0c38c4a9dc3bdd7201fcefb79db450daa9e. Independently downloaded assets match all bytes; downloaded APK verification passed. Evidence: docs/verification/release-1.33.0.4.json. All required stages COMPLETE; no blockers/deferrals or device changes.
