package com.gmestimator.core

import kotlin.math.sqrt

/**
 * Roll-period -> GM model.
 *
 * Canonical relation used throughout the app:
 *
 *      T = f * B / sqrt(GM)        =>        GM = ( f * B / T )^2
 *
 * where
 *      T  = natural roll period [s]
 *      B  = moulded breadth [m]
 *      f  = dimensionless roll coefficient [-]
 *
 * WARNING ABOUT CONVENTIONS. Two forms of this equation circulate in the literature:
 *
 *      IMO / IS Code 2008 (2.3.4):   T = 2*C*B / sqrt(GM),  C ~ 0.40..0.45
 *      "Rule of thumb":              T =   f*B / sqrt(GM),  f ~ 0.75..0.90
 *
 * They are the same equation with f = 2C. This app stores ONLY f. When the user selects the
 * IS Code source we compute C from the code formula and set f = 2C, so the two never get mixed.
 */
object GmModel {

    enum class FSource { IS_CODE, MANUAL, CALIBRATED }

    /**
     * IS Code 2008, Part A, 2.3.4 (severe wind and rolling / weather criterion):
     *
     *      C = 0.373 + 0.023 * (B/d) - 0.043 * (Lwl/100)
     *
     * B   = moulded breadth [m]
     * d   = mean moulded draught [m]
     * Lwl = waterline length [m]
     *
     * Note: this is the JSRA (1982) fit. Published validation (Grin, IMDC 2024) shows it is the
     * *weakest* of the common roll-coefficient estimators, with roll-period errors of several
     * seconds on large ships. It is fine as a first guess; it is NOT a substitute for
     * calibrating f against a condition of known GM.
     */
    fun isCodeC(bMeters: Double, draughtMeters: Double, lwlMeters: Double): Double =
        0.373 + 0.023 * (bMeters / draughtMeters) - 0.043 * (lwlMeters / 100.0)

    fun isCodeF(bMeters: Double, draughtMeters: Double, lwlMeters: Double): Double =
        2.0 * isCodeC(bMeters, draughtMeters, lwlMeters)

    /** GM from the measured natural roll period. */
    fun gmFromPeriod(period: Double, f: Double, beam: Double): Double {
        if (period <= 0.0) return Double.NaN
        val r = f * beam / period
        return r * r
    }

    /** Inverse: solve f from a condition whose GM is known (calibration / inclining reference). */
    fun fFromKnownGm(period: Double, knownGm: Double, beam: Double): Double {
        if (beam <= 0.0 || knownGm <= 0.0) return Double.NaN
        return period * sqrt(knownGm) / beam
    }

    /** Expected roll period for a given GM. Useful as a sanity check against the loading computer. */
    fun periodFromGm(gm: Double, f: Double, beam: Double): Double {
        if (gm <= 0.0) return Double.NaN
        return f * beam / sqrt(gm)
    }

    data class GmResult(
        val gm: Double,               // m
        val gmLow: Double,            // m, 1-sigma lower bound
        val gmHigh: Double,           // m, 1-sigma upper bound
        val relUncertainty: Double,   // fractional, 1-sigma
        val f: Double,
        val fSource: FSource,
        val relUncertaintyT: Double,
        val relUncertaintyF: Double
    )

    /**
     * GM plus a propagated 1-sigma uncertainty.
     *
     * Because GM ~ (f*B/T)^2, relative errors DOUBLE:
     *
     *      u(GM)/GM = 2 * sqrt( (u(T)/T)^2 + (u(f)/f)^2 )
     *
     * A 3% error in the measured period alone is already a 6% error in GM. The dominant term is
     * almost always u(f), not the measurement.
     */
    fun evaluate(
        period: Double,
        periodUncertainty: Double,
        f: Double,
        fRelUncertainty: Double,
        beam: Double,
        fSource: FSource
    ): GmResult {
        val gm = gmFromPeriod(period, f, beam)
        val rt = if (period > 0) periodUncertainty / period else 0.0
        val rf = fRelUncertainty
        val rel = 2.0 * sqrt(rt * rt + rf * rf)
        return GmResult(
            gm = gm,
            gmLow = gm * (1.0 - rel).coerceAtLeast(0.0),
            gmHigh = gm * (1.0 + rel),
            relUncertainty = rel,
            f = f,
            fSource = fSource,
            relUncertaintyT = rt,
            relUncertaintyF = rf
        )
    }

    /**
     * Default 1-sigma relative uncertainty on f, by source.
     * IS_CODE:    scatter of the JSRA fit against measured roll periods is large (r^2 ~ 0.6).
     * MANUAL:     assume the user took f from the stability booklet for this ship.
     * CALIBRATED: from the spread of the operator's own calibration points, floored at 2%.
     */
    fun defaultFUncertainty(source: FSource, calibrationSpread: Double? = null): Double = when (source) {
        FSource.IS_CODE -> 0.08
        FSource.MANUAL -> 0.05
        FSource.CALIBRATED -> (calibrationSpread ?: 0.03).coerceAtLeast(0.02)
    }
}
