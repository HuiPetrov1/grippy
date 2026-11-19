# Grippy

Grippy is a personal Android fork of [Get a Grip](https://github.com/plumbmybumb/get-a-grip)
by Nuri Bruner, used with a WeiHeng WH-C06(B) Bluetooth crane scale. It is not the official
app and is not endorsed by its maintainers. Get a Grip's source is MPL 2.0, and so are the
changes here.

Only the Android app (`android/`) is built. The iOS sources stay in the tree so upstream
changes merge cleanly.

## What differs from upstream

- App id `app.grippy` and the name Grippy, so it installs beside Get a Grip.
- A built-in updater (`android/app/src/main/kotlin/app/grippy/update/`): on launch the app
  reads `update.json` from the latest release of
  [HuiPetrov1/grippy-releases](https://github.com/HuiPetrov1/grippy-releases) and offers to
  install the new APK. Settings has an Updates card.
- No Google Play rating prompt and no support mail to the upstream maintainer.
- CI runs the Android job only.

## Release a new build

```sh
scripts/grippy-release.sh "What changed"
```

It builds the release APK, signs it with this PC's debug key (`~/.android/debug.keystore`,
the same key as Distill), and publishes `grippy.apk` + `update.json` to the releases repo.
Every build must be signed with the same key, so keep that keystore backed up.

## First install on the phone

Download `grippy.apk` from the latest release on the phone and open it. Allow installing
from that source when Android asks. Later updates come through the app itself.

## Pull in upstream changes

```sh
git fetch upstream
git merge upstream/main
```
