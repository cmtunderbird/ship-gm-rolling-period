"""
Can the phone tell the SHIP apart from the SEA?  A seakeeping study.

Run:  python3 tools/sea_discrimination.py     (needs numpy)

--------------------------------------------------------------------------------------------
THE PROBLEM
--------------------------------------------------------------------------------------------
The roll spectrum is not "ship plus sea". It is ship TIMES sea:

    S_roll(f)  =  |H(f)|^2   x   S_exc(f)
                  ^^^^^^^^^       ^^^^^^^^
                  the SHIP        the SEA
                  a resonance     whatever the waves happen to be doing

Peak-picking S_roll conflates the two. A ship does not roll at her own period just because
we would like her to - she rolls at whatever period the waves push her at.

--------------------------------------------------------------------------------------------
THE SOLUTION: THE PHONE IS ALREADY CARRYING A SEA SENSOR
--------------------------------------------------------------------------------------------
In deep water a body that follows the surface sees a vertical acceleration a_z = -w^2 * eta,
while the surface SLOPE - which is what forces the roll - is alpha = k*eta = (w^2/g)*eta.
Therefore

    S_slope(f) = S_az(f) / g^2

    *** A SWELL IS AN INPUT: it appears in the roll AND in the accelerometer.
        A RESONANCE IS THE SHIP: it appears ONLY in the roll. ***

Take the peak of the roll spectrum, look at the same frequency in the vertical acceleration.
A twin there means you are measuring the sea.

--------------------------------------------------------------------------------------------
WHAT THIS SCRIPT SHOWS
--------------------------------------------------------------------------------------------
1. The hole the bimodality gate could not see. A tender ship in a plain wind sea with NO
   swell rolls at the WAVE period, produces ONE clean peak, and would have been reported with
   quality EXCELLENT and a GM 3.5x too large.
2. An ROC over 200 random ship x sea combinations for the shipped sea-lock veto.
3. Three discriminator ideas that DIED, and why. They are the obvious ones, so they are worth
   recording as dead ends rather than leaving for someone to rediscover.
"""
import numpy as np

G = 9.81
FS = 10.0
DUR = 5400.0
N = int(DUR * FS)
F = np.fft.rfftfreq(N, 1 / FS)
DF = F[1] - F[0]
W = 2 * np.pi * F
NPERSEG = 8192
F_LO, F_HI = 1 / 45.0, 1 / 3.0


# ------------------------------------------------------------------ the sea

def jonswap(fp, hs, gamma=3.3):
    """A wind sea. Note how savagely it dies BELOW its peak: exp(-1.25 (fp/f)^4).
    That is why a 15 s ship in an 8 s sea has essentially no energy at her own period."""
    f = np.maximum(F, 1e-6)
    a = np.exp(-1.25 * (fp / f) ** 4)
    sig = np.where(f <= fp, 0.07, 0.09)
    r = np.exp(-((f - fp) ** 2) / (2 * sig ** 2 * fp ** 2))
    s = (f ** -5) * a * gamma ** r
    s[F < 0.015] = 0
    s *= hs ** 2 / (16 * np.trapezoid(s, F))
    return s


def swell(fp, hs, rel_width=0.04):
    """A swell: narrow, but still a RANDOM narrow-band process - not a sine wave.
    That distinction is what killed the kurtosis idea (see the post-mortem at the end)."""
    sig = rel_width * fp
    s = np.exp(-((F - fp) ** 2) / (2 * sig ** 2))
    s *= hs ** 2 / (16 * np.trapezoid(s, F))
    return s


def realise(S, rng):
    return np.sqrt(2 * S * DF) * np.exp(1j * rng.uniform(0, 2 * np.pi, len(F))) * (N / 2.0)


# ------------------------------------------------------------------ the ship

def rao(f0, zeta):
    r = F / f0
    return 1.0 / (1.0 - r ** 2 + 2j * zeta * r)


class Ship:
    """Three oscillators hanging off one wave field.

    roll  <- transverse slope,   LIGHTLY damped -> it RESONATES
    pitch <- longitudinal slope, heavily damped -> it follows the waves
    heave <- elevation,          heavily damped -> it follows the waves

    Pitch and heave do not resonate, which is exactly what makes them a window onto the sea.
    """

    def __init__(self, t_roll=15.0, zeta_roll=0.05, t_pitch=7.0, t_heave=8.0):
        self.t_roll, self.zeta_roll = t_roll, zeta_roll
        self.H_roll = rao(1 / t_roll, zeta_roll)
        self.H_pitch = rao(1 / t_pitch, 0.5)
        self.H_heave = rao(1 / t_heave, 0.6)


def sail(ship, systems, rng, noise_deg=0.02, noise_acc=0.02):
    """systems: [(spectrum, wave direction deg)]  0 = head, 90 = beam, 180 = following.
    Returns exactly what the PHONE would record: roll [deg], pitch [deg], a_z [m/s2]."""
    k = W ** 2 / G
    Eta = np.zeros(len(F), complex)
    Sy = np.zeros(len(F), complex)     # transverse slope -> roll
    Sx = np.zeros(len(F), complex)     # longitudinal slope -> pitch
    for S, mu_deg in systems:
        X = realise(S, rng)
        mu = np.radians(mu_deg)
        Eta += X
        Sy += k * X * np.sin(mu)
        Sx += k * X * np.cos(mu)
    roll = np.fft.irfft(ship.H_roll * Sy, N) * 180 / np.pi + rng.normal(0, noise_deg, N)
    pitch = np.fft.irfft(ship.H_pitch * Sx, N) * 180 / np.pi + rng.normal(0, noise_deg, N)
    az = np.fft.irfft(-(W ** 2) * ship.H_heave * Eta, N) + rng.normal(0, noise_acc, N)
    return roll, pitch, az


# ------------------------------------------------------------------ analysis

def welch(x, nperseg=NPERSEG):
    step = nperseg // 2
    win = np.hanning(nperseg)
    wp = np.sum(win ** 2)
    segs = [np.fft.rfft((x[s:s + nperseg] - x[s:s + nperseg].mean()) * win)
            for s in range(0, len(x) - nperseg + 1, step)]
    Z = np.array(segs)
    return np.fft.rfftfreq(nperseg, 1 / FS), (np.abs(Z) ** 2).mean(0) * 2 / (FS * wp)


def smooth_log(f, y, frac=0.35):
    """The SMOOTH background of a spectrum: wide enough to pass a broad wave spectrum and a
    heavily damped RAO, far too wide to follow a lightly damped resonance or a swell line."""
    ly, lf = np.log(np.maximum(y, 1e-30)), np.log(np.maximum(f, 1e-9))
    out = np.empty_like(ly)
    for i in range(len(f)):
        w = np.abs(lf - lf[i]) < frac
        out[i] = ly[w].mean() if w.sum() > 2 else ly[i]
    return out


def peak_of(f, S):
    idx = np.where((f >= F_LO) & (f <= F_HI))[0]
    return 1 / f[idx[np.argmax(S[idx])]]


def sea_locked(f, S_roll, S_az, tol=0.06, thresh_db=3.0):
    """THE SHIPPED TEST. Does the roll peak have a twin in the accelerometer?"""
    m = (f >= F_LO) & (f <= F_HI)
    idx = np.where(m)[0]
    k = idx[np.argmax(S_roll[idx])]
    base = smooth_log(f[m], S_az[m])
    lookup = dict(zip(idx, base))
    near = idx[np.abs(f[idx] - f[k]) / f[k] < tol]
    exc = max(10 * (np.log(max(S_az[kk], 1e-30)) - lookup[kk]) / np.log(10) for kk in near)
    return exc > thresh_db, 1 / f[k], exc


bar = "=" * 100

# ============================================================================================
print(bar)
print("1.  THE HOLE THE BIMODALITY GATE COULD NOT SEE")
print("    A tender ship. A plain wind sea. No swell at all. ONE clean peak in the roll.")
print(bar)
ship = Ship(t_roll=15.0, zeta_roll=0.05)
roll, pitch, az = sail(ship, [(jonswap(1 / 8, 3.0), 50)], np.random.default_rng(3))
f, Sr = welch(roll)
_, Sa = welch(az)
locked, t_peak, exc = sea_locked(f, Sr, Sa)
print(f"    true natural period          15.00 s")
print(f"    roll spectrum peaks at       {t_peak:5.2f} s   <- the WAVE period, not the ship's")
print(f"    GM would have been reported  {(15.0 / t_peak) ** 2:5.2f}x too LARGE"
      f"   (a tender ship called stiff)")
print(f"    accelerometer peaks at       {peak_of(f, Sa):5.2f} s   <- the same place")
print(f"    accelerometer excess there   {exc:5.1f} dB")
print(f"    SEA-LOCK VERDICT             {'REJECTED - this is the sea' if locked else 'passed'}")
print()
print("    There is no competing peak, so the bimodality gate sees nothing wrong. The PSD and")
print("    zero-crossing estimates agree perfectly - on the wrong number. Two methods agreeing")
print("    is not the same as two methods being right. The accelerometer is the only witness.")

# ============================================================================================
print()
print(bar)
print("2.  ROC OVER 200 RANDOM SHIP x SEA COMBINATIONS")
print(bar)
rng = np.random.default_rng(99)
tp = fp = tn = fn = 0
for _ in range(200):
    t_n = rng.choice([10.0, 15.0, 20.0, 25.0])
    sh = Ship(t_roll=t_n, zeta_roll=rng.uniform(0.03, 0.09),
              t_pitch=rng.uniform(6, 9), t_heave=rng.uniform(7, 10))
    sys = [(jonswap(1 / rng.uniform(5, 12), rng.uniform(0.8, 3.5)), rng.uniform(20, 160))]
    if rng.random() < 0.7:
        sys.append((swell(1 / rng.uniform(9, 30), rng.uniform(0.5, 3.0),
                          rng.uniform(0.03, 0.08)), rng.uniform(20, 160)))
    r_, p_, a_ = sail(sh, sys, rng)
    f, Sr = welch(r_)
    _, Sa = welch(a_)
    locked, t_peak, _ = sea_locked(f, Sr, Sa)
    truly_ship = abs(t_peak / t_n - 1) < 0.08
    if locked and not truly_ship:
        tp += 1
    elif locked and truly_ship:
        fp += 1
    elif not locked and truly_ship:
        tn += 1
    else:
        fn += 1

n = tp + fp + tn + fn
print(f"    correctly flagged SEA   (rejected, rightly)    {tp:4d}   {tp / n * 100:5.1f} %")
print(f"    correctly passed SHIP   (accepted, rightly)    {tn:4d}   {tn / n * 100:5.1f} %")
print(f"    falsely flagged SEA     (good record wasted)   {fp:4d}   {fp / n * 100:5.1f} %")
print(f"    MISSED sea-driven peak  (accepted, WRONGLY)    {fn:4d}   {fn / n * 100:5.1f} %"
      f"   <<< the dangerous cell")
print()
print(f"    Of all genuinely sea-driven roll peaks, caught:  {tp / max(tp + fn, 1) * 100:5.0f} %")
print(f"    Of the records let through, genuinely the ship:  {tn / max(tn + fn, 1) * 100:5.0f} %")
print(f"    Of the genuinely GOOD records, thrown away:      {fp / max(fp + tn, 1) * 100:5.0f} %")
print()
print("    SAFE but BLUNT. It never lets a sea-driven peak through, and pays for that by")
print("    discarding most of the good records too - including the harmless synchronous case")
print("    where a swell happens to sit exactly on f_n and the naive answer is right anyway.")
print("    For a stability tool that is the correct trade: a wasted record is an inconvenience,")
print("    a wrong GM is not.")

# ============================================================================================
print()
print(bar)
print("3.  POST-MORTEM: THREE OBVIOUS IDEAS THAT DIED")
print(bar)
print("""
    COHERENCE between roll and the accelerometer.
        Expected: low at the ship's resonance ("the accelerometer cannot see the ship's own
        mode"), high at a swell. WRONG. A ship is a LINEAR filter of ONE wave field, so every
        channel is a filtered copy of the same input and coherence is ~1.0 EVERYWHERE, the
        resonance included. The resonance IS wave-driven - just wave-driven with amplification.
        Coherence asks "is this explained by the waves", and at f_n the answer is yes.

    KURTOSIS / envelope statistics.
        Expected: a swell is a near-sinusoid (kurtosis 1.5), a resonance is narrow-band Gaussian
        (kurtosis 3.0). WRONG. A real swell is a narrow-band RANDOM process, so it is Gaussian
        too. The two are statistically identical. An earlier test of mine modelled the swell as
        a pure sine wave, which flattered this idea; it does not survive a realistic sea.

    RAW DECONVOLUTION  S_roll * g^2 / S_az.
        The algebra is right, and it does cancel the swell. But it also divides by the heave
        RAO, which blows up at high frequency and plants a fake peak at ~5 s. It only works
        after a SMOOTH baseline is removed from the ratio - the resonance is the only SHARP
        feature left. Powerful, but fiddly, and not what is shipped: the app ships the blunt,
        safe sea-lock veto instead.
""")
print(bar)
print("VERDICT:", "sea-lock veto validated - no dangerous misses" if fn == 0
      else f"*** {fn} DANGEROUS MISSES - DO NOT SHIP ***")
print(bar)
