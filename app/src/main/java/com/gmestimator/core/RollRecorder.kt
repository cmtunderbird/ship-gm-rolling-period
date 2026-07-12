package com.gmestimator.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Turns the phone's IMU into a ship roll-angle recorder - and, since the sea-lock work, into a
 * crude wave recorder as well.
 *
 * ------------------------------------------------------------------------------------------
 * WHY WE DO NOT USE THE ACCELEROMETER ALONE (for the ROLL ANGLE)
 * ------------------------------------------------------------------------------------------
 * The obvious approach - tilt angle from the gravity vector - is wrong on a ship. The
 * accelerometer measures (gravity - acceleration). A phone lying anywhere off the roll axis
 * experiences centripetal/tangential acceleration from the roll itself, plus sway, yaw and
 * heave. Those lateral accelerations are IN PHASE with the roll and inflate the apparent
 * amplitude; worse, the ship's transverse acceleration due to wave-induced sway biases the
 * apparent "vertical". A pure-accelerometer inclinometer on a rolling ship can be out by
 * several degrees and, crucially, its error is at the roll frequency, so filtering cannot
 * remove it.
 *
 * The GYROSCOPE measures rigid-body angular rate. It is INDEPENDENT of where the phone sits in
 * the ship, and it is immune to linear acceleration. Its only weakness is slow bias drift -
 * which is out of the roll band and is exactly what the accelerometer is good at fixing.
 *
 * Source priority:
 *   1. TYPE_GAME_ROTATION_VECTOR  (gyro + accel, no compass)     <- preferred
 *   2. TYPE_ROTATION_VECTOR       (gyro + accel + compass)
 *   3. complementary filter over TYPE_GYROSCOPE + TYPE_ACCELEROMETER (manual fallback)
 *   4. TYPE_ACCELEROMETER only    (degraded - flagged to the user)
 *
 * ------------------------------------------------------------------------------------------
 * ...BUT THE ACCELEROMETER IS INDISPENSABLE FOR THE HEAVE
 * ------------------------------------------------------------------------------------------
 * The vertical acceleration is our only witness to what the SEA is doing, and without it the
 * instrument cannot tell the ship's own resonance from a wave driving her at some other
 * period. See SeaAnalyzer. So the accelerometer is now registered in EVERY mode, not just the
 * fallbacks.
 *
 * ------------------------------------------------------------------------------------------
 * THE MOUNTING PROBLEM
 * ------------------------------------------------------------------------------------------
 * The phone is laid on some flat surface with an arbitrary heading relative to the centreline.
 * We do not want to force the operator to align it, and we cannot use the compass.
 *
 * Solution: record the full 2-D tilt vector (tiltX, tiltY) in the phone's own frame. For small
 * angles this is a fixed rotation of the ship's (roll, pitch) vector by the unknown heading psi.
 * Roll amplitude is normally several times pitch amplitude, so the PRINCIPAL AXIS of the
 * band-passed 2-D tilt series IS the roll axis. We find it by eigen-decomposition and project
 * onto it. The eigenvalue ratio (`axisDominance`) tells us how well-separated roll and pitch
 * were - if it drops towards 0.5, roll and pitch had similar amplitudes and the projection is
 * ambiguous, which the quality gate rejects.
 *
 * The operator can also override this and declare the phone's orientation manually.
 */
class RollRecorder(context: Context) : SensorEventListener {

    enum class Source { GAME_ROTATION_VECTOR, ROTATION_VECTOR, GYRO_ACCEL, ACCEL_ONLY, NONE }

    enum class AxisMode {
        /** Find the roll axis automatically by principal-component analysis. Recommended. */
        AUTO,

        /** Phone screen up, top edge pointing forward (to the bow) or aft. */
        LONGITUDINAL,

        /** Phone screen up, top edge pointing to port or starboard. */
        TRANSVERSE
    }

    companion object {
        /** Uniform resampling rate. Roll periods are 3-45 s, so 25 Hz is ~100x oversampled. */
        const val FS = 25.0
        private const val SAMPLE_US = 20_000        // request 50 Hz from the sensor
        private const val RAD2DEG = 57.29577951308232
        private const val GRAVITY = 9.80665
    }

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    var source: Source = Source.NONE
        private set
    var axisMode: AxisMode = AxisMode.AUTO

    /** Raw (irregularly timed) tilt samples, before resampling. */
    private val tRaw = ArrayList<Double>(200_000)
    private val xRaw = ArrayList<Double>(200_000)   // tilt about the phone's Y axis [deg]
    private val yRaw = ArrayList<Double>(200_000)   // tilt about the phone's X axis [deg]

    /**
     * WORLD-VERTICAL linear acceleration [m/s2], gravity removed. This is the ship's heave
     * acceleration - and in deep water it IS the wave-slope spectrum, up to a factor of g^2
     * (see SeaAnalyzer). It is how we tell the sea apart from the ship, and until now we were
     * recording the accelerometer and throwing this away.
     */
    private val tAz = ArrayList<Double>(200_000)
    private val azRaw = ArrayList<Double>(200_000)

    /** Latest world-"up" direction expressed in the device frame (third row of R). */
    private var upX = 0.0
    private var upY = 0.0
    private var upZ = 1.0
    private var haveUp = false

    private var t0Nanos = 0L
    private var recording = false

    // complementary-filter state (GYRO_ACCEL fallback)
    private var lastGyroNanos = 0L
    private var cfX = Double.NaN
    private var cfY = Double.NaN
    private var accX = 0.0
    private var accY = 0.0
    private val rotMat = FloatArray(9)

    /** Live callback for the UI: (elapsed seconds, tiltX deg, tiltY deg). */
    var onSample: ((Double, Double, Double) -> Unit)? = null

    // ------------------------------------------------------------------ lifecycle

    fun availableSource(): Source = when {
        sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null -> Source.GAME_ROTATION_VECTOR
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null -> Source.ROTATION_VECTOR
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null &&
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null -> Source.GYRO_ACCEL
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null -> Source.ACCEL_ONLY
        else -> Source.NONE
    }

    fun start() {
        stop()
        tRaw.clear(); xRaw.clear(); yRaw.clear()
        tAz.clear(); azRaw.clear()
        haveUp = false
        cfX = Double.NaN; cfY = Double.NaN
        lastGyroNanos = 0L
        t0Nanos = 0L
        source = availableSource()

        when (source) {
            Source.GAME_ROTATION_VECTOR ->
                reg(Sensor.TYPE_GAME_ROTATION_VECTOR)
            Source.ROTATION_VECTOR ->
                reg(Sensor.TYPE_ROTATION_VECTOR)
            Source.GYRO_ACCEL ->
                reg(Sensor.TYPE_GYROSCOPE)
            Source.ACCEL_ONLY -> Unit
            Source.NONE -> return
        }
        // The accelerometer is now registered in EVERY mode, not just the fallbacks. We need it
        // for the heave signal - it is our only witness to what the sea is doing.
        reg(Sensor.TYPE_ACCELEROMETER)
        recording = true
    }

    private fun reg(type: Int) {
        sm.getDefaultSensor(type)?.let { sm.registerListener(this, it, SAMPLE_US) }
    }

    fun stop() {
        if (recording) sm.unregisterListener(this)
        recording = false
    }

    fun durationSeconds(): Double = if (tRaw.isEmpty()) 0.0 else tRaw.last()

    fun sampleCount(): Int = tRaw.size

    // ------------------------------------------------------------------ sensor callbacks

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        if (!recording) return
        if (t0Nanos == 0L) t0Nanos = e.timestamp
        val t = (e.timestamp - t0Nanos) / 1e9

        when (e.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotMat, e.values)
                // R maps device -> world. World "up" is (0,0,1), so the up direction expressed in
                // the DEVICE frame is R^T * (0,0,1), which is the third ROW of R: rotMat[6..8].
                val gx = rotMat[6].toDouble()
                val gy = rotMat[7].toDouble()
                val gz = rotMat[8].toDouble()
                upX = gx; upY = gy; upZ = gz; haveUp = true
                push(t, tiltFromGravity(gx, gy, gz))
            }

            Sensor.TYPE_ACCELEROMETER -> {
                val ax = e.values[0].toDouble()
                val ay = e.values[1].toDouble()
                val az = e.values[2].toDouble()
                val n = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-6)
                val tilt = tiltFromGravity(ax / n, ay / n, az / n)
                accX = tilt.first
                accY = tilt.second

                // World-vertical LINEAR acceleration = (specific force . up) - g.
                // Android's accelerometer reports specific force, gravity included: at rest it
                // reads +9.81 along whatever axis points up. Project onto the world "up" direction
                // and subtract g; what is left is the ship's HEAVE acceleration - the only witness
                // we have to what the sea is doing. See SeaAnalyzer.
                // If attitude is not available yet, use the accelerometer's own direction, which
                // for a phone lying flat on a deck is a perfectly good estimate of "up".
                val ux: Double; val uy: Double; val uz: Double
                if (haveUp) {
                    ux = upX; uy = upY; uz = upZ
                } else {
                    ux = ax / n; uy = ay / n; uz = az / n
                }
                tAz.add(t)
                azRaw.add(ax * ux + ay * uy + az * uz - GRAVITY)

                if (source == Source.ACCEL_ONLY) push(t, tilt)
            }

            Sensor.TYPE_GYROSCOPE -> {
                if (source != Source.GYRO_ACCEL) return
                if (lastGyroNanos == 0L) {
                    lastGyroNanos = e.timestamp
                    cfX = accX; cfY = accY
                    return
                }
                val dt = (e.timestamp - lastGyroNanos) / 1e9
                lastGyroNanos = e.timestamp
                if (dt <= 0.0 || dt > 0.5) return

                // integrate angular rate; blend a little accelerometer back in to kill bias drift.
                // tau = 20 s: the accelerometer contributes only below 0.008 Hz, i.e. FAR below the
                // roll band, so wave-induced lateral acceleration never enters the roll signal.
                val tau = 20.0
                val alpha = tau / (tau + dt)
                // Sign convention. Rotating the device by +theta about its +Y axis puts world-up at
                // (-sin theta, 0, cos theta) in the device frame, so tiltX = atan2(gx, .) ~ -theta,
                // i.e. d(tiltX)/dt = -omega_y. Likewise d(tiltY)/dt = +omega_x. Get this wrong and
                // the gyro fights the accelerometer instead of complementing it.
                val gyroX = -e.values[1].toDouble() * RAD2DEG   // -omega_y -> tiltX rate
                val gyroY = e.values[0].toDouble() * RAD2DEG    // +omega_x -> tiltY rate
                cfX = alpha * (cfX + gyroX * dt) + (1 - alpha) * accX
                cfY = alpha * (cfY + gyroY * dt) + (1 - alpha) * accY
                push(t, Pair(cfX, cfY))
            }
        }
    }

    /** Small-angle tilt of the device from a unit gravity vector in the device frame, in degrees. */
    private fun tiltFromGravity(gx: Double, gy: Double, gz: Double): Pair<Double, Double> {
        val tx = atan2(gx, sqrt(gy * gy + gz * gz)) * RAD2DEG
        val ty = atan2(gy, sqrt(gx * gx + gz * gz)) * RAD2DEG
        return Pair(tx, ty)
    }

    private fun push(t: Double, tilt: Pair<Double, Double>) {
        tRaw.add(t); xRaw.add(tilt.first); yRaw.add(tilt.second)
        onSample?.invoke(t, tilt.first, tilt.second)
    }

    // ------------------------------------------------------------------ output

    data class RollSeries(
        val phi: DoubleArray,        // roll angle [deg], uniform at FS
        val fs: Double,
        val axisDominance: Double,   // 0.5 .. 1.0
        val headingOffsetDeg: Double,// estimated angle between the phone's +Y and the roll axis
        val source: Source,
        val durationSeconds: Double
    )

    /**
     * Resample to a uniform grid, find the roll axis, project. This is the only place where the
     * 2-D tilt becomes a scalar roll angle.
     */
    fun buildRollSeries(): RollSeries? {
        val n = tRaw.size
        if (n < 100) return null
        val dur = tRaw[n - 1] - tRaw[0]
        if (dur < 10.0) return null

        val m = (dur * FS).toInt()
        val ux = DoubleArray(m)
        val uy = DoubleArray(m)
        var j = 0
        for (i in 0 until m) {
            val t = tRaw[0] + i / FS
            while (j < n - 2 && tRaw[j + 1] < t) j++
            val t0 = tRaw[j]; val t1 = tRaw[j + 1]
            val w = if (t1 > t0) ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0) else 0.0
            ux[i] = xRaw[j] + w * (xRaw[j + 1] - xRaw[j])
            uy[i] = yRaw[j] + w * (yRaw[j + 1] - yRaw[j])
        }

        // band-pass both channels before finding the axis, so that a static list / slow trim change
        // cannot masquerade as "roll". Band edges come from the estimator's config so the two
        // cannot drift apart.
        val cfg = PeriodEstimator.Config()
        val fLo = 1.0 / cfg.tMax
        val fHi = 1.0 / cfg.tMin
        val bx = Dsp.bandpassFft(ux, FS, fLo, fHi)
        val by = Dsp.bandpassFft(uy, FS, fLo, fHi)

        val (axX, axY, dominance) = when (axisMode) {
            AxisMode.AUTO -> Dsp.principalAxis(bx, by).let { Triple(it.ux, it.uy, it.dominance) }
            // phone's top edge fore/aft => ship roll tilts the phone about its own Y axis => tiltX
            AxisMode.LONGITUDINAL -> Triple(1.0, 0.0, Dsp.principalAxis(bx, by).dominance)
            // phone's top edge athwartships => ship roll shows up in tiltY
            AxisMode.TRANSVERSE -> Triple(0.0, 1.0, Dsp.principalAxis(bx, by).dominance)
        }

        val phi = DoubleArray(m) { bx[it] * axX + by[it] * axY }

        // sign convention: make the largest excursion positive (starboard-down); irrelevant to the
        // period but keeps the trace readable.
        val mx = phi.maxOrNull() ?: 0.0
        val mn = phi.minOrNull() ?: 0.0
        if (-mn > mx) for (i in phi.indices) phi[i] = -phi[i]

        val heading = Math.toDegrees(atan2(axY, axX))

        return RollSeries(
            phi = phi,
            fs = FS,
            axisDominance = dominance,
            headingOffsetDeg = heading,
            source = source,
            durationSeconds = dur
        )
    }

    /**
     * The heave (world-vertical) acceleration, resampled onto the same uniform grid as the roll.
     * This is the sea's signature. Detrended, so any residual gravity offset does not matter.
     */
    fun buildHeaveSeries(): DoubleArray? {
        val n = tAz.size
        if (n < 100) return null
        val dur = tAz[n - 1] - tAz[0]
        if (dur < 10.0) return null
        val m = (dur * FS).toInt()
        val out = DoubleArray(m)
        var j = 0
        for (i in 0 until m) {
            val t = tAz[0] + i / FS
            while (j < n - 2 && tAz[j + 1] < t) j++
            val t0 = tAz[j]; val t1 = tAz[j + 1]
            val w = if (t1 > t0) ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0) else 0.0
            out[i] = azRaw[j] + w * (azRaw[j + 1] - azRaw[j])
        }
        return Dsp.detrend(out)
    }

    /** Raw tilt channels, for CSV export / offline re-analysis. */
    fun rawSamples(): Triple<DoubleArray, DoubleArray, DoubleArray> =
        Triple(tRaw.toDoubleArray(), xRaw.toDoubleArray(), yRaw.toDoubleArray())

    /** Raw heave-acceleration channel, for CSV export. */
    fun rawHeave(): Pair<DoubleArray, DoubleArray> =
        Pair(tAz.toDoubleArray(), azRaw.toDoubleArray())
}
