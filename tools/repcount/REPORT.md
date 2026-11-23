# Rep counting from a stationary phone camera — FINAL REPORT

Status: final (run 3, 2026-11-23). Everything below was measured with the files in this folder; what could not be
verified is listed at the end.

## 1. Bottom line

- **Recommended method** (reference: `repcounter.py`): three counters run side by side — one on the whole picture
  (32x24 brightness grid), one on each of up to two hand tracks (MediaPipe landmarks, wrist-relative). Each counter
  learns the main movement direction, **holds back until 3 alike reps** have happened (then counts them at once),
  then counts every return to the home side live. The set's count is the **highest of the three counts**; the
  on-screen signal and the reps reported follow whichever counter leads.
- **Accuracy, base variant (15 fps), 21 scored clips (17 with reps + 4 negatives):**
  - Tune clips (10): **9 exact, 1 within 1** (gripper set 17 vs 14-16 accepted), 0 worse.
  - Held-out clips (11): **10 exact**, 0 within 1, **1 worse** (the adversarial ball-massage negative: 7 counted, 0 true).
  - Stress variants q320 / drop5 / ramp: 20 of 21 exact each (only the ball massage fails).
  - Your draft (ported, with your new seed and moving-hand pick): 11 of 21 exact (tune 6/10, held-out 5/11).
- **Timing:** reps after the first three are reported on the frame the movement comes home: median 0.04 s,
  90th percentile 0.24 s after the labelled finish (max 1.0 s, a gripper rep after a 6 s hold). The first 3 reps of a
  set are reported together, at most 0.4 s after the 3rd one finishes (so the 1st rep is reported ~2 rep-times late).
- **Known failures:** a repeating non-exercise movement (rolling a ball along the forearm) is counted; a set of
  fewer than 3 reps is never counted; a pause longer than ~10 s before the 3rd rep loses the reps before it.

## 2. Data

- 26 clips (YouTube/Pexels, private offline testing), trimmed, analysed at 15 fps: 17 scored (8 tune, 9 held-out),
  4 negatives (2 tune, 2 held-out), 1 ambiguous (hammer_rot_medium: 3 or 6 accepted, reported but not scored),
  4 dropped (cannot be labelled). Ground truth in `videos/labels.json` (rep finish times read off contact sheets;
  `accepted_counts` lists every defensible count). Notes in `tools/labels_notes.md`.
- Variants (built by `tools/extract.py`, all clips): **base**; **q320** (frame shrunk to 320 wide before the hand
  detector); **drop5** (every 5th frame dropped, ~12 fps with gaps); **ramp** (brightness 1.0 -> 0.55 -> 1.30 like
  auto-exposure). Built in `eval.py`: **pause** (the set frozen for 15 s right after the middle rep, with sensor noise
  measured on still moments of the real clips: grid sigma 0.4 grey levels, landmarks sigma 0.005 hand lengths) and
  **pause4x** (same, 4x noise).
- Tune vs held-out: values were chosen on tune clips only. The one-at-a-time sweep (section 7) shows results are flat
  around nearly every value, so the held-out numbers are not the product of tuning.

## 3. Accuracy

Counts per clip. "(±1)" = within 1 of an accepted count, "✗" = worse. The draft port is replayed with `begin(0)`
before the first frame. "picture only" = the reference's picture counter alone (= the run-2 "reworked counter").

| clip | split | true (accepted) | draft port | picture only | **reference** | q320 | drop5 | ramp | pause | reference base: missed / extra rep finishes (s) |
|---|---|---|---|---|---|---|---|---|---|---|
| rwc_close_db  | tune | 4 (4) | 4 | 4 | **4** via picture | 4 | 4 | 4 | 4 | [] / [] |
| rwc_eccentric_helper  | heldout | 2 (2,3) | 3 | 3 | **3** via picture | 3 | 3 | 3 | 0 ✗ | [] / [8.1] |
| wc_palmsdown_close  | heldout | 3 (3) | 3 | 3 | **3** via picture | 3 | 3 | 3 | 0 ✗ | [] / [] |
| wext_close_db  | tune | 4 (4) | 0 ✗ | 4 | **4** via picture | 4 | 4 | 4 | 4 | [] / [] |
| ruldev_db_top  | heldout | 4 (4) | 0 ✗ | 4 | **4** via picture | 4 | 4 | 4 | 4 | [] / [] |
| raddev_plate  | heldout | 6 (5,6) | 5 | 4 (±1) | **6** via hand A | 6 | 6 | 6 | 6 | [] / [] |
| wflex_db_close  | tune | 7 (7) | 2 ✗ | 7 | **7** via picture | 7 | 7 | 7 | 7 | [] / [] |
| wfe_noobj_close  | tune | 4 (4) | 3 (±1) | 4 | **4** via picture | 4 | 4 | 4 | 4 | [] / [] |
| wfe_noobj_medium  | heldout | 8 (8) | 0 ✗ | 8 | **8** via picture | 8 | 8 | 8 | 8 | [] / [] |
| raddev_hammer_close  | tune | 5 (5) | 5 | 5 | **5** via picture | 5 | 5 | 5 | 5 | [] / [] |
| hammer_rot_medium (ambig.) | heldout | 6 (3,6) | 4 (±1) | 4 (±1) | **4 (±1)** via picture | 4 (±1) | 5 (±1) | 4 (±1) | 0 ✗ | [] / [14.5, 21.9, 26.3, 37.2] |
| wext_medium_db  | heldout | 4 (4) | 3 (±1) | 4 | **4** via picture | 4 | 4 | 4 | 4 | [] / [] |
| wc_both_medium  | tune | 4 (4) | 4 | 4 | **4** via picture | 4 | 4 | 4 | 4 | [] / [] |
| wext_side_medium  | heldout | 9 (9) | 8 (±1) | 9 | **9** via picture | 9 | 9 | 9 | 9 | [] / [] |
| wc_seated_wide  | heldout | 6 (6) | 0 ✗ | 6 | **6** via picture | 6 | 6 | 6 | 6 | [] / [] |
| gripper_side_set  | tune | 15 (14,15,16) | 13 (±1) | 16 | **17 (±1)** via hand B | 16 | 16 | 16 | 17 (±1) | [17.2, 22.6] / [8.2, 15.3, 21.1, 45.2] |
| neg_hammer_gesture (neg) | tune | 0 (0) | 0 | 0 | **0** via picture | 0 | 0 | 0 | 0 | [] / [] |
| neg_sit_down_setup (neg) | tune | 0 (0) | 0 | 0 | **0** via picture | 0 | 0 | 0 | 0 | [] / [] |
| neg_px_banana (neg) | heldout | 0 (0) | 0 | 0 | **0** via picture | 0 | 0 | 0 | 0 | [] / [] |
| neg_px_ball_massage (neg) | heldout | 0 (0) | 2 ✗ | 7 ✗ | **7 ✗** via picture | 7 ✗ | 7 ✗ | 7 ✗ | 8 ✗ | [] / [2.9, 4.0, 5.4, 6.5, 7.3, 11.0, 13.5] |
| prosup_hammer_front  | tune | 3 (3) | 3 | 0 ✗ | **3** via hand A | 3 | 3 | 3 | 0 ✗ | [] / [] |
| prosup_hammer_side  | heldout | 3 (2,3) | 3 | 0 ✗ | **3** via hand A | 3 | 3 | 3 | 0 ✗ | [] / [] |

Totals (exact / within 1 / worse; ambiguous clip not scored):

| method | variant | tune (10) | held-out (11) |
|---|---|---|---|
| draft port (RepSession.kt + RepCounter.kt, new seed, moving-hand pick) | base | 6 / 2 / 2 | 5 / 2 / 4 |
| draft port | q320 | 6 / 1 / 3 | 7 / 1 / 3 |
| draft port | drop5 | 6 / 0 / 4 | 5 / 2 / 4 |
| draft port | ramp | 6 / 2 / 2 | 5 / 1 / 5 |
| picture only (reference picture counter) | base | 9 / 0 / 1 | 8 / 1 / 2 |
| **reference (picture + 2 hand tracks, highest count)** | **base** | **9 / 1 / 0** | **10 / 0 / 1** |
| reference | q320 | 10 / 0 / 0 | 10 / 0 / 1 |
| reference | drop5 | 10 / 0 / 0 | 10 / 0 / 1 |
| reference | ramp | 10 / 0 / 0 | 10 / 0 / 1 |
| reference | pause (15 s, realistic noise) | 8 / 1 / 1 | 7 / 0 / 4 |
| reference | pause4x (15 s, 4x noise) | 8 / 1 / 1 | 7 / 0 / 4 |

Where reps were missed or added (reference, base; times in s):
- gripper_side_set (17 vs 14-16): this clip is a whole session (walk in, sit, wipe hands, pick up the gripper). The
  picture counter (16) takes one setup movement as a rep (8.2 s) and finishes its reps at the squeeze, not the
  release. The hand-B counter (17, leads at the end) gets the phase right (13 of 15 labelled finishes within 0.5 s)
  but adds 2 short partial squeezes (20.1 s, 45.2 s). The lead switched picture -> B (31 s) -> picture (36 s) -> B
  (45 s), and reported reps are never rewritten, so the reported list mixes both: "missed 17.2, 22.6 / extra 8.2,
  15.3, 21.1" are the picture counter's first 3 reps, "extra 45.2" is hand B's partial squeeze.
- hammer_rot_medium (ambiguous, 4): counts 4 of 6 excursions (or 4 of 3 L/R cycles); the label itself is ambiguous.
- neg_px_ball_massage (7 vs 0): the picture counter counts the rolling ball. The hand counters do not (the hand's
  shape does not change) but the session takes the highest count.
- pause variant: every new failure is a set where the pause comes **before the 3rd rep** (rwc_eccentric_helper,
  wc_palmsdown_close, both prosup clips, hammer_rot_medium): the 3-rep train is broken and the reps before the pause
  are lost; the sets that had locked before the pause all survived the 15 s pause (count unchanged).

Rep timing (reference, base, `exp_timing.py`, matched within 1.5 s): |finish time error| median 0.22 s, 90th pct
0.97 s. Live reps (n=53) reported median 0.04 s / p90 0.24 s / max 1.00 s after the labelled finish. Retroactive
reps (the first 2 of each set, n=36) reported median 4.2 s, max 10.6 s after their finish (slow 5 s reps).
Consistent offsets: wflex_db_close and prosup_hammer_side are reported ~1 s before the labelled finish (the counter's
"home" is reached as the band is crossed, earlier than the label's "fully back at rest").

## 4. Hand detection rate per clip

Share of frames where HandLandmarker (num_hands=2, confidences 0.5) returned at least one hand / two detections
(two detections include the same hand detected twice, see DUPLICATE_HAND).

| clip | frames | any hand base | two hands base | any hand q320 | drop5 | ramp |
|---|---|---|---|---|---|---|
| rwc_close_db | 128 | 9% | 0% | 20% | 6% | 1% |
| rwc_eccentric_helper | 132 | 62% | 2% | 70% | 56% | 55% |
| wc_palmsdown_close | 134 | 100% | 91% | 100% | 100% | 100% |
| wext_close_db | 152 | 100% | 100% | 100% | 100% | 100% |
| ruldev_db_top | 207 | 100% | 22% | 99% | 99% | 100% |
| raddev_plate | 200 | 100% | 46% | 100% | 100% | 100% |
| prosup_hammer_close (dropped) | 195 | 99% | 5% | 97% | 98% | 99% |
| gripper_close_top (dropped) | 572 | 94% | 4% | 94% | 94% | 95% |
| wflex_db_close | 396 | 100% | 100% | 100% | 100% | 100% |
| wfe_noobj_close | 335 | 100% | 2% | 100% | 100% | 100% |
| wfe_noobj_medium | 425 | 100% | 99% | 100% | 100% | 100% |
| raddev_hammer_close | 364 | 99% | 0% | 98% | 99% | 99% |
| hammer_rot_medium | 597 | 91% | 11% | 83% | 95% | 93% |
| wext_medium_db | 263 | 99% | 1% | 98% | 100% | 99% |
| wc_both_medium | 225 | 100% | 47% | 100% | 100% | 100% |
| prosup_club_medium (dropped) | 165 | 100% | 0% | 100% | 100% | 100% |
| wext_side_medium | 447 | 98% | 0% | 71% | 97% | 84% |
| wc_seated_wide | 239 | 28% | 0% | 40% | 29% | 24% |
| gripper_side_set | 1539 | 96% | 34% | 96% | 96% | 97% |
| px_gripper_handheld (dropped) | 225 | 95% | 78% | 98% | 98% | 95% |
| neg_hammer_gesture | 293 | 100% | 60% | 99% | 100% | 100% |
| neg_sit_down_setup | 144 | 66% | 23% | 65% | 66% | 66% |
| neg_px_banana | 228 | 95% | 0% | 93% | 95% | 95% |
| neg_px_ball_massage | 222 | 100% | 99% | 100% | 100% | 100% |
| prosup_hammer_front | 171 | 100% | 0% | 100% | 100% | 100% |
| prosup_hammer_side | 159 | 100% | 14% | 100% | 100% | 100% |


Hands are found in >= 91% of frames on 22 of 26 clips. They fail on a side-on dumbbell close-up where the weight
hides the hand (rwc_close_db 9%), a wide shot with a small hand (wc_seated_wide 28%), the setup clip (66%) and
when the other hand reaches in (rwc_eccentric_helper 62%). In all of those the picture counter still counts right,
which is why landmarks cannot be the only signal.

## 5. What fails and why

1. **Repeating movements that are not reps** (neg_px_ball_massage): any back-and-forth that repeats 3 times alike is
   counted by the picture counter. A veto ("the hand moves but its shape does not") is plausible but would be tuned
   on this single held-out clip, so it was not added.
2. **Fewer than 3 reps**: nothing is counted (by design: CONFIRM_REPS=3; with 2, a negative clip counted 2).
3. **Pause before the 3rd rep** longer than MAX_GAP_SEC (10 s): the reps before it are lost. After the lock, pauses of
   15 s (and holds of 6 s in the gripper set) are survived because range, middle and axis are HELD.
4. **Setup movements in a long recording** (gripper_side_set): one setup movement can be counted if it looks like the
   first reps. Starting the set (begin()) right before the first rep avoids this.
5. **Rep phase**: when the movement has no obvious rest side, the counter may finish reps at the other end (the
   picture counter did this on the gripper set: reps reported at the squeeze, 1-6 s before the release). The count is
   unaffected; per-rep durations/finish times are shifted.
6. **Draft (for comparison)**: follows the first hand seen when the set starts and never switches while counting
   (wext_close_db, ruldev_db_top, wfe_noobj_medium: the still helper hand -> 0); a followed hand lost for > 1.5 s is
   forgotten and never followed again in that set; hand minRange 0.06 on hand-length-scaled features starts on jitter
   (wfe_noobj_close counted half a cycle late); range decays during holds; 2 alike swings confirm.

## 6. Recommended method — parameters

All constants are in `repcounter.py` with what they do and how they were chosen. Values:

| constant | value | | constant | value |
|---|---|---|---|---|
| GRID 32x24, HAND_DIM | 768, 40 | | MAX_GAP_SEC | 10.0 |
| BUFFER_SEC | 20.0 | | HOLD_SHARE | 0.5 |
| WINDOW_SEC | 6.0 | | AXIS_KEEP_COS | 0.7 |
| AXIS_EVERY_SEC | 0.4 | | RELEARN_AFTER_SEC | 12.0 |
| MIN_FRAMES | 12 | | RELEARN_CONFIRM_SEC | 6.0 |
| POWER_ITERATIONS | 6 | | MIN_REL_AMP / MAX_REL_AMP | 0.4 / 2.5 |
| LOW_SHARE / HIGH_SHARE | 0.08 / 0.92 | | MIN_REL_DUR | 0.4 |
| SMOOTH_SEC | 0.12 | | TYPICAL_OF_LAST | 5 |
| RESMOOTH_FRAMES | 8 | | ACTIVE_TO_WHOLE | 1.55 |
| SWING | 0.22 | | PARK_FACTOR | 1.0 |
| MIN_REP_SEC / MAX_REP_SEC | 0.35 / 12.0 | | PICTURE_MIN_RANGE | 0.05 |
| CONFIRM_REPS | 3 | | HAND_MIN_RANGE | 0.15 (hand lengths) |
| ALIKE_DUR / ALIKE_AMP | 2.0 / 2.0 | | DUPLICATE_HAND | 1.0 (hand lengths) |
| GRID_SD_FLOOR | 4.0 | | LEAD_MARGIN | 1 |

Sensitivity (`exp_sweep.py`, logs/sweep_final_*.log, one constant at a time on base/drop5/ramp/pause): no change
at all for MAX_GAP 6-15, HOLD 0.7, AXIS_KEEP 0.5-0.85, MIN_REL_AMP 0.25-0.55, PICTURE_MIN_RANGE 0.03-0.08,
HAND_MIN_RANGE 0.06-0.25, RELEARN_AFTER 6-20, BUFFER 30, MIN_REL_DUR 0.5. Changes that hurt: CONFIRM 2 (a negative
counts 2) or 4 (all 2-3-rep sets lost), WINDOW 4 or 8, SMOOTH 0.06 or 0.2, BUFFER 12, HOLD 0 (6 clips break in the
pause variant), RELEARN_CONFIRM 0 (2 pause clips lose a rep), MIN_REL_DUR 0.3 (gripper drop5 18), DUPLICATE_HAND
0 (ramp loses a rep), LEAD_MARGIN 2 (ramp: 2 clips one short).

Method changes made in this run (each measured): (a) duplicate-hand merge (one hand detected twice split its frames
over two tracks; fixed wfe_noobj_close on ramp); (b) the relearn rule now also needs the locked movement to be absent
and the condition to hold for 6 s (without it the window right after a 15 s pause unlocked the counter and lost the
next rep on 2 clips); (c) MIN_REL_DUR 0.3 -> 0.4 (re-grips on the gripper set, drop5). Your new seed (furthest frame
from the mean, one reseed) gives identical counts to the old one on all clips and is used.

## 7. What to change in the Kotlin draft

`RepCounter.kt` — replace the counting core with a port of `repcounter.Counter` (line for line; only dot products,
means, sorts):
1. Keep a **20 s buffer** of (t, features, unit) instead of the 6 s window; learn from its last 6 s.
2. **Not locked**: every 0.4 s, if the window range >= minRange x median(unit), re-read the buffer since the last
   counted rep along the new axis (`_series`), cut it into reps for both possible home sides (`_cycles`, with the
   neutral-start rule), take the longest run of mutually alike reps (`_alike_run`: durations and sizes within 2x, rests
   <= 10 s); if >= 3 and the last ended <= 10 s ago, LOCK: store axis/mean/mid/range/home and report the run's reps at
   once; replay the frames after the run through the live state machine.
3. **Locked**: per frame project on the locked axis, smooth, and run the `Sides` hysteresis; a rep counts on return
   home if active >= max(0.35 s, 0.4 x usual) and <= 12 s and its swing is 0.4..2.5x the usual (median of last 5).
4. Every 0.4 s while locked: follow the new axis/mean/mid/range only if |cos| >= 0.7 AND window range >= 0.5 x usual
   swing; otherwise HOLD everything. **Remove rangeDecayPerSec** and the `mid += 0.2*(...)` drift.
5. Relearn: unlock only if no rep for 12 s, a different movement (|cos| < 0.7) with a real range, the range along the
   locked axis < 0.5 x usual, all continuously for 6 s. Keep the count.
6. Remove `pending`/`confirmAfter=2`/`alikeRatio=2.5`; add `finish(t)` (the park rule) and call it from `end()`.
7. Signal = -home x (smooth - mid) / (range/2), clamped: -1 home, +1 far side. Before lock: preview on the last
   learned axis (display only).
8. `seedAxis()`/reseed as you have it now is fine. If the power iteration collapses twice, skip that learning step
   (the reference returns no axis) rather than keeping an unnormalised vector.

`RepFeatures.hand` — 40 numbers: landmarks 1..20 minus the wrist, x times aspect, times 1/sqrt(20); **no wrist position
(out[40], out[41]) and no division by hand length**. Pass the hand length (wrist to landmark 9, picture heights) to the
counter as the per-frame unit; HAND_MIN_RANGE = 0.15 is in those units. `RepFeatures.grid` is unchanged.

`RepSession.kt` — replace `pick()`, `usingHand`, `carried`, `coverage()` and the give-up logic with:
1. `HandTracks`: up to 2 tracks by nearest wrist (|dx| x aspect + |dy|); a second track only opens when two hands are
   seen at once; **merge duplicates** (two detections whose wrists are < 1 hand length apart -> keep the larger);
   tracks are never forgotten.
2. Three counters (picture, track A, track B), fed every frame they have data for — while counting, never "choose"
   a hand up front.
3. Count = max of the three counts; the leader changes only when another counter has more reps; reps beyond the
   reported count are taken from the leader (`_report`) and are never rewritten. Signal and `Tracking` from the leader.
4. `begin(t)`: new counters and tracks, times relative to t. `end()`: `finish()` on every counter, then `_report`.
5. For drawing, show the leader's latest hand if the leader is a hand track, else the most recent hand.

`SetTrace.kt` — write **all hands** (v2: float64 t, uint8 n_hands, 2x42 float32, 768 bytes) so phone traces replay
exactly through the reference (`eval.py --bin2`); v1 (`--bin`, one followed hand) still replays but loses the second
track. Store the picture aspect in the json.

`HandTracker.kt` — the phone uses confidences 0.4, the lab 0.5; keep num_hands = 2. (0.4 was not measured.)

Checking the port: `expected/<clip>.json` holds the reference's output for every base trace: `count`, `source`,
`reps` (finish_ms, duration_ms, amplitude_pct), `reported_at_s` (frame time each rep was reported), `per_signal_counts`
and per-frame `signal` (6 decimals) and `frame_source`. Feed `traces/<clip>.npz` frame by frame (times as stored;
`begin()` before the first frame) and compare. With float32 arithmetic expect tiny signal differences (suggest
tolerance 1e-3) and identical counts/reps; a rep at a different frame means a logic difference.

## 8. Trace layout (`traces/<clip>.npz`, stress in `traces/stress/<clip>@<variant>.npz`)

Exactly the reference's inputs, per frame (made by `tools/make_traces.py` from `feat/`):
- `t` float64[N] — seconds from the start of the clip (15 fps; drop5 has gaps)
- `n_hands` uint8[N] — hands returned by HandLandmarker (0..2)
- `hands` float32[N,2,42] — x0,y0..x20,y20 of each returned hand, 0..1 of the picture, detector order; rows >= n_hands
  are zeros
- `grid` uint8[N,768] — whole picture area-averaged to 32 columns x 24 rows of brightness, row-major
- `aspect` float32 — picture width / height of the clip (the phone's upright picture is 0.75; the clips are mostly
  16:9 = 1.78, ruldev_db_top is portrait pillar-boxed in 16:9)

Feed: `rc = RepCounter(aspect); rc.begin(); for i: rc.push(t[i], [hands[i,k] for k < n_hands[i]], grid[i]); rc.end()`.

## 9. Camera placement / framing for the user (from what worked and failed)

- Phone **on a stand**, not handheld (the handheld clip could not even be labelled). Start the set just before the
  first rep, so walking in / sitting down / picking up the weight is not recorded.
- Frame the **working forearm and hand large**: roughly the forearm across a third to half of the picture, the whole
  movement inside the frame. Wide shots with a small hand (hand ~5% of the picture height) are not counted by the
  picture alone and rely on the hand being recognised (prosup clips: picture 0, hand 3).
- View so that **the hand is visible, not hidden behind the weight**: from the side for curls/extensions with the
  palm/back of the hand toward the camera, or from above. A side-on close-up where the dumbbell covers the hand drops
  hand detection to 9% (the picture still counted, but without backup).
- Keep the **background still** (nobody walking behind) and the **other hand still** (holding the forearm is fine; a
  helper hand reaching in each rep cost 38% of hand detection).
- Steady light helps but is not critical (the brightness ramp variant counted the same).
- Do at least **3 reps without stopping more than ~10 s**; the count appears after the 3rd rep (the first 3 arrive
  together). Rests later in the set are fine.

## 10. Speed

Reference in Python+numpy on the desktop: push() median 0.03 ms per frame, p99 1.1 ms, max 3-7 ms (the 0.4 s learning
steps). Work per frame in a port: grid normalisation (~2.3k ops) + one 768-long and up to two 40-long dot products.
Per learning step (every 0.4 s, per counter): picture: power iteration 6 x 2 x ~90 frames x 768 = ~0.83 M multiply-adds,
plus while not locked one pass over the <= 300 buffered frames (~0.23 M); hand counters ~5% of that. About 3 M
multiply-adds per second in total, a few ms per learning step expected on a phone (estimate, not measured). Memory:
20 s x 15 fps x 768 floats = 0.9 MB (float32) for the picture buffer; at 30 fps 1.8 MB.

## 11. What could not be verified

- No phone: neither the Kotlin draft nor a Kotlin port was run; the draft was measured through a Python port
  (`draft_port.py`, updated to the evening version incl. pick()). No real phone traces (.bin) were available; the .bin
  readers were checked on traces written from the lab data.
- Frame rate 15 fps (and ~12 fps with gaps) only; 30 fps not tested (all constants are in seconds except
  MIN_FRAMES and RESMOOTH_FRAMES).
- Detection confidence 0.4 (phone) vs 0.5 (lab); front-camera mirroring; the phone's own exposure/noise; the
  phone's upright 3:4 picture (the clips are 16:9 frames squeezed into 32x24).
- The clips are internet videos (mostly medium/close side views of other people), 21 scored, one adversarial
  negative; labels were read by eye (several "medium" confidence); finish times are +-0.3 s at best.
- Whether the amplitude % is meaningful to the user (it is relative to the set's own median swing).
- Relearning when the user switches exercise without begin(): implemented, never triggered by any clip.
- The pause test is synthetic (frozen frame + measured noise), not a real resting person.

## 12. Files

- `repcounter.py` — the reference (features, counter, hand tracks, session). `eval.py` — replay all traces/variants,
  `--expected` rewrites `expected/`, `--bin`/`--bin2` replay a phone trace.
- `expected/<clip>.json` — reference output per base trace (section 7). `traces/` — inputs (section 8).
- `draft_port.py` + `exp_draft.py` — the draft and its scores. `exp_ref.py`, `exp_timing.py`, `exp_sweep.py`,
  `sweep.sh` — experiments behind the numbers; logs in `logs/` (`eval_final.log`, `timing_base.log`, `draft_*.log`,
  `sweep_final_*.log`, `report_tables.md`).
- `lab_core.py`, `lab_run.py`, `lab_sess.py`, `lab_roi.py`, `harness.py` — run-2 work bench (superseded; `harness.py`
  is still used by the exp_* scripts). `tools/` — extraction and labelling. `cache/`, `feat/` — intermediate data.
