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
        /** sd/mean of the individual cycle periods. THE honest quality number - see below. */
        val periodScatter: Double,
        /** the spectral resolution limit of a record this short, in seconds of period */
        val resolutionLimit: Double,
        /** free decay only: initial roll amplitude / residual wave-driven roll. <3 = swamped. */
        val decayContrast: Double,
        /** Things I could not verify. Not fatal - but never hidden. See the note at `caveats`. */
        val caveats: List<String>,
        /** Does the raw gyroscope agree with the fused attitude? See AttitudeCheck. */
        val attitude: AttitudeCheck.Verdict,
        /** Speed and course, and WHERE THEY CAME FROM. Never a bare NaN. */
        val nav: Nav,
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
        nav: Nav = Nav.UNKNOWN,
        /** The SAME roll, integrated from the raw gyroscope alone. The one witness the ship's
         *  own acceleration cannot bribe. See AttitudeCheck. */
        phiGyro: DoubleArray? = null,
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

        // ---- 1b. HOW WELL CAN A RECORD THIS SHORT RESOLVE A PERIOD THIS LONG? --------------
        //
        // A Hann-windowed record of length D resolves frequencies no better than ~2/D. In
        // PERIOD terms that is dT = (2/D) * T^2, and it is brutal for a slow ship:
        //
        //      190 s record, 16 s roll  ->  only 12 cycles  ->  dT = +/- 2.7 s   (+/- 34% on GM)
        //
        // The first real record quoted its spectral peak as "16.20 s" and its uncertainty as
        // +/- 0.88 s. The honest figure was +/- 2.7 s. Quote a number to 0.01 s that is only
        // worth +/- 2.7 and you have not measured anything, you have decorated a guess.
        val resolutionLimit = (2.0 / durSecOf(detr, fs)) * tPsd * tPsd

        // ---- 1c. ASK THE SEA -------------------------------------------------------------
        // Does the roll peak have a twin in the accelerometer? If it does, this is a wave, and
        // nothing about this record says anything about GM. This is the ONLY check that can
        // catch a ship rolling at the wave period in a clean, single-peaked wind sea - the
        // competing-peak gate is blind to it, because there is nothing to compete with.
        // BEFORE asking the accelerometer about the sea, remove the part of the vertical
        // acceleration the SHIP'S OWN ROLL put there. The phone is not on the roll axis, so her
        // roll manufactures a_z at exactly f_n - the one frequency the veto assumes is quiet.
        // Real ship data found this; simulation never could. See SeaAnalyzer.removeRollInducedHeave.
        var leverArm = 0.0
        val sea: SeaAnalyzer.SeaState? = heave?.let { h ->
            if (h.size < detr.size / 2) null
            else {
                val n = minOf(h.size, detr.size)
                val (hCorr, lever) = SeaAnalyzer.removeRollInducedHeave(
                    h.copyOf(n), detr.copyOf(n), fs
                )
                leverArm = lever
                val psdAz = Dsp.welchPsd(Dsp.detrend(hCorr), fs, segLen, overlap = 0.5, padFactor = 4)
                SeaAnalyzer.analyse(psd, psdAz, peak.freq, fLo, fHi, leverArmM = lever)
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

        // THE UNCERTAINTY MUST NOT BE SMALLER THAN WHAT THE RECORD CAN PHYSICALLY RESOLVE.
        // uStat shrinks like 1/sqrt(n) and will happily go to zero; the resolution limit does
        // not. Take the worst of the three.
        val uPeriod = maxOf(maxOf(uStat, 0.5 * abs(tPsd - tZc)), resolutionLimit)

        // Scatter of the INDIVIDUAL cycles. This is the honest quality signal.
        val scatter = if (cycles.nCycles > 2 && cycles.meanPeriod > 0)
            cycles.sdPeriod / cycles.meanPeriod else Double.NaN

        // Free decay: did she actually ring DOWN, or is the "decay" swamped by wave-driven roll
        // that never stops? Contrast = biggest early swing / residual roll at the end.
        var contrast = Double.NaN
        if (mode == Mode.FREE_DECAY && core.size > 100) {
            val nEarly = core.size / 5
            val nLate = core.size / 3
            var early = 0.0
            for (i in 0 until nEarly) early = maxOf(early, abs(core[i]))
            var late2 = 0.0
            for (i in core.size - nLate until core.size) late2 += core[i] * core[i]
            val lateRms = sqrt(late2 / nLate)
            contrast = if (lateRms > 1e-6) early / lateRms else Double.MAX_VALUE
        }

        val meanAmp = if (cycles.amplitudes.isEmpty()) 0.0 else Dsp.mean(cycles.amplitudes)
        val maxAmp = cycles.amplitudes.maxOrNull() ?: 0.0

        // ---- 5. quality gates --------------------------------------------------------------
        val problems = ArrayList<String>()
        // A CAVEAT is not a PROBLEM. A problem means the number is wrong and the record is
        // rejected. A caveat means the number may be right but I could not check something I
        // should have been able to check - and staying quiet about that is how instruments lie.
        val caveats = ArrayList<String>()

        // ---- 0. IS THE ATTITUDE EVEN REAL? -----------------------------------------------
        //
        // Ask the raw gyroscope, which has never touched the accelerometer, whether the ship
        // actually rotated the way the fused attitude says she did. Anything in the fused
        // channel that the gyroscope did not see IS NOT ROTATION. This is checked FIRST,
        // because if the roll signal is an artefact then nothing computed from it means
        // anything - not the period, not the quality, and certainly not GM.
        val attitude = AttitudeCheck.compare(detr, phiGyro, fs, cfg.tMin, cfg.tMax)
        if (attitude.available && !attitude.agree) {
            problems.add(attitude.message)
        }
        if (!attitude.available) {
            caveats.add(
                "this device has no gyroscope, so the fused attitude could not be cross-checked. " +
                    "On a ship the accelerometer sees the hull's sway and surge, not just gravity, " +
                    "and a fused attitude can drift with it at the very frequencies a ship rolls at"
            )
        }

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

        // THE CYCLE SCATTER GATE.
        //
        // The first real record reported "methods agree to 1.7% - EXCELLENT" while its individual
        // cycles ran 10.2, 16.5, 10.3, 18.7, 19.0 s - a +/-20% scatter that never reached the
        // user. Of course the two methods agreed: the PSD peak and the zero-crossing MEDIAN are
        // both central-tendency estimators OF THE SAME SCATTERED DATA. Agreement between two
        // estimators of the same thing is not evidence about the thing.
        //
        // What the ship is actually telling us is in the spread of her own cycles.
        if (!scatter.isNaN() && scatter > 0.25) {
            problems.add(
                "the individual roll cycles scatter by ${fmt(scatter * 100)}% (" +
                    "${fmt(cycles.medianPeriod)} s median, sd ${fmt(cycles.sdPeriod)} s). She is not " +
                    "rolling at one clean period, so there is no single period to report"
            )
        }
        // SPEED. A "free" decay taken at speed is not free.
        //
        //   - every rudder movement rolls her, and under autopilot she is correcting
        //     continuously, so she is being RE-EXCITED several times a minute;
        //   - roll damping rises with speed (Ikeda's lift component), so she rings down faster
        //     and you get fewer usable cycles;
        //   - the steady wave pattern, sinkage and trim at speed raise the waterplane area aft,
        //     which raises BM and hence GM - so the period you measure at 14 kn is NOT the
        //     period she would have at rest, and the loading computer's GM is a zero-speed
        //     hydrostatic number (Grin, IMDC 2024);
        //   - and GPS gives speed over GROUND, while the encounter relation wants speed through
        //     the WATER.
        //
        // Slowing down kills all four at once. It is the same conclusion the century-old roll
        // test reached: calm water, let her roll.
        if (mode == Mode.FREE_DECAY && nav.speedKnown && nav.sogKn > 6.0) {
            problems.add(
                "she was making ${fmt(nav.sogKn)} kn. A free decay at speed is not free: the autopilot " +
                    "re-excites her roll at every rudder correction, speed adds lift damping, and " +
                    "forward speed changes GM itself. Slow to under 3 kn, or stop"
            )
        }

        // AND HERE IS THE CASE THAT USED TO PASS IN SILENCE.
        //
        // The old test was `if (!sogKn.isNaN() && sogKn > 6.0)`. With no GPS fix, sogKn is NaN,
        // NaN is not greater than 6, and a free decay taken at fourteen knots went through the
        // gate reported as EXCELLENT. Unknown speed was being treated as zero speed.
        //
        // A phone inside a steel deckhouse routinely has no fix. This was the normal case, not
        // an exotic one. We cannot reject the record - she may genuinely be stopped - but we can
        // refuse to pretend, and we can tell the operator exactly what to do about it: read the
        // speed off the bridge and type it in.
        if (mode == Mode.FREE_DECAY && !nav.speedKnown) {
            caveats.add(
                "I do not know how fast she was going - ${nav.detail}. A free decay is only valid " +
                    "at low speed, and I cannot confirm that. Enter SOG and COG by hand on the Sea " +
                    "tab, or take the record with a GPS fix"
            )
        }
        if (mode == Mode.SEAWAY && !nav.courseKnown) {
            caveats.add(
                "no course over ground - ${nav.detail}. Without it I cannot compute the encounter " +
                    "period of the forecast waves, so I cannot tell you whether the peak below is " +
                    "her or the sea meeting her. Enter SOG and COG by hand on the Sea tab"
            )
        }
        if (nav.speedKnown && !nav.steady) {
            caveats.add(
                "she was not holding a steady course or speed through this record. That smears the " +
                    "encounter frequency across a range and broadens every peak in the spectrum"
            )
        }
        if (mode == Mode.FREE_DECAY && !contrast.isNaN() && contrast < 3.0) {
            problems.add(
                "the decay is swamped: her biggest swing is only ${fmt(contrast)}x the roll still " +
                    "running at the end of the record. This was not calm water. Roll her harder, or " +
                    "do it somewhere more sheltered"
            )
        }

        // Quality is now driven by the CYCLE SCATTER, not by the agreement between two
        // estimators of the same central tendency. Agreement is still shown, but it is a
        // consistency check on the arithmetic, not evidence about the ship.
        val sc = if (scatter.isNaN()) 1.0 else scatter
        val quality = when {
            problems.isNotEmpty() -> Quality.POOR
            sc < 0.08 && agreement < 0.05 -> Quality.EXCELLENT
            sc < 0.15 && agreement < 0.10 -> Quality.GOOD
            sc < 0.25 -> Quality.FAIR
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
            periodScatter = scatter,
            resolutionLimit = resolutionLimit,
            decayContrast = contrast,
            caveats = caveats,
            attitude = attitude,
            nav = nav,
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
        competingPeriod = Double.NaN, competingRatio = 0.0,
        periodScatter = Double.NaN, resolutionLimit = Double.NaN, decayContrast = Double.NaN,
        caveats = emptyList(), nav = Nav.UNKNOWN, attitude = AttitudeCheck.NONE,
        sea = null, quality = Quality.POOR,
        phi = DoubleArray(0), fs = fs, psdFreq = DoubleArray(0), psdPower = DoubleArray(0)
    )

    private fun durSecOf(x: DoubleArray, fs: Double) = x.size / fs

    private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.1f", v)
}
