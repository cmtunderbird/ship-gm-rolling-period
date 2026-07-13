package com.gmestimator

import com.gmestimator.core.Nav
import com.gmestimator.core.NavSource
import com.gmestimator.core.PeriodEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * THE BUG THESE TESTS EXIST TO PIN DOWN.
 *
 * The free-decay veto used to read:
 *
 *      if (!sogKn.isNaN() && sogKn > 6.0) reject()
 *
 * With no GPS fix, sogKn was NaN. NaN is not greater than 6. So a free decay taken at fourteen
 * knots - autopilot re-exciting her roll at every correction, lift damping added, GM itself
 * changed by the speed - passed the gate and was reported EXCELLENT.
 *
 * UNKNOWN SPEED WAS BEING TREATED AS ZERO SPEED. And a phone in a steel deckhouse routinely has
 * no fix, so this was the normal case, not an exotic one.
 */
class NavTest {

    private val fs = 25.0
    private val rng = Random(4711)

    private fun gauss(): Double {
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        return sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2.0 * PI * rng.nextDouble())
    }

    /** A clean free decay - the kind that would be reported as EXCELLENT. */
    private fun decay(t: Double = 14.0, n: Int = (240 * 25).toInt()): DoubleArray {
        val wn = 2 * PI / t
        val zeta = 0.05
        val wd = wn * sqrt(1 - zeta * zeta)
        return DoubleArray(n) {
            val tt = it / fs
            6.0 * exp(-zeta * wn * tt) * cos(wd * tt) + 0.02 * gauss()
        }
    }

    // -------------------------------------------------------------------------------------
    // THE REGRESSION
    // -------------------------------------------------------------------------------------

    @Test
    fun `a free decay with no GPS fix is NOT silently accepted as a decay at rest`() {
        val r = PeriodEstimator.estimate(decay(), fs, PeriodEstimator.Mode.FREE_DECAY, 0.99,
            null, Nav.UNKNOWN)

        // We still give her a period - she may genuinely be stopped, and refusing to measure
        // would be its own kind of dishonesty.
        assertEquals(14.0, r.period, 0.03 * 14.0)

        // But we must NEVER imply we checked the speed when we did not.
        assertTrue(
            "an unknown speed must be declared, not assumed to be zero: ${r.caveats}",
            r.caveats.any { it.contains("do not know how fast") }
        )
        assertEquals(NavSource.UNKNOWN, r.nav.source)
        assertFalse("unknown speed is not a known speed", r.nav.speedKnown)
    }

    @Test
    fun `a free decay at speed is still rejected when the speed IS known`() {
        val r = PeriodEstimator.estimate(decay(), fs, PeriodEstimator.Mode.FREE_DECAY, 0.99,
            null, Nav.manual(14.0, 90.0))
        assertFalse("14 kn is not a free decay: ${r.message}", r.ok)
        assertTrue(r.message.contains("free decay at speed is not free"))
    }

    @Test
    fun `a free decay at rest, speed known, carries no speed caveat`() {
        val r = PeriodEstimator.estimate(decay(), fs, PeriodEstimator.Mode.FREE_DECAY, 0.99,
            null, Nav.manual(0.4, Double.NaN))
        assertTrue("a genuine decay at rest must pass: ${r.message}", r.ok)
        assertFalse(
            "we knew the speed, so there is nothing to caveat: ${r.caveats}",
            r.caveats.any { it.contains("do not know how fast") }
        )
    }

    // -------------------------------------------------------------------------------------
    // THE FALLBACK THE MASTER ASKED FOR
    // -------------------------------------------------------------------------------------

    @Test
    fun `with no GPS fix, the operator's own figures are used`() {
        val noFix = Nav.UNKNOWN.copy(fixes = 0)
        val n = Nav.resolve(gps = noFix, forceManual = false, manualSog = 11.5, manualCog = 275.0)
        assertEquals(NavSource.MANUAL, n.source)
        assertEquals(11.5, n.sogKn, 1e-9)
        assertEquals(275.0, n.cogDeg, 1e-9)
        assertTrue(n.speedKnown && n.courseKnown)
    }

    @Test
    fun `a GPS with a real fix beats an idle manual entry`() {
        val fix = Nav(12.0, 87.0, NavSource.GPS, fixes = 40)
        val n = Nav.resolve(gps = fix, forceManual = false, manualSog = 3.0, manualCog = 10.0)
        assertEquals(NavSource.GPS, n.source)
        assertEquals(12.0, n.sogKn, 1e-9)
    }

    @Test
    fun `but the operator can always overrule the GPS`() {
        val fix = Nav(12.0, 87.0, NavSource.GPS, fixes = 40)
        val n = Nav.resolve(gps = fix, forceManual = true, manualSog = 3.0, manualCog = 10.0)
        assertEquals(NavSource.MANUAL, n.source)
        assertEquals(3.0, n.sogKn, 1e-9)
    }

    @Test
    fun `two or three stray fixes are not a fix`() {
        // The receiver coughed twice on the way past a window. That is not navigation data.
        val flaky = Nav(9.0, 30.0, NavSource.GPS, fixes = 2)
        val n = Nav.resolve(gps = flaky, forceManual = false, manualSog = Double.NaN, manualCog = Double.NaN)
        assertEquals(NavSource.UNKNOWN, n.source)
    }

    @Test
    fun `nothing anywhere means UNKNOWN, and it says so in plain words`() {
        val n = Nav.resolve(null, false, Double.NaN, Double.NaN)
        assertEquals(NavSource.UNKNOWN, n.source)
        assertFalse(n.speedKnown)
        assertTrue(n.line().contains("UNKNOWN"))
    }

    @Test
    fun `zero knots is a real speed and must not be confused with no speed`() {
        // She is stopped. That is a FACT about the ship, and the most valid free-decay condition
        // there is. It must never be conflated with "the operator told me nothing".
        val stopped = Nav.manual(0.0, Double.NaN)
        assertEquals(NavSource.MANUAL, stopped.source)
        assertTrue("stopped is a known speed", stopped.speedKnown)
        assertFalse("but a stopped ship has no meaningful course", stopped.courseKnown)
    }
}
