# The first real ship, and the four things it broke

**m/v Androklis.** B = 27.4 m, d = 8.75 m, Lwl = 170 m. A 190-second free-decay record, roll
period around 16 s, amplitude about 1.2°.

The app reported:

```
T = 16.40 ± 0.88 s     quality EXCELLENT     GM = 1.54 m
```

The stability booklet requires **1.60 m**. So at first glance: a success. A phone, a gyroscope,
and a number that lands almost exactly on the loading computer's.

It was not a success. It was **four faults wearing a plausible answer**, and not one of them was
visible to the entire simulation suite — the 200-combination ROC, the swell-rejection tests, the
free-decay regression, none of it.

This document exists because the failures are more valuable than the number.

---

## 1. The quality metric was lying

The report said **"methods agree to 1.7% — EXCELLENT"**.

That is not evidence, and I should have seen it earlier — I had already written the sentence
*"two methods agreeing is not the same as two methods being right"* about the sea, and then
built a quality label that did exactly that.

The Welch PSD peak and the zero-crossing **median** are both *central-tendency estimators of the
same data*. They agree **by construction**. Agreement between two estimators of the same thing
tells you about your arithmetic, not about the ship.

What the ship was actually saying was in the spread of her own cycles:

```
18.1  18.7  13.7  17.2  16.6  18.0  10.2  16.5  10.3  16.5  19.0   seconds
```

**A 21% scatter.** It never reached the user.

**Fix:** quality is now driven by **cycle-to-cycle scatter**, not by method agreement. Above 25%
the record is refused outright. The Androklis record now reads **FAIR**, not EXCELLENT.

---

## 2. The uncertainty ignored physics

A Hann-windowed record of length *D* cannot resolve frequency better than about `2/D`. In period
terms that is

```
ΔT = (2/D) · T²
```

and it is brutal for a slow ship. 190 s at a 16 s roll is only **12 cycles**:

```
ΔT = (2/190) · 16²  =  ± 2.8 s     →     ± 38% on GM
```

The app quoted **± 0.88 s**. It printed a spectral peak as "16.20 s" — four significant figures
on a quantity worth ± 2.8. That is not a measurement, it is a decorated guess.

**Fix:** `u(T)` is now floored at the resolution limit of the record, and the recommended
free-decay length went from **180 s to 480 s**. For a 16 s ship you want ~25 cycles: eight
minutes, not three.

Honest restatement of the same record:

| | T | GM |
|---|---|---|
| as reported | 16.40 ± 0.88 s | 1.54 m (± 19%) |
| **honestly** | **16.4 ± 2.8 s** | **1.54 m (0.96 – 2.13 m, ± 38%)** |
| booklet | | 1.60 m |

The booklet value sits comfortably inside. But the record **cannot pin it**, and saying so is
the whole job.

---

## 3. The report contradicted itself

It printed, verbatim:

```
  SEA-LOCK                     YES - THIS IS A WAVE, NOT THE SHIP
  ...
  Quality                     EXCELLENT
  GM = 1.54 m
```

The veto is correctly **scoped to SEAWAY mode** — a free decay is a ring-down under her own
restoring moment, not a driven response, so the veto rightly did not fire. But `SeaAnalyzer` ran
anyway and its message was printed **unconditionally**.

This is worse than a cosmetic bug. **A warning that appears next to a confident answer teaches
the operator to ignore warnings** — and one day that warning will be right.

**Fix:** the sea-lock verdict is only printed in SEAWAY. In FREE_DECAY the sea is reported as
*context* ("not applicable to a free-decay test").

---

## 4. The lever arm — the real discovery

The accelerometer showed **7.0 dB of "wave" energy sitting exactly on the roll peak**.

My simulation said that could not happen. It had measured the two populations and found a clean,
empty gap:

| | accelerometer excess at the roll peak |
|---|---|
| genuine resonance | 0.9 ± 1.4 dB (max 3.9) |
| sea-driven peak | 11.2 ± 1.9 dB (min 7.2) |

**7.0 dB lands in the middle of the gap I had declared empty.**

### Why

The accelerometer **is not in the sea**. It is lying on the ship, and almost never on the roll
axis. A phone at transverse lever arm `y` from the roll axis rises and falls as she rolls:

```
z  ≈  y · φ          ⇒          a_z  ≈  y · φ̈  =  −y · ω² · φ
```

> ### The ship's own roll manufactures vertical acceleration at exactly f_n — the one frequency where the sea-lock veto assumes the accelerometer is silent.

At y ≈ 10 m, φ = 1.2°, T = 16 s, that is ~0.03 m/s² against a measured total of 0.07 m/s².
Easily a 7 dB bump.

### Why simulation could never have found it

Because **I had implicitly put the phone at y = 0**, on the roll axis. My simulator generated
`a_z` as pure wave-driven heave. The lever arm did not exist in the model, so it could not
appear in the results. No amount of Monte-Carlo over ships and seas would have produced it.

Only a real phone, on a real deck, off the centreline, could.

### The fix

Exact, because `φ̈` is not a mystery — we *measured* `φ`. Regress `a_z` on `φ̈`, subtract the
coherent part, and run the veto on the **residual**.

The regression coefficient **is the lever arm, in metres**, and comes out free. The app now
reports it.

---

## 5. And an export gap

The **heave channel was never written to the CSV**. So when the first real record came back with
a puzzling sea-lock flag, the one signal needed to explain it could not be re-examined. I had to
infer the lever-arm mechanism from the roll alone.

**Export what you rely on.** The record now writes `*_heave.csv` alongside the tilt.

---

## What this cost, and what it bought

The simulation work was not wasted — it found the swell trap and the wind-sea hole, both real,
both dangerous, neither visible from a deck. But it has a hard ceiling:

> **A simulation can only surprise you with consequences of things you already put in the model.**

The lever arm was not in the model. Neither was a 12-cycle record, nor an operator doing a
decay test in a 1.1 m sea. The ship supplied all three in one afternoon.

**Next record:** sheltered water, roll her to 5–8°, run it for 8–10 minutes, and note roughly
where the phone sat relative to the centreline — the fitted lever arm can then be checked against
a tape measure.
