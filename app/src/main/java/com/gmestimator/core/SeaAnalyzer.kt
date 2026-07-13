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
 *                    a resonance     whatever the waves happen to be doing
 *                    at f_n          at that frequency
 *
 * Picking the peak of S_roll conflates the two. That is the whole problem: if the sea has a
 * big line at 24 s, so does the roll, and we compute GM from the sea.
 *
 * ============================================================================================
 * THE PHONE IS ALREADY CARRYING A SEA SENSOR
 * ============================================================================================
 *
 * In deep water, a body that follows the surface sees a vertical acceleration
 *
 *      a_z = -w^2 * eta
 *
 * and the surface SLOPE - which is what actually forces the roll - is
 *
 *      alpha = k * eta = (w^2 / g) * eta
 *
 * Divide one by the other and the wave amplitude cancels:
 *
 *      *** S_slope(f) = S_az(f) / g^2 ***
 *
 * The wave-slope spectrum that forces the ship's roll IS the vertical-acceleration spectrum.
 * We have been recording the accelerometer all along and throwing this away.
 *
 * ============================================================================================
 * THE DISCRIMINATOR
 * ============================================================================================
 *
 *      A SWELL IS AN INPUT.  It appears in EVERY wave-driven channel: roll AND heave.
 *      A RESONANCE IS THE SHIP.  It appears ONLY in the roll. The ship puts no energy into
 *      the sea, so the accelerometer never sees a bump at f_n.
 *
 * So: take the peak of the roll spectrum, and look at the same frequency in the vertical
 * acceleration spectrum.
 *
 *      Is there a peak there too?  ->  you are measuring the SEA. The GM would be nonsense.
 *      Is the accelerometer smooth there?  ->  you are measuring the SHIP.
 *
 * No ship model. No wave theory. No calibration. Two spectra and a lookup.
 *
 * ============================================================================================
 * WHY THIS WAS URGENT: A HOLE THE BIMODALITY GATE COULD NOT SEE
 * ============================================================================================
 *
 * Simulation (tools/sea_lock_test.py), tender ship, T_n = 15 s, in a PLAIN WIND SEA with no
 * swell whatsoever, Tp = 8 s:
 *
 *   - A JONSWAP spectrum has almost no energy at 15 s (it dies as exp(-1.25 (fp/f)^4) below
 *     its peak). The ship therefore NEVER RESONATES. She rolls at the WAVE period, 8 s.
 *   - The roll spectrum has ONE clean peak. Nothing to be bimodal about.
 *   - The PSD and zero-crossing estimates agree perfectly - both find 8 s.
 *   - The old code reported T = 8.0 s with quality EXCELLENT.
 *   - GM came out 3.5x TOO LARGE. A tender ship reported as stiff. The worst possible
 *     direction to be wrong in.
 *
 * The competing-peak gate cannot catch this: there IS no competing peak. The accelerometer
 * is the only witness - and it peaks at 8.03 s, sitting exactly on top of the roll peak.
 *
 * Over 200 random ship x sea combinations the sea-lock test caught 100% of sea-driven roll
 * peaks, with ZERO misses. It is blunt - it also rejects ~80% of genuinely good records,
 * including the harmless case where a swell happens to sit exactly on f_n - but it is never
 * dangerously wrong, and that is the trade we want in a stability tool.
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
        /** indicative significant wave height from the heave spectrum [m]; rough - see note */
        val indicativeHs: Double,
        /** RMS vertical acceleration [m/s2] - a blunt "how rough is it" number */
        val rmsVerticalAcc: Double,
        /** transverse lever arm of the phone from the roll axis [m], fitted from the data */
        val leverArmM: Double,
        val message: String
    )

    /**
     * Smooth background of log(S) against log(f), by a wide moving average.
     *
     * The window (+/- `frac` in ln f, i.e. about +/-40%) is deliberately much wider than a
     * lightly damped roll resonance (relative width ~2*zeta ~ 0.10) and than a swell line, but
     * narrow enough to follow the broad shape of a wave spectrum and the smooth heave RAO.
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

    /** How far bin k stands above its own spectrum's smooth background, in dB. */
    private fun excessDb(power: DoubleArray, baseline: DoubleArray, lo: Int, k: Int): Double {
        val i = k - lo
        if (i < 0 || i >= baseline.size) return 0.0
        return 10.0 * (ln(maxOf(power[k], 1e-30)) - baseline[i]) / ln(10.0)
    }

    /**
     * The sea-lock test.
     *
     * @param psdRoll      Welch PSD of the roll angle
     * @param psdAz        Welch PSD of the WORLD-VERTICAL acceleration (gravity removed)
     * @param rollPeakFreq the frequency the period estimator settled on [Hz]
     * @param tolerance    how close in frequency counts as "the same peak" (fractional)
     * @param threshDb     accelerometer excess above which we call it a wave feature.
     *                     Deliberately LOW. Validated separation at the app's own resolution:
     *                     genuine resonance 0.9 +/- 1.4 dB, sea-driven peaks 11.2 +/- 1.9 dB,
     *                     with an empty gap between them. 3 dB sits in that gap and biases
     *                     towards REJECTION - a wasted record is an inconvenience, a missed
     *                     sea-driven peak is a wrong GM.
     */
    fun analyse(
        psdRoll: Dsp.Psd,
        psdAz: Dsp.Psd,
        rollPeakFreq: Double,
        fLo: Double,
        fHi: Double,
        tolerance: Double = 0.06,
        threshDb: Double = 3.0,
        waveRatioThreshold: Double = 4.0,
        leverArmM: Double = 0.0
    ): SeaState? {
        if (psdAz.freq.isEmpty() || psdRoll.freq.isEmpty()) return null
        val lo = psdAz.freq.indexOfFirst { it >= fLo }
        val hi = psdAz.freq.indexOfLast { it <= fHi }
        if (lo < 1 || hi <= lo + 5) return null

        // ============================================================================
        // HOW TO ASK "IS THERE A WAVE HERE?"  - rewritten after real ship data.
        // ============================================================================
        //
        // I used to ask: does the accelerometer stand ABOVE ITS OWN LOCAL SMOOTH BASELINE at
        // the roll peak? That baseline is a moving average in log-frequency, and at the
        // LONG-PERIOD END of the band the window becomes one-sided and the baseline is dragged
        // down. Long periods are exactly where a ship's roll lives. So an ordinary, quiet
        // stretch of spectrum read as a 4-7 dB "excess", and the veto fired on the very records
        // it exists to protect.
        //
        // On m/v Androklis it condemned a 19.3 s roll peak as "a wave" when the heave there was
        // 2x the band median - i.e. nothing at all. The ship's actual swell was at 5-6 s.
        //
        // The honest question is absolute, not local:
        //
        //      Is there significantly MORE wave energy at this period than the sea has
        //      typically got anywhere in the band?
        //
        // Real data separates cleanly on that test:
        //      a genuine swell under the roll peak   ->  x8.6 of the band median
        //      the ship's own resonance              ->  x0.4 to x2.0   (i.e. nothing)
        // so the threshold sits at x4, in the empty gap between them.
        val bandPow = DoubleArray(hi - lo + 1) { psdAz.power[lo + it] }
        val bandMedian = Dsp.median(bandPow).coerceAtLeast(1e-30)

        var peakAz = 0.0
        for (k in lo..hi) {
            if (abs(psdAz.freq[k] - rollPeakFreq) / rollPeakFreq < tolerance) {
                peakAz = maxOf(peakAz, psdAz.power[k])
            }
        }
        var maxAz = 0.0
        for (k in lo..hi) maxAz = maxOf(maxAz, psdAz.power[k])

        val ratio = peakAz / bandMedian
        val worst = 10.0 * ln(ratio.coerceAtLeast(1e-9)) / ln(10.0)   // still reported in dB

        // Second clause: the energy must also be a real FEATURE of the sea, not a noise
        // fluctuation. When the sea is narrow-band, most of the band IS the noise floor, so the
        // median is the noise floor too - and noise-over-noise can exceed 4x by luck. Requiring
        // the peak to carry at least 2% of the sea's own peak energy kills that.
        //
        // On m/v Androklis:  the genuine swell   x8.6 of median, 46% of peak  -> WAVE
        //                    her own resonance   x0.4-2.0 of median           -> SHIP
        val significant = peakAz > 0.02 * maxAz

        // the sea's own dominant period
        var kMax = lo
        for (k in lo..hi) if (psdAz.power[k] > psdAz.power[kMax]) kMax = k
        val wavePeak = 1.0 / psdAz.freq[kMax]

        // Indicative Hs. S_eta(w) = S_az(w) / w^4, then Hs = 4*sqrt(m0). This is what a wave
        // buoy does. It is INDICATIVE ONLY: the phone measures the SHIP's heave, not the wave
        // surface, and the ship filters out everything much shorter than her own length. Read
        // it as "how big is the swell", never as a metocean measurement.
        var m0 = 0.0
        for (k in lo..hi) {
            val w = 2.0 * PI * psdAz.freq[k]
            if (w > 0.05) m0 += psdAz.power[k] / (w * w * w * w) * psdAz.df
        }
        val hs = 4.0 * sqrt(maxOf(m0, 0.0))

        var acc2 = 0.0
        for (k in lo..hi) acc2 += psdAz.power[k] * psdAz.df
        val rmsAcc = sqrt(maxOf(acc2, 0.0))

        // x4 of the band median. See the note above: this replaces a local-baseline test that
        // had an edge artefact at long periods and was condemning good records.
        val locked = ratio > waveRatioThreshold && significant
        val tRoll = 1.0 / rollPeakFreq
        val msg = if (locked) {
            "There is real wave energy at ${f1(tRoll)} s - the accelerometer sees " +
                "${f1(ratio)}x the sea's typical level there. That is a WAVE, not the ship. " +
                "She is being driven at the wave period, so this record says nothing about GM."
        } else {
            "The accelerometer is quiet at ${f1(tRoll)} s (only ${f1(ratio)}x the sea's typical " +
                "level - the waves are at ${f1(wavePeak)} s). No wave is driving her at this " +
                "period, so this peak is the ship's own resonance."
        }
        return SeaState(locked, worst, wavePeak, hs, rmsAcc, leverArmM, msg)
    }

    /**
     * THE LEVER ARM. Found by real ship data, and impossible to find by simulation.
     *
     * The accelerometer is NOT in the sea. It is lying on the ship, and almost never on the
     * roll axis. A point at transverse lever arm y from the roll axis rises and falls as the
     * ship rolls:
     *
     *      z  ~=  y * phi          =>      a_z  ~=  y * phi_ddot  =  -y * w^2 * phi
     *
     * So THE SHIP'S OWN ROLL MANUFACTURES VERTICAL ACCELERATION AT EXACTLY f_n - which is the
     * one frequency where the sea-lock test assumes the accelerometer is silent.
     *
     * On the first real record (m/v Androklis, 16 s roll, 1.2 deg amplitude) this put 7.0 dB
     * of apparent "wave" energy precisely on the roll peak - landing in the middle of the gap
     * my simulation had declared empty (resonance 0.9 +/- 1.4 dB, sea 11.2 +/- 1.9 dB). The
     * simulation could never have caught it: I had implicitly placed the phone ON the roll
     * axis, where y = 0.
     *
     * The fix is exact, because phi_ddot is not a mystery - we measured phi. Regress a_z on
     * phi_ddot, subtract the coherent part, and apply the veto to the RESIDUAL. The regression
     * coefficient IS the lever arm in metres, which comes out free.
     *
     * @return (corrected heave, fitted lever arm in metres)
     */
    fun removeRollInducedHeave(
        heave: DoubleArray,
        phiDeg: DoubleArray,
        fs: Double
    ): Pair<DoubleArray, Double> {
        val n = minOf(heave.size, phiDeg.size)
        if (n < 100) return Pair(heave, 0.0)

        // phi_ddot by central differences, in rad/s^2
        val k = PI / 180.0
        val dd = DoubleArray(n)
        val h2 = 1.0 / (1.0 / fs * 1.0 / fs)
        for (i in 1 until n - 1) {
            dd[i] = (phiDeg[i - 1] - 2.0 * phiDeg[i] + phiDeg[i + 1]) * k * h2
        }
        dd[0] = dd[1]; dd[n - 1] = dd[n - 2]

        var num = 0.0
        var den = 0.0
        for (i in 0 until n) { num += heave[i] * dd[i]; den += dd[i] * dd[i] }
        if (den < 1e-12) return Pair(heave, Double.NaN)
        val lever = num / den                       // metres

        // DOES THE FIT ACTUALLY EXPLAIN ANYTHING?
        //
        // The 19:17 record fitted a lever arm of -0.2 m. The 19:54 record, 37 minutes later,
        // fitted +3.1 m. The phone did not move. What changed is that she was barely rolling
        // (0.46 deg) - and a least-squares fit ALWAYS returns a number, even when it is
        // regressing one noise signal onto another. It has no way of telling you that the
        // number means nothing.
        //
        // So ask it. If phi_ddot explains less than a few percent of the heave, there is no
        // lever-arm signal to remove, and subtracting `lever * phi_ddot` would be injecting
        // scaled roll noise INTO the very channel we use as an independent witness of the sea.
        // Leave the heave alone and report the lever arm as unknown.
        var hh = 0.0
        for (i in 0 until n) hh += heave[i] * heave[i]
        val r2 = if (hh < 1e-12) 0.0 else (num * num) / (den * hh)   // fraction of heave explained

        if (r2 < MIN_LEVER_R2) return Pair(heave, Double.NaN)

        val out = DoubleArray(n) { heave[it] - lever * dd[it] }
        return Pair(out, lever)
    }

    /** Below this, the lever-arm regression is fitting noise, not geometry. */
    private const val MIN_LEVER_R2 = 0.02

    // ============================================================================================
    // THE ENCOUNTER TEST - the one that needs no model at all
    // ============================================================================================
    //
    // A wave's ENCOUNTER frequency depends on how fast you are going and which way:
    //
    //      w_e  =  w_0  -  (w_0^2 / g) * U * cos(mu)
    //
    // where U is speed and mu is the angle between the ship's heading and the direction the
    // waves are travelling. Change course or change speed, and every wave-driven peak MOVES.
    //
    // The ship's natural roll period does not move. It depends on GM and the mass distribution,
    // and neither of those cares which way you are pointing.
    //
    //      *** Record twice, on different headings. The peak that STAYS PUT is your ship.
    //          The peak that MOVED is the sea. ***
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
     * So the API now takes the bearing the seas come FROM, and does the flip internally:
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
     * Compare two records taken on different headings / speeds.
     *
     * If the reported period is the same in both, it is the ship. If it moved, at least one of
     * them was the sea.
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
