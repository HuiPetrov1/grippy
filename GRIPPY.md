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
- Frez-style tabs: **Home**, **Workout** (Get a Grip's routines), **Measure**, **History**.
  Settings and Benchmarks open from Home. History is a swipeable month calendar of
  measurements, workouts and scheduled measurements (tap a day for its records, + Record,
  + Schedule), plus Analytics (activity, types, per-exercise trends). Get a Grip's own workout
  history is the "Workout log" inside Analytics. Results open as an interactive page: a
  scrubbable force curve, numbers counting in, left/right balance, and the share card.
- A **Measure** section for any exercise, not just climbing
  (`android/app/src/main/kotlin/app/grippy/measure/`). Pick Peak, Endurance, Critical Force
  (24 × 7 s/3 s) or RFD, then the hands, duration and countdown. Peak has no duration: pull as
  many times as you like, each pull is counted, the best one is kept, and Stop ends it
  (Passive starts the moment you pull; Active counts you in). Pick or create the exercise in the start sheet, or
  afterwards. Exercises live in folders, and each has Summary / Measure / How to tabs, a
  history chart per measurement type (Average / Max / Total Load, kg or % of bodyweight),
  and a shareable result card. The data lives in its own Room database, `grippy-measure.db`,
  so upstream's schema is untouched.

## Look

Grippy follows Frez's look: near-black (or iOS-grey) surfaces with outlined cards, a
system-blue accent, the bundled Inter typeface (OFL 1.1), and a blue launcher icon. The tokens live in
`android/app/src/main/kotlin/run/nuri/getagrip/ui/theme/` (Tokens, Typography, Materials);
`ContrastTests` keeps every text colour readable.

To see every screen without a phone (light and dark, with demo data):

```sh
scripts/grippy-screenshots.sh            # all screens
scripts/grippy-screenshots.sh workout    # just one
```

PNGs land in `android/app/build/outputs/roborazzi/`.

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
