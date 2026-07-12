"""
Faithful Python port of com.gmestimator.core.Dsp / PeriodEstimator, used to verify the
algorithm numerically before it ever runs on a phone. Same maths, same constants, same
band edges. If the Kotlin and this agree on synthetic signals, the Kotlin is doing what
we think it is.

Requires numpy.  Run:  python3 tools/verify_dsp.py

A NOTE ON TOLERANCES. They are not uniform, and deliberately so.

  Deterministic signals (pure sine, free decay) are held to 1-3%. A failure there is a bug.

  An irregular seaway is NOT deterministic. A ship's roll in a random sea is a narrow-band
  response to broadband wave forcing, so any finite record is one draw from a distribution:
  change the seed and the answer moves by several percent. The seaway tolerance is therefore
  6%, and CASE 3 prints the scatter across several realisations so you can see it. Do not
  tighten it by hunting for a seed that passes -- that tests the RNG, not the estimator.

  Swell rejection (CASE 4) must ALWAYS reject. That one is non-negotiable: a missed swell is
  a confidently wrong GM, in the dangerous direction.

See docs/VALIDATION.md.

Test cases:
  1. clean sinusoid, known period
  2. free decay with realistic roll damping  (the mode the app recommends)
  3. irregular seaway: roll response = narrow-band process around T_n
  4. THE TRAP: a regular SWELL at a different period (the ship rolls at the encounter
     period, not her own) -- and the bimodality gate that catches it
  5. contamination: static list + gyro bias drift + engine vibration
  6. small amplitude, near the sensor noise floor
  7. error budget: what u(T) really translates to in u(GM)
"""
import numpy as np

FS = 25.0
T_MIN, T_MAX = 3.0, 45.0
F_LO, F_HI = 1.0 / T_MAX, 1.0 / T_MIN
COMPETING_THRESHOLD = 0.25      # PeriodEstimator.Config.maxCompetingRatio
TOL_DETERMINISTIC = 0.03        # pure sine, free decay
TOL_SEAWAY = 0.06               # irregular seaway: a distribution, not a constant


# ----------------------------------------------------------------- Dsp port

def detrend(x):
    n = len(x)
    t = np.arange(n, dtype=float)
    A = np.vstack([t, np.ones(n)]).T
    coef, *_ = np.linalg.lstsq(A, x, rcond=None)
    return x - A @ coef


def next_pow2(n):
    p = 1
    while p < n:
        p <<= 1
    return p


def bandpass_fft(x, fs, f_lo, f_hi):
    n = len(x)
    d = detrend(x)
    nfft = next_pow2(n)
    X = np.fft.rfft(d, nfft)
    f = np.fft.rfftfreq(nfft, 1 / fs)
    X[(f < f_lo) | (f > f_hi)] = 0
    return np.fft.irfft(X, nfft)[:n]


def welch_psd(x, fs, seg_len, overlap=0.5, pad=4):
    n = len(x)
    L = min(next_pow2(seg_len), next_pow2(n))
    L = min(L, n)
    step = max(1, int(L * (1 - overlap)))
    nfft = L * pad
    win = np.hanning(L)
    norm = 1.0 / (fs * np.sum(win ** 2))
    acc, segs, start = None, 0, 0
    while start + L <= n:
        seg = detrend(x[start:start + L]) * win
        X = np.fft.rfft(seg, nfft)
        p = (np.abs(X) ** 2) * norm
        p[1:-1] *= 2
        acc = p if acc is None else acc + p
        segs += 1
        start += step
    if segs == 0:
        return None
    acc /= segs
    return np.fft.rfftfreq(nfft, 1 / fs), acc, fs / nfft, segs


def dominant_peak(freq, power, df, f_lo, f_hi):
    idx = np.where((freq >= f_lo) & (freq <= f_hi))[0]
    if len(idx) < 3:
        return None
    k = idx[np.argmax(power[idx])]
    if k <= 0 or k >= len(power) - 1:
        return None
    y0, y1, y2 = np.log(np.maximum(power[k - 1:k + 2], 1e-30))
    den = y0 - 2 * y1 + y2
    delta = 0.0 if abs(den) < 1e-12 else np.clip(0.5 * (y0 - y2) / den, -1, 1)
    f_peak = (k + delta) * df
    med = np.median(power[idx])
    prom = power[k] / med if med > 0 else np.inf
    half = power[k] / 2
    kl = k
    while kl > idx[0] and power[kl] > half:
        kl -= 1
    kr = k
    while kr < idx[-1] and power[kr] > half:
        kr += 1
    return f_peak, prom, (kr - kl) * df


def secondary_peak(freq, power, f_lo, f_hi, sep=0.15, valley=0.5):
    """Topographically prominent competing peak: a local max, separated in frequency from the
    primary, with a genuine valley between the two. The valley test stops the ragged shoulders
    of a single resonance from being mistaken for a second mode.

    This is the most important safety check in the instrument. See Dsp.secondaryPeak."""
    idx = np.where((freq >= f_lo) & (freq <= f_hi))[0]
    k1 = idx[np.argmax(power[idx])]
    p1 = power[k1]
    best = (np.nan, 0.0)
    for k in idx[1:-1]:
        if not (power[k] > power[k - 1] and power[k] >= power[k + 1]):
            continue
        if abs(freq[k] - freq[k1]) / freq[k1] < sep:
            continue
        lo, hi = (k, k1) if k < k1 else (k1, k)
        if power[lo:hi + 1].min() > valley * power[k]:
            continue
        r = power[k] / p1
        if r > best[1]:
            best = (1 / freq[k], r)
    return best


def zero_crossing_cycles(phi, fs, hyst, t_min, t_max):
    crossings = []
    armed = False
    for i in range(1, len(phi)):
        if phi[i] < -hyst:
            armed = True
        if armed and phi[i - 1] <= 0 < phi[i]:
            frac = (0 - phi[i - 1]) / (phi[i] - phi[i - 1]) if phi[i] != phi[i - 1] else 0
            crossings.append((i - 1 + frac) / fs)
            armed = False
    per, amp = [], []
    for k in range(1, len(crossings)):
        t = crossings[k] - crossings[k - 1]
        if t_min <= t <= t_max:
            per.append(t)
            i0, i1 = int(crossings[k - 1] * fs), int(crossings[k] * fs)
            amp.append(np.max(np.abs(phi[i0:i1 + 1])))
    return np.array(per), np.array(amp)


def fit_free_decay(phi, fs, t_min, t_max):
    ext = []
    for i in range(1, len(phi) - 1):
        a, b, c = phi[i - 1], phi[i], phi[i + 1]
        if (b > a and b >= c) or (b < a and b <= c):
            den = a - 2 * b + c
            d = 0.0 if abs(den) < 1e-12 else np.clip(0.5 * (a - c) / den, -1, 1)
            ext.append(((i + d) / fs, b - 0.25 * (a - c) * d))
    noise = 0.05 * max(np.max(phi), abs(np.min(phi)))
    used = []
    for t, v in ext:
        if abs(v) < noise:
            continue
        if used and used[-1][1] * v > 0:
            if abs(v) > abs(used[-1][1]):
                used[-1] = (t, v)
            continue
        used.append((t, v))
    if len(used) < 5:
        return None
    halves = np.array([2 * (used[k][0] - used[k - 1][0]) for k in range(1, len(used))])
    halves = halves[(halves >= t_min) & (halves <= t_max)]
    if len(halves) == 0:
        return None
    td = np.median(halves)
    xs = np.arange(len(used), dtype=float)
    ys = np.log(np.maximum(np.abs([v for _, v in used]), 1e-6))
    slope, _ = np.polyfit(xs, ys, 1)
    delta = -2 * slope
    zeta = np.clip(delta / np.sqrt(4 * np.pi ** 2 + delta ** 2), 0, 0.9)
    return td * np.sqrt(1 - zeta ** 2), zeta


def estimate(phi, mode='SEAWAY', fs=FS):
    """Mirrors PeriodEstimator.estimate, including the period-scaled edge guard."""
    d = detrend(phi)
    dur = len(d) / fs
    seg = max(min(next_pow2(int(fs * 4 * T_MAX)), next_pow2(len(d) // 2)), 256)
    freq, power, df, nseg = welch_psd(d, fs, seg)
    pk = dominant_peak(freq, power, df, F_LO, F_HI)
    t_psd, prom = 1 / pk[0], pk[1]
    t2, ratio = secondary_peak(freq, power, F_LO, F_HI)

    bp = bandpass_fft(d, fs, F_LO, F_HI)
    # Guard scaled to the MEASURED period, capped at 10% of the record. A fixed T_MAX guard
    # would eat half of a 3-minute free decay and fail the recommended procedure every time.
    guard = int(min(t_psd, 0.10 * dur) * fs)
    core = bp[guard:len(bp) - guard] if len(bp) > 3 * guard else bp
    sigma = np.std(core, ddof=1)
    per, amp = zero_crossing_cycles(core, fs, 0.20 * sigma, T_MIN, T_MAX)
    if len(per) < 2:
        return None
    t_zc = np.median(per)

    se_zc = np.std(per, ddof=1) / np.sqrt(len(per)) if len(per) > 1 else t_zc * 0.1
    se_psd = (pk[2] / (2 * np.sqrt(max(nseg, 1)))) * t_psd ** 2
    w1, w2 = 1 / max(se_zc ** 2, 1e-9), 1 / max(se_psd ** 2, 1e-9)
    adopted = (w1 * t_zc + w2 * t_psd) / (w1 + w2)
    agree = abs(t_psd - t_zc) / (0.5 * (t_psd + t_zc))

    min_cyc = 4 if mode == 'FREE_DECAY' else 10
    ok = len(per) >= min_cyc and ratio <= COMPETING_THRESHOLD and agree < 0.10
    return dict(t_psd=t_psd, t_zc=t_zc, adopted=adopted, agree=agree, ncyc=len(per),
                prom=prom, amp=np.mean(amp) if len(amp) else 0,
                t2=t2, ratio=ratio, ok=ok, guard=guard / fs)


# ----------------------------------------------------------------- synthetic ships

rng = np.random.default_rng(20260713)


def narrowband_roll(tn, dur, amp, zeta=0.05, fs=FS, sub=8):
    """Roll response of a 1-DOF oscillator to white-noise wave excitation. This is what a ship
    rolling in a confused/irregular sea actually looks like: energy concentrated at her own
    natural period, but the phase and amplitude wander. Each call is a different REALISATION."""
    n = int(dur * fs)
    wn = 2 * np.pi / tn
    dt = 1 / (fs * sub)
    x = np.zeros(n)
    v = p = 0.0
    for i in range(n):
        for _ in range(sub):
            f = rng.normal() * wn ** 2 * 3.0 * np.sqrt(sub)
            a = f - 2 * zeta * wn * v - wn ** 2 * p
            v += a * dt
            p += v * dt
        x[i] = p
    return x / np.std(x) * amp


def free_decay(tn, dur, amp0, zeta=0.05, fs=FS):
    t = np.arange(int(dur * fs)) / fs
    wn = 2 * np.pi / tn
    wd = wn * np.sqrt(1 - zeta ** 2)
    return amp0 * np.exp(-zeta * wn * t) * np.cos(wd * t)


def report(name, tn_true, r, tol):
    err = (r['adopted'] - tn_true) / tn_true
    ok = abs(err) < tol and r['ok']
    print(f"{name:34s} true={tn_true:5.1f}  T_psd={r['t_psd']:5.2f}  T_zc={r['t_zc']:5.2f}  "
          f"adopt={r['adopted']:5.2f}  err={err * 100:+5.1f}%  ->GM {abs(err) * 200:4.1f}%  "
          f"agree={r['agree'] * 100:4.1f}%  cyc={r['ncyc']:3d}  P2/P1={r['ratio']:4.2f}  "
          f"{'PASS' if ok else 'FAIL'}")
    return ok, err


bar = "=" * 128
allok = True

print(bar)
print("CASE 1  clean sinusoid (sanity: a period the estimator cannot miss)")
print(bar)
for tn in (8.0, 12.5, 18.0, 26.0):
    t = np.arange(int(600 * FS)) / FS
    ok, _ = report(f"  pure sine T={tn}s", tn, estimate(4.0 * np.sin(2 * np.pi * t / tn)),
                   TOL_DETERMINISTIC)
    allok &= ok

print()
print(bar)
print("CASE 2  free decay, 3-minute record  (the mode the app RECOMMENDS -- deterministic)")
print(bar)
for tn, z in ((10.0, 0.03), (14.0, 0.05), (20.0, 0.08), (26.0, 0.05)):
    phi = free_decay(tn, 180, 6.0, z) + rng.normal(0, 0.02, int(180 * FS))
    ok, _ = report(f"  free decay T={tn}s z={z}", tn, estimate(phi, 'FREE_DECAY'), TOL_DETERMINISTIC)
    allok &= ok
    fit = fit_free_decay(bandpass_fft(detrend(phi), FS, F_LO, F_HI), FS, T_MIN, T_MAX)
    if fit:
        print(f"{'':34s} decay fit: T_n={fit[0]:5.2f} ({(fit[0] - tn) / tn * 100:+.1f}%)  "
              f"zeta={fit[1]:.3f} (true {z})")

print()
print(bar)
print("CASE 3  irregular seaway, 20-minute record  (a RANDOM PROCESS -- expect scatter)")
print(bar)
errs = []
for tn in (9.0, 11.0, 16.0, 22.0, 28.0):
    phi = narrowband_roll(tn, 1200, 3.5) + rng.normal(0, 0.03, int(1200 * FS))
    ok, err = report(f"  clean seaway T={tn}s", tn, estimate(phi), TOL_SEAWAY)
    allok &= ok
    errs.append(abs(err))
print(f"\n  Realisation scatter across the 5 records above: mean |err| = {np.mean(errs) * 100:.1f}%, "
      f"worst = {np.max(errs) * 100:.1f}%  (-> GM error {np.max(errs) * 200:.1f}%)")
print("  This scatter is a property of the SEA, not of the code. It is why free decay is preferred.")

print()
print(bar)
print("CASE 4  THE TRAP: a regular swell forcing the ship AWAY from her natural period.")
print("        The ship rolls at the ENCOUNTER period; a naive spectral peak reports the WRONG GM,")
print("        and in the DANGEROUS direction. Every dangerous case MUST be rejected.")
print(bar)
tn = 15.0
for t_sw, a_sw in ((9.0, 3.0), (9.0, 1.0), (24.0, 3.0), (24.0, 1.0), (11.0, 2.0)):
    t = np.arange(int(1200 * FS)) / FS
    phi = narrowband_roll(tn, 1200, 2.0) + a_sw * np.sin(2 * np.pi * t / t_sw)
    r = estimate(phi)
    err = (r['adopted'] - tn) / tn
    dangerous = abs(err) > 0.05 and r['ok']
    allok &= not dangerous
    print(f"  swell {t_sw:4.1f}s amp {a_sw}deg   true={tn}  T1={r['t_psd']:5.2f}  T2={r['t2']:5.2f}  "
          f"P2/P1={r['ratio']:4.2f}  adopt={r['adopted']:5.2f}  err={err * 100:+6.1f}%  "
          f"{'REJECTED (good)' if not r['ok'] else 'accepted (benign)'}"
          f"{'  <<< DANGEROUS MISS' if dangerous else ''}")

print()
print(bar)
print("CASE 5  contamination: 4.5 deg static list + gyro bias drift + 8 Hz engine vibration")
print(bar)
tn = 13.0
t = np.arange(int(900 * FS)) / FS
phi = (narrowband_roll(tn, 900, 3.0)
       + 4.5                                   # static list
       + 0.002 * t                             # gyro bias -> 1.8 deg drift over 15 min
       + 0.05 * np.sin(2 * np.pi * 8.0 * t)    # engine vibration
       + rng.normal(0, 0.05, len(t)))
ok, _ = report("  list+drift+vibration", tn, estimate(phi), TOL_SEAWAY)
allok &= ok

print()
print(bar)
print("CASE 6  small amplitude, near the noise floor (gyro noise ~0.1 deg RMS in band)")
print(bar)
for amp in (2.0, 1.0, 0.5, 0.25):
    phi = narrowband_roll(14.0, 1200, amp) + rng.normal(0, 0.10, int(1200 * FS))
    report(f"  roll amplitude {amp} deg", 14.0, estimate(phi), TOL_SEAWAY)

print()
print(bar)
print("CASE 7  ERROR BUDGET: how a period error propagates into GM")
print(bar)
B, d, Lwl = 32.2, 11.0, 190.0
C = 0.373 + 0.023 * (B / d) - 0.043 * (Lwl / 100)
f_is = 2 * C
gm_true = 1.20
t_true = f_is * B / np.sqrt(gm_true)
print(f"  Example ship: B={B} m, d={d} m, Lwl={Lwl} m")
print(f"  IS Code:  C = {C:.4f}   ->   f = 2C = {f_is:.4f}")
print(f"  If the true GM is {gm_true} m, the ship should roll with T = {t_true:.2f} s\n")
print(f"  {'u(T)/T':>8s} {'u(f)/f':>8s} {'u(GM)/GM':>10s} {'GM range [m]':>22s}")
for ut in (0.01, 0.02, 0.05):
    for uf in (0.00, 0.05, 0.08):
        rel = 2 * np.sqrt(ut ** 2 + uf ** 2)
        print(f"  {ut * 100:7.0f}% {uf * 100:7.0f}% {rel * 100:9.1f}%   "
              f"{gm_true * (1 - rel):8.2f} .. {gm_true * (1 + rel):.2f}")
print()
print("  => Measuring T to 1% (easy for the phone, in a free decay) still gives +/-16% on GM if f")
print("     comes from the IS Code. The instrument is limited by the ROLL COEFFICIENT, not by the")
print("     sensor. Calibrating f on one known-GM condition (u(f)/f ~ 2-3%) is what makes it useful.")

print()
print(bar)
print("OVERALL:", "ALL CASES PASS" if allok else "SOME CASES FAILED")
print(bar)
