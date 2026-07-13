package com.gmestimator

import com.gmestimator.core.ForecastAdvisor
import com.gmestimator.core.ForecastAdvisor.WaveSystem
import com.gmestimator.core.GmModel
import com.gmestimator.core.PeriodEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The forecast is allowed to LABEL, WARN and ADVISE. It is never allowed to compute.
 */
class ForecastAdvisorTest {

    private val swell = WaveSystem("Swell", 2.0, 12.0, 270.0)   // from the west
    private val sea = WaveSystem("Sea", 1.5, 7.0, 300.0)

    /**
     * THE RULE. GM must be a function of the measured period, the beam and the roll coefficient.
     * Nothing else. If a forecast could nudge it, the instrument would confirm a misdeclared
     * cargo instead of catching it - and independence from the loading computer is the entire
     * product.
     */
    @Test
    fun `the forecast cannot move GM, at all`() {
        val fs = 25.0
        val n = (600 * fs).toInt()
        val phi = DoubleArray(n) { 4.0 * sin(2 * PI * (it / fs) / 14.0) }
        val r = PeriodEstimator.estimate(phi, fs, PeriodEstimator.Mode.FREE_DECAY, 0.99, null)

        // GM is computed from the period and the ship. There is no forecast argument to give it.
        val gm = GmModel.gmFromPeriod(r.period, 0.80, 30.0)
        assertEquals("GM depends only on T, f and B", (0.80 * 30.0 / r.period) * (0.80 * 30.0 / r.period), gm, 1e-9)
        assertTrue(gm > 0)
    }

    @Test
    fun `a peak that lands on a forecast wave is labelled as the sea`() {
        // steaming due north, 10 kn; the 12 s swell comes from the west -> pure beam sea,
        // so the encounter period equals the true period.
        val label = ForecastAdvisor.labelPeak(12.0, listOf(swell, sea), cogDeg = 0.0, sogKn = 10.0)
        assertNotNull("a 12 s roll peak in a 12 s beam swell IS the swell", label)
        assertTrue(label!!.contains("Swell"))
        assertTrue(label.contains("not the ship"))
    }

    @Test
    fun `a peak far from every forecast wave is left alone`() {
        // her roll is 20 s; nothing in the forecast meets her anywhere near that
        assertNull(ForecastAdvisor.labelPeak(20.0, listOf(swell, sea), cogDeg = 0.0, sogKn = 10.0))
    }

    @Test
    fun `synchronous rolling is warned about, in a beam sea`() {
        // 12 s beam swell, and her roll period is 12 s
        val w = ForecastAdvisor.warnings(12.0, listOf(swell), cogDeg = 0.0, sogKn = 8.0)
        assertTrue("must warn", w.any { it.text.contains("SYNCHRONOUS") && it.severe })
    }

    @Test
    fun `parametric roll is warned about when the sea meets her at half her roll period`() {
        // head sea: steaming 270 INTO a swell from 270. Encounter period shortens.
        val u = 12.0 * 0.514444
        val te = ForecastAdvisor.encounter(swell, cogDeg = 270.0, sogKn = 12.0).encounterPeriodS
        val w = ForecastAdvisor.warnings(2.0 * te, listOf(swell), cogDeg = 270.0, sogKn = 12.0)
        assertTrue("parametric warning expected at Tn = 2*Te", w.any { it.text.contains("PARAMETRIC") })
        assertTrue("and it is severe head-on", w.first { it.text.contains("PARAMETRIC") }.severe)
    }

    /**
     * The advisory must look for CALM, never for resonance. Steering a ship into synchronous
     * rolling to improve a measurement would be an appalling trade.
     */
    @Test
    fun `the recommended window avoids resonance rather than seeking it`() {
        val tn = 12.0                                  // her roll period == the swell period
        val wins = ForecastAdvisor.bestWindows(tn, listOf(swell), listOf(0.0, 6.0, 10.0))
        assertTrue(wins.isNotEmpty())
        val best = wins.first()

        // the swell comes FROM 270. A beam-on heading (0 or 180) would put her in synchronous
        // roll. The advice must NOT be that.
        val rel = ForecastAdvisor.relBearing(270.0, best.cogDeg)
        assertTrue(
            "recommended COG ${best.cogDeg} puts the swell ${rel}° off the bow - that is beam-on, " +
                "i.e. exactly the resonance we must avoid",
            rel < 55.0 || rel > 125.0
        )
    }

    @Test
    fun `a forecast that disagrees with the accelerometer is called out`() {
        // the phone measured a 7 s dominant wave; a beam 12 s swell cannot explain that
        val agrees = ForecastAdvisor.forecastAgrees(7.2, listOf(swell), cogDeg = 0.0, sogKn = 10.0)
        assertNotNull(agrees)
        assertFalse("a 12 s swell does not produce a 7 s encounter on the beam", agrees!!)

        // but the 7 s wind sea does
        assertTrue(ForecastAdvisor.forecastAgrees(7.2, listOf(sea), cogDeg = 0.0, sogKn = 2.0)!!)
    }

    @Test
    fun `head seas shorten the encounter period and following seas lengthen it`() {
        val head = ForecastAdvisor.encounter(swell, cogDeg = 270.0, sogKn = 12.0)   // into it
        val beam = ForecastAdvisor.encounter(swell, cogDeg = 0.0, sogKn = 12.0)
        val foll = ForecastAdvisor.encounter(swell, cogDeg = 90.0, sogKn = 12.0)    // away from it

        assertEquals("beam: encounter == true period", 12.0, beam.encounterPeriodS, 0.01)
        assertTrue("head sea shortens it", head.encounterPeriodS < 12.0)
        assertTrue("following sea lengthens it", foll.encounterPeriodS > 12.0)
        assertTrue("beam seas roll her hardest", beam.rollWeight > head.rollWeight)
    }
}
