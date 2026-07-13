package com.gmestimator.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SPEED AND COURSE OVER GROUND.
 *
 * WHY THE FIRST VERSION NEVER SHOWED A FIX
 * ----------------------------------------
 * The Master: "the phone is sensitive enough to receive the sats even inside of the Bridge, yet
 * the implementation doesn't seem to get the gps fix and the movement parameters."
 *
 * He was right, and the reason was embarrassing: requestLocationUpdates() was only ever called
 * from startRecording(). Open the Sea tab and the receiver was NOT RUNNING - so of course it
 * reported no fix. It had never been asked for one. And even during a record it listened to
 * GPS_PROVIDER alone, so a phone happily getting a fused fix reported nothing.
 *
 * Two more sharp edges in the old code:
 *   - `if (!loc.hasSpeed()) return` discarded the ENTIRE fix when it carried no speed field, so
 *     the fix counter stayed at zero even while fixes were arriving. Position and speed are
 *     separate questions; a fix is a fix.
 *   - start() cleared the accumulators, so it could not be used to keep the receiver warm.
 *
 * Now: the receiver runs from the moment the app opens, on every provider the phone offers, and
 * the record simply MARKS a window in a stream that is already flowing.
 */
class GpsTrack(private val context: Context) : LocationListener {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // --- the live state: the most recent fix, whatever it was and whenever it came -----------
    private var last: Location? = null
    private var lastElapsedMs: Long = 0L
    private var totalFixes = 0
    private var listening = false

    // --- the record window: only accumulated between beginRecord() and endRecord() -----------
    private var accumulating = false
    private val speeds = ArrayList<Double>()      // m/s
    private val cogSin = ArrayList<Double>()      // course as a unit vector, so the mean does not
    private val cogCos = ArrayList<Double>()      // break across 359 -> 001 deg

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Start listening, and KEEP listening. Called when the app opens, not when a record starts.
     * Idempotent.
     */
    fun startListening() {
        if (listening || !hasPermission()) return

        // Every provider the phone will give us. On this Xiaomi that is gps + fused + network;
        // asking only for GPS_PROVIDER is how the old code managed to see nothing on a phone
        // that had a perfectly good fix.
        val wanted = buildList {
            add(LocationManager.GPS_PROVIDER)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
            add(LocationManager.NETWORK_PROVIDER)
        }

        var any = false
        for (p in wanted) {
            try {
                if (!lm.allProviders.contains(p)) continue
                lm.requestLocationUpdates(p, 1000L, 0f, this)
                any = true
                // Seed from whatever the system already has, so the display is not blank for the
                // first few seconds after the app opens.
                lm.getLastKnownLocation(p)?.let { seed ->
                    if (last == null) {
                        last = seed
                        lastElapsedMs = SystemClock.elapsedRealtime()
                    }
                }
            } catch (_: SecurityException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        listening = any
    }

    fun stopListening() {
        try {
            lm.removeUpdates(this)
        } catch (_: SecurityException) {
        }
        listening = false
    }

    /** Mark the start of a record. The receiver keeps running either way. */
    fun beginRecord() {
        speeds.clear(); cogSin.clear(); cogCos.clear()
        accumulating = true
        startListening()          // in case permission was granted after the app opened
    }

    fun endRecord() {
        accumulating = false
    }

    override fun onLocationChanged(loc: Location) {
        last = loc
        lastElapsedMs = SystemClock.elapsedRealtime()
        totalFixes++

        if (!accumulating) return

        // A fix WITHOUT a speed field is still a fix - it just has nothing to say about speed.
        // The old code threw the whole thing away, which is why the counter never moved.
        if (loc.hasSpeed()) {
            val v = loc.speed.toDouble()
            speeds.add(v)
            // A course is meaningless when stopped: the receiver is pointing at noise.
            if (loc.hasBearing() && v > 0.5) {
                val b = Math.toRadians(loc.bearing.toDouble())
                cogSin.add(sin(b))
                cogCos.add(cos(b))
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // ---------------------------------------------------------------------------- live state

    /** Seconds since the last fix arrived. NaN if there has never been one. */
    fun fixAgeSeconds(): Double =
        if (last == null) Double.NaN
        else (SystemClock.elapsedRealtime() - lastElapsedMs) / 1000.0

    /**
     * What the receiver is seeing RIGHT NOW - for the display, before a record is started.
     * A stale fix is not a fix: if nothing has arrived for half a minute we have lost the sky.
     */
    fun liveNav(): Nav {
        val l = last
        val age = fixAgeSeconds()
        if (!hasPermission()) return Nav.UNKNOWN.copy(
            detail = "location permission has not been granted to the app"
        )
        if (!listening) return Nav.UNKNOWN.copy(
            detail = "the receiver is not running - no location provider could be started"
        )
        if (l == null) return Nav.UNKNOWN.copy(
            fixes = totalFixes,
            detail = "waiting for the first fix"
        )
        if (age > 30.0) return Nav.UNKNOWN.copy(
            fixes = totalFixes,
            detail = "the last fix is ${age.toInt()} s old - the sky has been lost"
        )
        if (!l.hasSpeed()) return Nav.UNKNOWN.copy(
            fixes = totalFixes,
            detail = "fixed, but this fix carries no speed - wait for the ship to move"
        )
        val v = l.speed.toDouble() * MS_TO_KN
        return Nav(
            sogKn = v,
            cogDeg = if (l.hasBearing() && l.speed > 0.5) l.bearing.toDouble() else Double.NaN,
            source = NavSource.GPS,
            fixes = totalFixes,
            steady = true,
            detail = "live fix, ${age.toInt()} s old, +/- ${l.accuracy.toInt()} m"
        )
    }

    // ------------------------------------------------------------------------ the record window

    fun fixCount(): Int = speeds.size

    fun sogKnots(): Double =
        if (speeds.isEmpty()) Double.NaN else speeds.average() * MS_TO_KN

    fun cogDeg(): Double {
        if (cogSin.isEmpty()) return Double.NaN
        val d = Math.toDegrees(atan2(cogSin.average(), cogCos.average()))
        return (d + 360.0) % 360.0
    }

    /**
     * How steady was the course? A record taken while the ship was altering course is not one
     * record at one encounter frequency - it is a smear across several.
     */
    fun courseSteadinessDeg(): Double {
        if (cogSin.size < 2) return Double.NaN
        val r = sqrt(cogSin.average() * cogSin.average() + cogCos.average() * cogCos.average())
        return Math.toDegrees(sqrt(-2.0 * kotlin.math.ln(r.coerceIn(1e-9, 1.0))))
    }

    fun speedSteadinessKn(): Double {
        if (speeds.size < 2) return Double.NaN
        val m = speeds.average()
        return sqrt(speeds.sumOf { (it - m) * (it - m) } / (speeds.size - 1)) * MS_TO_KN
    }

    fun steady(): Boolean {
        val c = courseSteadinessDeg()
        val s = speedSteadinessKn()
        // A record with no course (she was stopped) is not "unsteady" - there is simply no course.
        if (c.isNaN() || s.isNaN()) return true
        return c < 10.0 && s < 1.0
    }

    /** What the receiver collected over the record just taken. Never a bare NaN - see Nav. */
    fun nav(): Nav {
        if (fixCount() < Nav.MIN_FIXES) {
            // Nothing usable during the record itself. But if a live fix is sitting right there,
            // a moving ship's speed did not change in the last few seconds - use it and say so.
            val live = liveNav()
            if (live.source == NavSource.GPS) {
                return live.copy(
                    fixes = live.fixes,
                    detail = "only ${fixCount()} fixes during the record itself; " +
                        "using the current live fix"
                )
            }
            return Nav.UNKNOWN.copy(
                fixes = fixCount(),
                detail = when {
                    !hasPermission() -> "location permission has not been granted to the app"
                    !listening -> "no location provider could be started"
                    fixCount() == 0 -> "no fix during the record - the phone could not see the sky"
                    else -> "only ${fixCount()} fixes during the record, which is not enough to trust"
                }
            )
        }
        return Nav(
            sogKn = sogKnots(),
            cogDeg = cogDeg(),
            source = NavSource.GPS,
            fixes = fixCount(),
            steady = steady(),
            detail = "averaged over ${fixCount()} fixes during the record"
        )
    }

    fun summary(): String = nav().line()

    companion object {
        private const val MS_TO_KN = 1.943844
    }
}
