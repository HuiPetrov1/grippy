"""Replay every trace (and stress variant) through repcounter.py and print counted vs true.

usage:
  eval.py                 all clips, variants base q320 drop5 ramp pause
  eval.py base ramp       only those variants
  eval.py --expected      also (re)write expected/<clip>.json from the base variant
  eval.py --bin FILE.bin [aspect]   replay one phone trace (current v1 format) and print its reps
  eval.py --bin2 FILE.bin [aspect]  same for the recommended v2 format (all hands; see read_phone_bin2)

Variants: base (15 fps, 640-wide source), q320 (picture shrunk to 320 wide before the hand detector),
drop5 (every 5th frame dropped), ramp (brightness 1.0 -> 0.55 -> 1.30, auto-exposure stress), pause
(made here from base: the set is frozen for PAUSE_SEC with small sensor noise right after the middle rep,
as if the user rested; the expected count is unchanged).
"""
import sys, os, json, struct, numpy as np
# The lab folder with traces/ and videos/labels.json (not in the repo: built from internet clips).
# A phone trace (--bin / --bin2) needs nothing but repcounter.py next to this file.
LAB = os.environ.get("GRIPPY_REPLAB_LAB", os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import repcounter as R

VARIANTS = ["base", "q320", "drop5", "ramp", "pause", "pause4x"]
PAUSE_SEC = 15.0         # longer than RELEARN_AFTER_SEC, so a pause cannot be survived by luck
PAUSE_FPS = 15.0
# Noise during the synthetic pause, measured on still moments of the real clips and rounded UP:
# grid cells changed by 0.06..0.30 grey levels (mean |frame difference|) => sigma 0.4 grey levels;
# a still helper hand (wext_close_db) jittered 0.0008 per coordinate at hand length 0.23 => sigma
# 0.005 hand lengths. "pause4x" uses 4 times both.
PAUSE_GRID_SIGMA = 0.4
PAUSE_HAND_SIGMA = 0.005
MATCH_TOL = 1.5          # a counted rep matches a labelled finish within this many seconds


def labels():
    return {l["file"][:-4]: l for l in json.load(open(os.path.join(LAB, "videos/labels.json")))}


def load_trace(clip, variant="base"):
    """traces/<clip>.npz: t float64[N], n_hands uint8[N], hands float32[N,2,42], grid uint8[N,768], aspect."""
    if variant in ("pause", "pause4x"):
        return make_pause(load_trace(clip, "base"), labels()[clip], 4.0 if variant == "pause4x" else 1.0)
    p = os.path.join(LAB, "traces", f"{clip}.npz") if variant == "base" else os.path.join(LAB, "traces", "stress", f"{clip}@{variant}.npz")
    z = np.load(p)
    return dict(t=z["t"], n_hands=z["n_hands"], hands=z["hands"], grid=z["grid"], aspect=float(z["aspect"]))


def make_pause(tr, lab, noise=1.0):
    """Insert PAUSE_SEC of the frame 0.3 s after the middle labelled rep (the hand is at rest there), with
    sensor noise (see PAUSE_*_SIGMA, times `noise`). Label times after the pause shift (see shifted_truth)."""
    ft = lab["rep_finish_times"]
    at = (ft[len(ft) // 2] + 0.3) if ft else float(tr["t"][len(tr["t"]) // 2])
    i0 = int(np.searchsorted(tr["t"], at))
    i0 = min(i0, len(tr["t"]) - 1)
    m = int(PAUSE_SEC * PAUSE_FPS)
    rng = np.random.RandomState(0)
    grid_p = np.clip(np.round(tr["grid"][i0][None, :].astype(np.float64) + rng.normal(0, PAUSE_GRID_SIGMA * noise, size=(m, 768))), 0, 255).astype(np.uint8)
    nh = int(tr["n_hands"][i0])
    hands_p = np.repeat(tr["hands"][i0][None], m, 0).astype(np.float32)
    for k in range(nh):
        hl = R.hand_length(tr["hands"][i0, k], tr["aspect"])
        hands_p[:, k] += rng.normal(0, PAUSE_HAND_SIGMA * noise * hl, size=(m, 42)).astype(np.float32)
    t_p = tr["t"][i0] + (np.arange(m) + 1) / PAUSE_FPS
    t_after = tr["t"][i0 + 1:] + m / PAUSE_FPS
    return dict(t=np.concatenate([tr["t"][:i0 + 1], t_p, t_after]),
                n_hands=np.concatenate([tr["n_hands"][:i0 + 1], np.full(m, nh, np.uint8), tr["n_hands"][i0 + 1:]]),
                hands=np.concatenate([tr["hands"][:i0 + 1], hands_p, tr["hands"][i0 + 1:]]),
                grid=np.concatenate([tr["grid"][:i0 + 1], grid_p, tr["grid"][i0 + 1:]]),
                aspect=tr["aspect"], pause_at=float(tr["t"][i0]), pause_len=m / PAUSE_FPS)


def shifted_truth(tr, lab):
    ft = lab["rep_finish_times"]
    if "pause_at" not in tr:
        return ft
    return [f if f <= tr["pause_at"] else f + tr["pause_len"] for f in ft]


def replay(tr, keep_signal=False):
    """Feed a trace frame by frame. Returns count, reps [(finish_ms, duration_ms, amplitude_pct)], source,
    the frame time each rep was reported at, and (optionally) per-frame signal and source."""
    rc = R.RepCounter(tr["aspect"])
    rc.begin()
    sig = []; src = []; reported_at = []
    for i in range(len(tr["t"])):
        hands = [tr["hands"][i, k] for k in range(int(tr["n_hands"][i]))]
        o = rc.push(float(tr["t"][i]), hands, tr["grid"][i])
        reported_at += [float(tr["t"][i])] * len(o.new_reps)
        if keep_signal:
            sig.append(round(float(o.signal), 6)); src.append(o.source)
    o = rc.end()
    reported_at += [float(tr["t"][-1])] * len(o.new_reps)
    return dict(count=rc.reported_count, reps=[r.as_list() for r in rc.reps], source=R.SOURCES[rc.lead],
                reported_at=reported_at, signal=sig, frame_source=src,
                per_signal_counts=[c.count for c in rc.counters])


def match(true_t, got_t, tol=MATCH_TOL):
    used = set(); missed = []
    for tt in true_t:
        best = None
        for j, g in enumerate(got_t):
            if j not in used and abs(g - tt) <= tol and (best is None or abs(g - tt) < abs(got_t[best] - tt)):
                best = j
        if best is None:
            missed.append(tt)
        else:
            used.add(best)
    extra = [g for j, g in enumerate(got_t) if j not in used]
    return missed, extra


def verdict(lab, n):
    if n in lab["accepted_counts"]:
        return "exact"
    d = min(abs(n - a) for a in lab["accepted_counts"])
    return "within1" if d == 1 else "worse"


def read_phone_bin(path, aspect=0.75):
    """Phone trace .bin, big-endian, one record per frame: float64 t, uint8 hand_found, 42 float32 landmarks of
    the followed hand (zeros if none), 768 uint8 grid row-major. -> trace dict usable by replay()."""
    rec = 8 + 1 + 42 * 4 + 768
    data = open(path, "rb").read()
    n = len(data) // rec
    if len(data) % rec:
        print(f"warning: {len(data) % rec} trailing bytes ignored", file=sys.stderr)
    t = np.zeros(n); nh = np.zeros(n, np.uint8); hands = np.zeros((n, 2, 42), np.float32); grid = np.zeros((n, 768), np.uint8)
    for i in range(n):
        o = i * rec
        t[i] = struct.unpack_from(">d", data, o)[0]
        nh[i] = 1 if data[o + 8] else 0
        hands[i, 0] = struct.unpack_from(">42f", data, o + 9)
        grid[i] = np.frombuffer(data, np.uint8, 768, o + 9 + 168)
    return dict(t=t, n_hands=nh, hands=hands, grid=grid, aspect=aspect)


def read_phone_bin2(path, aspect=0.75):
    """RECOMMENDED phone trace format (v2), big-endian, one record per frame: float64 t, uint8 n_hands (0..2),
    2 x 42 float32 landmarks of every returned hand (zeros for missing ones), 768 uint8 grid row-major.
    This replays bit-for-bit what the reference sees; v1 (.bin above) keeps only one hand."""
    rec = 8 + 1 + 84 * 4 + 768
    data = open(path, "rb").read()
    n = len(data) // rec
    t = np.zeros(n); nh = np.zeros(n, np.uint8); hands = np.zeros((n, 2, 42), np.float32); grid = np.zeros((n, 768), np.uint8)
    for i in range(n):
        o = i * rec
        t[i] = struct.unpack_from(">d", data, o)[0]
        nh[i] = min(data[o + 8], 2)
        hands[i] = np.array(struct.unpack_from(">84f", data, o + 9), np.float32).reshape(2, 42)
        grid[i] = np.frombuffer(data, np.uint8, 768, o + 9 + 336)
    return dict(t=t, n_hands=nh, hands=hands, grid=grid, aspect=aspect)


def main(args):
    if args and args[0] in ("--bin", "--bin2"):
        reader = read_phone_bin if args[0] == "--bin" else read_phone_bin2
        tr = reader(args[1], float(args[2]) if len(args) > 2 else 0.75)
        r = replay(tr)
        print(f"{args[1]}: {len(tr['t'])} frames, count {r['count']} via {r['source']} (per signal {r['per_signal_counts']})")
        for rep, at in zip(r["reps"], r["reported_at"]):
            print(f"  rep finish {rep[0] / 1000:7.2f} s  duration {rep[1]} ms  amplitude {rep[2]}%  reported at {at:.2f} s")
        return
    write_expected = "--expected" in args
    variants = [a for a in args if not a.startswith("--")] or VARIANTS
    labs = labels()
    summary = {}
    for var in variants:
        tot = {"tune": [0, 0, 0], "heldout": [0, 0, 0]}
        print(f"\n== {var}\n{'clip':22s} {'split':7s} true  got  (pict/hA/hB)  source   verdict  missed / extra (s)")
        for clip, lab in labs.items():
            if lab["status"] == "drop":
                continue
            tr = load_trace(clip, var)
            r = replay(tr, keep_signal=(write_expected and var == "base"))
            got = [x[0] / 1000 for x in r["reps"]]
            missed, extra = match(shifted_truth(tr, lab), got)
            v = verdict(lab, r["count"])
            if lab["status"] in ("use", "negative"):
                tot[lab["split"]][["exact", "within1", "worse"].index(v)] += 1
            pc = "/".join(str(c) for c in r["per_signal_counts"])
            print(f"{clip:22s} {str(lab['split']):7s} {lab['true_reps']:4d} {r['count']:4d}  ({pc:>9s})  {r['source']:8s} {v:8s} "
                  f"{[round(m, 1) for m in missed]} / {[round(e, 1) for e in extra]}")
            if write_expected and var == "base":
                os.makedirs(os.path.join(LAB, "expected"), exist_ok=True)
                json.dump(dict(clip=clip, frames=len(tr["t"]), count=r["count"], source=r["source"],
                               reps=[dict(finish_ms=a, duration_ms=b, amplitude_pct=c) for a, b, c in r["reps"]],
                               reported_at_s=r["reported_at"], per_signal_counts=r["per_signal_counts"],
                               signal=r["signal"], frame_source=r["frame_source"]),
                          open(os.path.join(LAB, "expected", f"{clip}.json"), "w"))
        summary[var] = tot
        print(f"{var}: exact/within1/worse  tune {tot['tune']}  held-out {tot['heldout']}")
    print("\nSUMMARY (17 scored + 4 negatives; ambiguous hammer_rot_medium not scored)")
    for var, tot in summary.items():
        print(f"  {var:6s} tune {tot['tune']}  held-out {tot['heldout']}")


if __name__ == "__main__":
    main(sys.argv[1:])
