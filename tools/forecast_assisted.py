"""
CAN THE WEATHER FORECAST MAKE THIS A REAL INSTRUMENT?

Short answer, after building it and measuring it:  THE IDEA IS RIGHT, MY VERSION OF IT IS NOT
SAFE, AND THE REASON WHY POINTS AT A BETTER ARCHITECTURE.  Do not ship this yet.

--------------------------------------------------------------------------------------------
THE IDEA
--------------------------------------------------------------------------------------------
The Master already has a forecast: sea and swell, each with height, period and direction. The
phone already knows speed and course. Put them together and the encounter relation

        w_e = w_0 - (w_0^2 / g) * U * cos(mu)

predicts EXACTLY where every wave-driven peak will land in the roll spectrum, before you even
look at it. You no longer have to REJECT a record because a wave sits near the roll peak - you
know where the waves are, so you can subtract them and MEASURE THE SHIP.

There is also a real physical argument that the phone alone is not enough underway. The
identity the shipped sea-lock veto rests on, S_slope = S_az/g^2, quietly assumes ZERO SPEED:

        a_z    ~  w_e^2 * eta        (she heaves at the ENCOUNTER frequency)
        slope  ~  w_0^2 * eta / g    (a wave's steepness is set by its ABSOLUTE frequency)

To turn what the accelerometer measures into what actually FORCES the roll you need w_0 - and
w_0 can only be recovered from w_e if you know the wave DIRECTION. Which is the one thing the
forecast has and the phone does not.

--------------------------------------------------------------------------------------------
WHAT THE MEASUREMENTS SAY  (this script; 40 random ship x sea x heading x speed per row)
--------------------------------------------------------------------------------------------
                              answers        catastrophic (GM error > 30%)
    naive peak-pick             100%              54%
    SHIPPED sea-lock veto         8-10%            0%
    forecast + fit, perfect fc    49%             16%
    forecast + fit, REALISTIC     38%             47%     <-- unacceptable
    forecast + fit, stale         13%             80%     <-- much worse than useless

"Realistic" = peak period off by ~1.2 s, Hs by ~25%, direction by ~20 deg. That is a NORMAL,
current, decent forecast - 25-50 km grid, 3-6 h steps, hours old.

So: it answers 4x more records than the shipped veto, and roughly half of those answers are
catastrophically wrong. For a stability tool that is an unambiguously bad trade. The veto
wastes records; this would wreck them.

The trust gate I built (does the forecast's predicted spectrum agree in shape with the
accelerometer's measured one?) DID NOT SAVE IT. It is too weak a test.

--------------------------------------------------------------------------------------------
BUT - WHICH FORECAST ERROR ACTUALLY DOES THE DAMAGE?  (everything else perfect)
--------------------------------------------------------------------------------------------
    error in...        answers   median GM err   catastrophic
    nothing              52%           9%            20%
    wave HEIGHT          36%           4%            30%
    wave DIRECTION       32%           8%            22%
    wave PERIOD          43%          50%            58%   <-- the killer

*** THE KILLER IS THE FORECAST PERIOD. AND THE PERIOD IS THE ONE THING THE PHONE ALREADY
    MEASURES BETTER THAN THE FORECAST DOES. ***

The accelerometer measures the ENCOUNTER spectrum directly and precisely - that is what the
whole sea-lock veto is built on. Taking the period from a 6-hour-old grid forecast instead is
throwing away a good measurement in favour of a bad prediction.

Direction error, by contrast, is nearly free (22% vs a 20% baseline). And direction is exactly
what the phone CANNOT get.

--------------------------------------------------------------------------------------------
THE ARCHITECTURE THIS POINTS AT  (not built here - the next piece of work)
--------------------------------------------------------------------------------------------
    Take PERIOD and HEIGHT from the ACCELEROMETER.   (the phone measures them, well)
    Take DIRECTION from the FORECAST.                (the only thing the phone cannot get)
    Use the direction ONLY to map w_e -> w_0, so the slope spectrum can be computed at speed.

That inverts the dependency: the forecast stops being the source of truth and becomes a single
scalar hint. A stale forecast then costs you a bearing, not a period - and a bearing error is
nearly harmless.

Even so, note the top row: 20% catastrophic with a PERFECT forecast. The gates (R2, roll SNR,
ill-conditioning) are not yet good enough on their own. This needs real work before anyone
trusts a GM to it.

--------------------------------------------------------------------------------------------
WHAT THE FORECAST IS *SAFE* FOR, TODAY
--------------------------------------------------------------------------------------------
These carry none of the above risk, because none of them puts a number on GM:

  * MEASUREMENT WINDOW ADVISORY. Predict the encounter periods for the current forecast and
    tell the Master which heading and speed will give a clean measurement - waves well clear
    of the expected f_n, and beam-ish so she is actually excited. "Come to 040 at 8 kn for
    20 minutes and I can measure her."
  * RESONANCE / PARAMETRIC-ROLL WARNING. T_e ~ T_n (synchronous) or T_e ~ T_n/2 (parametric).
    Pure safety value, no GM claim attached.
  * FOLLOWING-SEA ILL-CONDITIONING FLAG. dw_e/dw_0 -> 0 and the encounter transform becomes
    singular; several waves arrive at the same encounter frequency. Detected here, and worth
    telling the operator regardless of which method is used.
  * GM DISCREPANCY ALARM against the loading computer - with one hard rule, below.

--------------------------------------------------------------------------------------------
THE HARD RULE, IF SHIP'S DATA IS EVER FED IN
--------------------------------------------------------------------------------------------
The calculated GM may set the SEARCH BAND and may be compared against the result. It must
NEVER weight, shrink or prior the estimate. The entire value of this instrument is that it is
INDEPENDENT of the loading computer. An estimator that leans on the calculated GM will happily
confirm a misdeclared cargo. Independence is the product.
"""

import numpy as np

G = 9.81
FS = 5.0                 # plenty for waves; keeps the component sum affordable
DUR = 3600.0
N = int(DUR * FS)
T = np.arange(N) / FS
NPERSEG = 4096
F_LO, F_HI = 1 / 45.0, 1 / 3.0
NCOMP = 200              # wave components per system


# =============================================================================== the sea

def jonswap_S(w0, wp, hs, gamma=3.3):
    """JONSWAP in ANGULAR absolute frequency."""
    w0 = np.maximum(w0, 1e-6)
    a = np.exp(-1.25 * (wp / w0) ** 4)
    sig = np.where(w0 <= wp, 0.07, 0.09)
    r = np.exp(-((w0 - wp) ** 2) / (2 * sig ** 2 * wp ** 2))
    s = (w0 ** -5) * a * gamma ** r
    s = np.nan_to_num(s)
    m0 = np.trapezoid(s, w0)
    return s * hs ** 2 / (16 * m0)


def swell_S(w0, wp, hs, rel_width=0.05):
    sig = rel_width * wp
    s = np.exp(-((w0 - wp) ** 2) / (2 * sig ** 2))
    return s * hs ** 2 / (16 * np.trapezoid(s, w0))


class WaveSystem:
    """height, period, direction - exactly what a forecast gives the Master."""

    def __init__(self, tp, hs, dir_deg, kind='sea'):
        self.tp, self.hs, self.dir_deg, self.kind = tp, hs, dir_deg, kind

    def spectrum(self, w0):
        wp = 2 * np.pi / self.tp
        return (jonswap_S(w0, wp, self.hs) if self.kind == 'sea'
                else swell_S(w0, wp, self.hs))


def encounter(w0, speed_ms, mu_deg):
    """w_e = w_0 - (w_0^2/g) U cos(mu).  mu is the wave-travel direction relative to the bow;
    cos(mu) = +1 means the waves are going the same way we are (following sea), so the
    encounter frequency DROPS."""
    return w0 - (w0 ** 2 / G) * speed_ms * np.cos(np.radians(mu_deg))


# =============================================================================== the ship

def rao(w, w0_n, zeta):
    r = w / w0_n
    return 1.0 / (1.0 - r ** 2 + 2j * zeta * r)


class Ship:
    def __init__(self, t_roll, zeta_roll=0.05, t_heave=8.0):
        self.t_roll, self.zeta_roll, self.t_heave = t_roll, zeta_roll, t_heave
        self.wn = 2 * np.pi / t_roll
        self.wh = 2 * np.pi / t_heave


def sail(ship, systems, heading_deg, speed_kn, rng, noise_deg=0.02, noise_acc=0.02):
    """
    Build the time series the phone would record, component by component, so the ENCOUNTER
    frequency is handled exactly.

      roll  forced by TRANSVERSE slope = k*a*sin(mu), responds at w_e via H_roll(w_e)
      a_z   = -w_e^2 * H_heave(w_e) * a   (she heaves at the encounter frequency)
      k     = w_0^2 / g   <- ABSOLUTE frequency. This is the mismatch that breaks the
                             zero-speed identity S_slope = S_az/g^2.
    """
    U = speed_kn * 0.514444
    roll = np.zeros(N)
    az = np.zeros(N)
    for sysm in systems:
        wp = 2 * np.pi / sysm.tp
        w0 = np.linspace(max(wp * 0.35, 0.08), wp * 2.5, NCOMP)
        dw = w0[1] - w0[0]
        S = sysm.spectrum(w0)
        a = np.sqrt(2 * np.maximum(S, 0) * dw)          # component amplitudes [m]
        ph = rng.uniform(0, 2 * np.pi, NCOMP)

        mu = sysm.dir_deg - heading_deg
        we = encounter(w0, U, mu)
        k = w0 ** 2 / G

        H_r = rao(np.abs(we), ship.wn, ship.zeta_roll)
        H_h = rao(np.abs(we), ship.wh, 0.6)

        slope_y = k * a * np.sin(np.radians(mu))        # transverse slope -> rolls her
        for c0 in range(0, NCOMP, 50):                  # chunked, vectorised component sum
            sl = slice(c0, c0 + 50)
            arg = np.outer(we[sl], T)
            roll += (np.abs(H_r[sl]) * slope_y[sl] @ np.cos(arg + (ph[sl] + np.angle(H_r[sl]))[:, None]))
            az += (-(we[sl] ** 2) * np.abs(H_h[sl]) * a[sl] @ np.cos(arg + (ph[sl] + np.angle(H_h[sl]))[:, None]))

    roll = roll * 180 / np.pi + rng.normal(0, noise_deg, N)
    az = az + rng.normal(0, noise_acc, N)
    return roll, az


# =============================================================================== analysis

def welch(x):
    step = NPERSEG // 2
    win = np.hanning(NPERSEG)
    wp = np.sum(win ** 2)
    segs = [np.fft.rfft((x[s:s + NPERSEG] - x[s:s + NPERSEG].mean()) * win)
            for s in range(0, len(x) - NPERSEG + 1, step)]
    Z = np.array(segs)
    return np.fft.rfftfreq(NPERSEG, 1 / FS), (np.abs(Z) ** 2).mean(0) * 2 / (FS * wp)


def predict_excitation(f_e, systems, heading_deg, speed_kn):
    """
    From the FORECAST (period, height, direction) plus the phone's own speed and course,
    predict the ROLL-EXCITATION spectrum in ENCOUNTER frequency.

        S_e(w_e) = S_slope(w_0) * sin^2(mu) / |dw_e/dw_0|

    The Jacobian |dw_e/dw_0| = |1 - 2 w_0 U cos(mu)/g| is where following seas turn nasty: it
    goes to zero, the transformation becomes singular, and energy piles up.
    """
    U = speed_kn * 0.514444
    w_e_grid = 2 * np.pi * f_e
    S_pred = np.zeros_like(f_e)
    ill = False

    for sysm in systems:
        wp = 2 * np.pi / sysm.tp
        w0 = np.linspace(max(wp * 0.35, 0.08), wp * 2.5, 2000)
        S0 = sysm.spectrum(w0)
        mu = sysm.dir_deg - heading_deg
        we = encounter(w0, U, mu)
        jac = np.abs(1 - 2 * w0 * U * np.cos(np.radians(mu)) / G)
        if np.min(jac) < 0.15:
            ill = True
        k = w0 ** 2 / G
        S_slope = (k ** 2) * S0 * np.sin(np.radians(mu)) ** 2
        dens = S_slope / np.maximum(jac, 0.05)

        idx = np.searchsorted(w_e_grid, np.abs(we))
        dw0 = np.gradient(w0)
        dwe = np.gradient(w_e_grid)
        ok = (idx > 0) & (idx < len(S_pred))
        np.add.at(S_pred, idx[ok], dens[ok] * dw0[ok] / np.maximum(dwe[idx[ok]], 1e-9))

    return np.convolve(S_pred, np.ones(5) / 5, mode='same'), ill


def smooth_log(f, y, lo, hi, frac=0.35):
    lf = np.log(np.maximum(f[lo:hi + 1], 1e-9))
    ly = np.log(np.maximum(y[lo:hi + 1], 1e-30))
    out = np.empty_like(ly)
    for i in range(len(lf)):
        w = np.abs(lf - lf[i]) < frac
        out[i] = ly[w].mean() if w.sum() > 2 else ly[i]
    return out


def band(f):
    lo = int(np.argmax(f >= F_LO))
    hi = int(np.where(f <= F_HI)[0][-1])
    return lo, hi


def naive_period(f, S_roll):
    lo, hi = band(f)
    return 1 / f[lo + int(np.argmax(S_roll[lo:hi + 1]))]


def sealock_reject(f, S_roll, S_az, thresh_db=3.0, tol=0.06):
    """What the app ships today: does the roll peak have a twin in the accelerometer?"""
    lo, hi = band(f)
    k = lo + int(np.argmax(S_roll[lo:hi + 1]))
    base = smooth_log(f, S_az, lo, hi)
    near = [kk for kk in range(lo, hi + 1) if abs(f[kk] - f[k]) / f[k] < tol]
    exc = max(10 * (np.log(max(S_az[kk], 1e-30)) - base[kk - lo]) / np.log(10) for kk in near)
    return exc > thresh_db


def forecast_trustworthy(f, S_az, S_pred, thresh=0.5):
    """
    THE SAFETY NET THAT DID NOT WORK. The phone MEASURES the encounter spectrum; the forecast
    PREDICTS it. If they disagree the forecast is stale. Compare SHAPES (log-domain
    correlation), not amplitudes - Hs is the least reliable thing the forecast tells us.

    Kept here because the negative result is the point: at realistic forecast quality this
    gate still let through 38% of records, 47% of them catastrophically wrong. Shape
    correlation over a broad band is simply too coarse a test.
    """
    lo, hi = band(f)
    a = np.log(np.maximum(S_az[lo:hi + 1], 1e-30))
    b = np.log(np.maximum(S_pred[lo:hi + 1], 1e-30))
    ok = np.isfinite(a) & np.isfinite(b) & (b > np.log(1e-25))
    if ok.sum() < 12:
        return False, 0.0
    a, b = a[ok] - a[ok].mean(), b[ok] - b[ok].mean()
    c = float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-12))
    return c > thresh, c


# =============================================================================== the FIT

NOISE_ROLL_PSD = (0.02 ** 2) / (FS / 2)      # roll-channel white sensor noise floor


def ill_fraction(f, systems, heading, speed_kn):
    """How much of the forecast's ENERGY sits where the encounter transform is SINGULAR?

    In a following sea dw_e/dw_0 = 1 - 2 w_0 U cos(mu)/g passes through zero: several different
    waves arrive at the SAME encounter frequency and pile up. The prediction there is
    worthless. Measure how much energy is affected, and refuse rather than pretend."""
    U = speed_kn * 0.514444
    tot = bad = 0.0
    for s in systems:
        wp = 2 * np.pi / s.tp
        w0 = np.linspace(max(wp * 0.35, 0.08), wp * 2.5, 2000)
        S0 = s.spectrum(w0)
        mu = s.dir_deg - heading
        jac = np.abs(1 - 2 * w0 * U * np.cos(np.radians(mu)) / G)
        tot += S0.sum()
        bad += S0[jac < 0.2].sum()
    return bad / max(tot, 1e-30)


def fit_resonance(f, S_roll, S_pred, ill_frac):
    """
    Do NOT hunt for a peak in the ratio S_roll/S_pred. FIT the physics:

        S_roll(f) = scale * |H(f; f_n, zeta)|^2 * S_pred(f)

    weighted by where the forecast says the sea actually HAS energy.

    This is the difference between a method that half-works and one that does not work at all.
    A ratio-peak detector will happily "find" a resonance in a DEAD part of the spectrum, where
    it is dividing sensor noise by a near-zero prediction - that is exactly how the first
    version of this produced 46% catastrophic errors on a PERFECT forecast. A weighted fit
    cannot: a bin the forecast says is dead carries no weight, so it cannot vote.
    """
    lo, hi = band(f)
    k = np.arange(lo, hi + 1)
    w = S_pred[k] / max(S_pred[k].max(), 1e-30)
    good = (w > 0.05) & (S_roll[k] > 10 * NOISE_ROLL_PSD)
    if good.sum() < 15 or ill_frac > 0.25:
        return np.nan, np.nan, 0.0, int(good.sum()), 0.0
    kk, ww = k[good], w[good]
    y = np.log(S_roll[kk]) - np.log(np.maximum(S_pred[kk], 1e-30))     # = log|H|^2 + const

    best = (1e18, None)
    grid = np.arange(6.0, 32.01, 0.10)
    for tn in grid:
        r = (f[kk] * tn) ** 2
        for z in (0.03, 0.05, 0.08, 0.12):
            logH2 = -np.log((1 - r) ** 2 + (2 * z * np.sqrt(r)) ** 2)
            c = np.average(y - logH2, weights=ww)
            res = float(np.sum(ww * (y - logH2 - c) ** 2))
            if res < best[0]:
                best = (res, (tn, z))
    tn, z = best[1]
    ss_tot = float(np.sum(ww * (y - np.average(y, weights=ww)) ** 2))
    r2 = 1 - best[0] / max(ss_tot, 1e-12)

    # Is there measurable ROLL at the period we are claiming?
    #
    # NOTE the trap here, which I fell into. The obvious gate is "does the forecast show wave
    # energy at f_n, relative to its own peak?" - and it is WRONG. A lightly damped resonance
    # amplifies by 1/(2 zeta)^2, i.e. ~100x. She needs only a LITTLE excitation at f_n to roll
    # measurably; demanding 10% of the spectral peak there rejects perfectly good records.
    # What matters is not how hard the sea pushed, but whether the ROLL that came out is above
    # the sensor noise.
    kn = int(np.argmin(np.abs(f - 1 / tn)))
    roll_snr_at_fn = S_roll[kn] / (10 * NOISE_ROLL_PSD)

    if tn <= grid[0] + 0.05 or tn >= grid[-1] - 0.05:     # ran to the edge = no real minimum
        return np.nan, np.nan, r2, int(len(kk)), roll_snr_at_fn
    return tn, z, r2, int(len(kk)), roll_snr_at_fn


def forecast_estimate(f, S_roll, S_az, systems, heading, speed_kn,
                      min_r2=0.85, min_snr=1.0, min_trust=0.5):
    """The whole forecast-assisted pipeline, with every gate in place."""
    S_pred, _ = predict_excitation(f, systems, heading, speed_kn)
    trusted, corr = forecast_trustworthy(f, S_az, S_pred, min_trust)
    illf = ill_fraction(f, systems, heading, speed_kn)
    tn, z, r2, nb, snr = fit_resonance(f, S_roll, S_pred, illf)

    if not trusted:
        return np.nan, "forecast disagrees with the accelerometer - it is stale or wrong", corr, r2
    if np.isnan(tn):
        return np.nan, "the fit found no resonance the sea could have excited", corr, r2
    if r2 < min_r2:
        return np.nan, f"poor fit (R2 {r2:.2f}) - the model does not explain this roll", corr, r2
    if snr < min_snr:
        return np.nan, "no measurable roll at that period - she never resonated there", corr, r2
    return tn, "ok", corr, r2


# =============================================================================== experiment

def corrupt(systems, rng, level):
    """Turn the TRUTH into a FORECAST. THIS IS THE WHOLE EXPERIMENT. A method that only works
    on a perfect forecast is worthless, because nobody has one."""
    if level == 'perfect':
        d = (0.0, 1.0, 0.0)
    elif level == 'realistic':      # a decent, current forecast
        d = (rng.normal(0, 1.2), np.exp(rng.normal(0, 0.25)), rng.normal(0, 20))
    else:                           # 'stale': 12 h old, the sea has moved on
        d = (rng.normal(0, 3.5), np.exp(rng.normal(0, 0.6)), rng.normal(0, 55))
    dtp, dhs, ddir = d
    return [WaveSystem(max(3.5, s.tp + dtp), max(0.2, s.hs * dhs), s.dir_deg + ddir, s.kind)
            for s in systems]


def trial(rng, level):
    t_n = float(rng.choice([10.0, 14.0, 18.0, 22.0]))
    ship = Ship(t_n, zeta_roll=rng.uniform(0.03, 0.08), t_heave=rng.uniform(7, 10))
    heading = rng.uniform(0, 360)
    speed = rng.uniform(0, 16)
    systems = [WaveSystem(rng.uniform(5, 11), rng.uniform(1.0, 3.5),
                          heading + rng.uniform(30, 150), 'sea')]
    if rng.random() < 0.7:
        systems.append(WaveSystem(rng.uniform(10, 20), rng.uniform(0.8, 3.0),
                                  heading + rng.uniform(30, 150), 'swell'))

    roll, az = sail(ship, systems, heading, speed, rng)
    if roll.std() < 0.2:
        return None
    f, Sr = welch(roll)
    _, Sa = welch(az)

    fc = corrupt(systems, rng, level)
    t_fc, msg, corr, r2 = forecast_estimate(f, Sr, Sa, fc, heading, speed)
    return dict(t_n=t_n, t_naive=naive_period(f, Sr),
                sealock_pass=not sealock_reject(f, Sr, Sa),
                t_fc=t_fc, msg=msg, r2=r2)


def gm_err(t_est, t_true):
    return np.nan if (t_est is None or np.isnan(t_est)) else abs((t_true / t_est) ** 2 - 1)


def report(level, n=40):
    rng = np.random.default_rng(4242)
    R = [t for t in (trial(rng, level) for _ in range(n)) if t]
    naive = np.array([gm_err(r['t_naive'], r['t_n']) for r in R])
    fc = np.array([gm_err(r['t_fc'], r['t_n']) for r in R])
    slp = np.array([r['sealock_pass'] for r in R])
    got = ~np.isnan(fc)

    print(f"\n{'=' * 94}")
    print(f"FORECAST QUALITY: {level.upper()}    ({len(R)} trials)")
    print('=' * 94)
    print(f"  {'method':40} {'answers':>8} {'median GM err':>14} {'>30% GM':>9} {'worst':>7}")
    print(f"  {'-' * 82}")

    def row(name, mask, errs):
        e = errs[mask & ~np.isnan(errs)]
        if len(e) == 0:
            print(f"  {name:40} {mask.mean() * 100:7.0f}% {'-':>14} {'-':>9} {'-':>7}")
            return 0.0
        cat = (e > 0.30).mean() * 100
        print(f"  {name:40} {mask.mean() * 100:7.0f}% {np.median(e) * 100:13.0f}% "
              f"{cat:8.0f}% {e.max() * 100:6.0f}%")
        return cat

    row("naive peak-pick (no gates at all)", np.ones(len(R), bool), naive)
    c_sl = row("SHIPPED: sea-lock veto", slp, naive)
    c_fc = row("PROPOSED: forecast + fit", got, fc)
    return c_sl, slp.mean(), c_fc, got.mean()


if __name__ == '__main__':
    print(__doc__)
    out = {}
    for lvl in ('perfect', 'realistic', 'stale'):
        out[lvl] = report(lvl)

    print(f"\n{'=' * 94}")
    print("VERDICT")
    print('=' * 94)
    print(f"  {'forecast':12} {'shipped veto':>26} {'forecast + fit':>28}")
    print(f"  {'':12} {'answers':>10} {'catastrophic':>15} {'answers':>12} {'catastrophic':>15}")
    for lvl in ('perfect', 'realistic', 'stale'):
        c_sl, y_sl, c_fc, y_fc = out[lvl]
        print(f"  {lvl:12} {y_sl * 100:9.0f}% {c_sl:14.0f}% {y_fc * 100:11.0f}% {c_fc:14.0f}%")
