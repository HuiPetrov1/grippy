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
- **Reps · camera**: a second phone counts reps for exercises the scale cannot hang on
  (`android/app/src/main/kotlin/app/grippy/camera/`). See "Camera phone" below.

## Camera phone

Two phones, both with Grippy. One stands still and watches; the other is the one you use.

- **Camera phone:** Settings → Camera phone → "Use this phone as the camera". It then waits
  over Bluetooth (a foreground service, restarted after a reboot). The same page lists what
  Android still needs allowed for the phone to wake by itself; Xiaomi has two extra switches
  of its own that an app cannot read.
- **Main phone:** Measure → "Reps · camera" → hands and "Start after". The camera phone lights
  its screen, opens the camera on top of its lock screen (it is never unlocked), finds your
  hand and counts until you press Stop. The count, time and tempo are saved to the exercise
  like any measurement; the count can be corrected before saving.
- **What crosses between the phones:** the count, each rep's time, the hand as 21 points for
  a small preview, and a movement signal. No pictures or video leave the camera phone.
- **How it counts:** the camera runs at up to 30 frames a second; every frame is shrunk to a 32×24
  brightness grid (each cell the average of its block of pixels). MediaPipe's hand model (Apache-2.0,
  bundled as `assets/hand_landmarker.task`, runs on the phone, on its own thread) looks at the newest
  frame whenever it is free and gives up to two hands. Six counters run side by side (`RepCounter.kt`):
  the picture, hand A and hand B, each counted as it is and "fast" (minus its 1-second trend, short reps
  only: small quick reps riding on a slower sway of the arm or body). Each learns the direction the
  movement goes, waits for 3 alike reps, then counts every return home; the set's count is the highest,
  and a fast counter only counts once it has 8 evenly spaced reps and 3× the best slow count. It is a
  port of `tools/repcount/repcounter.py`, measured on 21 real exercise clips plus quick-rep sets
  (`tools/repcount/REPORT.md`); `RepCounterReferenceTests` checks the port against it when
  `GRIPPY_REPLAB` points at the lab's fixtures.
- **Framing:** phone on a stand; the working forearm and hand large in the picture (a third to half
  of it); the hand visible, not hidden behind the weight; background and the other hand still. The
  count appears after the 3rd rep (quick small reps on a sway: about the 8th); sets of 1–2 reps are not
  counted. The main phone shows the camera's frame rate; below 20 a second, give it more light.
- **Camera sessions:** Measure → "Camera session" keeps plans: exercises in order, each with a weight and
  the arm order (drag to reorder). A session goes set by set: you tap **Start** when ready, tap the card
  to finish the set, and the rest screen shows the rest time (counting up; nothing starts by itself), the
  next set and **Resume** for a set finished by accident. The weight can be changed on the rest screen,
  the countdown (tap it to start at once) and the counting card (tap the kg); it carries to the other arm
  and is kept in the plan. Finishing a set goes straight to the rest screen; the camera's own tally
  replaces the live count when it arrives. Each set is saved with its weight (correctable in the review). The
  exercise drawings (`res/drawable-nodpi/exercise_*.webp`) were made for Grippy with an image model.
- **Camera phone screen:** the back camera by default; with "Screen off after a set" allowed (device
  admin, lock only) the camera phone switches its screen off when the session ends.
- **Tuning data:** with "Keep each set's measurements" on, the camera phone writes each set to
  `Android/data/app.grippy/files/traces/` as numbers only (format in `SetTrace.kt`).
  "Send the last sets" (camera phone, Settings → Camera phone) zips the newest 12 for the share sheet;
  or `adb pull` them. Replay one with `tools/repcount/eval.py --bin3` (exactly as the phone counted).
- **Testing without the main phone:** `scripts/grippy-camtest.py` plays the main phone from a
  PC with Bluetooth: `grippy-camtest.py set 30` wakes the camera, counts for 30 s and prints
  the reps.

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
