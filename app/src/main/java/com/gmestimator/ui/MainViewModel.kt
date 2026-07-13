package com.gmestimator.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.gmestimator.core.GmModel
import com.gmestimator.core.GpsTrack
import com.gmestimator.core.Nav
import com.gmestimator.core.PeriodEstimator
import com.gmestimator.core.RollRecorder
import com.gmestimator.core.SeaAnalyzer
import com.gmestimator.data.CalPoint
import com.gmestimator.data.ProfileStore
import com.gmestimator.data.ShipProfile
import com.gmestimator.export.Exporter
import java.io.File
import kotlin.math.abs

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** PeriodEstimator hard-fails below 2 * tMax. Keep the UI and the estimator in step. */
        const val MIN_RECORD_SECONDS = 90.0
    }

    private val store = ProfileStore(app)
    val recorder = RollRecorder(app)
    val gps = GpsTrack(app)

    /** Completed records this session, kept so we can run the encounter test across them. */
    val history = mutableStateListOf<SeaAnalyzer.RecordSummary>()
    var encounter by mutableStateOf<SeaAnalyzer.EncounterVerdict?>(null)
        private set

    var profile by mutableStateOf(store.load())
        private set

    var mode by mutableStateOf(PeriodEstimator.Mode.FREE_DECAY)
    var axisMode by mutableStateOf(RollRecorder.AxisMode.AUTO)

    var recording by mutableStateOf(false)
        private set
    var elapsed by mutableStateOf(0.0)
        private set

    /** Rolling window of the live trace for the UI. */
    val liveTrace = mutableStateListOf<Float>()
    private var liveDecim = 0

    var series by mutableStateOf<RollRecorder.RollSeries?>(null)
        private set
    var result by mutableStateOf<PeriodEstimator.Result?>(null)
        private set
    var gm by mutableStateOf<GmModel.GmResult?>(null)
        private set
    var lastFiles by mutableStateOf<List<File>>(emptyList())
        private set
    var note by mutableStateOf("")

    /** Speed and course for the record just taken, with its provenance. Never a bare NaN. */
    var nav by mutableStateOf<Nav?>(null)
        private set

    /** Live, before and during a record: is the receiver actually seeing anything? */
    fun liveNav(): Nav = if (profile.forceManualNav) resolveNav() else gps.nav()

    val sensorSource: RollRecorder.Source get() = recorder.availableSource()

    /**
     * Target record length.
     *
     * 180 s was too short and the first real ship proved it: at a 16 s roll that is only 12
     * cycles, and a 12-cycle record cannot resolve the period better than +/- 2.7 s - which is
     * +/- 34% on GM. You need ~25 cycles. For a slow ship that means eight minutes, not three.
     */
    val recommendedSeconds: Double
        get() = if (mode == PeriodEstimator.Mode.FREE_DECAY) 480.0 else 1800.0

    fun updateProfile(block: ShipProfile.() -> Unit) {
        val p = profile.copy(calibrations = profile.calibrations.toMutableList())
        p.block()
        profile = p
        store.save(p)
    }

    fun start() {
        result = null; gm = null; series = null; lastFiles = emptyList()
        liveTrace.clear()
        liveDecim = 0
        elapsed = 0.0
        recorder.axisMode = axisMode
        recorder.onSample = { t, x, y ->
            elapsed = t
            if (liveDecim++ % 5 == 0) {                    // 50 Hz -> ~10 Hz for the plot
                liveTrace.add((x + y).toFloat())           // provisional; axis unknown until analysis
                while (liveTrace.size > 1200) liveTrace.removeAt(0)
            }
        }
        recorder.start()
        gps.start()                    // for the encounter test; harmless if permission is denied
        recording = true
    }

    fun stopAndAnalyse() {
        recorder.stop()
        gps.stop()
        recording = false
        val s = recorder.buildRollSeries() ?: return
        series = s

        // The heave signal is what lets the estimator ask the sea whether the roll peak is even
        // the ship's. Without it, SEAWAY mode is flying blind.
        val heave = recorder.buildHeaveSeries()

        // ONE place decides what we know about her speed, and it is never allowed to answer
        // "zero" when it means "no idea". See Nav.
        val nav = resolveNav()
        this.nav = nav
        val r = PeriodEstimator.estimate(s.phi, s.fs, mode, s.axisDominance, heave, nav)
        result = r

        // Log the record so a second one, on a different heading, can be compared against it.
        if (!r.period.isNaN()) {
            history.add(
                SeaAnalyzer.RecordSummary(
                    label = "#${history.size + 1}",
                    period = r.period,
                    sogKn = nav.sogKn,
                    cogDeg = nav.cogDeg
                )
            )
            encounter = runEncounterCheck()
        }

        if (!r.period.isNaN() && profile.isValid()) {
            gm = GmModel.evaluate(
                period = r.period,
                periodUncertainty = r.periodUncertainty,
                f = profile.effectiveF(),
                fRelUncertainty = profile.fUncertainty(),
                beam = profile.beam,
                fSource = profile.fSource
            )
            autoSave()          // a measurement the instrument does not keep is not a measurement
        }
    }

    /**
     * What we know about her speed and course, and where it came from.
     *
     * The GPS first. If it never saw the sky - which, in a steel deckhouse, is the usual outcome
     * - the operator's own figures off the bridge. If neither: UNKNOWN, said out loud.
     */
    fun resolveNav(): Nav = Nav.resolve(
        gps = gps.nav(),
        forceManual = profile.forceManualNav,
        manualSog = profile.manualSogKn,
        manualCog = profile.manualCogDeg
    )

    /** Turn the current measurement into a calibration point against a GM you already trust. */
    fun addCalibration(label: String, knownGm: Double) {
        val r = result ?: return
        if (r.period.isNaN() || knownGm <= 0.0 || profile.beam <= 0.0) return
        val f = GmModel.fFromKnownGm(r.period, knownGm, profile.beam)
        updateProfile {
            calibrations.add(
                CalPoint(label, r.period, knownGm, draught, f, System.currentTimeMillis())
            )
            fSource = GmModel.FSource.CALIBRATED
        }
        gm = GmModel.evaluate(
            r.period, r.periodUncertainty, profile.effectiveF(),
            profile.fUncertainty(), profile.beam, profile.fSource
        )
    }

    fun removeCalibration(index: Int) = updateProfile {
        if (index in calibrations.indices) calibrations.removeAt(index)
    }

    /**
     * Find the two records in this session taken on the most different headings, and ask whether
     * the period held. A wave-driven peak moves with heading; the ship's does not.
     */
    private fun runEncounterCheck(): SeaAnalyzer.EncounterVerdict? {
        val usable = history.filter { !it.cogDeg.isNaN() && !it.sogKn.isNaN() }
        if (usable.size < 2) return null
        var best: Pair<SeaAnalyzer.RecordSummary, SeaAnalyzer.RecordSummary>? = null
        var bestSpread = -1.0
        for (i in usable.indices) for (j in i + 1 until usable.size) {
            var d = abs(usable[i].cogDeg - usable[j].cogDeg) % 360.0
            if (d > 180.0) d = 360.0 - d
            val spread = d + 10.0 * abs(usable[i].sogKn - usable[j].sogKn)
            if (spread > bestSpread) {
                bestSpread = spread
                best = usable[i] to usable[j]
            }
        }
        return best?.let { SeaAnalyzer.encounterCheck(it.first, it.second) }
    }

    /** The last period she actually gave us, for the forecast warnings. NaN if none yet. */
    fun lastMeasuredPeriod(): Double =
        result?.period?.takeIf { !it.isNaN() } ?: Double.NaN

    fun clearHistory() {
        history.clear()
        encounter = null
    }

    /**
     * SAVE EVERY MEASUREMENT, ALWAYS.
     *
     * The 1.31 m record was analysed, shown, and then lost, because the file was only written if
     * the operator remembered to press Export. A measurement an instrument does not keep is a
     * measurement you cannot defend afterwards. Every completed analysis is now written to disk
     * the moment it exists; Share is what the button does now, not Save.
     */
    private fun autoSave() {
        try {
            exportRecord()
        } catch (_: Throwable) {
            // never let a disk problem destroy a result the operator is looking at
        }
    }

    fun exportRecord() {
        val s = series ?: return
        val r = result ?: return
        val g = gm ?: return
        lastFiles = Exporter.export(
            getApplication(), profile, s, r, g, mode,
            recorder.rawSamples(), recorder.rawHeave(),
            nav ?: resolveNav(), note
        )
    }

    fun share() {
        if (lastFiles.isNotEmpty()) Exporter.share(getApplication(), lastFiles)
    }

    override fun onCleared() {
        recorder.stop()
        gps.stop()
        super.onCleared()
    }
}
