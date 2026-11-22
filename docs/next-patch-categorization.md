# Next release scope requested by the user

Implementation and release acceptance are now tracked by
[`categories-and-download-queue-spec.md`](categories-and-download-queue-spec.md).
All stages are COMPLETE in published v1.35.0.4. Independent delivery proof is retained in
[`verification/release-1.35.0.4.json`](verification/release-1.35.0.4.json).

These requests are for the release after the first unified patch-panel redesign, not acceptance
criteria or deferred work of that first release.

- Dump the actual repository patch metadata through a script; evaluate keyword coverage against
  real rows and combine author-declared categories with derived universal-patch subcategories.
- Add a third home app-row shortcut beside information and Play Store: a download icon shown
  when a newer APK version supported by the applicable patch sources is available. Universal
  patches alone do not verify a newer version; pin each queued request to its supported target. Tapping enqueues acquisition in the background through Byparr.
  A circular ring distinguishes waiting from actual transfer progress. Retain downloaded originals
  so the user can patch them later without another acquisition.

The completed specification establishes update/version eligibility and queue persistence
against the existing APK cache and acquisition lifecycle. Do not infer byte progress
while waiting for Byparr, and do not patch or install merely because a download was requested.
