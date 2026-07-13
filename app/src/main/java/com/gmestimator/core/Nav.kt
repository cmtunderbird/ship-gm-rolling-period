package com.gmestimator.core

import java.util.Locale

/**
 * SPEED AND COURSE OVER GROUND - AND, THE WHOLE POINT OF THIS FILE, WHERE THEY CAME FROM.
 *
 * WHY THIS EXISTS
 * ---------------
 * The first version read speed straight out of GpsTrack, which returns NaN when there is no
 * fix. Downstream, the free-decay veto said:
 *
 *      if (!sogKn.isNaN() && sogKn > 6.0) reject()
 *
 * Look at what that does when the GPS never got a fix. NaN is not greater than 6, so the record
 * PASSES. A free decay taken at fourteen knots - autopilot re-exciting her roll at every rudder
 * correction, lift damping added, GM itself changed by the speed - sails straight through the
 * gate and is reported as a valid measurement.
 *
 *      UNKNOWN SPEED WAS BEING TREATED AS ZERO SPEED.
 *
 * And a phone inside a steel deckhouse very often has no fix at all. So this was not an exotic
 * failure. It was the normal case.
 *
 * THE RULE THIS TYPE ENFORCES: THERE ARE THREE STATES, NOT TWO. She is known to be slow, she is
 * known to be fast, or WE DO NOT KNOW - and the third must be said out loud, never quietly
 * rounded down to the convenient answer.
 *
 * The fallback is the honest cure: if the GPS cannot see the sky, the man on the bridge reads
 * the speed and the course off his own instruments in two seconds. His numbers are better than
 * no numbers, and infinitely better than a NaN pretending to be zero.
 */
enum class NavSource {
    /** The phone's own receiver, with at least [Nav.MIN_FIXES] fixes during the record. */
    GPS,

    /** Typed in by the operator - off the bridge repeaters, the ECDIS, the Doppler log. */
    MANUAL,

    /** No fix, nothing entered. We do not know how fast she was going, and we must say so. */
    UNKNOWN
}

data class Nav(
    val sogKn: Double,
    val cogDeg: Double,
    val source: NavSource,
    /** GPS only: how many fixes arrived while the record was running. */
    val fixes: Int = 0,
    /** Was she holding a steady course and speed? A record taken mid-alteration is a smear
     *  across several encounter frequencies, not one. */
    val steady: Boolean = true,
    val detail: String = ""
) {

    /** True only if a NUMBER exists AND we know where it came from. */
    val speedKnown: Boolean get() = source != NavSource.UNKNOWN && !sogKn.isNaN()

    /** A course is meaningless below about a knot: the receiver is pointing at noise. */
    val courseKnown: Boolean get() = source != NavSource.UNKNOWN && !cogDeg.isNaN()

    fun label(): String = when (source) {
        NavSource.GPS -> "GPS ($fixes fixes)"
        NavSource.MANUAL -> "MANUAL - entered by the operator"
        NavSource.UNKNOWN -> "UNKNOWN - no GPS fix, nothing entered"
    }

    fun line(): String = when {
        source == NavSource.UNKNOWN -> "Speed and course: UNKNOWN"
        !courseKnown -> "SOG ${f1(sogKn)} kn, no course (stopped or drifting)   [${label()}]"
        else -> "SOG ${f1(sogKn)} kn, COG ${f0(cogDeg)} deg   [${label()}]" +
            (if (!steady) "   - NOT STEADY" else "")
    }

    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun f0(v: Double) = String.format(Locale.US, "%.0f", v)

    companion object {

        /** Below this, the receiver has not really acquired. Treat it as no fix at all. */
        const val MIN_FIXES = 3

        val UNKNOWN = Nav(
            Double.NaN, Double.NaN, NavSource.UNKNOWN, 0, false,
            "the GPS never got a fix and no speed was entered by hand"
        )

        fun manual(sogKn: Double, cogDeg: Double, why: String = ""): Nav =
            if (sogKn.isNaN() || sogKn < 0.0) UNKNOWN
            else Nav(
                sogKn = sogKn,
                cogDeg = if (cogDeg.isNaN() || sogKn < 0.5) Double.NaN
                else ((cogDeg % 360.0) + 360.0) % 360.0,
                source = NavSource.MANUAL,
                fixes = 0,
                steady = true,        // he is asserting a steady course; we take the seaman's word
                detail = why
            )

        /**
         * Decide what we actually know, in ONE place, so that no call site can invent its own
         * policy and no NaN can leak out pretending to be a number.
         *
         * @param gps         what the receiver collected, or null if it was never started
         * @param forceManual the operator has said: ignore the GPS, use my figures
         * @param manualSog   knots, NaN if he entered nothing
         * @param manualCog   degrees, NaN if he entered nothing
         */
        fun resolve(gps: Nav?, forceManual: Boolean, manualSog: Double, manualCog: Double): Nav {
            val hand = manual(manualSog, manualCog)

            if (forceManual) {
                return if (hand.speedKnown) hand.copy(detail = "you chose to enter speed by hand")
                else UNKNOWN.copy(detail = "manual entry is selected, but no speed has been entered")
            }

            val fix = gps?.takeIf {
                it.source == NavSource.GPS && it.fixes >= MIN_FIXES && it.speedKnown
            }
            if (fix != null) return fix

            // The receiver could not see the sky. THIS is the case that used to fail silently.
            if (hand.speedKnown) {
                return hand.copy(
                    detail = "the GPS got ${gps?.fixes ?: 0} fixes - not enough to trust. " +
                        "Using the speed and course you entered"
                )
            }
            return UNKNOWN
        }
    }
}
