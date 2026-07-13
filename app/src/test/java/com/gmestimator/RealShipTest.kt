package com.gmestimator

import com.gmestimator.core.Dsp
import com.gmestimator.core.ForecastAdvisor
import com.gmestimator.core.PeriodEstimator
import com.gmestimator.core.SeaAnalyzer
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
 * REGRESSION TESTS FROM THE FIRST REAL SHIP.
 *
 * m/v Androklis, B = 27.4 m, 190 s free-decay record, ~16 s roll, 1.2 deg amplitude.
 *
 * The app reported:  T = 16.40 +/- 0.88 s, quality EXCELLENT, "methods agree to 1.7%".
 * The truth was:     the individual cycles ran 10.2, 16.5, 10.3, 18.7, 19.0 s - a +/-20%
 *                    scatter - and a 12-cycle record cannot resolve the period better than
 *                    +/- 2.7 s. The report also printed "THIS IS A WAVE, NOT THE SHIP" three
 *                    lines above "GM = 1.54 m, quality EXCELLENT".
 *
 * Nothing in the simulation suite caught any of it. These tests exist so it cannot come back.
 */
class RealShipTest {

    private val fs = 25.0
    private val rng = Random(4242)

    private fun gauss(): Double {
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        return sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2.0 * PI * rng.nextDouble())
    }

    /**
     * A roll that WANDERS: a decaying ring-down at 15 s sitting on wave-driven roll at 11 s.
     * Its cycles scatter wildly, exactly as the Androklis cycles did - yet a PSD peak and a
     * zero-crossing median will still agree with each other, because they are both averaging
     * the same mess.
     */
    private fun wanderingRoll(n: Int): DoubleArray = DoubleArray(n) {
        val t = it / fs
        3.0 * exp(-0.05 * (2 * PI / 15.0) * t) * cos(2 * PI * t / 15.0) +
            0.9 * cos(2 * PI * t / 11.0 + 1.1) + 0.05 * gauss()
    }

    /**
     * THE BIG ONE. Agreement between the two estimators is NOT evidence, and the old quality
     * label said EXCELLENT on exactly this kind of record.
     */
    @Test
    fun `a roll whose cycles scatter is not EXCELLENT, however well the two methods agree`() {
        val phi = wanderingRoll((600 * fs).toInt())
        val r = PeriodEstimator.estimate(phi, fs, PeriodEstimator.Mode.FREE_DECAY, 0.95, null)

        assertTrue("cycle scatter must be measured", !r.periodScatter.isNaN())
        assertTrue(
            "scatter is ${r.periodScatter} - this record is a mess and must not read EXCELLENT",
            r.quality != PeriodEstimator.Quality.EXCELLENT
        )
    }

    /**
     * The uncertainty may never be smaller than what the record can physically resolve.
     * A 190 s record at a 16 s period resolves the period to about +/- 2.7 s. The old code
     * quoted +/- 0.88 s.
     */
    @Test
    fun `the uncertainty respects the resolution limit of a short record`() {
        val n = (190 * fs).toInt()
        val phi = DoubleArray(n) { 2.0 * sin(2 * PI * (it / fs) / 16.0) + 0.02 * gauss() }
        val r = PeriodEstimator.estimate(phi, fs, PeriodEstimator.Mode.FREE_DECAY, 0.99, null)

        val expected = (2.0 / 190.0) * 16.0 * 16.0        // ~2.7 s
        assertEquals("resolution limit", expected, r.resolutionLimit, 0.5)
        assertTrue(
            "u(T) = ${r.periodUncertainty} s must not be below the ${r.resolutionLimit} s the " +
                "record can resolve",
            r.periodUncertainty >= r.resolutionLimit * 0.99
        )
    }

    /**
     * THE LEVER ARM. The phone is not on the roll axis, so the ship's own roll manufactures
     * vertical acceleration at exactly f_n - and the sea-lock test assumes silence there.
     * Simulation never showed this because I had implicitly put the phone at y = 0.
     */
    @Test
    fun `the ship's own roll is removed from the accelerometer before the sea is judged`() {
        val n = (900 * fs).toInt()
        val tn = 16.0
        val w = 2 * PI / tn
        val leverTrue = 9.0                                   // metres off the roll axis

        val phi = DoubleArray(n) { 1.5 * sin(w * (it / fs)) }  // deg
        // a_z from the lever arm alone: a_z = y * phi_ddot
        val noise = 0.002
        val heave = DoubleArray(n) {
            val p = phi[it] * PI / 180.0
            leverTrue * (-w * w * p) + noise * gauss()
        }

        val (corrected, lever) = SeaAnalyzer.removeRollInducedHeave(heave, phi, fs)
        assertEquals("the fitted lever arm", leverTrue, lever, 1.0)

        val before = Dsp.std(heave)
        val after = Dsp.std(corrected)
        assertTrue(
            "the roll-induced heave must be removed ($before -> $after)",
            after < 0.25 * before
        )
        // and what is LEFT should be nothing but the sensor noise - i.e. the correction is not
        // merely shrinking the signal, it is removing precisely the roll-coherent part.
        assertTrue(
            "the residual ($after) should be down at the noise floor ($noise)",
            after < 3.0 * noise
        )
    }

    /** A free decay that never actually rings down is not a free decay. */
    @Test
    fun `a decay swamped by wave-driven roll is refused`() {
        val n = (600 * fs).toInt()
        val phi = DoubleArray(n) {
            val t = it / fs
            0.8 * exp(-0.05 * (2 * PI / 15.0) * t) * cos(2 * PI * t / 15.0) +   // feeble decay
                1.2 * cos(2 * PI * t / 15.4 + 0.7) + 0.03 * gauss()             // never stops
        }
        val r = PeriodEstimator.estimate(phi, fs, PeriodEstimator.Mode.FREE_DECAY, 0.95, null)
        assertTrue("decay contrast must be computed", !r.decayContrast.isNaN())
        assertTrue(
            "contrast ${r.decayContrast} - she never rang down, so this must be refused: ${r.message}",
            !r.ok
        )
    }

    /**
     * A "free" decay at 14 kn is not free. The autopilot re-excites her roll at every rudder
     * correction; speed adds lift damping; and forward speed raises GM itself, so the period is
     * not the one the loading computer's zero-speed GM corresponds to.
     */
    @Test
    fun `a free decay taken at speed is refused`() {
        val n = (600 * fs).toInt()
        val tn = 15.0
        val z = 0.05
        val wn = 2 * PI / tn
        val phi = DoubleArray(n) {
            val t = it / fs
            8.0 * exp(-z * wn * t) * cos(wn * sqrt(1 - z * z) * t) + 0.02 * gauss()
        }
        val slow = PeriodEstimator.estimate(phi, fs, PeriodEstimator.Mode.FREE_DECAY, 0.99, null, 2.0)
        val fast = PeriodEstimator.estimate(phi, fs, PeriodEstimator.Mode.FREE_DECAY, 0.99, null, 14.0)

        assertTrue("the same decay at 2 kn is fine: ${slow.message}", slow.ok)
        assertTrue("but at 14 kn it must be refused", !fast.ok)
        assertTrue("and it must say why: ${fast.message}", fast.message.contains("not free"))
    }

    /** Speed over ground is not speed through the water. The encounter relation wants the latter. */
    @Test
    fun `speed through water subtracts the current`() {
        assertEquals(9.0, ForecastAdvisor.speedThroughWaterKn(12.0, 3.0), 1e-9)
        assertEquals(15.0, ForecastAdvisor.speedThroughWaterKn(12.0, -3.0), 1e-9)
        assertEquals("never negative", 0.0, ForecastAdvisor.speedThroughWaterKn(2.0, 5.0), 1e-9)
    }

    /** A clean, well-executed decay must still sail through all of the above. */
    @Test
    fun `a proper long clean free decay still passes`() {
        // ...and she is stopped, as she should be
        val n = (600 * fs).toInt()
        val tn = 15.0
        val z = 0.05
        val wn = 2 * PI / tn
        val wd = wn * sqrt(1 - z * z)
        val phi = DoubleArray(n) {
            val t = it / fs
            8.0 * exp(-z * wn * t) * cos(wd * t) + 0.02 * gauss()
        }
        val r = PeriodEstimator.estimate(phi, fs, PeriodEstimator.Mode.FREE_DECAY, 0.99, null, 1.0)
        assertTrue("a clean decay must pass: ${r.message}", r.ok)
        assertEquals(tn, r.period, 0.05 * tn)
        assertTrue("and its cycles should be tight", r.periodScatter < 0.10)
    }
}
