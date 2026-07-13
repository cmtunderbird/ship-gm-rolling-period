# GM by Rolling Period

**Estimate a ship's transverse metacentric height (GM) from her natural rolling period, using nothing but the IMU already inside an ordinary Android phone.**

![build](https://github.com/cmtunderbird/ship-gm-rolling-period/actions/workflows/build.yml/badge.svg)
![licence](https://img.shields.io/badge/licence-MIT-blue)
![platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)

Reference device: **Xiaomi Redmi Note 14 Pro**. Runs on any Android 8.0+ phone with a gyroscope.

> **This is an estimate, not an instrument of record.** Not type-approved, not class-approved, and not yet validated against a real ship. It is not a substitute for the vessel's approved loading computer, stability booklet, or an inclining experiment, and must not be the sole basis for a stability decision.

---

## The one thing to understand first

A ship **does not roll at her own period just because we would like her to.** She rolls at whatever period the waves push her at. Get that wrong and you compute GM from the sea instead of the ship.

This is not a footnote. It is the central problem, and most of this project is about it. See **[docs/SEA_DISCRIMINATION.md](docs/SEA_DISCRIMINATION.md)**.

---

## 1. What it does

A ship is a torsional pendulum. Her natural roll period depends on how stiff she is, and stiffness is GM:

```
T = f · B / √GM          =>          GM = ( f · B / T )²
```

Measure `T` **when she is genuinely rolling freely**, and you have an independent physical check on the GM your loading computer is claiming.

---

## 2. The measurement chain

```
gyroscope + accelerometer                 (50 Hz, TYPE_GAME_ROTATION_VECTOR)
        │
        ├─ quaternion → gravity vector in the device frame
        │     │
        │     └─ 2-D tilt vector → band-pass → PCA → the ROLL AXIS → φ(t)
        │            │
        │            ├─ Welch PSD → dominant peak → T_psd
        │            └─ hysteresis zero-crossings → median → T_zc
        │                          │
        │                    cross-check
        │                          │
        └─ world-vertical acceleration a_z  =  THE SEA
                     │
                     └─ SEA-LOCK VETO: does the roll peak have a twin here?
                                │
                                ▼
                      GM = ( f · B / T )²  ±  propagated uncertainty
```

---

## 3. Four design decisions that matter

### 3.1 The gyroscope, not the accelerometer (for the roll angle)

An accelerometer measures *gravity minus acceleration*. A phone anywhere off the roll axis picks up centripetal and tangential acceleration from the roll itself, plus sway and heave — **at the roll frequency**, where no filter can remove them.

A gyroscope measures rigid-body angular rate: independent of where in the ship the phone sits, immune to linear acceleration. Its only weakness — slow bias drift — is far below the roll band, which is exactly where the accelerometer *is* trustworthy. So: `TYPE_GAME_ROTATION_VECTOR`, magnetometer deliberately excluded (a steel ship's magnetic field is useless).

### 3.2 ...but the accelerometer is indispensable for the SEA

In deep water, `a_z = −ω²η` and the wave slope forcing the roll is `α = ω²η/g`. Therefore

```
S_slope(f) = S_az(f) / g²
```

**The wave-slope spectrum that forces the roll IS the vertical-acceleration spectrum.** We were carrying the sensor and throwing the signal away.

### 3.3 The phone can be laid down at any angle

The principal axis of the band-passed 2-D tilt series *is* the roll axis (roll amplitude normally dominates pitch). Found by eigen-decomposition, no compass needed. Manual override available.

### 3.4 Two independent period estimates, cross-checked

Welch-PSD peak vs. hysteresis zero-crossing counting. Their agreement is a quality indicator — **but see §5. Two methods agreeing is not the same as two methods being right.**

---

## 4. The swell trap

A ship in a regular swell rolls at the **wave encounter period**, not her own. Feed that into `GM = (f·B/T)²` and you get a confidently wrong answer, in the dangerous direction.

Defence: a **topographically prominent secondary-peak test**. If a second peak in the roll band carries >25 % of the primary's power, one of the two is the sea and the record is rejected. Clean records score 0.00–0.06; swell-contaminated ones 0.30–0.79.

---

## 5. The hole the swell gate could NOT see

This is the one that matters, and it is **not** the swell case.

**A tender ship (T_n = 15 s) in a plain wind sea with no swell at all, Tp = 8 s:**

| | |
|---|---|
| True natural period | 15.00 s |
| Roll spectrum peaks at | **8.03 s** — the *wave* period |
| GM would have been reported | **3.49× too LARGE** |
| Accelerometer peaks at | 8.03 s — the same place |
| Old verdict | quality **EXCELLENT** |

A JONSWAP spectrum dies as `exp(−1.25(fp/f)⁴)` below its peak, so at 15 s there is essentially no wave energy. **The ship never resonates.** She rolls at the wave period, and the roll spectrum has **one clean peak** — nothing for the bimodality gate to find, and the PSD and zero-crossing estimates agree *perfectly* on the wrong number.

A tender ship reported as stiff. The worst possible direction to be wrong in.

### The fix: ask the accelerometer

> **A SWELL IS AN INPUT** — it appears in the roll *and* in the heave.
> **A RESONANCE IS THE SHIP** — it appears *only* in the roll. The ship puts no energy into the sea.

Take the peak of the roll spectrum; look at the same frequency in the vertical acceleration. A twin there means you are measuring the sea.

**Validated over 200 random ship × sea combinations** (`tools/sea_discrimination.py`): **100 % of sea-driven peaks caught, zero misses.** It is blunt — it also discards 82 % of genuinely good records — but for a stability tool that is the correct trade. A wasted record is an inconvenience; a wrong GM is not.

### The encounter test — the one that assumes nothing

```
ω_e = ω₀ − (ω₀²/g)·U·cos(μ)
```

A wave's period shifts with heading and speed. **A ship's natural roll period does not.** Record twice on courses ≥30° apart: the period that stays put is the ship. No wave theory, no ship model — the only test here immune to every modelling assumption. **When the clever methods disagree with it, believe it.** (Uses GPS; optional.)

---

## 6. Where the error actually comes from

`GM = (f·B/T)²` — **every relative error doubles**:

```
u(GM)/GM = 2 · √[ (u(T)/T)² + (u(f)/f)² ]
```

| u(T)/T | u(f)/f | u(GM)/GM |
|---|---|---|
| 1 % | 0 % | 2 % |
| **1 %** | **8 %** (IS Code) | **16 %** |
| 5 % | 8 % | 19 % |

**The phone is not the limiting factor. The roll coefficient is.** The IMU measures the period to ~1 % in a free decay; the IS Code coefficient carries ~8 % scatter, and published validation (Grin, IMDC 2024) shows the JSRA fit adopted by IS 2008 is the *worst performing* of the eight common estimators.

### Which is why calibration is the whole point

Measure once in a condition whose GM you already trust, and the app solves `f = T·√GM / B` for **your** ship. Uncertainty on f drops from ~8 % to ~2–3 %, and GM from ±16 % to about ±5 %.

---

## 7. Conventions — read before touching the maths

```
IMO / IS Code 2008 (2.3.4):   T = 2·C·B / √GM     C ≈ 0.40 – 0.45
Common rule of thumb:         T =   f·B / √GM     f ≈ 0.75 – 0.90
```

Same equation, with **f = 2C**. The app stores **only f**, so the factor of two cannot drift. A unit test pins it.

```
C = 0.373 + 0.023·(B/d) − 0.043·(Lwl/100)
```

where **d is the mean moulded draught** — *not* the depth. (Some secondary sources, including a peer-reviewed paper, print `B/D` with D = moulded depth. The IS Code text says draught.)

---

## 8. Operating procedure

**Free decay — the recommended mode (calm water, ~3 min):**

1. Enter B, d, Lwl on the **Ship** tab.
2. Lay the phone flat, screen up, on a rigid horizontal surface. *Anywhere* in the ship — the gyroscope measures the ship's rotation, the same everywhere. Not on a cushion, not in your hand, not on a table that can slide.
3. Select **Free decay**, press **Start**.
4. Start her rolling — rudder cycling, weight shift, wash from a passing vessel — then stop and **let her roll freely**.
5. Record ≥ 3 minutes. Do not touch the phone or switch away from the app.

**Seaway — expect to be refused.** In a realistic sea, most records will now be rejected. That is not the gate being fussy; it is the honest answer. A ship rolling in a seaway usually is *not* rolling at her own period, and no amount of signal processing can extract GM from a record where she never resonated.

Which is exactly why the century-old instruction for a rolling-period test says: **do it in calm water, and let her roll freely.** The simulation just put a number on why.

**Then calibrate.** The first GOOD record in a condition whose GM you trust turns this from a novelty into an instrument.

---

## 9. When the method is simply invalid

| condition | why it breaks | detected? |
|---|---|---|
| Ship rolling at the wave period | she never resonated | **yes** — sea-lock veto |
| Two swell systems / swell-driven roll | one peak is the sea | **yes** — bimodality gate |
| Roll amplitude > ~12° | GZ non-linearity breaks T ∝ 1/√GM | **yes** |
| Roll amplitude < ~0.5° | sensor noise dominates | **yes** |
| Significant free surface in slack tanks | changes the effective GM | **no** |
| Active anti-roll tanks / fin stabilisers | she is no longer a free pendulum | **no** |
| Shallow water | added mass rises, period lengthens, GM under-read | **no** |

---

## 10. Build

```bash
gradle wrapper --gradle-version 8.7   # first time only
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest      # the acceptance tests
```

AGP 8.5.2 · Kotlin 1.9.24 · Compose BOM 2024.06 · minSdk 26 · targetSdk 34.

**No third-party dependencies.** The FFT, Welch PSD, band-pass, zero-crossing detector, free-decay fit, PCA and spectral-baseline estimator are all implemented from scratch in `core/`.

---

## 11. Project layout

```
app/src/main/java/com/gmestimator/
  core/Dsp.kt              FFT, Welch PSD, band-pass, zero-crossings, free-decay fit,
                           principal-axis solver, secondary-peak (bimodality) test
  core/SeaAnalyzer.kt      the sea-lock veto and the encounter relation
  core/RollRecorder.kt     sensor selection, roll-axis solution, AND the heave signal
  core/GpsTrack.kt         SOG/COG, for the encounter test
  core/PeriodEstimator.kt  the two estimates, the cross-check, the quality gates
  core/GmModel.kt          T <-> GM, IS Code coefficient, calibration, error propagation
  data/ShipProfile.kt      ship particulars + calibration points
  export/Exporter.kt       raw CSV + measurement record (Locale.US-safe)
  ui/                      Compose: Ship · Measure · Result · Calibrate

app/src/test/java/com/gmestimator/
  DspTest.kt               period recovery, swell rejection, error propagation
  SeaAnalyzerTest.kt       the sea-lock veto and the encounter test

docs/SEA_DISCRIMINATION.md  telling the ship from the sea — including what does NOT work
docs/VALIDATION.md          what the accuracy numbers mean, and what they do not

tools/verify_dsp.py         validates the period estimator
tools/sea_discrimination.py seakeeping simulator + the 200-combination ROC
tools/verify_sealock.py     proves the sea-lock threshold at the app's own resolution
```

---

## 12. Roadmap

- [ ] **Validate against a real ship with a known GM** — by far the most important open item
- [ ] Ship the spectral deconvolution (`S_roll·g²/S_az` with a smooth baseline removed): it *recovers* the natural period from swell-contaminated records instead of merely rejecting them — median GM error 1.4 %, zero catastrophic failures behind a 9 dB confidence gate
- [ ] Alternative roll-coefficient estimators (Doyère, JSRA-without-length, beam method) — all outperform the IS Code fit
- [ ] Calibration curve of f against draught, rather than a single mean
- [ ] Resonance / parametric-roll warning from the encounter period

Contributions welcome — particularly **real measurements** with an independently known GM.

---

## References

1. IMO, *International Code on Intact Stability, 2008* (MSC.267(85)), Part A, 2.3.4.
2. Shipbuilding Research Association of Japan (1982), No. 114R — the original JSRA fit.
3. R. Grin, *On empirical methods to predict the rolling period of ships*, IMDC 2024, MARIN. DOI: [10.59490/imdc.2024.881](https://doi.org/10.59490/imdc.2024.881)
4. Kato, H. (1956), *Approximate Methods of Calculating the Period of Roll of Ships*.

---

## Licence

MIT — see [LICENSE](LICENSE), including the additional notice for mariners.
