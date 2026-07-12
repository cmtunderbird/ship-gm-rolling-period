# GM by Rolling Period

**Estimate a ship's transverse metacentric height (GM) from her natural rolling period, using nothing but the IMU already inside an ordinary Android phone.**

![build](https://github.com/cmtunderbird/ship-gm-rolling-period/actions/workflows/build.yml/badge.svg)
![licence](https://img.shields.io/badge/licence-MIT-blue)
![platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)

Reference device: **Xiaomi Redmi Note 14 Pro** (accelerometer + gyroscope, both confirmed present). Runs on any Android 8.0+ phone that has a gyroscope.

> **This is an estimate, not an instrument of record.** It is not type-approved, not class-approved, and has not yet been validated against a real ship. It is not a substitute for the vessel's approved loading computer, stability booklet, or an inclining experiment, and must not be the sole basis for a stability decision. See [Limitations](#8-when-the-method-is-simply-invalid).

---

## 1. What it does

A ship is a torsional pendulum. Her natural roll period depends on how stiff she is, and stiffness is GM:

```
T = f · B / √GM          =>          GM = ( f · B / T )²
```

Measure `T` accurately and you have an independent, physical check on the GM your loading computer is claiming. Mariners have done this for a century with a stopwatch. The phone does it better — but only if three specific traps are avoided, and most of this project is about those traps.

---

## 2. The measurement chain

```
gyroscope + accelerometer                 (50 Hz, TYPE_GAME_ROTATION_VECTOR)
        │
        ├─ quaternion → gravity vector in the device frame
        │
        ├─ 2-D tilt vector (tiltX, tiltY) in the PHONE's frame        ← heading still unknown
        │
        ├─ band-pass 0.022 – 0.33 Hz  (roll periods 3 – 45 s)
        │
        ├─ principal-component analysis → the roll axis               ← heading now solved
        │
        └─ scalar roll angle φ(t), uniform 25 Hz
                │
                ├─── Welch PSD → dominant peak → parabolic interp → T_psd
                │                └─ secondary-peak check  ─────────────┐
                │                                                      │
                └─── hysteresis zero-up-crossings → median → T_zc      │  quality gates
                                                                       │
                        cross-check |T_psd − T_zc| / T ────────────────┘
                                        │
                                        ▼
                              GM = ( f · B / T )²  ±  propagated uncertainty
```

---

## 3. Three design decisions that matter

### 3.1 The gyroscope, not the accelerometer

The obvious approach — read the tilt angle from the gravity vector — is **wrong on a ship**.

An accelerometer measures *gravity minus acceleration*. A phone sitting anywhere off the roll axis picks up centripetal and tangential acceleration from the roll itself, plus sway, yaw and heave. Those contaminating accelerations are **at the roll frequency**, so no filter can remove them: they inflate the apparent roll amplitude and bias the apparent vertical.

A gyroscope measures rigid-body angular rate. It does not care where in the ship the phone is, and it is immune to linear acceleration. Its only weakness — slow bias drift — sits far below the roll band, which is exactly where the accelerometer *is* trustworthy.

So the app uses `TYPE_GAME_ROTATION_VECTOR`: gyro fused with accelerometer, **magnetometer deliberately excluded** (a steel ship's magnetic field is useless for attitude). Fallbacks, in order: `TYPE_ROTATION_VECTOR` → a hand-rolled complementary filter (τ = 20 s) → accelerometer-only, which the app flags as degraded.

### 3.2 The phone can be laid down at any angle

You should not have to align the phone with the centreline, and you cannot use the compass.

For small angles the phone's 2-D tilt vector is just the ship's (roll, pitch) vector rotated by the unknown heading ψ. Roll amplitude is normally several times pitch amplitude, so the **principal axis of the band-passed 2-D tilt series *is* the roll axis**. The app finds it by eigen-decomposition and projects onto it.

The eigenvalue ratio (`axisDominance`) reports how cleanly roll separated from pitch. Below 0.70 the projection is ambiguous and the record is rejected. A manual override (fore/aft or athwartships) is available.

### 3.3 Two independent period estimates, cross-checked

| | method | strength | weakness |
|---|---|---|---|
| **T_psd** | Welch PSD, dominant peak, parabolic sub-bin interpolation | robust in irregular seas, uses every sample | needs a long record |
| **T_zc** | hysteresis zero-up-crossing counting, interpolated crossing instants, median over all cycles | this is the classic roll test, done properly | noisy in a confused sea |

Their **agreement** is the primary quality indicator:

| agreement | quality |
|---|---|
| < 3 % | EXCELLENT |
| < 5 % | GOOD |
| < 10 % | FAIR |
| ≥ 10 % | POOR — rejected |

---

## 4. The trap this instrument is built to avoid

**A ship in a regular swell rolls at the wave encounter period, not at her own natural period.**

Feed that peak into `GM = (f·B/T)²` and you get a *confidently wrong* answer — and wrong in the dangerous direction, because a long swell makes a stiff ship look tender.

Synthetic test, ship's true natural period 15 s, roll 2°:

| swell | naive spectral peak | implied GM error | caught? |
|---|---|---|---|
| 24 s, 3° | 24.0 s | GM **2.5× too small** | ✅ bimodality gate |
| 9 s, 3° | 9.0 s | GM **2.8× too large** | ✅ bimodality gate |
| 11 s, 2° | 11.0 s | GM **1.9× too large** | ✅ bimodality gate |

The two-method cross-check alone **nearly missed** the 24 s case (9.4 % disagreement, just under the 10 % threshold). That is not good enough for a stability tool.

The defence is a **topographically prominent secondary-peak test**: if a second peak in the roll band carries more than 25 % of the primary's power, *one of the two is the sea* and the spectrum alone cannot say which. The record is rejected and both candidate periods are shown to the operator.

Validated margins on synthetic records:

| record type | competing-peak ratio |
|---|---|
| clean resonant roll (irregular sea) | **0.00 – 0.04** |
| free decay | **0.00** |
| swell-contaminated | **0.30 – 0.79** |

The 0.25 threshold sits in a wide, empty gap between the two populations.

**This is also why FREE DECAY is the recommended mode.** In calm water, roll the ship with the rudder or a weight shift, let her go, and the record contains nothing but her own natural period — plus, as a bonus, the roll damping ratio ζ from the logarithmic decrement.

---

## 5. Validation

`tools/verify_dsp.py` is a faithful Python port of the Kotlin algorithm, run against synthetic roll records before a line of the app was written. `app/src/test/java/com/gmestimator/DspTest.kt` pins the same cases as JVM unit tests, run on every push by CI.

| case | result |
|---|---|
| Pure sinusoid, T = 8 … 26 s | error **< 0.1 %** |
| Free decay, ζ = 0.03 … 0.08, 3-min record | T error **< 0.8 %**, ζ recovered to **±0.001** |
| Irregular seaway, 20-min record | T error **< 3 %** |
| Static list 4.5° + gyro drift + 8 Hz engine vibration | T error **< 1 %** |
| Roll amplitude down to 0.25° (at the noise floor) | T error **< 3.5 %** |
| Swell-contaminated seaway | **rejected**, no dangerous misses |
| Clean seaway, T = 9 … 28 s | **accepted**, no false rejections |
| Principal-axis solver, phone at 5 headings | roll axis recovered to **> 0.99** dot product |

Reproduce with:

```bash
python3 tools/verify_dsp.py        # needs numpy
./gradlew :app:testDebugUnitTest   # the same cases, in Kotlin
```

---

## 6. Where the error actually comes from

`GM = (f·B/T)²` — so **every relative error doubles**:

```
u(GM)/GM = 2 · √[ (u(T)/T)² + (u(f)/f)² ]
```

Example ship, B = 32.2 m, d = 11.0 m, Lwl = 190 m, true GM = 1.20 m (→ T ≈ 21.1 s):

| u(T)/T | u(f)/f | u(GM)/GM | GM range |
|---|---|---|---|
| 1 % | 0 % | 2 % | 1.18 – 1.22 m |
| 1 % | 5 % | 10 % | 1.08 – 1.32 m |
| **1 %** | **8 %** (IS Code) | **16 %** | **1.01 – 1.39 m** |
| 5 % | 8 % | 19 % | 0.97 – 1.43 m |

### The phone is not the limiting factor. The roll coefficient is.

The IMU measures the period to ~1 %. The IS Code roll coefficient carries ~8 % scatter — and published validation (Grin, IMDC 2024) shows the JSRA fit adopted by IS 2008 is the **worst performing** of the eight common estimators, with errors of several seconds on large ships.

### Which is why calibration is the whole point

Take one measurement in a condition whose GM you already trust — straight after an inclining experiment, or a condition the loading computer has a good GM for — and the app solves

```
f = T · √GM / B
```

for **your** ship. Uncertainty on f drops from ~8 % to ~2–3 %, and GM uncertainty from ±16 % to about ±5 %. Every later measurement then inherits your ship's own coefficient instead of a generic formula.

Store several calibration points across draughts and the app uses their mean and reports their spread as the honest uncertainty.

---

## 7. Conventions — read this before touching the maths

Two forms of the equation circulate, differing by a factor of two:

```
IMO / IS Code 2008 (2.3.4):   T = 2·C·B / √GM     C ≈ 0.40 – 0.45
Common rule of thumb:         T =   f·B / √GM     f ≈ 0.75 – 0.90
```

They are the same equation with **f = 2C**. The app stores **only f** and derives C for display, so the two can never get mixed up. `GmModel.isCodeF()` returns `2 * isCodeC()`, and a unit test pins that relationship.

The IS Code coefficient itself:

```
C = 0.373 + 0.023·(B/d) − 0.043·(Lwl/100)
```

where **d is the mean moulded draught** — *not* the depth. (Some secondary sources, including at least one peer-reviewed paper, print `B/D` with D = moulded depth. The IS Code text says draught.)

---

## 8. When the method is simply invalid

The rolling-period method assumes small-amplitude, lightly damped, **free** roll of a rigid ship in deep water. The app detects some violations and rejects the record; it **cannot** detect the rest, so the operator must:

| condition | why it breaks |
|---|---|
| Significant free surface in slack tanks | changes the effective GM the ship rolls about |
| Active anti-roll tanks or fin stabilisers | the ship is no longer a free pendulum |
| Shallow water | roll added mass rises, period lengthens, GM is under-read |
| Roll amplitude > ~12° | GZ non-linearity breaks T ∝ 1/√GM — **gate: rejected** |
| Roll amplitude < ~0.5° | sensor noise dominates — **gate: rejected** |
| Ship not free to roll (alongside, moored, in ice) | no natural roll to measure |
| Two swell systems / rolling at encounter period | **gate: rejected** (section 4) |

---

## 9. Operating procedure

**Preferred — free decay (calm water, ~3 min):**

1. Enter B, d, Lwl on the **Ship** tab.
2. Lay the phone flat, screen up, on a rigid horizontal surface. *Anywhere* in the ship — the gyroscope measures the ship's rotation, which is the same everywhere. Not on a cushion, not in your hand, not on a table that can slide.
3. Select **Free decay**, press **Start**.
4. Start the ship rolling — rudder cycling, weight shift, wash from a passing vessel — then stop and let her roll freely.
5. Record ≥ 3 minutes. Do not touch the phone. Do not switch away from the app (Android throttles sensors for backgrounded apps; the screen is held on for this reason).
6. **Stop & analyse.**

**Seaway (rolling at sea, ~20 min):** same, but select **Seaway** and expect the app to reject the record if a swell is present. If it does, change heading and re-measure.

**Then calibrate.** The first time you get a GOOD or EXCELLENT record in a condition whose GM you trust, go to the **Calibrate** tab and enter that GM. This is the step that turns the app from a novelty into an instrument.

---

## 10. Build

```bash
# Open the project in Android Studio (Hedgehog or newer) and let it sync, or:
gradle wrapper --gradle-version 8.7   # first time only, to generate ./gradlew
./gradlew :app:assembleDebug          # APK -> app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest      # the acceptance tests
```

AGP 8.5.2 · Kotlin 1.9.24 · Compose BOM 2024.06 · minSdk 26 · targetSdk 34.

**No third-party dependencies.** The FFT, Welch PSD, band-pass filter, zero-crossing detector, free-decay fit and PCA are all implemented from scratch in `core/Dsp.kt` (~480 lines). Nothing but AndroidX and Compose.

CI builds the APK and runs the unit tests on every push; the debug APK is uploaded as a build artifact.

---

## 11. Project layout

```
app/src/main/java/com/gmestimator/
  core/Dsp.kt              FFT, Welch PSD, band-pass, zero-crossings, free-decay fit,
                           principal-axis solver, secondary-peak (bimodality) test
  core/PeriodEstimator.kt  the two estimates, the cross-check, the quality gates
  core/GmModel.kt          T <-> GM, IS Code coefficient, calibration, error propagation
  core/RollRecorder.kt     sensor selection, tilt extraction, roll-axis solution, resampling
  data/ShipProfile.kt      ship particulars + calibration points (SharedPreferences + JSON)
  export/Exporter.kt       raw CSV + human-readable measurement record, Locale.US-safe
  ui/                      Compose: Ship · Measure · Result · Calibrate

app/src/test/java/com/gmestimator/
  DspTest.kt               acceptance tests, incl. the swell-rejection case

tools/
  verify_dsp.py            Python port of the algorithm; reproduces every number above
```

---

## 12. Roadmap

- [ ] Validate against a real ship with a known GM (the single most important open item)
- [ ] Support alternative roll-coefficient estimators (Doyère, JSRA-without-length, beam method) — all of which outperform the IS Code fit in published validation
- [ ] Log a calibration curve of f against draught, rather than a single mean
- [ ] Optional foreground service so the screen can be turned off during long seaway records
- [ ] Import/export ship profiles between devices

Contributions welcome — particularly **real measurements** with an independently known GM.

---

## References

1. IMO, *International Code on Intact Stability, 2008* (Resolution MSC.267(85)), Part A, 2.3.4 — severe wind and rolling (weather) criterion. Source of `T = 2CB/√GM` and the C formula.
2. Shipbuilding Research Association of Japan (1982), No. 114R, *IMCO research to new stability rules* — the original JSRA fit that the IS Code adopted.
3. R. Grin, *On empirical methods to predict the rolling period of ships*, 15th International Marine Design Conference (IMDC), Amsterdam, 2024. MARIN. — benchmarks eight roll-period estimators against full-scale measurements on two container ships; the source of the accuracy figures used in the error budget above. DOI: [10.59490/imdc.2024.881](https://doi.org/10.59490/imdc.2024.881)
4. Kato, H. (1956), *Approximate Methods of Calculating the Period of Roll of Ships*, J. Soc. Naval Architects of Japan, Vol. 89.

---

## Licence

MIT — see [LICENSE](LICENSE), including the additional notice for mariners.
