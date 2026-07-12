package com.gmestimator.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.gmestimator.core.GmModel
import com.gmestimator.core.PeriodEstimator
import com.gmestimator.core.RollRecorder
import com.gmestimator.data.CalPoint
import com.gmestimator.data.ProfileStore
import com.gmestimator.data.ShipProfile
import com.gmestimator.export.Exporter
import java.io.File

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** PeriodEstimator hard-fails below 2 * tMax. Keep the UI and the estimator in step. */
        const val MIN_RECORD_SECONDS = 90.0
    }

    private val store = ProfileStore(app)
    val recorder = RollRecorder(app)

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

    val sensorSource: RollRecorder.Source get() = recorder.availableSource()

    /** Target record length: a free decay needs ~10 cycles; a seaway record needs many more. */
    val recommendedSeconds: Double
        get() = if (mode == PeriodEstimator.Mode.FREE_DECAY) 180.0 else 1200.0

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
        recording = true
    }

    fun stopAndAnalyse() {
        recorder.stop()
        recording = false
        val s = recorder.buildRollSeries() ?: return
        series = s
        val r = PeriodEstimator.estimate(s.phi, s.fs, mode, s.axisDominance)
        result = r
        if (!r.period.isNaN() && profile.isValid()) {
            gm = GmModel.evaluate(
                period = r.period,
                periodUncertainty = r.periodUncertainty,
                f = profile.effectiveF(),
                fRelUncertainty = profile.fUncertainty(),
                beam = profile.beam,
                fSource = profile.fSource
            )
        }
    }

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
        // recompute with the new f
        gm = GmModel.evaluate(
            r.period, r.periodUncertainty, profile.effectiveF(),
            profile.fUncertainty(), profile.beam, profile.fSource
        )
    }

    fun removeCalibration(index: Int) = updateProfile {
        if (index in calibrations.indices) calibrations.removeAt(index)
    }

    fun exportRecord() {
        val s = series ?: return
        val r = result ?: return
        val g = gm ?: return
        lastFiles = Exporter.export(
            getApplication(), profile, s, r, g, mode, recorder.rawSamples(), note
        )
    }

    fun share() {
        if (lastFiles.isNotEmpty()) Exporter.share(getApplication(), lastFiles)
    }

    override fun onCleared() {
        recorder.stop()
        super.onCleared()
    }
}
