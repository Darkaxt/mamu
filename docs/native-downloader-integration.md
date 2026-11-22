# Native original-APK download

Integrated from Morphe Downloader source commit 4aa6d472a9c88904cf8f5d071bff9f57a84c8dfc,
under the existing GPL-3.0 license. Attribution and companion source:
https://github.com/Darkaxt/Morphe-Manager-Downloaders.

The existing app/version picker resolves its normal Morphe link. Its download dialog now
runs the Byparr resolver directly; APKMirror/APKPure/APKCombo choices preserve their actual
labels, and Uptodown includes the caller-owned browser recipe. The recipe remains the same
one developed with Helium. Queue GETs are display-only and never submit/replay /v1.

One ViewModel retains the attempt across rotation. Explicit Cancel disconnects Byparr,
stops feedback, cancels the Android transfer and invalidates stale callbacks. Download
admission and cancellation share an ownership lock; validation snapshots are bound to the
same download ID. Retry is explicit. Failures leave Retry/Open in Browser and the settings
cog available. The HTTPS endpoint is a device preference, exported only in the Patching
section; old Morphe exports cannot overwrite a configured endpoint with an absent field.

Android DownloadManager owns file transfer. Its status/broadcast completes the operation;
progress sampling only displays real bytes, scaled by the existing locale-aware formatter.
Archives are validated before handoff. A content URI keeps the detected APK/APKM/XAPK name
while the normal Morphe importer performs package, split-format, signature and patch-version
checks. Batch selection uses its existing attachment validation too. The downloader never
installs or patches an APK by itself.

Acquisition now skips the Have/Need APK dialog in both single and batch flows. A retained
original is read from its actual manifest and reused only for the requested/recommended
version and allowed build code. Universal targets can reuse the compatible retained copy.
Otherwise the same compact progress dialog starts the download immediately. "Choose another
APK or version" returns to the existing manual picker; genuine source/variant ambiguity and
signature/version/experimental warnings still require their normal decisions.

Original retention uses the existing preference and repository. A fully staged replacement
is committed to Room before the superseded original is deleted. Failed acquisition cannot
touch that original. Same-version different builds are no longer mistaken for duplicates,
and retained version metadata comes from the original rather than a version-rewriting patch's
output. Native DownloadManager and provider staging copies are discarded only after their
complete successor copy exists; cancellation invalidates ownership before cleanup.
Changing a requested version cancels the preceding URL-resolution job; a late response cannot
replace the current request's destination. No arbitrary delay is used between acquisition dialogs.

Verification is host contracts, integration compilation and artifact inspection. The user
waived live phone/emulator/browser trials and will report behavior through standard usage.
