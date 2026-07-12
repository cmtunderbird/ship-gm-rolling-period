package com.gmestimator.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Cross-checked roll-period estimator.
 *
 * Two independent estimates are produced from the same record:
 *
 *   1. SPECTRAL  - Welch PSD of the (detrended) roll angle, dominant peak in the roll band,
 *                  parabolic sub-bin interpolation.  T_psd = 1 / f_peak.
 *   2. TIME-DOMAIN - hysteresis zero-up-crossing counting on the band-passed roll angle;
 *                  the same thing a mate does with a stopwatch, but with interpolated crossing
 *                  instants and a median over every cycle in the record.
 *
 * Their AGREEMENT is the primary quality indicator.
 *
 * BUT AGREEMENT IS NOT ENOUGH. Both methods measure the period the ship is ROLLING at. Neither
 * has any way of knowing whether that is the ship's OWN period or simply the period the waves
 * are pushing her at - and in a seaway those are usually different. Two methods that agree
 * perfectly on the wrong number are still wrong. That is what the sea-lock veto is for: it asks
 * the accelerometer whether the peak belongs to the sea. See SeaAnalyzer.
 */
object PeriodEstimator {

    enum class Mode {
        /** Ship rolled by rudder / weight shift in calm water, then left to roll freely. */
        FREE_DECAY,

        /** Ship rolling in a seaway; long record, resonant response assumed to dominate. */
        SEAWAY
    }

    enum class Quality { EXCELLENT, GOOD, FAIR, POOR }

    data class Result(
        val ok: Boolean,
        val message: String,

        val period: Double,             // s  - adopted natural roll period
        val periodUncertainty: Double,  // s  - 1 sigma

        val periodSpectral: Double,     // s
        val periodZeroCross: Double,    // s
        val agreement: Double,          // |Tpsd - Tzc| / Tmean

        val nCycles: Int,
        val meanAmplitude: Double,      // deg
        val maxAmplitude: Double,       // deg
        val prominence: Double,         // spectral peak / median band power
        val axisDominance: Double,      // 0.5 = no dominant axis, 1.0 = pure single-axis motion
        val consistency: Double,        // fraction of sub-windows whose peak agrees within 5%
        val zeta: Double,               // roll damping ratio (FREE_DECAY only, else NaN)
        val competingPeriod: Double,    // s, second roll period present in the record (NaN if none)
        val competingRatio: Double,     // its power relative to the primary peak
        /** What the sea was doing, from the accelerometer. Null if no heave signal was supplied. */
        val sea: SeaAnalyzer.SeaState?,
        val quality: Quality,

        val phi: DoubleArray,           // band-passed roll angle actually analysed [deg]
        val fs: Double,
        val psdFreq: DoubleArray,
        val psdPower: DoubleArray
    )

    data class Config(
        val tMin: Double = 3.0,      // s   shortest credible roll period
        val tMax: Double = 45.0,     // s   longest credible roll period
        /** A free decay is a clean, deterministic signal: a handful of cycles is enough, and the
         *  ship stops rolling anyway. An irregular seaway is a random process and needs many more
         *  cycles before the median period means anything. */
        val minCyclesFreeDecay: Int = 4,
        val minCyclesSeaway: Int = 10,
        val minAmplitudeDeg: Double = 0.5,   // below this, sensor noise and vibration dominate
        val maxAmplitudeDeg: Double = 12.0,  // above this, GZ non-linearity breaks T ~ 1/sqrt(GM)
        val minProminence: Double = 4.0,
        val minAxisDominance: Double = 0.70,
        /** Reject the record if a competing spectral peak carries more than this fraction of the
         *  primary peak's power. Validated on synthetic swell-contaminated records: clean resonant
         *  roll sits at <= 0.05, swell-contaminated roll at 0.30-0.80. */
        val maxCompetingRatio: Double = 0.25
    )

    /**
     * @param heave world-vertical acceleration [m/s2] on the SAME uniform grid as phiRaw.
     *              Optional for FREE_DECAY. MANDATORY in practice for SEAWAY: without it we are
     *              blind to the sea, and the roll peak may simply be the wave period. See
     *              SeaAnalyzer for the failure this prevents.
     */
    fun estimate(
        phiRaw: DoubleArray,
        fs: Double,
        mode: Mode,
        axisDominance: Double,
        heave: DoubleArray? = null,
        cfg: Config = Config()
    ): Result {
        val fLo = 1.0 / cfg.tMax
        val fHi = 1.0 / cfg.tMin

        if (phiRaw.size < (fs * cfg.tMax * 2).toInt()) {
            return fail("Record too short. Need at least ${(2 * cfg.tMax).toInt()} s of data.", fs)
        }

        val detr = Dsp.detrend(phiRaw)

        // ---- 1. spectral estimate -------------------------------------------------------
        val targetSeg = Dsp.nextPow2((fs * 4.0 * cfg.tMax).toInt())      // >= 4 * longest period
        val segLen = minOf(targetSeg, Dsp.nextPow2(detr.size / 2)).coerceAtLeast(256)
        val psd = Dsp.welchPsd(detr, fs, segLen, overlap = 0.5, padFactor = 4)
        val peak = Dsp.dominantPeak(psd, fLo, fHi)
            ?: return fail("No spectral peak found in the ${cfg.tMin.toInt()}-${cfg.tMax.toInt()} s band.", fs)
        val tPsd = 1.0 / peak.freq

        // Is there a SECOND roll period in this record? If so, one of the two is the sea, and the
        // spectrum cannot tell us which. See Dsp.secondaryPeak.
        val competing = Dsp.secondaryPeak(psd, fLo, fHi)
        val tCompeting = competing?.let { 1.0 / it.first } ?: Double.NaN
        val rCompeting = competing?.second ?: 0.0

        // ---- 1b. ASK THE SEA -------------------------------------------------------------
        // Does the roll peak have a twin in the accelerometer? If it does, this is a wave, and
        // nothing about this record says anything about GM. This is the ONLY check that can
        // catch a ship rolling at the wave period in a clean, single-peaked wind sea - the
        // competing-peak gate is blind to it, because there is nothing to compete with.
        val sea: SeaAnalyzer.SeaState? = heave?.let { h ->
            if (h.size < detr.size / 2) null
            else {
                val psdAz = Dsp.welchPsd(Dsp.detrend(h), fs, segLen, overlap = 0.5, padFactor = 4)
                SeaAnalyzer.analyse(psd, psdAz, peak.freq, fLo, fHi)
            }
        }

        // ---- 2. time-domain estimate ----------------------------------------------------
        val bp = Dsp.bandpassFft(detr, fs, fLo, fHi)

        // Edge guard. The FFT band-pass rings where the record is truncated, so the ends must be
        // discarded before counting zero crossings. The guard is scaled to the period we just
        // measured - NOT to tMax. A fixed 45 s guard would eat half of a 3-minute free-decay
        // record and leave too few cycles to pass the quality gate, i.e. the recommended
        // procedure would fail every time. It is also capped at 10% of the record so that a long
        // measured period cannot swallow it.
        val durSec = detr.size / fs
        val guard = (minOf(tPsd, 0.10 * durSec) * fs).toInt().coerceAtLeast(1)
        if (bp.size <= 3 * guard) {
            return fail("Record too short for a ${fmt(tPsd)} s roll period. Record at least ${(6 * tPsd).toInt()} s.", fs)
        }
        val core = bp.copyOfRange(guard, bp.size - guard)

        val sigma = Dsp.std(core)
        val cycles = Dsp.zeroCrossingCycles(core, fs, hyst = 0.20 * sigma, tMin = cfg.tMin, tMax = cfg.tMax)
        if (cycles.nCycles < 2) {
            return fail("Only ${cycles.nCycles} complete roll cycles detected. Record longer.", fs)
        }
        val tZc = cycles.medianPeriod

        // ---- 3. free-decay damping (optional) --------------------------------------------
        var zeta = Double.NaN
        var tDecay = Double.NaN
        if (mode == Mode.FREE_DECAY) {
            val fit = Dsp.fitFreeDecay(core, fs, cfg.tMin, cfg.tMax)
            if (fit != null && fit.r2 > 0.5) {
                zeta = fit.zeta
                tDecay = fit.naturalPeriod
            }
        }

        // ---- 4. cross-check ---------------------------------------------------------------
        val tMean = 0.5 * (tPsd + tZc)
        val agreement = abs(tPsd - tZc) / tMean

        val consistency = subWindowConsistency(detr, fs, segLen, fLo, fHi, peak.freq)

        val seZc = if (cycles.nCycles > 1) cycles.sdPeriod / sqrt(cycles.nCycles.toDouble()) else tZc * 0.1
        val sePsd = (peak.halfPowerWidth / (2.0 * sqrt(psd.nSegments.toDouble().coerceAtLeast(1.0)))) *
            tPsd * tPsd   // df -> dT via dT = df / f^2 = df * T^2

        val adopted: Double
        if (!tDecay.isNaN()) {
            adopted = tDecay
        } else {
            val wZc = 1.0 / (seZc * seZc).coerceAtLeast(1e-9)
            val wPsd = 1.0 / (sePsd * sePsd).coerceAtLeast(1e-9)
            adopted = (wZc * tZc + wPsd * tPsd) / (wZc + wPsd)
        }

        val uStat = 1.0 / sqrt(1.0 / (seZc * seZc).coerceAtLeast(1e-9) + 1.0 / (sePsd * sePsd).coerceAtLeast(1e-9))
        val uPeriod = maxOf(uStat, 0.5 * abs(tPsd - tZc))

        val meanAmp = if (cycles.amplitudes.isEmpty()) 0.0 else Dsp.mean(cycles.amplitudes)
        val maxAmp = cycles.amplitudes.maxOrNull() ?: 0.0

        // ---- 5. quality gates --------------------------------------------------------------
        val problems = ArrayList<String>()

        // THE SEA-LOCK VETO. Highest priority: if the accelerometer says this peak is a wave,
        // nothing else matters. In a seaway this is the check that stops the instrument from
        // confidently reporting the wave period as the ship's, which in a plain wind sea makes
        // a tender ship look 3.5x stiffer than she really is.
        if (mode == Mode.SEAWAY && sea != null && sea.seaLocked) {
            problems.add(sea.message)
        }
        if (mode == Mode.SEAWAY && sea == null) {
            problems.add(
                "no heave signal, so the sea could not be checked. In a seaway the roll peak may " +
                    "simply be the wave period, and there is no way to tell without the accelerometer"
            )
        }

        if (rCompeting > cfg.maxCompetingRatio) {
            problems.add(
                "TWO roll periods are present (${fmt(tPsd)} s and ${fmt(tCompeting)} s). One of them is " +
                    "the sea, not the ship. Re-measure on a different heading, or use a free-decay test"
            )
        }
        val minCycles =
            if (mode == Mode.FREE_DECAY) cfg.minCyclesFreeDecay else cfg.minCyclesSeaway
        if (cycles.nCycles < minCycles) {
            problems.add("only ${cycles.nCycles} complete cycles (need $minCycles for $mode)")
        }
        if (meanAmp < cfg.minAmplitudeDeg) problems.add("roll amplitude ${fmt(meanAmp)} deg is below the noise floor")
        if (maxAmp > cfg.maxAmplitudeDeg) problems.add("roll amplitude ${fmt(maxAmp)} deg is outside the linear (small-angle) range")
        if (peak.prominence < cfg.minProminence) problems.add("spectral peak is not prominent")
        if (axisDominance < cfg.minAxisDominance) problems.add("roll axis is poorly defined (pitch/yaw contamination)")
        if (mode == Mode.SEAWAY && consistency < 0.5) problems.add("peak period is not repeatable across the record")

        val quality = when {
            problems.isNotEmpty() -> Quality.POOR
            agreement < 0.03 -> Quality.EXCELLENT
            agreement < 0.05 -> Quality.GOOD
            agreement < 0.10 -> Quality.FAIR
            else -> Quality.POOR
        }

        val msg = when {
            problems.isNotEmpty() -> "Unreliable: " + problems.joinToString("; ")
            quality == Quality.POOR -> "Spectral and zero-crossing periods disagree by ${fmt(agreement * 100)}%."
            else -> "Methods agree to ${fmt(agreement * 100)}%."
        }

        return Result(
            ok = problems.isEmpty() && quality != Quality.POOR,
            message = msg,
            period = adopted,
            periodUncertainty = uPeriod,
            periodSpectral = tPsd,
            periodZeroCross = tZc,
            agreement = agreement,
            nCycles = cycles.nCycles,
            meanAmplitude = meanAmp,
            maxAmplitude = maxAmp,
            prominence = peak.prominence,
            axisDominance = axisDominance,
            consistency = consistency,
            zeta = zeta,
            competingPeriod = tCompeting,
            competingRatio = rCompeting,
            sea = sea,
            quality = quality,
            phi = core,
            fs = fs,
            psdFreq = psd.freq,
            psdPower = psd.power
        )
    }

    /**
     * Split the record into independent windows and check that the same peak keeps coming back.
     */
    private fun subWindowConsistency(
        x: DoubleArray,
        fs: Double,
        segLen: Int,
        fLo: Double,
        fHi: Double,
        fRef: Double
    ): Double {
        val w = segLen
        if (x.size < 2 * w) return 1.0
        var n = 0
        var agree = 0
        var start = 0
        while (start + w <= x.size) {
            val seg = x.copyOfRange(start, start + w)
            val p = Dsp.welchPsd(seg, fs, w, overlap = 0.0, padFactor = 4)
            val pk = Dsp.dominantPeak(p, fLo, fHi)
            if (pk != null) {
                n++
                if (abs(pk.freq - fRef) / fRef < 0.05) agree++
            }
            start += w / 2
        }
        return if (n == 0) 0.0 else agree.toDouble() / n
    }

    private fun fail(msg: String, fs: Double) = Result(
        ok = false, message = msg, period = Double.NaN, periodUncertainty = Double.NaN,
        periodSpectral = Double.NaN, periodZeroCross = Double.NaN, agreement = Double.NaN,
        nCycles = 0, meanAmplitude = 0.0, maxAmplitude = 0.0, prominence = 0.0,
        axisDominance = 0.0, consistency = 0.0, zeta = Double.NaN,
        competingPeriod = Double.NaN, competingRatio = 0.0, sea = null, quality = Quality.POOR,
        phi = DoubleArray(0), fs = fs, psdFreq = DoubleArray(0), psdPower = DoubleArray(0)
    )

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
}
