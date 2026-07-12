package com.gmestimator.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Speed and course over ground, logged for the duration of a record.
 *
 * WHY A STABILITY APP WANTS GPS
 * -----------------------------
 * The encounter frequency of a wave depends on where you are pointing and how fast you are
 * going:
 *
 *      w_e = w_0 - (w_0^2 / g) * U * cos(mu)
 *
 * The ship's natural roll period does not. It depends on GM and the mass distribution, and
 * neither of those cares about your heading.
 *
 * So: take two records on different headings. Every WAVE-driven peak moves. The SHIP's peak
 * stays exactly where it was. That is the only test in this whole instrument that makes no
 * modelling assumptions whatsoever - no wave theory, no ship model, no spectrum shape. When
 * the clever methods disagree with this one, believe this one.
 *
 * GPS is optional. Without it the app still works; it just cannot offer the encounter test.
 * Nothing is transmitted anywhere.
 */
class GpsTrack(private val context: Context) : LocationListener {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val speeds = ArrayList<Double>()      // m/s
    private val cogSin = ArrayList<Double>()      // course, accumulated as a unit vector so that
    private val cogCos = ArrayList<Double>()      // the mean does not break across 359 -> 001 deg

    var available = false
        private set

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun start() {
        stop()
        speeds.clear(); cogSin.clear(); cogCos.clear()
        available = false
        if (!hasPermission()) return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, this)
            available = true
        } catch (_: SecurityException) {
            available = false
        } catch (_: IllegalArgumentException) {
            available = false          // no GPS provider on this device
        }
    }

    fun stop() {
        try {
            lm.removeUpdates(this)
        } catch (_: SecurityException) {
        }
    }

    override fun onLocationChanged(loc: Location) {
        // A course over ground is meaningless when stopped, and a stopped ship gives the GPS
        // nothing but noise to point at. Below ~1 kn, ignore the bearing entirely.
        if (!loc.hasSpeed()) return
        val v = loc.speed.toDouble()
        speeds.add(v)
        if (loc.hasBearing() && v > 0.5) {
            val b = Math.toRadians(loc.bearing.toDouble())
            cogSin.add(sin(b))
            cogCos.add(cos(b))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    /** Mean speed over ground [knots]. NaN if we never got a fix. */
    fun sogKnots(): Double =
        if (speeds.isEmpty()) Double.NaN else speeds.average() * 1.943844

    /** Mean course over ground [deg]. NaN if the ship was stopped or we never got a fix. */
    fun cogDeg(): Double {
        if (cogSin.isEmpty()) return Double.NaN
        val d = Math.toDegrees(atan2(cogSin.average(), cogCos.average()))
        return (d + 360.0) % 360.0
    }

    /**
     * How steady was the course? A record taken while the ship was altering course is not one
     * record at one encounter frequency - it is a smear across several, and the spectral peak
     * is broadened by it.
     */
    fun courseSteadinessDeg(): Double {
        if (cogSin.size < 2) return Double.NaN
        val r = sqrt(cogSin.average() * cogSin.average() + cogCos.average() * cogCos.average())
        return Math.toDegrees(sqrt(-2.0 * kotlin.math.ln(r.coerceIn(1e-9, 1.0))))
    }

    fun speedSteadinessKn(): Double {
        if (speeds.size < 2) return Double.NaN
        val m = speeds.average()
        val v = speeds.sumOf { (it - m) * (it - m) } / (speeds.size - 1)
        return sqrt(v) * 1.943844
    }

    fun fixCount(): Int = speeds.size

    /** True if the record was taken on a steady enough course/speed to mean anything. */
    fun steady(): Boolean {
        val c = courseSteadinessDeg()
        val s = speedSteadinessKn()
        return !c.isNaN() && !s.isNaN() && c < 10.0 && s < 1.0
    }

    fun summary(): String = when {
        !available || fixCount() == 0 -> "no GPS fix"
        cogSin.isEmpty() -> "SOG ${"%.1f".format(sogKnots())} kn (stopped / no course)"
        else -> "SOG ${"%.1f".format(sogKnots())} kn, COG ${"%.0f".format(cogDeg())} deg" +
            (if (!steady()) "  - COURSE OR SPEED NOT STEADY" else "")
    }
}
