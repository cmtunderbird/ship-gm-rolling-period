package com.gmestimator.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ============================================================================================
 * WHAT THE MASTER'S FORECAST IS ALLOWED TO DO HERE
 * ============================================================================================
 *
 * The Master already has sea and swell: height, period and direction. The phone has speed and
 * course. Together the encounter relation says exactly where every wave will land in the roll
 * spectrum. That is real information and we were ignoring it.
 *
 * But it is ALSO information that can wreck this instrument, and I measured how. Feeding a
 * forecast-derived excitation spectrum into a deconvolution and reading GM off the result gave
 * 47% catastrophic errors at REALISTIC forecast quality - four times more answers than the
 * shipped veto, and half of them badly wrong (docs/FORECAST_ASSISTED.md). The killer is the
 * forecast PERIOD, which is precisely the thing the accelerometer already measures better than
 * any 6-hour-old grid.
 *
 * So there is one absolute rule in this file:
 *
 *      *** NOTHING HERE TOUCHES GM. ***
 *
 * GM comes from the measured roll period and nothing else. The forecast is used only to:
 *
 *   1. LABEL the peaks    - "that 23 s peak is your swell, at 23.4 s encounter"
 *   2. WARN               - synchronous roll, parametric roll (IMO MSC.1/Circ.1228 territory)
 *   3. ADVISE A WINDOW    - which heading and speed will let you take a clean decay test
 *   4. FLAG ill-conditioning - following seas, where the encounter transform goes singular
 *
 * (1) and (4) make the existing sea-lock veto smarter. (2) and (3) are pure safety value with
 * no GM claim attached. None of them can produce a wrong number, because none of them produces
 * a number at all.
 *
 * A NOTE ON (3). The advisory looks for the heading that MINIMISES wave-driven roll, because a
 * free-decay test needs the sea to leave her alone. It deliberately does NOT hunt for the
 * heading that makes her resonate hardest - that would be steering a ship into synchronous
 * rolling to improve a measurement, which is an appalling trade. And it is advice about a
 * MEASUREMENT, never about navigation: the Master decides where the ship goes.
 */
object ForecastAdvisor {

    private const val G = 9.80665
    private const val KN = 0.514444
    private const val ZETA = 0.05          // typical roll damping, for the RAO weighting only

    /**
     * SPEED OVER GROUND IS THE WRONG SPEED.
     *
     * The encounter relation needs speed relative to the WAVE MEDIUM - i.e. speed through the
     * water. GPS gives speed over GROUND. In a current the two differ, and I was feeding it the
     * wrong one: a 3 kn current unnoticed shifts the predicted encounter period by 5-10%.
     *
     * That is inside the 25% labelling tolerance, so it never produced a wrong label - but it
     * is a real, unmodelled bias and it does not belong hidden in an assumption. If the Master
     * knows the current, he can now say so.
     */
    fun speedThroughWaterKn(sogKn: Double, currentSetKn: Double): Double =
        (sogKn - currentSetKn).coerceAtLeast(0.0)

    /** Exactly what a forecast gives you, per system. */
    data class WaveSystem(
        val label: String,             // "Sea" / "Swell"
        val hsM: Double,               // significant height [m]
        val tpS: Double,               // peak period [s]
        val fromBearingDeg: Double     // direction the waves come FROM, TRUE
    ) {
        fun isSet() = hsM > 0.0 && tpS > 1.0
    }

    data class Encounter(
        val system: WaveSystem,
        val relBearingDeg: Double,     // seas FROM, relative to the bow: 0 head, 90 beam, 180 following
        val encounterPeriodS: Double,
        val rollWeight: Double,        // how hard this system rolls her, 0..1 (sin^2 of relative bearing)
        val illConditioned: Boolean    // following-sea singularity: dw_e/dw_0 -> 0
    )

    fun relBearing(fromBearingDeg: Double, cogDeg: Double): Double {
        var d = (fromBearingDeg - cogDeg) % 360.0
        if (d < 0) d += 360.0
        if (d > 180.0) d = 360.0 - d
        return d                        // 0 = dead ahead, 180 = dead astern
    }

    /**
     * w_e = w_0 + (w_0^2/g) * U * cos(mu_from)
     *
     * mu_from = bearing the seas come FROM, relative to the bow. Head sea (0 deg) SHORTENS the
     * encounter period; following (180 deg) LENGTHENS it. See SeaAnalyzer.encounterPeriod for
     * why the sign is written this way - I got it backwards once and a unit test caught it.
     */
    fun encounter(sys: WaveSystem, cogDeg: Double, sogKn: Double): Encounter {
        val mu = relBearing(sys.fromBearingDeg, cogDeg)
        val te = SeaAnalyzer.encounterPeriod(sys.tpS, sogKn * KN, mu)
        val s = sin(Math.toRadians(mu))
        val w0 = 2 * Math.PI / sys.tpS
        // the encounter transform is singular where 1 - 2 w0 U cos(travel)/g -> 0.
        // travel direction = from + 180, so cos(travel) = -cos(from).
        val jac = abs(1.0 + 2.0 * w0 * (sogKn * KN) * cos(Math.toRadians(mu)) / G)
        return Encounter(sys, mu, te, s * s, jac < 0.25)
    }

    // ---------------------------------------------------------------- 1. LABEL A PEAK

    /**
     * Does this roll peak coincide with a forecast wave? If it does, it is the SEA. If no
     * forecast system lands anywhere near it, that is real evidence it is the SHIP.
     *
     * Tolerance is wide (25%) on purpose: forecast periods are routinely 1-2 s out, and a
     * tight window would simply be a lie about how well we know where the waves are.
     */
    fun labelPeak(
        periodS: Double,
        systems: List<WaveSystem>,
        cogDeg: Double,
        sogKn: Double,
        tol: Double = 0.25
    ): String? {
        if (periodS.isNaN() || cogDeg.isNaN()) return null
        for (s in systems.filter { it.isSet() }) {
            val e = encounter(s, cogDeg, sogKn)
            if (e.encounterPeriodS.isNaN()) continue
            if (abs(e.encounterPeriodS - periodS) / periodS < tol) {
                return "${s.label} (${s.tpS.f1()} s, from ${s.fromBearingDeg.f0()}°) meets you at " +
                    "${e.encounterPeriodS.f1()} s — the same period as this roll peak. " +
                    "That is the sea, not the ship."
            }
        }
        return null
    }

    // ---------------------------------------------------------------- 2. WARN

    data class Warning(val severe: Boolean, val text: String)

    /**
     * Synchronous and parametric roll. Pure safety output - no GM claim.
     *
     * @param naturalPeriodS the ship's roll period. May come from the loading computer's GM
     *        (it is only being used to raise a WARNING, never to compute an answer) or from a
     *        previous measurement.
     */
    fun warnings(
        naturalPeriodS: Double,
        systems: List<WaveSystem>,
        cogDeg: Double,
        sogKn: Double
    ): List<Warning> {
        val out = ArrayList<Warning>()
        if (naturalPeriodS.isNaN() || naturalPeriodS <= 0 || cogDeg.isNaN()) return out

        for (s in systems.filter { it.isSet() }) {
            val e = encounter(s, cogDeg, sogKn)
            if (e.encounterPeriodS.isNaN()) continue
            val te = e.encounterPeriodS

            // SYNCHRONOUS ROLLING: encounter period ~ natural period, and she is beam-on enough
            // for the waves to actually roll her.
            if (abs(te - naturalPeriodS) / naturalPeriodS < 0.15 && e.rollWeight > 0.15) {
                out.add(
                    Warning(
                        true,
                        "SYNCHRONOUS ROLLING. The ${s.label.lowercase()} meets you every " +
                            "${te.f1()} s and your roll period is ${naturalPeriodS.f1()} s. Every " +
                            "wave arrives in step with the roll and the angles build. Alter course " +
                            "or speed."
                    )
                )
            }

            // PARAMETRIC ROLL: encounter period ~ HALF the natural period. Classically dangerous
            // in head and following seas, where it can build from nothing in a few minutes.
            val half = naturalPeriodS / 2.0
            if (abs(te - half) / half < 0.15) {
                val bowOrStern = e.relBearingDeg < 45 || e.relBearingDeg > 135
                out.add(
                    Warning(
                        bowOrStern,
                        "PARAMETRIC ROLL RISK. The ${s.label.lowercase()} meets you every " +
                            "${te.f1()} s, which is about HALF your roll period " +
                            "(${naturalPeriodS.f1()} s)" +
                            if (bowOrStern) ", and she is nearly head-on or nearly following - the " +
                                "classic set-up. Roll can build from almost nothing in minutes."
                            else ". Watch her."
                    )
                )
            }

            if (e.illConditioned) {
                out.add(
                    Warning(
                        false,
                        "The ${s.label.lowercase()} is nearly overtaking you at your own speed. " +
                            "Several different waves arrive at the same encounter period, so any " +
                            "spectral reading in this condition is unreliable."
                    )
                )
            }
        }
        return out
    }

    // ---------------------------------------------------------------- 3. ADVISE A WINDOW

    data class Window(
        val cogDeg: Double,
        val sogKn: Double,
        val rollScore: Double,          // predicted wave-driven roll, arbitrary units. Lower = calmer.
        val worstEncounterS: Double     // the encounter period closest to her natural period
    )

    /**
     * Where should she steer, and how fast, to take a CLEAN free-decay measurement?
     *
     * We want the sea to LEAVE HER ALONE: minimise the wave-driven roll, so that what we record
     * is her own ring-down and not the weather. That means:
     *
     *   - head or following seas (little transverse wave slope: sin^2(mu) small), and
     *   - encounter periods FAR from her natural period (off resonance).
     *
     * We deliberately do NOT search for the heading that makes her roll hardest. That would be
     * advising a Master to steer into synchronous rolling to improve a measurement, and no
     * measurement is worth that.
     *
     * THIS IS ADVICE ABOUT A MEASUREMENT, NOT ABOUT NAVIGATION.
     */
    fun bestWindows(
        naturalPeriodS: Double,
        systems: List<WaveSystem>,
        speedsKn: List<Double>,
        n: Int = 3
    ): List<Window> {
        val live = systems.filter { it.isSet() }
        if (live.isEmpty() || naturalPeriodS.isNaN() || naturalPeriodS <= 0) return emptyList()

        val all = ArrayList<Window>()
        for (sog in speedsKn) {
            var cog = 0.0
            while (cog < 360.0) {
                var score = 0.0
                var worst = Double.MAX_VALUE
                for (s in live) {
                    val e = encounter(s, cog, sog)
                    if (e.encounterPeriodS.isNaN()) { score += 1e6; continue }
                    if (e.illConditioned) score += 1e4          // never recommend this
                    // roll response = wave slope x transverse component x the roll RAO
                    val r = naturalPeriodS / e.encounterPeriodS
                    val rao = 1.0 / sqrt(
                        (1 - r * r) * (1 - r * r) + (2 * ZETA * r) * (2 * ZETA * r)
                    )
                    score += s.hsM * s.hsM * e.rollWeight * rao
                    val d = abs(e.encounterPeriodS - naturalPeriodS)
                    if (d < worst) worst = e.encounterPeriodS
                }
                all.add(Window(cog, sog, score, worst))
                cog += 10.0
            }
        }
        return all.sortedBy { it.rollScore }.take(n)
    }

    // ---------------------------------------------------------------- 4. IS THE FORECAST STALE?

    /**
     * The phone MEASURES the encounter spectrum. The forecast PREDICTS it. If the dominant wave
     * period the accelerometer actually saw is nowhere near any predicted encounter period, the
     * forecast is stale or the ship is somewhere else - and the labelling above should not be
     * believed.
     */
    fun forecastAgrees(
        measuredWavePeriodS: Double,
        systems: List<WaveSystem>,
        cogDeg: Double,
        sogKn: Double,
        tol: Double = 0.30
    ): Boolean? {
        val live = systems.filter { it.isSet() }
        if (live.isEmpty() || measuredWavePeriodS.isNaN() || cogDeg.isNaN()) return null
        return live.any {
            val e = encounter(it, cogDeg, sogKn)
            !e.encounterPeriodS.isNaN() &&
                abs(e.encounterPeriodS - measuredWavePeriodS) / measuredWavePeriodS < tol
        }
    }

    private fun Double.f0() = String.format(java.util.Locale.US, "%.0f", this)
    private fun Double.f1() = String.format(java.util.Locale.US, "%.1f", this)
}
