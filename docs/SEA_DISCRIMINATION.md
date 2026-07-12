# Telling the ship apart from the sea

> **The short version.** A ship does not roll at her own period just because we would like her
> to. She rolls at whatever period the waves push her at. The instrument must be able to tell
> the difference — and the accelerometer, which we were already carrying and ignoring, is the
> only witness that can.

---

## 1. The roll spectrum is a product, not a sum

```
S_roll(f)  =  |H(f)|²   ×   S_exc(f)
              ↑ the SHIP    ↑ the SEA
              a resonance   whatever the waves are doing at that frequency
              at f_n
```

Peak-picking `S_roll` conflates the two. If the sea has a big line at 24 s, so does the roll,
and we compute GM from the sea.

## 2. The phone is already carrying a sea sensor

In deep water, a body that follows the surface sees a vertical acceleration

```
a_z = −ω²·η
```

and the surface **slope** — which is what actually forces the roll — is

```
α = k·η = (ω²/g)·η
```

Divide, and the wave amplitude cancels:

```
S_slope(f) = S_az(f) / g²
```

**The wave-slope spectrum that forces the ship's roll IS the vertical-acceleration spectrum.**

## 3. The discriminator

> **A SWELL IS AN INPUT.** It appears in every wave-driven channel: roll *and* heave.
>
> **A RESONANCE IS THE SHIP.** It appears *only* in the roll. The ship puts no energy into the
> sea, so the accelerometer never sees a bump at f_n.

Take the peak of the roll spectrum. Look at the same frequency in the vertical acceleration.
A twin there means you are measuring the sea. No ship model, no wave theory, no calibration —
two spectra and a lookup.

---

## 4. The hole this was built to plug

This is the part that made it urgent. It is **not** the swell case.

**A tender ship (T_n = 15 s) in a plain wind sea with no swell whatsoever, Tp = 8 s:**

| | |
|---|---|
| True natural period | 15.00 s |
| Roll spectrum peaks at | **8.03 s** — the *wave* period |
| GM would have been reported | **3.49× too LARGE** |
| Accelerometer peaks at | 8.03 s — the same place |
| Accelerometer excess there | 6.4 dB |
| Sea-lock verdict | **REJECTED** |

A JONSWAP spectrum dies as `exp(−1.25 (fp/f)⁴)` below its peak, so at 15 s there is essentially
no wave energy at all. The ship **never resonates**. She rolls at the wave period, quietly, and
the roll spectrum has **one clean peak**.

The consequences for the old code:

* the competing-peak gate sees nothing wrong — there *is* no competing peak
* the PSD and zero-crossing estimates agree **perfectly** — on the wrong number
* quality was reported as **EXCELLENT**
* GM came out 3.5× too large: **a tender ship reported as stiff**, the worst possible direction

**Two methods agreeing is not the same as two methods being right.** Both were measuring the
period the ship was *rolling at*; neither had any way of knowing that was not her own.

---

## 5. Validation

`tools/sea_discrimination.py` — 200 random ship × sea combinations (roll periods 10–25 s,
ζ 0.03–0.09, JONSWAP wind seas Tp 5–12 s and Hs 0.8–3.5 m, a swell 70% of the time at 9–30 s,
all from independent directions):

| | count | |
|---|---|---|
| Correctly flagged SEA (rejected, rightly) | 133 | 66.5 % |
| Correctly passed SHIP (accepted, rightly) | 12 | 6.0 % |
| Falsely flagged SEA (good record wasted) | 55 | 27.5 % |
| **MISSED sea-driven peak (accepted, wrongly)** | **0** | **0.0 %** |

* Of all genuinely sea-driven roll peaks: **100 % caught**
* Of the records it lets through: **100 % genuinely the ship**
* Of the genuinely good records: **82 % thrown away**

**Safe but blunt.** It never lets a sea-driven peak through, and pays for that by discarding
most of the good records too — including the harmless *synchronous* case, where a swell happens
to sit exactly on f_n and the naive answer would have been right anyway.

For a stability tool that is the correct trade. **A wasted record is an inconvenience. A wrong
GM is not.**

### Threshold

`tools/verify_sealock.py` ports `SeaAnalyzer` line-for-line and runs it at the app's *own*
resolution (fs = 25 Hz, Welch segLen = 8192), because a safety gate should not be shipped on
thresholds tuned at the convenient resolution of a research script:

| population | accelerometer excess at the roll peak |
|---|---|
| genuine resonance | **0.9 ± 1.4 dB** (max 3.9) |
| sea-driven peak | **11.2 ± 1.9 dB** (min 7.2) |

An empty 3.3 dB gap between them. The threshold sits at **3 dB** — deliberately low, biased
towards rejection: 0 % misses, 3 % false rejections.

---

## 6. The encounter test — the one that assumes nothing

```
ω_e = ω₀ − (ω₀²/g)·U·cos(μ)
```

A wave's period shifts with heading and speed. **A ship's natural roll period does not** — it
depends on GM and the mass distribution, and neither cares which way you are pointing.

> **Record twice, on courses at least 30° apart. The period that stays put is your ship. The
> period that moved is the sea.**

No wave theory, no ship model, no assumption about the spectrum. It is the only test in this
instrument that is immune to every modelling error, and **when the clever methods disagree with
it, believe it.** The app logs SOG/COG for each record and runs the comparison automatically.
GPS is optional; without it everything else still works.

---

## 7. Post-mortem: three obvious ideas that died

Worth recording, so nobody rediscovers them.

### Coherence between roll and the accelerometer — **dead**

Expected: low coherence at the ship's resonance ("the accelerometer cannot see the ship's own
mode"), high at a swell.

Wrong. A ship is a **linear filter of one wave field**. Every channel — roll, pitch, heave — is
a filtered copy of the same input, so coherence is ~1.0 *everywhere*, resonance included. The
resonance **is** wave-driven; it is just wave-driven *with amplification*. Coherence asks "is
this explained by the waves?", and at f_n the answer is yes.

### Kurtosis / envelope statistics — **dead**

Expected: a swell is a near-sinusoid (kurtosis 1.5, envelope CV ≈ 0.1); a resonance is
narrow-band Gaussian (kurtosis 3.0, CV ≈ 0.52).

Wrong. **A real swell is a narrow-band *random* process, so it is Gaussian too.** Kurtosis ~3,
CV ~0.5 — statistically identical to the resonance. An earlier test of mine modelled the swell
as a pure sine wave, which flattered this idea considerably. It does not survive a realistic
sea.

### Raw deconvolution `S_roll·g²/S_az` — **works, but not shipped**

The algebra is right and it *does* cancel the swell. But it also divides by the **heave RAO**,
which rolls off at high frequency and plants a fake peak at ~5 s. It only works once a *smooth
baseline* is removed from the ratio — the roll resonance is then the only sharp feature left,
because `|H_heave|²` is smooth (heave is heavily damped) while `|H_roll|²` is sharp.

With a 9 dB confidence gate it recovers the true natural period even when a swell dominates the
roll spectrum (median GM error 1.4 %, zero catastrophic failures), and it rescues records the
blunt veto would simply discard. It is kept in `tools/` as the next step, not shipped: the veto
is simpler, safer, and easier to explain to someone standing on a bridge wing.

---

## 8. What this means for SEAWAY mode

Be blunt about it: **in a realistic sea, most records will now be refused.** That is not the
gate being fussy — it is the honest answer. A ship rolling in a seaway usually is *not* rolling
at her own period, and there is no way to extract GM from a record where she never resonated.

Which is, in the end, exactly why the century-old instruction for a rolling-period test says:
**do it in calm water, and let her roll freely.** The simulation just put a number on why.
