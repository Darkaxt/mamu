# Patch source ordering

Authorized 2026-10-05: promote sources in this order: recommended-version sources
with the most app-specific patches, other app-specific sources, universal-only
sources. Publish the complete fix as 1.33.0.5/code 5.

Use the catalogue's current recommended version, falling back to the chosen APK
version when there is no recommendation. Count a patch once only when it explicitly
names the original app package. Unrestricted versions match the recommendation;
universal declarations (including null-package compatibility entries) do not
contribute. Rank matching sources by matching custom patch count, then total custom
count; other custom sources by total custom count. Preserve incoming order on ties.
Sources for other APK versions remain visible under existing compatibility rules.
Do not change patch selection/options, compatibility, recommended version, or run order.
Apply the same display policy to the single-app and batch editors.

| Stage | Status | Acceptance |
| --- | --- | --- |
| Ordering integration | COMPLETE | Regression checks cover version priority, custom count, both universal representations, mixed sources, ties, absent recommendation; both editors use policy without selection mutation; focused host tests pass. |
| Release | COMPLETE | Numeric version/code increase; required host release checks, exact committed input/provenance, signer/alignment and independently downloaded GitHub assets verify; publish latest private stable release. |

No blockers or deferrals. Device testing excluded by standing user preference.

Ordering closure: focused PatchSourceOrderTest XML inspected with no failures/errors,
gate 129/f3032f66b25644fd9a90818cdef836f3.log. Both viewmodels compile with the shared
comparator applied only after display pairs are created. Sorting retains bundle/patch
objects and enabled flags; tab pager already preserves selected bundle UID. Original
package identity used in both editors; catalogue recommendation wins over selected APK,
with selected version fallback. Gate acquired; 2 workers/no parallel/3 GiB Gradle,
Kotlin in-process; one configured 512 MiB test fork; no memory failure.

Release host verification: gate 130/7b9bc6b7155b43aabae6770e315458cb.log passed the
required host suite and signed assembly. Reports inspected with no failures/errors.
Captured inputs, numeric version/code, persistent signer, v2 signature, 16 KiB
alignment and packaged recipe verified. Generated native Ninja pools inspected for
all release ABIs: compile depth 2, link depth 1. Same observed Gradle/Kotlin/test
profile, no memory failure. Remaining acceptance: committed-source/VCS provenance,
publication and independent private GitHub asset verification.

Release closure: v1.33.0.5 is latest/private/non-draft/non-prerelease at source commit
260321847b4e49948c46db59a68280fbdf240a58. Gate 131 refreshed packaged VCS metadata
without changing compiled source; committed inputs and packaged revision verify.
Both GitHub assets independently downloaded and byte-matched; downloaded APK signer,
alignment and recipe verified again. Evidence: docs/verification/release-1.33.0.5.json.
All acceptance criteria satisfied, no blockers/deferrals. No device changes or runtime
device verification claimed.

Amendment in 1.33.0.6: docs/source-visibility-and-version-choice-spec.md extends ordering to the pre-download preview and both source choosers. Recommendations choose the newest offered/installable target across source groups. Once an APK version is deliberately chosen, editors rank that version before the global recommendation. Version coverage/counts are visible before acquisition. This authorized correction supersedes the earlier recommended-over-selected ordering rule for editors.
