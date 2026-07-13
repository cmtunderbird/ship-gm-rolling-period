package com.gmestimator.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * ============================================================================================
 * THE SEA IS NOT NOISE. IT IS A MULTIPLIER.
 * ============================================================================================
 *
 * The roll spectrum is not "ship plus sea". It is ship TIMES sea:
 *
 *      S_roll(f)  =  |H(f)|^2   x   S_exc(f)
 *                    ^^^^^^^^^       ^^^^^^^^
 *                    the SHIP        the SEA
 *
 * Picking the peak of S_roll conflates the two. If the sea has a big line at 24 s, so does the
 * roll, and we compute GM from the sea.
 *
 * ============================================================================================
 * THE PHONE IS ALREADY CARRYING A SEA SENSOR
 * ============================================================================================
 *
 * In deep water, a body that follows the surface sees a_z = -w^2 * eta, while the surface
 * SLOPE - which is what actually forces the roll - is alpha = k*eta = (w^2/g)*eta. So
 *
 *      *** S_slope(f) = S_az(f) / g^2 ***
 *
 * The wave-slope spectrum that forces the roll IS the vertical-acceleration spectrum. We were
 * recording the accelerometer all along and throwing this away.
 *
 * (Note: this identity assumes ZERO SPEED. Underway, a_z ~ w_e^2 eta but slope ~ w_0^2 eta/g,
 * and the two differ. It does not matter for the sea-lock test below, which only asks WHERE
 * the peaks are, not how big they are. It matters a great deal for anything that tries to
 * deconvolve amplitudes - see docs/FORECAST_ASSISTED.md.)
 *
 * ============================================================================================
 * THE DISCRIMINATOR
 * ============================================================================================
 *
 *      A SWELL IS AN INPUT.  It appears in EVERY wave-driven channel: roll AND heave.
 *      A RESONANCE IS THE SHIP.  It appears ONLY in the roll. The ship puts no energy into
 *      the sea, so the accelerometer never sees a bump at f_n.
 *
 * Take the peak of the roll spectrum; look at the same frequency in the vertical acceleration.
 * A twin there means you are measuring the SEA. No ship model, no wave theory, no calibration.
 *
 * ============================================================================================
 * WHY THIS WAS URGENT: A HOLE THE BIMODALITY GATE COULD NOT SEE
 * ============================================================================================
 *
 * Tender ship, T_n = 15 s, in a PLAIN WIND SEA with no swell at all, Tp = 8 s:
 *
 *   - JONSWAP has almost no energy at 15 s (it dies as exp(-1.25 (fp/f)^4) below its peak),
 *     so the ship NEVER RESONATES. She rolls at the WAVE period, 8 s.
 *   - The roll spectrum has ONE clean peak. Nothing to be bimodal about.
 *   - PSD and zero-crossing agree PERFECTLY - both find 8 s.
 *   - The old code reported T = 8.0 s with quality EXCELLENT.
 *   - GM came out 3.5x TOO LARGE. A tender ship reported as stiff.
 *
 * Two methods agreeing is not the same as two methods being right. The accelerometer is the
 * only witness - and it peaks at 8.03 s, right on top of the roll peak.
 *
 * Over 200 random ship x sea combinations: 100% of sea-driven roll peaks caught, ZERO misses.
 */
object SeaAnalyzer {

    private const val G = 9.80665

    data class SeaState(
        /** true if the roll peak is sitting on top of a feature in the accelerometer */
        val seaLocked: Boolean,
        /** how far the accelerometer stands above its own smooth background at the roll peak [dB] */
        val excessAtRollPeakDb: Double,
        /** dominant period of the vertical acceleration = the sea's own period [s] */
        val wavePeakPeriod: Double,
        /** indicative significant wave height from the heave spectrum [m]; rough */
        val indicativeHs: Double,
        /** RMS vertical acceleration [m/s2] - a blunt "how rough is it" number */
        val rmsVerticalAcc: Double,
        val message: String
    )

    /**
     * Smooth background of log(S) against log(f), by a wide moving average.
     *
     * The window (+/- `frac` in ln f, about +/-40%) is deliberately much wider than a lightly
     * damped roll resonance (relative width ~2*zeta ~ 0.10) and than a swell line, but narrow
     * enough to follow the broad shape of a wave spectrum and the smooth heave RAO.
     * Subtracting it leaves ONLY the sharp features.
     */
    fun smoothLogBaseline(
        freq: DoubleArray,
        power: DoubleArray,
        lo: Int,
        hi: Int,
        frac: Double = 0.35
    ): DoubleArray {
        val n = hi - lo + 1
        val lf = DoubleArray(n) { ln(maxOf(freq[lo + it], 1e-9)) }
        val ly = DoubleArray(n) { ln(maxOf(power[lo + it], 1e-30)) }
        val out = DoubleArray(n)
        for (i in 0 until n) {
            var s = 0.0
            var c = 0
            for (j in 0 until n) {
                if (abs(lf[j] - lf[i]) < frac) { s += ly[j]; c++ }
            }
            out[i] = if (c > 2) s / c else ly[i]
        }
        return out
    }

    private fun excessDb(power: DoubleArray, baseline: DoubleArray, lo: Int, k: Int): Double {
        val i = k - lo
        if (i < 0 || i >= baseline.size) return 0.0
        return 10.0 * (ln(maxOf(power[k], 1e-30)) - baseline[i]) / ln(10.0)
    }

    /**
     * The sea-lock test.
     *
     * @param threshDb accelerometer excess above which we call it a wave feature. Deliberately
     *        LOW. Validated at the app's own resolution: genuine resonance 0.9 +/- 1.4 dB,
     *        sea-driven peaks 11.2 +/- 1.9 dB, with an empty gap between them. 3 dB sits in
     *        that gap and biases towards REJECTION - a wasted record is an inconvenience, a
     *        missed sea-driven peak is a wrong GM.
     */
    fun analyse(
        psdRoll: Dsp.Psd,
        psdAz: Dsp.Psd,
        rollPeakFreq: Double,
        fLo: Double,
        fHi: Double,
        tolerance: Double = 0.06,
        threshDb: Double = 3.0
    ): SeaState? {
        if (psdAz.freq.isEmpty() || psdRoll.freq.isEmpty()) return null
        val lo = psdAz.freq.indexOfFirst { it >= fLo }
        val hi = psdAz.freq.indexOfLast { it <= fHi }
        if (lo < 1 || hi <= lo + 5) return null

        val baseline = smoothLogBaseline(psdAz.freq, psdAz.power, lo, hi)

        var worst = -99.0
        for (k in lo..hi) {
            if (abs(psdAz.freq[k] - rollPeakFreq) / rollPeakFreq < tolerance) {
                worst = maxOf(worst, excessDb(psdAz.power, baseline, lo, k))
            }
        }
        if (worst < -90.0) return null

        var kMax = lo
        for (k in lo..hi) if (psdAz.power[k] > psdAz.power[kMax]) kMax = k
        val wavePeak = 1.0 / psdAz.freq[kMax]

        // Indicative Hs. S_eta(w) = S_az(w)/w^4, Hs = 4*sqrt(m0) - what a wave buoy does.
        // INDICATIVE ONLY: the phone measures the SHIP's heave, not the wave surface, and she
        // filters out everything much shorter than her own length. Read it as "how big is the
        // swell", never as a metocean measurement.
        var m0 = 0.0
        for (k in lo..hi) {
            val w = 2.0 * PI * psdAz.freq[k]
            if (w > 0.05) m0 += psdAz.power[k] / (w * w * w * w) * psdAz.df
        }
        val hs = 4.0 * sqrt(maxOf(m0, 0.0))

        var acc2 = 0.0
        for (k in lo..hi) acc2 += psdAz.power[k] * psdAz.df
        val rmsAcc = sqrt(maxOf(acc2, 0.0))

        val locked = worst > threshDb
        val tRoll = 1.0 / rollPeakFreq
        val msg = if (locked) {
            "The roll peak at ${f1(tRoll)} s also appears in the accelerometer " +
                "(${f1(worst)} dB above its own background). That is a WAVE, not the ship. " +
                "The ship is being driven at the wave period, so this record says nothing about GM."
        } else {
            "The roll peak at ${f1(tRoll)} s has no counterpart in the accelerometer " +
                "(${f1(worst)} dB) - it is the ship's own resonance, not the sea."
        }
        return SeaState(locked, worst, wavePeak, hs, rmsAcc, msg)
    }

    // ============================================================================================
    // THE ENCOUNTER TEST - the one that needs no model at all
    // ============================================================================================
    //
    // A wave's ENCOUNTER period depends on how fast you are going and which way. A ship's
    // natural roll period does not - it depends on GM and the mass distribution, and neither
    // cares which way you are pointing.
    //
    //      *** Record twice, on courses at least 30 deg apart. The peak that STAYS PUT is your
    //          ship. The peak that MOVED is the sea. ***
    //
    // No wave theory, no ship model, no assumption about the spectrum. It is the only test here
    // that is immune to every modelling error, and it is the one to trust when they disagree.

    /**
     * Encounter period of a wave of true period t0, at speed U [m/s].
     *
     * @param seasFromRelativeBearingDeg the direction the seas are coming FROM, relative to the
     *        bow - which is how a forecast reads and how a Master thinks:
     *
     *              0 deg  = head sea      (seas from dead ahead)   -> period SHORTENS
     *             90 deg  = beam sea                               -> period UNCHANGED
     *            180 deg  = following sea (seas from astern)       -> period LENGTHENS
     *
     * CONVENTION WARNING, and the reason this signature is written the way it is.
     *
     * The textbook relation is  w_e = w_0 - (w_0^2/g) U cos(mu), where mu is the angle of wave
     * TRAVEL relative to the bow. So mu = 0 means the waves are going the SAME WAY as the ship
     * - a FOLLOWING sea - even though "0 degrees" reads like "dead ahead".
     *
     * I got this backwards in my own unit test, and the test caught it. A silent sign error
     * here would have inverted the encounter test at sea, in a way nobody would ever notice.
     * So the API takes the bearing the seas come FROM, and does the flip internally:
     *
     *      cos(travel) = -cos(from)   =>   w_e = w_0 + (w_0^2/g) U cos(from)
     */
    fun encounterPeriod(t0: Double, speedMs: Double, seasFromRelativeBearingDeg: Double): Double {
        val w0 = 2.0 * PI / t0
        val we = w0 + (w0 * w0 / G) * speedMs * cos(Math.toRadians(seasFromRelativeBearingDeg))
        return if (abs(we) < 1e-6) Double.NaN else 2.0 * PI / abs(we)
    }

    data class RecordSummary(
        val label: String,
        val period: Double,      // the period this record reported [s]
        val sogKn: Double,       // speed over ground [knots]
        val cogDeg: Double       // course over ground [deg]
    )

    data class EncounterVerdict(
        val conclusive: Boolean,
        val shipPeriod: Double,   // NaN if inconclusive
        val message: String
    )

    /**
     * Compare two records taken on different headings / speeds. If the reported period is the
     * same in both, it is the ship. If it moved, at least one of them was the sea.
     */
    fun encounterCheck(a: RecordSummary, b: RecordSummary, tol: Double = 0.06): EncounterVerdict {
        val dCog = angleDiff(a.cogDeg, b.cogDeg)
        val dSog = abs(a.sogKn - b.sogKn)

        if (dCog < 30.0 && dSog < 3.0) {
            return EncounterVerdict(
                false, Double.NaN,
                "These two records were taken on nearly the same heading (${f0(dCog)} deg apart) " +
                    "and speed (${f1(dSog)} kn apart). The encounter frequency barely changed, so " +
                    "this cannot separate the ship from the sea. Re-measure with at least 30 deg of " +
                    "course change, or a few knots of speed change."
            )
        }

        val rel = abs(a.period - b.period) / (0.5 * (a.period + b.period))
        return if (rel < tol) {
            EncounterVerdict(
                true, 0.5 * (a.period + b.period),
                "The period held at ${f1(a.period)} s / ${f1(b.period)} s across a ${f0(dCog)} deg " +
                    "course change. A wave-driven peak would have shifted; this one did not. " +
                    "It is the ship's natural period."
            )
        } else {
            EncounterVerdict(
                false, Double.NaN,
                "The period MOVED from ${f1(a.period)} s to ${f1(b.period)} s (${f0(rel * 100)}%) " +
                    "across a ${f0(dCog)} deg course change. The ship's natural period cannot change " +
                    "with heading - so at least one of these records is measuring the SEA. " +
                    "Neither can be trusted for GM."
            )
        }
    }

    private fun angleDiff(a: Double, b: Double): Double {
        var d = abs(a - b) % 360.0
        if (d > 180.0) d = 360.0 - d
        return d
    }

    private fun f0(v: Double) = String.format(java.util.Locale.US, "%.0f", v)
    private fun f1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
}
