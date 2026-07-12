package com.gmestimator

import com.gmestimator.core.Dsp
import com.gmestimator.core.PeriodEstimator
import com.gmestimator.core.SeaAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Acceptance tests for the sea-lock veto.
 *
 * The Kotlin counterpart of tools/sea_lock_test.py, which over 200 random ship x sea
 * combinations caught 100% of sea-driven roll peaks with ZERO misses, and of
 * tools/verify_sealock.py, which confirms the populations separate cleanly at the app's own
 * resolution: genuine resonance 0.9 +/- 1.4 dB of accelerometer excess, sea-driven peaks
 * 11.2 +/- 1.9 dB, with an empty gap between them.
 *
 * The physics, in one line:
 *
 *      A SWELL IS AN INPUT   - it appears in the roll AND in the heave.
 *      A RESONANCE IS THE SHIP - it appears ONLY in the roll.
 */
class SeaAnalyzerTest {

    private val fs = 25.0
    private val rng = Random(31337)

    private fun gauss(): Double {
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2.0 * PI * u2)
    }

    /**
     * A narrow-band random process. A real swell looks like THIS - not like a sine wave.
     * (That distinction killed two of my first three discriminator ideas: a narrow-band random
     * process is Gaussian, so kurtosis and envelope statistics cannot tell it from a resonance.)
     */
    private fun narrowBand(t: Double, n: Int, amp: Double, zeta: Double = 0.04): DoubleArray {
        val wn = 2 * PI / t
        val sub = 8
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
        return DoubleArray(n) { x[it] / s * amp }
    }

    private fun psd(x: DoubleArray) = Dsp.welchPsd(Dsp.detrend(x), fs, 4096)

    // ------------------------------------------------------------------------------------
    // THE HOLE THIS EXISTS TO PLUG
    // ------------------------------------------------------------------------------------

    /**
     * The dangerous case, and the one the bimodality gate is blind to.
     *
     * A tender ship (T_n = 15 s) in a plain wind sea with NO swell. A JONSWAP spectrum has
     * almost no energy at 15 s, so she never resonates - she rolls at the WAVE period, 8 s.
     * The roll spectrum has ONE clean peak. PSD and zero-crossing agree perfectly. The old code
     * reported T = 8 s with quality EXCELLENT, and GM came out 3.5x TOO LARGE: a tender ship
     * reported as stiff, which is the worst direction to be wrong in.
     *
     * The accelerometer is the only witness. It peaks at 8 s too.
     */
    @Test
    fun `a ship rolling at the wave period is caught, even with only one peak in the spectrum`() {
        val n = (1800 * fs).toInt()

        // both roll and heave are driven by the same 8 s sea: the roll peak has a TWIN
        val sea = narrowBand(8.0, n, 1.0)
        val roll = DoubleArray(n) { 2.0 * sea[it] + 0.02 * gauss() }
        val heave = DoubleArray(n) { 3.0 * sea[it] + 0.02 * gauss() }

        val r = PeriodEstimator.estimate(roll, fs, PeriodEstimator.Mode.SEAWAY, 0.95, heave)

        assertNotNull("the sea must have been analysed", r.sea)
        assertTrue("the accelerometer must show a feature at the roll peak", r.sea!!.seaLocked)
        assertTrue("the record must be REJECTED", !r.ok)
        assertTrue(
            "the message must say why: ${r.message}",
            r.message.contains("also appears in the accelerometer")
        )
    }

    /**
     * The complement: a genuine resonance. The ship rolls at 15 s while the sea's energy sits at
     * 8 s. The accelerometer is smooth at 15 s - it has no idea the ship is doing anything there,
     * because the ship puts no energy into the sea.
     */
    @Test
    fun `a genuine roll resonance is NOT flagged as sea`() {
        val n = (1800 * fs).toInt()
        val resonance = narrowBand(15.0, n, 3.0, zeta = 0.05)   // the ship
        val waves = narrowBand(8.0, n, 1.0)                     // the sea

        val roll = DoubleArray(n) { resonance[it] + 0.4 * waves[it] + 0.02 * gauss() }
        val heave = DoubleArray(n) { 3.0 * waves[it] + 0.02 * gauss() }   // NO 15 s content

        val r = PeriodEstimator.estimate(roll, fs, PeriodEstimator.Mode.SEAWAY, 0.95, heave)

        assertNotNull(r.sea)
        assertFalse(
            "a resonance has no twin in the accelerometer: ${r.sea!!.message}",
            r.sea!!.seaLocked
        )
        assertEquals("period", 15.0, r.period, 0.06 * 15.0)
    }

    @Test
    fun `the sea's own dominant period is reported`() {
        val n = (1800 * fs).toInt()
        val heave = narrowBand(11.0, n, 1.0)
        val roll = narrowBand(17.0, n, 2.0)
        val sea = SeaAnalyzer.analyse(psd(roll), psd(heave), 1.0 / 17.0, 1.0 / 45.0, 1.0 / 3.0)
        assertNotNull(sea)
        assertEquals("dominant wave period", 11.0, sea!!.wavePeakPeriod, 1.5)
    }

    @Test
    fun `seaway mode without a heave signal is refused outright`() {
        val n = (1800 * fs).toInt()
        val roll = narrowBand(15.0, n, 3.0)
        val r = PeriodEstimator.estimate(roll, fs, PeriodEstimator.Mode.SEAWAY, 0.95, null)
        assertTrue("must not silently accept a blind seaway record", !r.ok)
        assertTrue(r.message.contains("no heave signal"))
    }

    /** Free decay is done in calm water, so there is no sea to check and no veto to apply. */
    @Test
    fun `free decay is unaffected by the sea-lock veto`() {
        val n = (240 * fs).toInt()
        val wn = 2 * PI / 14.0
        val zeta = 0.05
        val wd = wn * sqrt(1 - zeta * zeta)
        val roll = DoubleArray(n) {
            val t = it / fs
            6.0 * exp(-zeta * wn * t) * cos(wd * t) + 0.02 * gauss()
        }
        val r = PeriodEstimator.estimate(roll, fs, PeriodEstimator.Mode.FREE_DECAY, 0.99, null)
        assertTrue("free decay must still work with no heave signal: ${r.message}", r.ok)
        assertEquals(14.0, r.period, 0.03 * 14.0)
    }

    // ------------------------------------------------------------------------------------
    // THE ENCOUNTER TEST
    // ------------------------------------------------------------------------------------

    @Test
    fun `encounter period shifts with heading, exactly as the dispersion relation says`() {
        val t0 = 12.0
        val u = 6.0                                            // m/s, about 12 knots
        val head = SeaAnalyzer.encounterPeriod(t0, u, 0.0)     // steaming into them
        val beam = SeaAnalyzer.encounterPeriod(t0, u, 90.0)    // waves on the beam
        val follow = SeaAnalyzer.encounterPeriod(t0, u, 180.0) // running with them

        assertEquals("on the beam, encounter = true period", t0, beam, 1e-6)
        assertTrue("meeting waves shortens the encounter period", head < t0)
        assertTrue("running with them lengthens it", follow > t0)

        val w0 = 2 * PI / t0
        assertEquals(2 * PI / (w0 - w0 * w0 / 9.80665 * u), head, 1e-6)
    }

    @Test
    fun `a period that holds across a big course change is the ship`() {
        val a = SeaAnalyzer.RecordSummary("#1", 15.1, 12.0, 10.0)
        val b = SeaAnalyzer.RecordSummary("#2", 14.9, 12.0, 100.0)   // 90 deg away
        val v = SeaAnalyzer.encounterCheck(a, b)
        assertTrue("it did not move: ${v.message}", v.conclusive)
        assertEquals(15.0, v.shipPeriod, 0.2)
    }

    @Test
    fun `a period that moves with heading is the sea, and both records are condemned`() {
        val a = SeaAnalyzer.RecordSummary("#1", 11.0, 12.0, 10.0)
        val b = SeaAnalyzer.RecordSummary("#2", 17.0, 12.0, 110.0)
        val v = SeaAnalyzer.encounterCheck(a, b)
        assertFalse(v.conclusive)
        assertTrue(v.message.contains("measuring the SEA"))
    }

    @Test
    fun `two records on the same heading prove nothing, and say so`() {
        val a = SeaAnalyzer.RecordSummary("#1", 15.0, 12.0, 10.0)
        val b = SeaAnalyzer.RecordSummary("#2", 15.0, 12.5, 15.0)   // barely different
        val v = SeaAnalyzer.encounterCheck(a, b)
        assertFalse("no course change -> no information", v.conclusive)
        assertTrue(v.message.contains("cannot separate"))
    }
}
