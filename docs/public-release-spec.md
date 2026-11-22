# Public release preparation

Authority: user, 2026-11-21. Publish the fork as a public project. Scope: Google Play anonymous acquisition and the app aggregation always on. Without a Byparr server, the other download sites fall back to upstream's browser download instructions ("traditional shortcut"). With the user's Byparr server they stay native. Upstream contact is optional and must not become an argument.

## Requirements

- P1 Google Play is tried natively first. Native download sites run only with a configured Byparr server. Without one, a Play miss or an explicit "Try other download sites" hands off to upstream's DownloadInstructionsDialog. Background queue items report that the app's browser instructions are needed.
- P2 Rebrand before any public build. GPLv3 7c/7e in upstream NOTICE: no "Morphe" name or logo rights, and the fork is clearly marked as modified. Needs a new name, icon, applicationId and About/README text. The upstream maintainers avoid in-app acquisition for DMCA reasons, so the fork is not presented as theirs or endorsed by them.
- P3 New public repository from the current tree as one fresh commit. Excludes docs/verification, the device migration documents (serials, fingerprints, local paths) and internal stage specs. The private repository stays as the record.
- P4 Updater targets the public repository anonymously; the PAT setting stays optional.
- P5 Remove or repoint upstream-only CI (issue triage, FCM release script, Crowdin).
- P6 Publish a Byparr deployment guide, including the browser-scripting support Uptodown requires.

## Stages

| Stage | Status | Acceptance |
| --- | --- | --- |
| P1 manual fallback | IMPLEMENTED, unverified | Branch public-prep. OriginalDownloadPopupTest covers the no-Byparr handoff. Runs through the gate with the rest of this batch. |
| P2 rebrand | IMPLEMENTED, unverified | Name Mamu (Mesopotamian goddess of dreams, a parallel to Morpheus), user 2026-11-22. The user chose to keep the M logo and loading animation, recoloured to an Ishtar Gate palette (lapis #1F3F8F, gold ochre #C8963E, sand backgrounds); like the 282 public upstream forks, the logo stays. App mentions read Mamu in every locale; applicationId app.morphe.extended and the signing key stay so existing installs update in place (user: keep it simple). |
| P3 public tree | IMPLEMENTED, unverified | scripts/export-public-tree.sh writes one fresh commit to Darkaxt/mamu without the excluded files. |
| P4 updater | IMPLEMENTED, unverified | MANAGER_GITHUB_OWNER/REPO = Darkaxt/mamu, assets Mamu-<version>.apk, anonymous download when no PAT is set. |
| P5 upstream CI | IMPLEMENTED | The upstream workflows were already inert (.github/upstream-*); the public tree leaves them, the triage scripts and crowdin.yml out. |
| P6 Byparr guide | IMPLEMENTED | README section. |
| Release | NOT STARTED | Gate full-host and committed checks, then 2.7.0.2/code 52 as the first public release. |
