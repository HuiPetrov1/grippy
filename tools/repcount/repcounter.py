"""Reference rep counter for a stationary phone camera (forearm / wrist / grip exercises).

ONE self-contained file: feature functions, the streaming counter for one signal, two-hand tracking and
the choice of signal. Pure Python + numpy (only dot products, means and sorts, written as plain calls so a
Kotlin port is line-for-line). No scipy / sklearn. Every frame is used once, in order: fully causal.

    rc = RepCounter(aspect=0.75)        # picture width / height of the upright camera picture
    rc.begin()                          # start of a set (times below are relative to the first push after it)
    out = rc.push(t_seconds, hands, grid)
        hands: list of 0..2 arrays of 42 floats (x0,y0,...,x20,y20 of the 21 MediaPipe hand landmarks,
               0..1 of the picture), in any order
        grid : 768 uint8 = the whole picture shrunk to 32 columns x 24 rows of brightness, row-major
        -> Output(count, signal (-1..1), new_reps [Rep(finish_ms, duration_ms, amplitude_pct)], source)
    summary = rc.end()                  # end of the set: may add a last rep (see Counter.finish)

How it counts (per signal; there are three signals: the whole picture, hand track A, hand track B):
  1. Each frame becomes a vector (picture: 768 normalised brightness values; hand: 20 landmarks relative
     to the wrist).
  2. Every 0.4 s the direction of greatest change over the last 6 s is found (principal axis by power
     iteration) together with its middle and range (8th..92nd percentile of the projections).
  3. LEARNING (nothing counted yet): the last <= 20 s are re-read along that axis and cut into reps (leave
     one side, reach the other, come back). Once 3 reps in a row look alike (duration and size within 2x),
     the axis is LOCKED and those reps are counted at once (retroactively, within <= 0.4 s of the 3rd).
  4. COUNTING (locked): every frame is projected on the locked axis; a rep is counted the frame the signal
     comes back to its home side, if it was long and big enough compared with the reps so far. The axis
     follows slow changes, but only while the movement is as big as usual (a pause or a hold cannot move it).
  5. The set's count is the HIGHEST count of the three signals; the signal shown and the reps reported
     follow the one that leads (the picture misses movements of a small hand; a hand track misses frames
     where the hand is not recognised, and a still helper hand never locks at all).
Times, durations and the amplitude are reported per rep; the amplitude is % of the set's usual swing.
"""
import math
import numpy as np

# ---------------------------------------------------------------------------------------------------------
# Constants. Each says what it does and how its value was chosen. "Sweep" = one-at-a-time sensitivity run
# (exp_sweep.py, logs/sweep_final_*.log) on base, drop5, ramp and pause; "same" = identical exact counts on
# all four. Values were picked on the tune clips (split "tune" in videos/labels.json); where a sweep result
# also mentions held-out clips it is reported, not used to choose. Most values are inherited from the
# previous lab runs (lab_core.P) and the draft; the sweep shows the results are flat around nearly all of them.
# ---------------------------------------------------------------------------------------------------------
GRID_W, GRID_H = 32, 24
GRID_DIM = GRID_W * GRID_H   # 768. 64x48 was measured in run 2: no gain.
HAND_DIM = 40                # x, y of landmarks 1..20 relative to the wrist (landmark 0)

BUFFER_SEC = 20.0      # history kept for retroactive counting while learning (3 slow reps must fit).
                       # Sweep: 12 s lost reps on 2 tune clips (slow sets), 30 s same.
WINDOW_SEC = 6.0       # history the axis, middle and range are learned from (draft value).
                       # Sweep: 4 s and 8 s each broke 2-4 clips across the variants.
AXIS_EVERY_SEC = 0.4   # how often the axis is re-learned (draft value). Also the longest wait between the
                       # 3rd rep and the moment the first 3 reps are confirmed.
MIN_FRAMES = 12        # fewer buffered frames than this: do not learn yet (draft value).
POWER_ITERATIONS = 6   # power-iteration rounds per learning step, warm-started from the last axis (draft).
LOW_SHARE, HIGH_SHARE = 0.08, 0.92   # percentiles that define the range: ignores 8% outliers per side (draft).
SMOOTH_SEC = 0.12      # time constant of the exponential smoothing of the signal (draft value).
                       # Sweep: 0.06 over-counted the gripper set, 0.2 lost reps on 2 clips.
RESMOOTH_FRAMES = 8    # when the axis changes, the smoothed signal is rebuilt from this many recent frames
                       # (8 frames at 15 fps = 4.4 time constants: settled). Not swept.
SWING = 0.22           # half-width of the middle band, as a share of the range: the signal must pass
                       # +-0.22 ranges from the middle to be on a side (draft value).
                       # Sweep: 0.16 put 1 clip off (pause variant), 0.28 put 2 off (ramp, pause).
MIN_REP_SEC = 0.35     # a rep whose active part (band to band) is shorter is jitter (draft value; not swept).
MAX_REP_SEC = 12.0     # a rep longer than this is two unrelated movements (draft value; not swept).
CONFIRM_REPS = 3       # alike reps in a row needed before a signal counts anything.
                       # Sweep: 2 let a negative count 2 (neg_px_banana, held-out; base and drop5);
                       # 4 lost every set of 2-3 reps (5 clips).
ALIKE_DUR = 2.0        # "alike": active durations within this ratio of each other.
                       # Sweep: 1.5 lost a 2-rep clip, 3.0 over-counted the gripper set on drop5.
ALIKE_AMP = 2.0        # "alike": swing sizes within this ratio. Sweep: 1.5 and 3.0 each 1 clip off.
MAX_GAP_SEC = 10.0     # longest rest between two reps of the learning train; the 3rd rep must also have
                       # ended this recently. Sweep: 6 and 15 same.
HOLD_SHARE = 0.5       # while locked, axis/middle/range follow the window only when its range is at least
                       # this share of the usual rep size; otherwise they are HELD, so a pause or a long hold
                       # cannot shrink the range until noise counts (the draft decays it instead).
                       # Sweep: 0.0 (always follow) broke 6 more clips in the pause variant; 0.7 same.
AXIS_KEEP_COS = 0.7    # while locked, a new axis is followed only if |cos| to the locked one is at least
                       # this (same movement). Sweep: 0.5 and 0.85 same.
RELEARN_AFTER_SEC = 12.0   # no rep for this long, a different movement (|cos| < AXIS_KEEP_COS) with a real
                           # range, and the locked movement absent: unlock and learn again; the count is kept.
                           # Sweep: 6 and 20 same (no clip changes exercise mid-set; untested in practice).
RELEARN_CONFIRM_SEC = 6.0  # ...and all that must hold for a whole window. Sweep: 0 lost the rep after the
                           # pause on 2 clips (the window right after a pause is mostly stillness); 10 same.
MIN_REL_AMP, MAX_REL_AMP = 0.4, 2.5   # while locked, a rep must swing 0.4..2.5x the usual rep.
                                      # Sweep: 0.25/0.55 same; MAX 1.8 put 2 pause clips one short, 4.0 same.
MIN_REL_DUR = 0.4      # while locked, a rep must last at least 0.4x the usual active time.
                       # Sweep: 0.3 counted 2 short re-grips in the gripper set (drop5: 18/15); 0.5 same.
TYPICAL_OF_LAST = 5    # "usual" = median of the last 5 counted reps (not swept).
ACTIVE_TO_WHOLE = 1.55 # reported duration = active (band-to-band) time x 1.55, capped by the time since the
                       # previous rep (draft value; affects only the reported duration).
PARK_FACTOR = 1.0      # end(): parked on the far side for this many usual rep times => that last swing was
                       # a rep whose return was cut off. Sweep: 0.7 same; 1.5 or off lost 1 rep (ramp).
PICTURE_MIN_RANGE = 0.05   # picture signal: window range needed to be a movement at all, in normalised picture
                           # units (1 = the whole picture's contrast). Sweep: 0.03 and 0.08 same.
HAND_MIN_RANGE = 0.15  # hand signal: window range needed, in hand lengths of RMS landmark travel (the draft's
                       # 0.06 in its own units started on jitter). Sweep: 0.06 and 0.25 same here, because
                       # the session takes the highest count; 0.15 kept still helper hands from locking in run 2.
DUPLICATE_HAND = 1.0   # two detected hands whose wrists are closer than this many (larger) hand lengths are
                       # one hand detected twice (measured 0.1..0.7 hand lengths apart on 6 clips; real
                       # second hands >= 2.2 apart); only the larger is kept. Without it a duplicate opens a
                       # second track and one hand's frames are split between two counters (ramp lost a rep).
                       # Sweep: 0 / 0.5 / 2.0 each 1 clip off on ramp.
GRID_SD_FLOOR = 4.0    # grid normalisation: contrast below 4 grey levels is treated as 4, so a dark picture's
                       # noise is not blown up to full scale (draft value; not swept).
LEAD_MARGIN = 1        # another signal takes the lead when it has this many more reps than the leader.
                       # Sweep: 2 left 2 clips one short on ramp; 1 = plain maximum.
EPS = 1e-12


def median(values):
    """Plain median (mean of the two middle values for an even count)."""
    s = sorted(values)
    n = len(s)
    if n == 0:
        return 0.0
    if n % 2 == 1:
        return float(s[n // 2])
    return 0.5 * (float(s[n // 2 - 1]) + float(s[n // 2]))


# ---------------------------------------------------------------------------------------------------------
# Features
# ---------------------------------------------------------------------------------------------------------
def grid_features(grid):
    """768 brightness bytes -> 768 floats with mean 0 and unit total length when the picture's contrast is
    normal: (v - mean) / (max(sd, 4) * sqrt(768)). Exposure / brightness changes cancel out."""
    g = np.asarray(grid, dtype=np.float64).reshape(-1)
    m = float(np.sum(g)) / GRID_DIM
    d = g - m
    sd = max(math.sqrt(float(np.dot(d, d)) / GRID_DIM), GRID_SD_FLOOR)
    return d / (sd * math.sqrt(GRID_DIM))


def hand_features(points, aspect):
    """42 landmark numbers -> 40: landmarks 1..20 minus the wrist, x multiplied by aspect so x and y are
    both in picture heights, divided by sqrt(20) so a projection range reads as RMS landmark travel.
    Not divided by hand size (the range test divides by hand length instead): a still hand stays still."""
    p = np.asarray(points, dtype=np.float64).reshape(-1)
    out = np.zeros(HAND_DIM)
    wx = p[0] * aspect
    wy = p[1]
    k = 1.0 / math.sqrt(20.0)
    for i in range(1, 21):
        out[(i - 1) * 2] = (p[i * 2] * aspect - wx) * k
        out[(i - 1) * 2 + 1] = (p[i * 2 + 1] - wy) * k
    return out


def hand_length(points, aspect):
    """Wrist (0) to the middle finger's knuckle (9), in picture heights: how big the hand looks."""
    return math.hypot((points[18] - points[0]) * aspect, points[19] - points[1])


# ---------------------------------------------------------------------------------------------------------
# Hysteresis on one number
# ---------------------------------------------------------------------------------------------------------
class Sides:
    """Which side of the middle band the signal u (centred, in ranges) is on. step() returns
    (new_side, extreme reached on the side just left) when it changes side, else None."""

    def __init__(self):
        self.side = 0
        self.ext = 0.0

    def step(self, u):
        now = 1 if u > SWING else (-1 if u < -SWING else 0)
        if self.side == 0:
            if now != 0:
                self.side = now
                self.ext = u
            return None
        if self.side > 0:
            self.ext = max(self.ext, u)
        else:
            self.ext = min(self.ext, u)
        if now == 0 or now == self.side:
            return None
        ev = (now, self.ext)
        self.side = now
        self.ext = u
        return ev


# ---------------------------------------------------------------------------------------------------------
# Counter for one signal
# ---------------------------------------------------------------------------------------------------------
class Counter:
    """Counts reps in one stream of feature vectors. push() may be skipped on frames where the signal
    is not available (hand not seen); time gaps are fine."""

    def __init__(self, dim, min_range):
        self.dim = dim
        self.min_range = min_range
        self.T = []          # buffered times (<= BUFFER_SEC)
        self.X = []          # buffered feature vectors
        self.U = []          # buffered unit sizes (hand length; 1 for the picture)
        self.last_learn_at = -math.inf
        self.free_axis = None        # last learned axis (locked or not)
        # preview (not locked): the last learned movement, for the on-screen signal only
        self.pv_mean = None; self.pv_axis = None; self.pv_mid = 0.0; self.pv_range = 0.0; self.pv_smooth = None
        self.moving_ratio = 0.0      # last window range / (min_range * unit)
        # locked state
        self.locked = False
        self.axis = None; self.mean = None; self.mid = 0.0; self.range = 0.0; self.home = 0
        self.smooth = None
        self.last_t = None
        self.sides = Sides()
        self.was_home = False
        self.left_home_at = None
        self.prev_home_ext = None
        self.away_at = None
        self.typ_amp = []            # absolute swing sizes of counted reps
        self.typ_dur = []            # active durations of counted reps
        self.reps = []               # dicts: t, dur, active, amp (relative to usual), emitted_at
        self.last_rep_at = -math.inf
        self.other_since = None      # since when another movement has been seen instead of the locked one

    @property
    def count(self):
        return len(self.reps)

    def signal(self):
        """-1..1 position along the movement: -1 = home (where reps start and end), +1 = the far side.
        Before a lock (no home known yet) the sign is arbitrary. 0 when nothing moves."""
        if self.locked and self.smooth is not None and self.range > 0:
            v = -self.home * (self.smooth - self.mid) / (self.range / 2.0)
        elif self.pv_smooth is not None and self.pv_range > 0:
            v = (self.pv_smooth - self.pv_mid) / (self.pv_range / 2.0)
        else:
            return 0.0
        return max(-1.0, min(1.0, v))

    # ---- per frame ----
    def push(self, t, x, unit):
        out = []
        dt = max(t - self.last_t, 0.0) if self.last_t is not None else 0.0
        k = 1.0 - math.exp(-dt / SMOOTH_SEC)
        self.T.append(t); self.X.append(x); self.U.append(unit)
        while len(self.T) > 2 and t - self.T[0] > BUFFER_SEC:
            self.T.pop(0); self.X.pop(0); self.U.pop(0)
        if self.locked:
            raw = float(np.dot(x - self.mean, self.axis))
            self.smooth = raw if self.smooth is None else self.smooth + (raw - self.smooth) * k
            r = self._advance(t, (self.smooth - self.mid) / self.range)
            if r is not None:
                out.append(r)
        elif self.pv_axis is not None:
            raw = float(np.dot(x - self.pv_mean, self.pv_axis))
            self.pv_smooth = raw if self.pv_smooth is None else self.pv_smooth + (raw - self.pv_smooth) * k
        self.last_t = t
        if t - self.last_learn_at >= AXIS_EVERY_SEC and len(self.T) >= MIN_FRAMES:
            self.last_learn_at = t
            out += self._learn(t)
        for r in out:
            r["emitted_at"] = t
        return out

    # ---- learning ----
    def _power_axis(self, D, warm):
        """Direction of greatest variance of the rows of D (already centred). Warm start from the last axis;
        otherwise (or if the data has nothing along it) from the frame furthest from the mean."""
        v = warm
        reseeded = warm is None
        if v is None:
            v = self._seed(D)
            if v is None:
                return None
        rnd = 0
        while rnd < POWER_ITERATIONS:
            proj = D @ v
            nxt = D.T @ proj
            norm = math.sqrt(float(np.dot(nxt, nxt)))
            if norm < EPS:
                if reseeded:
                    return None
                v = self._seed(D)
                if v is None:
                    return None
                reseeded = True
                rnd = 0
                continue
            v = nxt / norm
            rnd += 1
        return v

    @staticmethod
    def _seed(D):
        best = -1
        bd = 0.0
        for i in range(len(D)):
            d = float(np.dot(D[i], D[i]))
            if d > bd:
                bd = d
                best = i
        if best < 0 or bd < 1e-18:
            return None
        return D[best] / math.sqrt(bd)

    def _series(self, mean, axis, mid, rng, t_from):
        """The buffered frames after t_from, projected, smoothed (fresh), centred and scaled: (t, u)."""
        out = []
        sm = None
        lt = None
        for t, x in zip(self.T, self.X):
            if t <= t_from:
                continue
            raw = float(np.dot(x - mean, axis))
            if sm is None:
                sm = raw
            else:
                sm += (raw - sm) * (1.0 - math.exp(-(t - lt) / SMOOTH_SEC))
            lt = t
            out.append((t, (sm - mid) / rng))
        return out, sm

    @staticmethod
    def _cycles(series, home):
        """Reps in a series: leave the home side, reach the other side, come back. If the series starts in
        the middle band and first goes to the far side, the first arrival home is a rep too."""
        sides = Sides()
        reps = []
        left = None
        prev_ext = None
        was_home = False
        neutral_start = None
        for t, u in series:
            if neutral_start is None:
                neutral_start = abs(u) <= SWING
            at_home = u * home > SWING
            if was_home and not at_home:
                left = t
            was_home = at_home
            had_side = sides.side
            ev = sides.step(u)
            if had_side == 0 and sides.side == -home and neutral_start:
                left = t
                prev_ext = None
            if ev is None:
                continue
            now, ext = ev
            if now != home:
                prev_ext = ext
            elif left is not None:
                amp = abs(ext - prev_ext) if prev_ext is not None else 2.0 * abs(ext)
                reps.append(dict(t=t, start=left, active=t - left, amp=amp))
                left = None
        return reps

    @staticmethod
    def _alike_run(reps):
        """Longest run of mutually alike reps ending with the last one."""
        if not reps:
            return []
        run = [reps[-1]]
        for r in reversed(reps[:-1]):
            ok = run[0]["start"] - r["t"] <= MAX_GAP_SEC
            for q in run:
                if max(r["active"], q["active"]) / max(min(r["active"], q["active"]), 1e-3) > ALIKE_DUR:
                    ok = False
                if max(r["amp"], q["amp"]) / max(min(r["amp"], q["amp"]), 1e-6) > ALIKE_AMP:
                    ok = False
            if not ok:
                break
            run.insert(0, r)
        return run

    def _learn(self, t):
        k0 = 0
        while self.T[k0] < t - WINDOW_SEC:
            k0 += 1
        W = np.array(self.X[k0:])
        n = len(W)
        if n < MIN_FRAMES:
            return []
        mean = W.mean(axis=0)
        D = W - mean
        v = self._power_axis(D, self.free_axis)
        if v is None:
            return []
        if self.free_axis is not None and float(np.dot(self.free_axis, v)) < 0:
            v = -v
        self.free_axis = v
        proj = sorted(float(p) for p in D @ v)
        lo = proj[min(n - 1, int(n * LOW_SHARE))]
        hi = proj[min(n - 1, int(n * HIGH_SHARE))]
        rng = hi - lo
        mid = (hi + lo) / 2.0
        unit = median(self.U[k0:])
        need = self.min_range * unit
        self.moving_ratio = rng / need if need > 0 else 0.0
        if not self.locked:
            return self._try_lock(t, mean, v, mid, rng, need)
        # locked: follow the same movement, only while it is really moving
        typ = median(self.typ_amp[-TYPICAL_OF_LAST:])
        dot = float(np.dot(v, self.axis))
        if abs(dot) >= AXIS_KEEP_COS and rng >= HOLD_SHARE * typ:
            if dot < 0:
                v = -v
                mid = -mid
            self.axis = v; self.mean = mean; self.mid = mid; self.range = rng
            self.smooth = self._resmooth(mean, v)
        elif t - self.last_rep_at > RELEARN_AFTER_SEC and rng >= need and abs(dot) < AXIS_KEEP_COS:
            # Something else moves and no rep for a while. Unlock only if the locked movement itself is
            # absent from the window: right after a pause the window mixes stillness and the resumed reps,
            # which tilts the learned axis although the old movement is back.
            old = sorted(float(p) for p in D @ self.axis)
            old_rng = old[min(n - 1, int(n * HIGH_SHARE))] - old[min(n - 1, int(n * LOW_SHARE))]
            if old_rng < HOLD_SHARE * typ:
                if self.other_since is None:
                    self.other_since = t
                if t - self.other_since >= RELEARN_CONFIRM_SEC:
                    self.locked = False      # a different movement took over: learn it again, keep the count
                    self.pv_axis = None
                    self.other_since = None
                return []
        self.other_since = None
        return []

    def _resmooth(self, mean, axis):
        """Smoothed projection of the last RESMOOTH_FRAMES frames on a new axis, so the signal does not
        jump when the axis is re-learned."""
        sm = None
        lt = None
        for tt, x in zip(self.T[-RESMOOTH_FRAMES:], self.X[-RESMOOTH_FRAMES:]):
            raw = float(np.dot(x - mean, axis))
            sm = raw if sm is None else sm + (raw - sm) * (1.0 - math.exp(-(tt - lt) / SMOOTH_SEC))
            lt = tt
        return sm

    def _try_lock(self, t, mean, v, mid, rng, need):
        if rng < need or rng <= 0:
            self.pv_axis = None
            return []
        # preview of the latest movement, for the on-screen signal only
        self.pv_mean = mean; self.pv_axis = v; self.pv_mid = mid; self.pv_range = rng
        self.pv_smooth = self._resmooth(mean, v)
        series, sm = self._series(mean, v, mid, rng, self.last_rep_at)
        best = None
        for home in (1, -1):
            reps = [r for r in self._cycles(series, home) if MIN_REP_SEC <= r["active"] <= MAX_REP_SEC]
            run = self._alike_run(reps)
            if len(run) >= CONFIRM_REPS and t - run[-1]["t"] <= MAX_GAP_SEC:
                if best is None or run[0]["start"] < best[1][0]["start"]:
                    best = (home, run)
        if best is None:
            return []
        home, run = best
        self.locked = True
        self.home = home; self.axis = v; self.mean = mean; self.mid = mid; self.range = rng
        for r in run:
            self.typ_amp.append(r["amp"] * rng)
            self.typ_dur.append(r["active"])
        out = [self._emit(r["t"], r["active"], r["amp"] * rng) for r in run]
        # bring the live state machine to where the replay is now
        self.sides = Sides(); self.smooth = sm; self.left_home_at = None; self.was_home = False
        self.prev_home_ext = None; self.away_at = None
        for tt, u in series:
            if tt > run[-1]["t"]:
                r = self._advance(tt, u)
                if r is not None:
                    out.append(r)
        if self.sides.side == 0:
            self.sides.side = home
        return out

    # ---- counting ----
    def _emit(self, t, active, amp_abs):
        typ = median(self.typ_amp[-TYPICAL_OF_LAST:]) if self.typ_amp else amp_abs
        dur = min(t - self.last_rep_at, active * ACTIVE_TO_WHOLE)
        r = dict(t=t, dur=dur, active=active, amp=amp_abs / typ if typ > 0 else 0.0)
        self.reps.append(r)
        self.last_rep_at = t
        return r

    def _advance(self, t, u):
        home = self.home
        at_home = u * home > SWING
        if self.was_home and not at_home:
            self.left_home_at = t
        self.was_home = at_home
        ev = self.sides.step(u)
        if ev is None:
            return None
        now, ext = ev
        if now != home:
            self.prev_home_ext = ext
            self.away_at = t
            return None
        self.away_at = None
        if self.left_home_at is None or self.prev_home_ext is None:
            return None
        active = t - self.left_home_at
        amp_abs = abs(ext - self.prev_home_ext) * self.range
        typ_a = median(self.typ_amp[-TYPICAL_OF_LAST:])
        typ_d = median(self.typ_dur[-TYPICAL_OF_LAST:])
        if active < max(MIN_REP_SEC, MIN_REL_DUR * typ_d) or active > MAX_REP_SEC:
            return None
        if not (MIN_REL_AMP * typ_a <= amp_abs <= MAX_REL_AMP * typ_a):
            return None
        self.typ_amp.append(amp_abs)
        self.typ_dur.append(active)
        return self._emit(t, active, amp_abs)

    def finish(self, t):
        """End of the set: if the signal went to the far side and stayed there for a whole usual rep, the
        set ended there and home was guessed on the wrong side: that last swing is a rep."""
        if not self.locked or self.away_at is None or not self.typ_dur:
            return None
        typ_d = median(self.typ_dur[-TYPICAL_OF_LAST:])
        if t - self.away_at < PARK_FACTOR * typ_d:
            return None
        r = dict(t=self.away_at, dur=typ_d * ACTIVE_TO_WHOLE, active=typ_d, amp=1.0, emitted_at=t)
        self.reps.append(r)
        self.last_rep_at = self.away_at
        self.away_at = None
        return r


# ---------------------------------------------------------------------------------------------------------
# Two hand tracks
# ---------------------------------------------------------------------------------------------------------
class HandTracks:
    """Keeps up to two hands apart by wrist continuity (|dx|*aspect + |dy| to the last wrist of each track).
    A second track is opened only when two clearly separate hands are seen in the same frame. Tracks are never forgotten:
    a hand that comes back is given to the nearest track."""

    def __init__(self, aspect):
        self.aspect = aspect
        self.last = [None, None]

    def _d(self, k, p):
        return abs(p[0] - self.last[k][0]) * self.aspect + abs(p[1] - self.last[k][1])

    def assign(self, hands):
        """-> [(track index, points)]"""
        det = [np.asarray(h, dtype=np.float64).reshape(-1) for h in hands[:2]]
        if not det:
            return []
        if len(det) == 2:
            la = hand_length(det[0], self.aspect)
            lb = hand_length(det[1], self.aspect)
            gap = abs(det[0][0] - det[1][0]) * self.aspect + abs(det[0][1] - det[1][1])
            if gap < DUPLICATE_HAND * max(la, lb):
                det = [det[0]] if la >= lb else [det[1]]
        if len(det) == 2:
            a, b = det
            if self.last[0] is None and self.last[1] is None:
                asg = [(0, a), (1, b)]
            elif self.last[1] is None:
                asg = [(0, a), (1, b)] if self._d(0, a) <= self._d(0, b) else [(0, b), (1, a)]
            elif self.last[0] is None:
                asg = [(1, a), (0, b)] if self._d(1, a) <= self._d(1, b) else [(1, b), (0, a)]
            else:
                same = self._d(0, a) + self._d(1, b)
                swap = self._d(0, b) + self._d(1, a)
                asg = [(0, a), (1, b)] if same <= swap else [(0, b), (1, a)]
        else:
            p = det[0]
            if self.last[1] is None:
                kk = 0
            elif self.last[0] is None:
                kk = 1
            else:
                kk = 0 if self._d(0, p) <= self._d(1, p) else 1
            asg = [(kk, p)]
        for kk, p in asg:
            self.last[kk] = (p[0], p[1])
        return asg


# ---------------------------------------------------------------------------------------------------------
# The session: three signals, the leading one is reported
# ---------------------------------------------------------------------------------------------------------
class Rep:
    def __init__(self, finish_ms, duration_ms, amplitude_pct):
        self.finish_ms = finish_ms
        self.duration_ms = duration_ms
        self.amplitude_pct = amplitude_pct

    def as_list(self):
        return [self.finish_ms, self.duration_ms, self.amplitude_pct]

    def __repr__(self):
        return f"Rep({self.finish_ms} ms, {self.duration_ms} ms, {self.amplitude_pct}%)"


class Output:
    """count: reps so far; signal: -1 (home) .. +1 (far side) of the followed signal, for display;
    new_reps: reps reported with this frame (the first 3 arrive together); source: which signal leads."""
    def __init__(self, count, signal, new_reps, source):
        self.count = count
        self.signal = signal
        self.new_reps = new_reps
        self.source = source


SOURCES = ("picture", "hand A", "hand B")


class RepCounter:
    def __init__(self, aspect=0.75):
        self.aspect = aspect
        self.begin()

    def begin(self):
        """Start a set. Everything learned before is forgotten."""
        self.counters = [Counter(GRID_DIM, PICTURE_MIN_RANGE),
                         Counter(HAND_DIM, HAND_MIN_RANGE),
                         Counter(HAND_DIM, HAND_MIN_RANGE)]
        self.tracks = HandTracks(self.aspect)
        self.t0 = None
        self.t_last = 0.0
        self.lead = 0                 # index of the counter followed for signal and reps
        self.reps = []                # reported reps (Rep), never rewritten
        self.reported_count = 0

    def _to_rep(self, r):
        return Rep(int(round(r["t"] * 1000)), int(round(r["dur"] * 1000)), int(round(r["amp"] * 100)))

    def _report(self):
        """Count = highest count of the three counters. The leader changes only when another counter has
        MORE reps (ties keep the current one). Reps beyond what was already reported come from the leader."""
        best = self.lead
        for i, c in enumerate(self.counters):
            if c.count >= self.counters[best].count + (LEAD_MARGIN if best == self.lead else 1):
                best = i
        self.lead = best
        leader = self.counters[best]
        new = []
        while self.reported_count < leader.count:
            rep = self._to_rep(leader.reps[self.reported_count])
            self.reps.append(rep)
            new.append(rep)
            self.reported_count += 1
        return new

    def _signal(self):
        lead = self.counters[self.lead]
        if lead.locked or lead.count > 0:
            return lead.signal()
        # nothing counted yet: show whichever signal moves most clearly above its threshold (display only)
        best = None
        for c in self.counters:
            if c.pv_axis is not None and (best is None or c.moving_ratio > best.moving_ratio):
                best = c
        return best.signal() if best is not None else 0.0

    def push(self, t, hands, grid):
        if self.t0 is None:
            self.t0 = t
        ts = t - self.t0
        self.t_last = ts
        self.counters[0].push(ts, grid_features(grid), 1.0)
        for k, pts in self.tracks.assign(hands):
            self.counters[1 + k].push(ts, hand_features(pts, self.aspect), hand_length(pts, self.aspect))
        new = self._report()
        return Output(self.reported_count, self._signal(), new, SOURCES[self.lead])

    def end(self):
        """End of the set. Returns an Output whose new_reps holds a last rep if one was cut off (see
        Counter.finish); all reps of the set are in self.reps. Call begin() before the next set."""
        for c in self.counters:
            c.finish(self.t_last)
        new = self._report()
        return Output(self.reported_count, 0.0, new, SOURCES[self.lead])
