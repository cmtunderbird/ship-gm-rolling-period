package com.gmestimator

import com.gmestimator.core.AttitudeCheck
import com.gmestimator.core.Nav
import com.gmestimator.core.PeriodEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * THE FUSED ATTITUDE IS NOT AN INDEPENDENT WITNESS. THE GYROSCOPE IS.
 *
 * GAME_ROTATION_VECTOR is gyro AND accelerometer, blended. On a ship the accelerometer sees the
 * hull's sway and surge, not gravity, so the fusion filter's idea of "down" is dragged around by
 * the ship's motion - at the very frequencies a ship rolls at.
 *
 * The gyroscope cannot see gravity. What the gyroscope does not see IS NOT ROTATION.
 */
class AttitudeCheckTest {

    private val fs = 25.0
    private val rng = Random(90210)

    private fun gauss(): Double {
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        return sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2.0 * PI * rng.nextDouble())
    }

    private fun wave(t: Double, amp: Double, n: Int, phase: Double = 0.0) =
        DoubleArray(n) { amp * sin(2 * PI * (it / fs) / t + phase) }

    // ---------------------------------------------------------------------------------------

    @Test
    fun `when the ship really rolls, both witnesses agree`() {
        val n = (1200 * fs).toInt()
        val real = wave(16.0, 3.0, n)
        val fused = DoubleArray(n) { real[it] + 0.02 * gauss() }
        // the gyro sees the same rotation, with its own noise and a slow integration drift
        val gyro = DoubleArray(n) { real[it] + 0.03 * gauss() + 0.002 * (it / fs) }

        val v = AttitudeCheck.compare(fused, gyro, fs)
        assertTrue("both saw the same ship: ${v.message}", v.agree)
        assertEquals(16.0, v.periodGyro, 0.5)
        assertTrue("and they should be strongly correlated", kotlin.math.abs(v.correlation) > 0.9)
    }

    /**
     * THE CASE THIS WHOLE FILE EXISTS FOR.
     *
     * The ship is barely rolling - she is doing 0.3 deg at 8 s, driven by the sea. But the phone's
     * fusion filter, chasing a "down" that is being dragged around by the ship's 17 s sway, reports
     * a fat 17 s oscillation that never happened. The old code would have called that her natural
     * period, and turned it into a GM.
     *
     * The gyroscope never saw it, because there was nothing to see. It was never rotation.
     */
    @Test
    fun `a peak that exists only in the fused attitude is NOT rotation, and is caught`() {
        val n = (1200 * fs).toInt()
        val realRoll = wave(8.0, 0.3, n)                 // what she actually did
        val fusionArtefact = wave(17.0, 1.2, n, 0.7)     // what the filter invented

        val fused = DoubleArray(n) { realRoll[it] + fusionArtefact[it] + 0.02 * gauss() }
        val gyro = DoubleArray(n) { realRoll[it] + 0.02 * gauss() }   // NO 17 s content

        val v = AttitudeCheck.compare(fused, gyro, fs)

        assertFalse("the two witnesses must not be allowed to agree here: ${v.message}", v.agree)
        assertEquals("the fused attitude is fooled", 17.0, v.periodFused, 1.0)
        assertEquals("the gyroscope is not", 8.0, v.periodGyro, 0.5)
        assertTrue(
            "and the message must say plainly that this is not rotation: ${v.message}",
            v.message.contains("IS NOT ROTATION")
        )
    }

    @Test
    fun `and such a record is REJECTED, not quietly turned into a GM`() {
        val n = (1200 * fs).toInt()
        val realRoll = wave(8.0, 0.3, n)
        val artefact = wave(17.0, 1.2, n, 0.7)
        val fused = DoubleArray(n) { realRoll[it] + artefact[it] + 0.02 * gauss() }
        val gyro = DoubleArray(n) { realRoll[it] + 0.02 * gauss() }
        val heave = DoubleArray(n) { 0.4 * sin(2 * PI * (it / fs) / 8.0) + 0.02 * gauss() }

        val r = PeriodEstimator.estimate(
            fused, fs, PeriodEstimator.Mode.SEAWAY, 0.95, heave,
            Nav.manual(17.0, 90.0), gyro
        )

        assertFalse("a fusion artefact must never become a GM: ${r.message}", r.ok)
        assertTrue(r.message.contains("IS NOT ROTATION") || r.message.contains("DISAGREE"))
        assertFalse(r.attitude.agree)
    }

    @Test
    fun `no gyroscope is a caveat, not a silent pass`() {
        val n = (600 * fs).toInt()
        val roll = DoubleArray(n) {
            val t = it / fs
            6.0 * kotlin.math.exp(-0.05 * (2 * PI / 14.0) * t) *
                cos(2 * PI / 14.0 * sqrt(1 - 0.05 * 0.05) * t) + 0.02 * gauss()
        }
        val r = PeriodEstimator.estimate(
            roll, fs, PeriodEstimator.Mode.FREE_DECAY, 0.99, null,
            Nav.manual(0.5, Double.NaN), null
        )
        assertFalse("no gyro = nothing to cross-check with", r.attitude.available)
        assertTrue(
            "and that must be said out loud: ${r.caveats}",
            r.caveats.any { it.contains("no gyroscope") }
        )
    }
}
