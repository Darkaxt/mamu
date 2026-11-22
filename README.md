# Mamu

Mamu is an unofficial Android patch manager compatible with Morphe patches. It is a modified
version of [Morphe Manager](https://github.com/MorpheApp/morphe-manager) and is not affiliated
with or endorsed by the Morphe project. Named after Mamu, the Mesopotamian goddess of dreams.

## What it adds

- **Google Play downloads without an account.** Mamu fetches the original APK straight from
  Google Play with an anonymous session, so patching an app is one tap.
- **App aggregation.** Community patch sources are added automatically during update checks and
  all their apps appear in one library. Your own sources, selections and exclusions are kept.
- **Other download sites** (APKMirror, APKPure, APKCombo, Uptodown) download natively when you
  configure a Byparr server (below). Without one, Mamu shows the usual browser download steps.
- Home ordering (updates first, then patched apps), patching with the newest patch pack first and
  automatic retries of failed patches, hidden broken sources and dismissible notifications.
- Updates itself from this repository's releases.

## Install

Download the latest `Mamu-<version>.apk` from [Releases](https://github.com/Darkaxt/mamu/releases)
or add this repository to Obtainium. Mamu follows stable Morphe Manager releases; version
`2.7.0.2` is Morphe Manager 2.7.0 plus Mamu revision 2.

## Optional: Byparr server for the other download sites

Mamu talks to [Byparr](https://github.com/Darkaxt/Byparr/tree/custom-post) (`custom-post` branch),
which adds the browser scripting and queue feedback those sites need:

```sh
git clone -b custom-post https://github.com/Darkaxt/Byparr.git
cd Byparr
docker build -f Dockerfile.custom -t byparr-custom .
docker run -d --name byparr --restart unless-stopped -p 8191:8191 --shm-size 256m byparr-custom
```

Then set `http://<server>:8191` in **Settings > Downloader**. Keep the server on your own network
(for example behind Tailscale); it is not meant to be exposed to the internet. Upstream Byparr
without the `custom-post` changes works for APKMirror, APKPure and APKCombo but not Uptodown.

## Building

JDK 21 or newer. `./gradlew :app:assembleRelease` builds an APK; release signing reads the
keystore given with `-PextendedKeystore=<file>` and its passwords from the environment.

## Licence

GPLv3, see [LICENSE](LICENSE) and [NOTICE](NOTICE). Under its Section 7 terms Mamu is marked as
modified and makes no claim to the Morphe name.
