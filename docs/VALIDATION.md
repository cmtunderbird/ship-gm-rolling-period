# Validation notes

## How the algorithm was validated

`tools/verify_dsp.py` is a faithful Python port of `core/Dsp.kt` and `core/PeriodEstimator.kt`
— same maths, same constants, same band edges. It was written first, and the Kotlin was written
from it. `app/src/test/java/com/gmestimator/DspTest.kt` pins the same cases as JVM unit tests,
which CI runs on every push.

There is no real-ship validation yet. That is the single most important open item on the roadmap.

## What the numbers actually mean

### Free decay is deterministic. The seaway is not.

A free-decay record is a clean, nearly deterministic signal: the ship is rung like a bell and
allowed to ring down. The estimator recovers the period to **< 1 %** and the damping ratio ζ to
**±0.001**, repeatably.

A ship rolling in an irregular sea is a **random process**. Her roll is a narrow-band response
to broadband wave forcing: the energy concentrates at her natural frequency, but the phase and
amplitude wander, and any finite record is one realisation drawn from a distribution. So the
measured period is itself a random variable.

This matters, and an early version of this README got it wrong. The first validation run reported
"seaway error < 3 %". Re-running the identical harness on a different random realisation produced
a **5.2 %** error at T = 9 s. Nothing had changed but the seed. The 3 % figure was one lucky draw
reported as if it were a bound.

The honest statement:

| mode | period error | resulting GM error |
|---|---|---|
| **Free decay**, 3-min record | < 1 % | < 2 % |
| **Seaway**, 20-min record | typically 1–3 %, **up to ~5 %** on a single realisation | up to ~10 % |

Remember that `GM = (f·B/T)²` doubles every relative error. A 5 % period error is a 10 % GM error
— on its own, before the roll coefficient's ~8 % is even added.

**This is a large part of why free decay is the recommended mode.** It is not merely more
convenient; it is a fundamentally better-conditioned measurement.

If you must work from a seaway record, take several on different headings and look at the spread.
The spread *is* the uncertainty.

## Test tolerances, and why they are what they are

| test | tolerance | rationale |
|---|---|---|
| Pure sinusoid | 1 % | Deterministic. Any failure here is a real bug. |
| Free decay | 2–3 % | Deterministic. The recommended procedure must work every time. |
| Irregular seaway | 6–7 % | A distribution, not a constant. A tight bound here would only be testing the RNG seed. |
| Swell rejection | must reject | **Non-negotiable.** A missed swell is a confidently wrong GM in the dangerous direction. |
| Clean seaway not falsely rejected | competing ratio < 0.25 | Observed 0.00–0.06 across realisations; the gate sits in a wide empty gap. |

The seaway tolerance was deliberately widened rather than the seed pinned to a passing draw.
A test that passes only for one seed is not a test.

## Reproducing

```bash
python3 tools/verify_dsp.py        # needs numpy
./gradlew :app:testDebugUnitTest   # the same cases, in Kotlin
```

Expect the seaway numbers to move a little between runs of the Python harness if you change the
seed. That is the point.
