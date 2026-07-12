package com.gmestimator.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.gmestimator.core.GmModel
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
     *   *_report.txt  the measurement record: inputs, both period estimates, quality, GM
     */
    fun export(
        context: Context,
        profile: ShipProfile,
        series: RollRecorder.RollSeries,
        result: PeriodEstimator.Result,
        gm: GmModel.GmResult,
        mode: PeriodEstimator.Mode,
        raw: Triple<DoubleArray, DoubleArray, DoubleArray>,
        note: String
    ): List<File> {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(root, "records").apply { mkdirs() }
        val base = "GM_${profile.name.ifBlank { "ship" }.replace(Regex("[^A-Za-z0-9]"), "_")}_${stamp()}"

        val csv = File(dir, "${base}_raw.csv")
        csv.bufferedWriter().use { w ->
            w.write("# GM Estimator raw record\n")
            w.write("# sensor_source,${series.source}\n")
            w.write("# fs_resampled_hz,${n(series.fs, 1)}\n")
            w.write("# roll_axis_heading_deg,${n(series.headingOffsetDeg, 1)}\n")
            w.write("# axis_dominance,${n(series.axisDominance, 3)}\n")
            w.write("t_s,tilt_x_deg,tilt_y_deg\n")
            val (t, x, y) = raw
            for (i in t.indices) {
                w.write("${n(t[i], 4)},${n(x[i], 5)},${n(y[i], 5)}\n")
            }
        }

        val rep = File(dir, "${base}_report.txt")
        rep.writeText(buildReport(profile, series, result, gm, mode, note))

        return listOf(rep, csv)
    }

    fun buildReport(
        profile: ShipProfile,
        series: RollRecorder.RollSeries,
        r: PeriodEstimator.Result,
        gm: GmModel.GmResult,
        mode: PeriodEstimator.Mode,
        note: String
    ): String = buildString {
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
        appendLine("SENSOR / RECORD")
        appendLine("  Source          : ${series.source}")
        appendLine("  Duration        : ${f1(series.durationSeconds)} s")
        appendLine("  Resample rate   : ${f1(series.fs)} Hz")
        appendLine("  Roll-axis offset: ${f1(series.headingOffsetDeg)} deg from phone +Y")
        appendLine("  Axis dominance  : ${f3(series.axisDominance)}  (1.0 = pure single-axis motion)")
        appendLine()
        appendLine("ROLL PERIOD")
        appendLine("  Spectral (Welch PSD peak)   T = ${f2(r.periodSpectral)} s")
        appendLine("  Zero-crossing (median)      T = ${f2(r.periodZeroCross)} s  over ${r.nCycles} cycles")
        appendLine("  Agreement                     ${f1(r.agreement * 100)} %")
        appendLine("  Peak prominence               ${f1(r.prominence)} x median band power")
        appendLine("  Peak repeatability            ${f1(r.consistency * 100)} % of sub-windows")
        if (!r.competingPeriod.isNaN()) {
            appendLine("  Competing period            T = ${f2(r.competingPeriod)} s at ${f1(r.competingRatio * 100)} % of primary power")
        }
        if (!r.zeta.isNaN()) appendLine("  Roll damping ratio zeta       ${f3(r.zeta)}")
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
