package com.gmestimator

import com.gmestimator.core.Dsp
import com.gmestimator.core.GmModel
import com.gmestimator.core.PeriodEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * These tests are the acceptance criteria for the instrument. They were first run as a Python
 * port of the same algorithm against synthetic roll records (see tools/verify_dsp.py); the
 * numbers below are the tolerances that port achieved.
 *
 * NOTE ON TOLERANCES. They are not uniform, and deliberately so:
 *
 *   deterministic signals (pure sine, free decay)  -> tight (1-3%). A failure is a real bug.
 *   irregular seaway                               -> loose (7%). A ship rolling in a random sea
 *                                                     is a RANDOM PROCESS; a finite record is one
 *                                                     draw from a distribution. A tight bound here
 *                                                     would only be testing the RNG seed.
 *   swell rejection                                -> must ALWAYS reject. Non-negotiable: a missed
 *                                                     swell is a confidently wrong GM, in the
 *                                                     dangerous direction.
 *
 * See docs/VALIDATION.md.
 */
class DspTest {

    private val fs = 25.0
    private val rng = Random(20260713)

    private fun gauss(): Double {
        // Box-Muller
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2.0 * PI * u2)
    }

    /** Roll of a 1-DOF oscillator driven by white-noise waves: what irregular-sea roll looks like. */
    private fun narrowbandRoll(tn: Double, durSec: Double, ampDeg: Double, zeta: Double = 0.05): DoubleArray {
        val n = (durSec * fs).toInt()
        val sub = 8
        val wn = 2 * PI / tn
        val dt = 1.0 / (fs * sub)
        val x = DoubleArray(n)
        var v = 0.0
        var p = 0.0
        for (i in 0 until n) {
            repeat(sub) {
                val f = gauss() * wn * wn * 3.0 * sqrt(sub.toDouble())
                val a = f - 2 * zeta * wn * v - wn * wn * p
                v += a * dt
                p += v * dt
            }
            x[i] = p
        }
        val s = Dsp.std(x).coerceAtLeast(1e-9)
        return DoubleArray(n) { x[it] / s * ampDeg }
    }

    private fun freeDecay(tn: Double, durSec: Double, amp0: Double, zeta: Double): DoubleArray {
        val n = (durSec * fs).toInt()
        val wn = 2 * PI / tn
        val wd = wn * sqrt(1 - zeta * zeta)
        return DoubleArray(n) {
            val t = it / fs
            amp0 * exp(-zeta * wn * t) * cos(wd * t)
        }
    }

    // ------------------------------------------------------------------ FFT

    @Test
    fun `fft round trip is lossless`() {
        val n = 256
        val re = DoubleArray(n) { sin(2 * PI * 5 * it / n) + 0.3 * cos(2 * PI * 17 * it / n) }
        val orig = re.copyOf()
        val im = DoubleArray(n)
        Dsp.fft(re, im, false)
        Dsp.fft(re, im, true)
        for (i in 0 until n) assertEquals(orig[i], re[i], 1e-9)
    }

    @Test
    fun `detrend removes a static list and a linear drift`() {
        val n = 1000
        val x = DoubleArray(n) { 5.0 + 0.01 * it + sin(2 * PI * it / 100.0) }
        val d = Dsp.detrend(x)
        assertEquals(0.0, Dsp.mean(d), 1e-9)
        // the oscillation must survive
        assertTrue(Dsp.std(d) > 0.6)
    }

    // ------------------------------------------------------------------ period recovery

    @Test
    fun `pure sinusoid period recovered to better than 1 percent`() {
        for (tn in listOf(8.0, 12.5, 18.0, 26.0)) {
            val n = (600 * fs).toInt()
            val phi = DoubleArray(n) { 4.0 * sin(2 * PI * (it / fs) / tn) }
            val r = PeriodEstimator.estimate(phi, fs, PeriodEstimator.Mode.SEAWAY, 0.99)
            assertTrue("no result for T=$tn", !r.period.isNaN())
            assertEquals("T=$tn", tn, r.period, 0.01 * tn)
            assertTrue("methods should agree on a pure sine", r.agreement < 0.01)
        }
    }

    @Test
    fun `free decay recovers both the natural period and the damping ratio`() {
        for ((tn, zeta) in listOf(10.0 to 0.03, 14.0 to 0.05, 20.0 to 0.08)) {
            val phi = freeDecay(tn, 240.0, 6.0, zeta).map { it + 0.02 * gauss() }.toDoubleArray()
            val r = PeriodEstimator.estimate(phi, fs, PeriodEstimator.Mode.FREE_DECAY, 0.99)
            assertEquals("T for zeta=$zeta", tn, r.period, 0.02 * tn)
            assertEquals("zeta", zeta, r.zeta, 0.02)
        }
    }

    /**
     * 7%, not 3%. A ship rolling in an irregular sea is a narrow-band RANDOM response to broadband
     * wave forcing, so a single 20-minute record is one draw from a distribution: re-seeding the
     * generator moves the answer by several percent. That scatter is a real property of the
     * measurement, not of the code, and it is a large part of why FREE_DECAY is the recommended
     * mode. Do not tighten this bound by hunting for a seed that passes.
     */
    @Test
    fun `resonant roll in an irregular seaway is recovered, with realisation scatter`() {
        for (tn in listOf(11.0, 16.0, 22.0)) {
            val phi = narrowbandRoll(tn, 1200.0, 3.5)
            val r = PeriodEstimator.estimate(phi, fs, PeriodEstimator.Mode.SEAWAY, 0.95)
            assertEquals("T=$tn", tn, r.period, 0.07 * tn)
        }
    }

    @Test
    fun `static list, gyro bias drift and engine vibration do not shift the period`() {
        val tn = 13.0
        val base = narrowbandRoll(tn, 900.0, 3.0)
        val phi = DoubleArray(base.size) {
            val t = it / fs
            base[it] + 4.5 + 0.002 * t + 0.05 * sin(2 * PI * 8.0 * t) + 0.05 * gauss()
        }
        val r = PeriodEstimator.estimate(phi, fs, PeriodEstimator.Mode.SEAWAY, 0.95)
        assertEquals(tn, r.period, 0.07 * tn)
    }

    // ------------------------------------------------------------------ the safety gate

    @Test
    fun `swell contamination is detected and the record is rejected`() {
        // The ship's natural period is 15 s, but a 24 s swell is forcing her. A naive spectral
        // peak reports 24 s -> GM would come out 2.5x too small. This MUST be rejected.
        val tn = 15.0
        val base = narrowbandRoll(tn, 1200.0, 2.0)
        val phi = DoubleArray(base.size) { base[it] + 3.0 * sin(2 * PI * (it / fs) / 24.0) }
        val r = PeriodEstimator.estimate(phi, fs, PeriodEstimator.Mode.SEAWAY, 0.95)

        assertTrue("a competing peak must be found", !r.competingPeriod.isNaN())
        assertTrue("competing peak must be strong", r.competingRatio > 0.25)
        assertTrue("record must be rejected", !r.ok)
        assertEquals(PeriodEstimator.Quality.POOR, r.quality)
        assertTrue(r.message.contains("TWO roll periods"))
    }

    @Test
    fun `clean resonant roll is NOT falsely rejected as bimodal`() {
        for (tn in listOf(9.0, 11.0, 16.0, 22.0, 28.0)) {
            val phi = narrowbandRoll(tn, 1200.0, 3.5)
            val r = PeriodEstimator.estimate(phi, fs, PeriodEstimator.Mode.SEAWAY, 0.95)
            assertTrue(
                "clean seaway T=$tn falsely rejected: ${r.message}",
                r.competingRatio < 0.25
            )
        }
    }

    @Test
    fun `unimodal spectrum yields no secondary peak`() {
        val n = (600 * fs).toInt()
        val phi = DoubleArray(n) { 4.0 * sin(2 * PI * (it / fs) / 14.0) }
        val psd = Dsp.welchPsd(Dsp.detrend(phi), fs, 2048)
        val sec = Dsp.secondaryPeak(psd, 1.0 / 45.0, 1.0 / 3.0)
        assertTrue("a pure sine must not produce a competing peak", sec == null || sec.second < 0.05)
    }

    /**
     * REGRESSION. The edge guard used to be a fixed 45 s (= tMax) at each end. On the 3-minute
     * free-decay record the app itself recommends, that left only 90 s of usable signal, so a ship
     * with a normal 12-25 s roll period never produced enough cycles to pass the quality gate:
     * the RECOMMENDED procedure failed every single time. The guard is now scaled to the measured
     * period and capped at 10% of the record.
     */
    @Test
    fun `the recommended 3-minute free-decay record passes for realistic roll periods`() {
        for (tn in listOf(10.0, 14.0, 18.0, 22.0, 26.0)) {
            val phi = freeDecay(tn, 180.0, 6.0, 0.05).map { it + 0.02 * gauss() }.toDoubleArray()
            val r = PeriodEstimator.estimate(phi, fs, PeriodEstimator.Mode.FREE_DECAY, 0.99)
            assertTrue(
                "180 s free decay at T=$tn s was rejected: ${r.message} (${r.nCycles} cycles)",
                r.ok
            )
            assertEquals("T=$tn", tn, r.period, 0.03 * tn)
        }
    }

    @Test
    fun `too short a record is rejected rather than guessed at`() {
        val phi = narrowbandRoll(14.0, 40.0, 3.0)
        val r = PeriodEstimator.estimate(phi, fs, PeriodEstimator.Mode.SEAWAY, 0.95)
        assertTrue(!r.ok)
    }

    // ------------------------------------------------------------------ principal axis

    @Test
    fun `principal axis finds the roll direction whatever the phone heading`() {
        val n = 5000
        for (headingDeg in listOf(0.0, 30.0, 75.0, 120.0, 200.0)) {
            val c = cos(headingDeg * PI / 180)
            val s = sin(headingDeg * PI / 180)
            val ax = DoubleArray(n)
            val ay = DoubleArray(n)
            for (i in 0 until n) {
                val roll = 5.0 * sin(2 * PI * (i / fs) / 14.0)
                val pitch = 1.0 * sin(2 * PI * (i / fs) / 7.0)   // 5x smaller, different period
                ax[i] = c * roll - s * pitch
                ay[i] = s * roll + c * pitch
            }
            val pa = Dsp.principalAxis(ax, ay)
            // the recovered axis must be parallel (or anti-parallel) to the true roll direction
            val dot = abs(pa.ux * c + pa.uy * s)
            assertTrue("heading=$headingDeg dot=$dot", dot > 0.99)
            assertTrue("roll should dominate pitch", pa.dominance > 0.90)
        }
    }

    // ------------------------------------------------------------------ GM model

    @Test
    fun `IS Code coefficient matches the published formula`() {
        // B=32.2 d=11.0 Lwl=190  ->  C = 0.373 + 0.023*(32.2/11) - 0.043*1.9
        val c = GmModel.isCodeC(32.2, 11.0, 190.0)
        assertEquals(0.373 + 0.023 * (32.2 / 11.0) - 0.043 * 1.9, c, 1e-9)
        // and f is exactly 2C - the factor-of-two convention must never drift
        assertEquals(2.0 * c, GmModel.isCodeF(32.2, 11.0, 190.0), 1e-12)
    }

    @Test
    fun `GM and period are exact inverses of each other`() {
        val b = 32.2
        val f = 0.80
        for (gm in listOf(0.35, 1.20, 3.50, 8.00)) {
            val t = GmModel.periodFromGm(gm, f, b)
            assertEquals(gm, GmModel.gmFromPeriod(t, f, b), 1e-9)
            assertEquals(f, GmModel.fFromKnownGm(t, gm, b), 1e-9)
        }
    }

    @Test
    fun `relative errors double when going from period to GM`() {
        val r = GmModel.evaluate(
            period = 20.0, periodUncertainty = 0.2,   // 1%
            f = 0.80, fRelUncertainty = 0.0,
            beam = 30.0, fSource = GmModel.FSource.MANUAL
        )
        assertEquals(0.02, r.relUncertainty, 1e-9)   // 1% in T -> 2% in GM

        val r2 = GmModel.evaluate(
            period = 20.0, periodUncertainty = 0.2,   // 1%
            f = 0.80, fRelUncertainty = 0.08,        // IS Code scatter
            beam = 30.0, fSource = GmModel.FSource.IS_CODE
        )
        // 2*sqrt(0.01^2 + 0.08^2) = 0.1612
        assertEquals(0.1612, r2.relUncertainty, 1e-3)
        assertTrue("the roll coefficient must dominate the error budget", r2.relUncertainty > 0.15)
    }
}
