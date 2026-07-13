package com.gmestimator.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.gmestimator.core.GmModel
import com.gmestimator.core.Nav
import com.gmestimator.core.NavSource
import com.gmestimator.core.PeriodEstimator
import com.gmestimator.core.RollRecorder
import com.gmestimator.data.ShipProfile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Exporter {

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /**
     * ALWAYS format numbers with Locale.US.
     *
     * The default locale on a phone set to Romanian, German, French, ... uses a COMMA as the
     * decimal separator, so "%.4f".format(1.5) yields "1,5000" and a comma-separated file is
     * destroyed: 1,5000,0,12345,-0,54321. This is not hypothetical - it is the default behaviour
     * of String.format() on most of the world's phones.
     */
    private fun n(v: Double, decimals: Int = 4): String =
        String.format(Locale.US, "%.${decimals}f", v)

    /**
     * Writes two files:
     *   *_raw.csv     every sensor sample (irregular timing preserved) - for offline re-analysis
     *   *_report.txt  the measurement record: inputs, both period estimates, the sea, quality, GM
     */
    fun export(
        context: Context,
        profile: ShipProfile,
        series: RollRecorder.RollSeries,
        result: PeriodEstimator.Result,
        gm: GmModel.GmResult,
        mode: PeriodEstimator.Mode,
        raw: Triple<DoubleArray, DoubleArray, DoubleArray>,
        heave: Pair<DoubleArray, DoubleArray>,
        gyro: Array<DoubleArray>,
        nav: Nav,
        note: String
    ): List<File> {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, "records").apply { mkdirs() }
        val base = "GM_${profile.name.ifBlank { "ship" }.replace(Regex("[^A-Za-z0-9]"), "_")}_${stamp()}"

        val csv = File(dir, "${base}_raw.csv")
        csv.bufferedWriter().use { w ->
            // THE HEADER MUST DESCRIBE THE ROWS BENEATH IT.
            //
            // It used to say `fs_resampled_hz,25.0` - the rate the ANALYSIS runs at - directly
            // above raw sensor rows arriving at about 50 Hz. Anyone re-analysing this file
            // offline reads the header, assumes 25 Hz, and gets EVERY PERIOD WRONG BY A FACTOR
            // OF TWO. It cost me ten minutes and very nearly a false bug report against my own
            // sea-lock: at the wrong rate, the ship's roll and the sea land on top of each other.
            //
            // State the rate of the rows that are actually here, measured from the timestamps.
            val (t, x, y) = raw
            val rawFs = if (t.size > 2 && t.last() > t.first())
                (t.size - 1) / (t.last() - t.first()) else Double.NaN
            w.write("# GM Estimator raw record\n")
            w.write("# sensor_source,${series.source}\n")
            w.write("# sample_rate_hz_of_THIS_file,${n(rawFs, 2)}\n")
            w.write("# (the analysis resamples to ${n(series.fs, 1)} Hz; these rows are NOT resampled)\n")
            w.write("# roll_axis,${axisNote(series.headingOffsetDeg)}\n")
            w.write("# axis_dominance,${n(series.axisDominance, 3)}\n")
            w.write("t_s,tilt_x_deg,tilt_y_deg\n")
            for (i in t.indices) {
                w.write("${n(t[i], 4)},${n(x[i], 5)},${n(y[i], 5)}\n")
            }
        }

        // The HEAVE channel. It was missing from the first version, which meant that when the
        // first real record came back with a puzzling sea-lock flag, the one signal needed to
        // explain it could not be re-examined. Export what you rely on.
        val hcsv = File(dir, "${base}_heave.csv")
        hcsv.bufferedWriter().use { w ->
            w.write("# world-vertical linear acceleration (gravity removed)\n")
            w.write("t_s,az_ms2\n")
            val (ht, ha) = heave
            for (i in ht.indices) w.write("${n(ht[i], 4)},${n(ha[i], 5)}\n")
        }

        val rep = File(dir, "${base}_report.txt")
        // THE RAW GYROSCOPE. The only witness aboard that cannot be fooled by the ship's own
        // acceleration. Exported so the fused attitude can be audited offline, which is exactly
        // how the fused attitude's honesty gets established or destroyed.
        val gyroFile = File(dir, "${base}_gyro.csv")
        if (gyro.size == 4 && gyro[0].isNotEmpty()) {
            gyroFile.bufferedWriter().use { w ->
                w.write("# raw gyroscope, device frame, rad/s - NEVER touched by the accelerometer\n")
                val tg = gyro[0]
                val fsG = if (tg.size > 2 && tg.last() > tg.first())
                    (tg.size - 1) / (tg.last() - tg.first()) else Double.NaN
                w.write("# sample_rate_hz_of_THIS_file,${n(fsG, 2)}\n")
                w.write("t_s,wx,wy,wz\n")
                for (i in tg.indices) {
                    w.write("${n(tg[i], 4)},${n(gyro[1][i], 6)},${n(gyro[2][i], 6)},${n(gyro[3][i], 6)}\n")
                }
            }
        }

        rep.writeText(buildReport(profile, series, result, gm, mode, nav, note))

        return listOfNotNull(rep, csv, hcsv, gyroFile.takeIf { it.exists() })
    }

    /** Which of the phone's own axes was taken as the roll axis. "0.0 deg from +Y" was ambiguous. */
    private fun axisNote(headingDeg: Double): String = when {
        headingDeg.isNaN() -> "unknown"
        kotlin.math.abs(headingDeg) < 1.0 -> "tilt_x  (phone's long edge fore-and-aft; tilt_y is then PITCH)"
        kotlin.math.abs(headingDeg - 90.0) < 1.0 -> "tilt_y  (phone's long edge athwartships; tilt_x is then PITCH)"
        else -> "${n(headingDeg, 1)} deg from phone +X (found by PCA)"
    }

    fun buildReport(
        profile: ShipProfile,
        series: RollRecorder.RollSeries,
        r: PeriodEstimator.Result,
        gm: GmModel.GmResult,
        mode: PeriodEstimator.Mode,
        nav: Nav,
        note: String
    ): String = buildString {
        val f0 = { v: Double -> n(v, 0) }
        val f1 = { v: Double -> n(v, 1) }
        val f2 = { v: Double -> n(v, 2) }
        val f3 = { v: Double -> n(v, 3) }

        appendLine("GM BY ROLLING PERIOD - MEASUREMENT RECORD")
        appendLine("=========================================")
        appendLine("Date/time (local) : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("Ship              : ${profile.name}")
        appendLine("Mode              : $mode")
        if (note.isNotBlank()) appendLine("Note              : $note")
        appendLine()
        appendLine("SHIP PARTICULARS")
        appendLine("  Breadth   B   = ${f2(profile.beam)} m")
        appendLine("  Draught   d   = ${f2(profile.draught)} m  (mean moulded)")
        appendLine("  Length    Lwl = ${f2(profile.lwl)} m")
        appendLine()
        appendLine("SHIP'S MOTION DURING THE RECORD")
        // This was missing from the first version. When the Androklis record came back with a
        // roll floor that never decayed, I could not tell whether it was the sea or the
        // AUTOPILOT re-exciting her, because the record did not say how fast she was going.
        // Record the conditions you will later need to explain the result.
        // AND WHERE THOSE FIGURES CAME FROM. A number in a report with no provenance is worse
        // than no number: the reader assumes it was measured. If the GPS never got a fix and
        // nobody typed anything in, this report says UNKNOWN, in capitals, and does not pretend.
        appendLine("  Source             ${nav.label()}")
        when (nav.source) {
            NavSource.UNKNOWN -> {
                appendLine("  Speed over ground  UNKNOWN")
                appendLine("  Course over ground UNKNOWN")
                appendLine("  ${nav.detail}.")
                appendLine("  NOTE: an unknown speed is NOT a zero speed. Nothing in this report has")
                appendLine("  been checked against her speed - including whether a free decay was")
                appendLine("  taken slowly enough to be a free decay at all.")
            }
            else -> {
                appendLine("  Speed over ground  ${f1(nav.sogKn)} kn")
                appendLine(
                    "  Course over ground " +
                        (if (nav.courseKnown) f0(nav.cogDeg) + " deg" else "none (stopped or drifting)")
                )
                if (nav.detail.isNotBlank()) appendLine("  ${nav.detail}.")
                if (!nav.steady) appendLine("  WARNING: course/speed NOT steady - every peak is smeared.")
            }
        }
        if (nav.speedKnown && nav.sogKn > 6.0 && mode == PeriodEstimator.Mode.FREE_DECAY) {
            appendLine("  WARNING: a free decay at ${f1(nav.sogKn)} kn is not free - autopilot rudder")
            appendLine("  corrections keep re-exciting her roll, speed adds lift damping, and forward")
            appendLine("  speed raises GM itself. The booklet GM is a ZERO-SPEED number.")
        }
        appendLine()
        appendLine("ATTITUDE CROSS-CHECK  (the fused attitude, audited by the raw gyroscope)")
        val at = r.attitude
        if (!at.available) {
            appendLine("  NO GYROSCOPE - the fused attitude could not be checked.")
        } else {
            appendLine("  Fused attitude peak    ${f2(at.periodFused)} s")
            appendLine("  Raw gyroscope peak     ${f2(at.periodGyro)} s")
            appendLine("  Correlation            ${f2(kotlin.math.abs(at.correlation))}")
            appendLine("  Amplitude ratio        ${f2(at.amplitudeRatio)}  (gyro / fused)")
            appendLine("  VERDICT                ${if (at.agree) "AGREE" else "*** DISAGREE ***"}")
            appendLine("  ${at.message}.")
            appendLine()
            appendLine("  Why this matters: GAME_ROTATION_VECTOR is gyro AND accelerometer, blended.")
            appendLine("  On a ship the accelerometer sees the hull's sway and surge, not just")
            appendLine("  gravity, so the fusion filter's 'down' is dragged about by the ship at")
            appendLine("  the very frequencies she rolls at. A gyroscope cannot see gravity. What")
            appendLine("  the gyroscope does not see IS NOT ROTATION.")
        }
        appendLine()
        appendLine("SENSOR / RECORD")
        appendLine("  Source          : ${series.source}")
        appendLine("  Duration        : ${f1(series.durationSeconds)} s")
        appendLine("  Resample rate   : ${f1(series.fs)} Hz")
        appendLine("  Roll axis       : ${axisNote(series.headingOffsetDeg)}")
        appendLine("  Axis dominance  : ${f3(series.axisDominance)}  (1.0 = pure single-axis motion)")
        appendLine()
        appendLine("ROLL PERIOD")
        appendLine("  Spectral (Welch PSD peak)   T = ${f2(r.periodSpectral)} s")
        appendLine("  Zero-crossing (median)      T = ${f2(r.periodZeroCross)} s  over ${r.nCycles} cycles")
        appendLine("  Agreement                     ${f1(r.agreement * 100)} %")
        appendLine("  Peak prominence               ${f1(r.prominence)} x median band power")
        appendLine("  Peak repeatability            ${f1(r.consistency * 100)} % of sub-windows")
        if (!r.periodScatter.isNaN()) {
            appendLine("  CYCLE-TO-CYCLE SCATTER        ${f1(r.periodScatter * 100)} %   <- the honest quality number")
            appendLine("     (the spread of her OWN cycles. Two estimators agreeing tells you nothing;")
            appendLine("      they are both averaging the same data. This is what she actually did.)")
        }
        if (!r.resolutionLimit.isNaN()) {
            appendLine("  Resolution limit of this record +/- ${f2(r.resolutionLimit)} s")
            appendLine("     (a ${f0(series.durationSeconds)} s record at a ${f1(r.period)} s period is only " +
                "${f0(series.durationSeconds / r.period)} cycles)")
        }
        if (!r.decayContrast.isNaN()) {
            appendLine("  Decay contrast                ${f1(r.decayContrast)}x  (biggest swing / roll still running at the end)")
        }
        if (!r.competingPeriod.isNaN()) {
            appendLine("  Competing period            T = ${f2(r.competingPeriod)} s at ${f1(r.competingRatio * 100)} % of primary power")
        }
        if (!r.zeta.isNaN()) appendLine("  Roll damping ratio zeta       ${f3(r.zeta)}")
        appendLine()
        appendLine("THE SEA  (from the vertical accelerometer)")
        val sea = r.sea
        if (sea == null) {
            appendLine("  NOT CHECKED - no heave signal.")
        } else {
            appendLine("  Dominant wave period         ${f2(sea.wavePeakPeriod)} s")
            appendLine("  Vertical acceleration (RMS)  ${f2(sea.rmsVerticalAcc)} m/s2")
            appendLine("  Indicative Hs                ${f1(sea.indicativeHs)} m   (rough)")
            if (sea.leverArmM.isNaN()) {
                appendLine("  Phone lever arm  NOT FITTED - she was rolling too little for the")
                appendLine("     regression to mean anything. The heave has been left untouched,")
                appendLine("     which is the safe choice: a lever arm fitted to noise would inject")
                appendLine("     scaled roll noise into the one channel that is meant to be an")
                appendLine("     INDEPENDENT witness of the sea.")
            } else {
                appendLine("  Phone lever arm from roll axis ${f1(sea.leverArmM)} m  (fitted; its own")
                appendLine("     roll-induced heave has been removed before the check below)")
            }
            appendLine("  Wave energy at the roll peak ${f1(sea.excessAtRollPeakDb)} dB above background")
            if (mode == PeriodEstimator.Mode.SEAWAY) {
                appendLine(
                    "  SEA-LOCK                     " +
                        if (sea.seaLocked) "YES - THIS IS A WAVE, NOT THE SHIP"
                        else "no - the roll peak is the ship's own"
                )
            } else {
                // The veto does NOT apply to a free decay: she is ringing down under her own
                // restoring moment, not being driven. Printing "THIS IS A WAVE" next to an
                // EXCELLENT-quality GM - as the first shipped version did - is worse than
                // useless: it teaches the operator to ignore the warning.
                appendLine("  SEA-LOCK                     not applicable to a free-decay test")
                appendLine("     (she is ringing down under her own restoring moment, not being driven)")
            }
        }
        appendLine()
        appendLine("  ADOPTED                     T = ${f2(r.period)} +/- ${f2(r.periodUncertainty)} s")
        appendLine("  Roll amplitude              mean ${f2(r.meanAmplitude)} deg, max ${f2(r.maxAmplitude)} deg")
        appendLine("  Quality                     ${r.quality}  - ${r.message}")
        appendLine()
        appendLine("GM")
        appendLine("  Model            GM = ( f * B / T )^2")
        appendLine("  f source         ${gm.fSource}")
        appendLine("  f                ${f3(gm.f)}   (equivalent IS Code C = ${f3(gm.f / 2.0)})")
        appendLine("  u(T)/T           ${f1(gm.relUncertaintyT * 100)} %")
        appendLine("  u(f)/f           ${f1(gm.relUncertaintyF * 100)} %")
        appendLine("  ---------------------------------------------")
        appendLine("  GM = ${f2(gm.gm)} m   (1-sigma: ${f2(gm.gmLow)} .. ${f2(gm.gmHigh)} m, +/- ${f1(gm.relUncertainty * 100)} %)")
        appendLine("  ---------------------------------------------")
        if (profile.minRequiredGm > 0) {
            val verdict = when {
                gm.gmLow >= profile.minRequiredGm -> "ABOVE required GM"
                gm.gmHigh < profile.minRequiredGm -> "BELOW required GM"
                else -> "INCONCLUSIVE - required GM lies inside the uncertainty band"
            }
            appendLine("  Required GM (booklet) = ${f2(profile.minRequiredGm)} m  ->  $verdict")
        }
        appendLine()
        if (r.caveats.isNotEmpty()) {
            appendLine("WHAT I COULD NOT CHECK")
            r.caveats.forEach { c -> appendLine("  * $c.") }
            appendLine()
        }
        appendLine("DISCLAIMER")
        appendLine("  Estimate only. The rolling-period method assumes small-amplitude, lightly damped,")
        appendLine("  free roll of a rigid ship in deep water with no significant free surface or")
        appendLine("  anti-roll device active. It is NOT a substitute for the approved loading computer")
        appendLine("  or an inclining experiment, and must not be used as the sole basis for a")
        appendLine("  stability decision.")
    }

    fun share(context: Context, files: List<File>) {
        val uris = ArrayList<Uri>(files.map {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
        })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"                                  // a .txt AND a .csv
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // share() is called with the Application context (from the ViewModel), so the chooser
        // needs NEW_TASK or startActivity() throws AndroidRuntimeException.
        val chooser = Intent.createChooser(intent, "Share measurement record")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
