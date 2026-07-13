# Using the weather forecast — a negative result, and what it points at

**Status: investigated, measured, NOT shipped.** The idea is right. My implementation of it is
not safe. The reason it fails is the interesting part.

---

## The idea

The Master already has a forecast: sea and swell, each with **height, period and direction**.
The phone already knows **speed and course**. Together, the encounter relation

```
ω_e = ω₀ − (ω₀²/g)·U·cos(μ)
```

predicts *exactly* where every wave-driven peak will land in the roll spectrum — before you
even look at it. You would no longer have to **reject** a record because a wave sits near the
roll peak. You would know where the waves are, subtract them, and **measure the ship**.

There's also a real physical argument that the phone alone is not enough underway. The identity
the shipped sea-lock veto rests on, `S_slope = S_az/g²`, quietly **assumes zero speed**:

```
a_z    ~ ω_e²·η      (she heaves at the ENCOUNTER frequency)
slope  ~ ω₀²·η/g    (a wave's steepness is set by its ABSOLUTE frequency)
```

To turn what the accelerometer measures into what actually *forces* the roll you need ω₀ — and
ω₀ can only be recovered from ω_e if you know the wave **direction**. Which is the one thing
the forecast has and the phone does not.

---

## What the measurements say

`tools/forecast_assisted.py` — a full encounter-frequency seakeeping simulation, 40 random
ship × sea × heading × speed combinations per row:

| method | answers | catastrophic (GM error > 30 %) |
|---|---|---|
| naive peak-pick | 100 % | 54 % |
| **SHIPPED: sea-lock veto** | 8–10 % | **0 %** |
| forecast + fit, *perfect* forecast | 49 % | 16 % |
| forecast + fit, **realistic** forecast | 38 % | **47 %** |
| forecast + fit, stale forecast | 13 % | **80 %** |

**"Realistic" is a normal forecast** — peak period off by ~1.2 s, Hs by ~25 %, direction by
~20°. A 25–50 km grid, 3–6 h steps, hours old by the time it's used. That is not a pessimistic
assumption; it's Tuesday.

So the method answers **4× more records** than the shipped veto and gets roughly **half of them
catastrophically wrong**. For a stability tool that is an unambiguously bad trade. The veto
wastes records; this would wreck them.

The consistency gate I built — *does the forecast's predicted spectrum agree in shape with the
accelerometer's measured one?* — **did not save it**. It's too weak a test.

---

## Which forecast error does the damage?

This is the finding worth having. Everything else held perfect:

| error in… | answers | median GM error | catastrophic |
|---|---|---|---|
| nothing | 52 % | 9 % | 20 % |
| wave **height** | 36 % | 4 % | 30 % |
| wave **direction** | 32 % | 8 % | 22 % |
| wave **period** | 43 % | **50 %** | **58 %** |

> ### The killer is the forecast PERIOD.
> ### And the period is precisely the thing the phone already measures BETTER than the forecast does.

The accelerometer measures the **encounter spectrum directly** — that is the entire basis of
the sea-lock veto. Taking the period from a six-hour-old grid forecast instead is discarding a
good measurement in favour of a bad prediction.

Direction error, by contrast, is **nearly free** (22 % against a 20 % baseline). And direction
is exactly what the phone *cannot* get.

---

## The architecture this points at

```
PERIOD and HEIGHT   <-  the ACCELEROMETER   (the phone measures them, well)
DIRECTION           <-  the FORECAST        (the only thing the phone cannot get)
```

Use the direction **only** to map ω_e → ω₀, so the wave-slope spectrum can be computed
correctly at speed.

That inverts the dependency. The forecast stops being the source of truth and becomes a single
scalar hint. A stale forecast then costs you a *bearing*, not a *period* — and a bearing error
is nearly harmless.

**Even so**, note the top row of the sensitivity table: **20 % catastrophic with a perfect
forecast.** The gates (R², roll SNR, ill-conditioning) are not good enough on their own yet.
This needs real work before anyone trusts a GM to it.

---

## What the forecast IS safe for, today

None of these puts a number on GM, so none carries the risk above:

* **Measurement-window advisory.** Predict the encounter periods and tell the Master which
  heading and speed will give a clean measurement — waves well clear of the expected f_n, and
  beam-ish so she's actually excited. *"Come to 040° at 8 kn for twenty minutes and I can
  measure her."* This may be the most operationally valuable thing on the list.
* **Resonance / parametric-roll warning.** T_e ≈ T_n (synchronous) or T_e ≈ T_n/2 (parametric).
  Pure safety value, no GM claim attached.
* **Following-sea ill-conditioning flag.** `dω_e/dω₀ → 0`: the encounter transform goes
  singular and several different waves arrive at the same encounter frequency. Detected and
  validated here, and worth telling the operator regardless of which method is used.
* **GM discrepancy alarm** against the loading computer — subject to the rule below.

---

## The hard rule, if ship's data is ever fed in

> The calculated GM may set the **search band**, and may be **compared against** the result.
> It must **NEVER** weight, shrink, or prior the estimate.

The entire value of this instrument is that it is **independent** of the loading computer. An
estimator that leans on the calculated GM will happily confirm a misdeclared cargo — which is
exactly the failure it exists to catch.

**Independence is the product.**
