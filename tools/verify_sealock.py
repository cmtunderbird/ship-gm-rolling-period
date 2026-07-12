"""
Line-for-line port of com.gmestimator.core.SeaAnalyzer, run against the exact signals in
SeaAnalyzerTest.kt, at the app's REAL settings (fs = 25 Hz, Welch segLen = 8192, pad x4).

Purpose: the Kotlin cannot be compiled everywhere, and a SAFETY gate should never be shipped
on a threshold that was only ever checked at the convenient resolution of a research script.
This proves the two populations actually separate where the app will be looking at them.

Run:  python3 tools/verify_sealock.py     (needs numpy)
"""
import numpy as np

FS = 25.0
T_MIN, T_MAX = 3.0, 45.0
F_LO, F_HI = 1 / T_MAX, 1 / T_MIN
SEG = 8192          # what PeriodEstimator actually picks: min(nextPow2(25*4*45), ...)
PAD = 4
THRESH_DB = 3.0     # SeaAnalyzer.analyse default
TOL = 0.06

rng = np.random.default_rng(31337)


def detrend(x):
    t = np.arange(len(x), dtype=float)
    A = np.vstack([t, np.ones(len(x))]).T
    c, *_ = np.linalg.lstsq(A, x, rcond=None)
    return x - A @ c


def welch(x, seg=SEG, pad=PAD):
    n = len(x)
    L = min(seg, 1 << (n.bit_length() - 1))
    step = L // 2
    nfft = L * pad
    win = np.hanning(L)
    wp = np.sum(win ** 2)
    acc, segs = None, 0
    for s in range(0, n - L + 1, step):
        z = np.fft.rfft(detrend(x[s:s + L]) * win, nfft)
        p = (np.abs(z) ** 2) * 2 / (FS * wp)
        acc = p if acc is None else acc + p
        segs += 1
    return np.fft.rfftfreq(nfft, 1 / FS), acc / segs


def smooth_log_baseline(f, p, lo, hi, frac=0.35):
    lf = np.log(np.maximum(f[lo:hi + 1], 1e-9))
    ly = np.log(np.maximum(p[lo:hi + 1], 1e-30))
    out = np.empty_like(ly)
    for i in range(len(lf)):
        w = np.abs(lf - lf[i]) < frac
        out[i] = ly[w].mean() if w.sum() > 2 else ly[i]
    return out


def analyse(f, S_roll, S_az, roll_peak_f):
    lo = int(np.argmax(f >= F_LO))
    hi = int(np.where(f <= F_HI)[0][-1])
    base = smooth_log_baseline(f, S_az, lo, hi)
    worst = -99.0
    for k in range(lo, hi + 1):
        if abs(f[k] - roll_peak_f) / roll_peak_f < TOL:
            e = 10 * (np.log(max(S_az[k], 1e-30)) - base[k - lo]) / np.log(10)
            worst = max(worst, e)
    kmax = lo + int(np.argmax(S_az[lo:hi + 1]))
    return worst, 1 / f[kmax], worst > THRESH_DB


def narrow_band(t, n, amp, zeta=0.04, sub=8):
    """A narrow-band RANDOM process. A real swell looks like this - not like a sine wave."""
    wn = 2 * np.pi / t
    dt = 1 / (FS * sub)
    x = np.zeros(n)
    v = p = 0.0
    for i in range(n):
        for _ in range(sub):
            fo = rng.normal() * wn ** 2 * 3.0 * np.sqrt(sub)
            a = fo - 2 * zeta * wn * v - wn ** 2 * p
            v += a * dt
            p += v * dt
        x[i] = p
    return x / np.std(x) * amp


def roll_peak(f, S):
    idx = np.where((f >= F_LO) & (f <= F_HI))[0]
    return f[idx[np.argmax(S[idx])]]


n = int(1800 * FS)
print("=" * 104)
print("SeaAnalyzerTest, run for real at the app's own resolution (fs=25 Hz, segLen=8192)")
print("=" * 104)
print(f"{'case':52} {'roll peak':>10} {'az excess':>10} {'verdict':>10}  expected")
print("-" * 104)

# 1. the dangerous one: ship rolling AT the wave period. Roll and heave share the 8 s sea.
sea = narrow_band(8.0, n, 1.0)
roll = 2.0 * sea + rng.normal(0, 0.02, n)
heave = 3.0 * sea + rng.normal(0, 0.02, n)
f, Sr = welch(roll)
_, Sa = welch(heave)
fp = roll_peak(f, Sr)
exc, wave_t, locked = analyse(f, Sr, Sa, fp)
ok1 = locked
print(f"{'1. rolling at the WAVE period (8 s), single peak':52} {1 / fp:9.2f}s "
      f"{exc:9.1f}dB {'SEA':>10}  REJECT   {'PASS' if ok1 else 'FAIL'}")

# 2. a genuine resonance: roll has 15 s, heave does NOT
res = narrow_band(15.0, n, 3.0, zeta=0.05)
wav = narrow_band(8.0, n, 1.0)
roll = res + 0.4 * wav + rng.normal(0, 0.02, n)
heave = 3.0 * wav + rng.normal(0, 0.02, n)
f, Sr = welch(roll)
_, Sa = welch(heave)
fp = roll_peak(f, Sr)
exc, wave_t, locked = analyse(f, Sr, Sa, fp)
ok2 = not locked
print(f"{'2. genuine resonance at 15 s (sea is at 8 s)':52} {1 / fp:9.2f}s "
      f"{exc:9.1f}dB {'SHIP':>10}  ACCEPT   {'PASS' if ok2 else 'FAIL'}")
print(f"{'   -> dominant wave period reported':52} {wave_t:9.2f}s")

# 3. the swell trap: roll peak at the 24 s swell, which IS in the heave
swl = narrow_band(24.0, n, 1.0)
wav = narrow_band(8.0, n, 1.0)
roll = 3.0 * swl + 1.0 * wav + rng.normal(0, 0.02, n)
heave = 1.5 * swl + 3.0 * wav + rng.normal(0, 0.02, n)
f, Sr = welch(roll)
_, Sa = welch(heave)
fp = roll_peak(f, Sr)
exc, wave_t, locked = analyse(f, Sr, Sa, fp)
ok3 = locked
print(f"{'3. roll peak sits on a 24 s SWELL (also in heave)':52} {1 / fp:9.2f}s "
      f"{exc:9.1f}dB {'SEA':>10}  REJECT   {'PASS' if ok3 else 'FAIL'}")

# 4. the margin. This is the number that decides whether the threshold is safe to ship.
print()
print("Separation of the two populations (30 realisations each):")
ship_exc, sea_exc = [], []
for _ in range(30):
    res = narrow_band(15.0, n // 2, 3.0, zeta=0.05)
    wav = narrow_band(8.0, n // 2, 1.0)
    r_ = res + 0.4 * wav + rng.normal(0, 0.02, n // 2)
    h_ = 3.0 * wav + rng.normal(0, 0.02, n // 2)
    f, Sr = welch(r_)
    _, Sa = welch(h_)
    ship_exc.append(analyse(f, Sr, Sa, roll_peak(f, Sr))[0])

    sea_ = narrow_band(float(rng.uniform(7, 26)), n // 2, 1.0)
    r_ = 2.0 * sea_ + rng.normal(0, 0.02, n // 2)
    h_ = 3.0 * sea_ + rng.normal(0, 0.02, n // 2)
    f, Sr = welch(r_)
    _, Sa = welch(h_)
    sea_exc.append(analyse(f, Sr, Sa, roll_peak(f, Sr))[0])

ship_exc, sea_exc = np.array(ship_exc), np.array(sea_exc)
print(f"   genuine resonance : accelerometer excess {ship_exc.mean():6.1f} +/- {ship_exc.std():.1f} dB"
      f"   (max {ship_exc.max():5.1f})")
print(f"   sea-driven peak   : accelerometer excess {sea_exc.mean():6.1f} +/- {sea_exc.std():.1f} dB"
      f"   (min {sea_exc.min():5.1f})")
print(f"   threshold at {THRESH_DB} dB  ->  false rejections "
      f"{np.mean(ship_exc > THRESH_DB) * 100:.0f} %,  MISSES {np.mean(sea_exc <= THRESH_DB) * 100:.0f} %")
gap = sea_exc.min() - ship_exc.max()
print(f"   gap between the populations: {gap:.1f} dB "
      f"({'clean separation' if gap > 0 else 'THEY OVERLAP - the threshold is NOT safe'})")
print()
print("   The threshold is deliberately LOW, biased towards rejection. A wasted record is an")
print("   inconvenience; a missed sea-driven peak is a wrong GM, in the dangerous direction.")

print()
print("=" * 104)
print("RESULT:", "ALL PASS" if (ok1 and ok2 and ok3) else "FAILURES ABOVE")
print("=" * 104)
