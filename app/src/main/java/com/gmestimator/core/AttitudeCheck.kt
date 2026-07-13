package com.gmestimator.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * THE CROSS-CHECK THE MASTER ASKED FOR.
 *
 * "the system was reading the accelerometer data and gyro... but never considered to corroborate
 *  the sensor returned data with the fused accelerometer and gyro data - the attitude"
 *
 * He is right that the phone is very good at attitude. He is right that we should corroborate.
 * And the corroboration turns out to matter more than either of us expected, because of WHICH WAY
 * the dependency runs:
 *
 * GAME_ROTATION_VECTOR - the fused attitude we have been trusting all along - is not independent
 * of the accelerometer. It IS the gyroscope AND the accelerometer, blended. The gyro supplies the
 * fast motion; the accelerometer supplies the slow "which way is down" that keeps the gyro from
 * drifting away.
 *
 * On land that is exactly right. On a ship it is a trap. The accelerometer does not measure
 * gravity - it measures SPECIFIC FORCE, which is gravity plus the ship's own sway, surge, and the
 * lever-arm acceleration of the phone about the roll axis. So the filter's idea of "down" gets
 * dragged around by the ship's motion. And it is dragged around SLOWLY - which is to say, at
 * exactly the frequencies at which a ship rolls. Androklis answers at 17.6 s: 0.057 Hz. That is
 * not a safe place to assume a drift-correction loop is quiet.
 *
 * We cannot see inside Android's fusion filter, so we cannot argue about it. We can only measure
 * it - against a witness that has no accelerometer in it at all.
 *
 *      A GYROSCOPE CANNOT SEE GRAVITY.
 *      It cannot see sway, surge, or heave. It measures rotation rate, and nothing else.
 *
 * So strapdown-integrate the raw gyro, band-pass away its drift, and compare. Then:
 *
 *      ANYTHING IN THE FUSED ATTITUDE THAT IS ABSENT FROM THE GYROSCOPE IS NOT ROTATION.
 *
 * That is not a heuristic or a tuned threshold. It is what the two instruments physically are.
 * If the fused channel shows a 17.6 s peak and the gyroscope does not, then the ship did not roll
 * at 17.6 s, and the GM computed from it is a number about a filter, not about a ship.
 */
object AttitudeCheck {

    data class Verdict(
        /** true if the two witnesses tell the same story */
        val agree: Boolean,
        /** did we have a gyroscope at all? */
        val available: Boolean,
        val periodFused: Double,      // s, from the fused attitude
        val periodGyro: Double,       // s, from the raw gyroscope alone
        val correlation: Double,      // -1..1, over the band, after alignment
        val amplitudeRatio: Double,   // gyro RMS / fused RMS
        val message: String
    )

    val NONE = Verdict(
        agree = true, available = false,
        periodFused = Double.NaN, periodGyro = Double.NaN,
        correlation = Double.NaN, amplitudeRatio = Double.NaN,
        message = "no gyroscope on this device - the fused attitude cannot be cross-checked"
    )

    /** Periods this far apart mean the two sensors are not describing the same motion. */
    private const val PERIOD_TOLERANCE = 0.10

    /** Below this correlation the two channels are not even looking at the same thing. */
    private const val MIN_CORRELATION = 0.5

    fun compare(
        phiFused: DoubleArray,
        phiGyro: DoubleArray?,
        fs: Double,
        tMin: Double = 3.0,
        tMax: Double = 45.0
    ): Verdict {
        if (phiGyro == null || phiGyro.size < 100) return NONE

        val n = minOf(phiFused.size, phiGyro.size)
        if (n < 100) return NONE

        val fLo = 1.0 / tMax
        val fHi = 1.0 / tMin

        val a = Dsp.bandpassFft(Dsp.detrend(phiFused.copyOf(n)), fs, fLo, fHi)
        val b = Dsp.bandpassFft(Dsp.detrend(phiGyro.copyOf(n)), fs, fLo, fHi)

        // Drop the edges: the band-pass rings there, and the gyro integration is still settling.
        val g = (0.10 * n).toInt()
        val ca = a.copyOfRange(g, n - g)
        val cb = b.copyOfRange(g, n - g)

        val segTarget = Dsp.nextPow2((fs * 4.0 * tMax).toInt())
        val segLen = minOf(segTarget, Dsp.nextPow2(ca.size / 2)).coerceAtLeast(256)
        val pa = Dsp.welchPsd(ca, fs, segLen, overlap = 0.5, padFactor = 4)
        val pb = Dsp.welchPsd(cb, fs, segLen, overlap = 0.5, padFactor = 4)

        val peakA = Dsp.dominantPeak(pa, fLo, fHi)
        val peakB = Dsp.dominantPeak(pb, fLo, fHi)
        val tA = if (peakA != null) 1.0 / peakA.freq else Double.NaN
        val tB = if (peakB != null) 1.0 / peakB.freq else Double.NaN

        // correlation and amplitude ratio, sign-free: we do not care which way the phone was
        // facing, only whether the two saw the same motion.
        var sab = 0.0; var saa = 0.0; var sbb = 0.0
        for (i in ca.indices) {
            sab += ca[i] * cb[i]; saa += ca[i] * ca[i]; sbb += cb[i] * cb[i]
        }
        val corr = if (saa < 1e-12 || sbb < 1e-12) 0.0 else sab / sqrt(saa * sbb)
        val ratio = if (saa < 1e-12) Double.NaN else sqrt(sbb / saa)

        val periodsAgree =
            !tA.isNaN() && !tB.isNaN() && abs(tA - tB) / tA < PERIOD_TOLERANCE
        val correlated = abs(corr) > MIN_CORRELATION
        val agree = periodsAgree && correlated

        val msg = when {
            agree -> "The raw gyroscope confirms the fused attitude: both see ${f2(tA)} s " +
                "(correlation ${f2(abs(corr))}). The accelerometer is not driving this peak."

            !periodsAgree && !tA.isNaN() && !tB.isNaN() ->
                "THE TWO SENSORS DISAGREE. The fused attitude peaks at ${f2(tA)} s; the raw " +
                    "gyroscope peaks at ${f2(tB)} s. A gyroscope cannot see gravity, sway or " +
                    "surge - it sees rotation and nothing else. So the ${f2(tA)} s in the fused " +
                    "channel IS NOT ROTATION: it is the fusion filter's own 'which way is down' " +
                    "being dragged about by the ship's lateral acceleration. Believe the " +
                    "gyroscope: she is turning at ${f2(tB)} s"

            !correlated ->
                "The fused attitude and the raw gyroscope are barely correlated " +
                    "(${f2(abs(corr))}). They are not describing the same motion, and at least " +
                    "one of them is not describing the ship"

            else -> "The gyroscope cross-check could not be completed"
        }

        return Verdict(agree, true, tA, tB, corr, ratio, msg)
    }

    private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)
}
