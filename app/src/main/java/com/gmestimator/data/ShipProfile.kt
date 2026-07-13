package com.gmestimator.data

import android.content.Context
import com.gmestimator.core.ForecastAdvisor
import com.gmestimator.core.GmModel
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/** One calibration point: a roll period measured in a condition whose GM was known. */
data class CalPoint(
    val label: String,
    val periodSeconds: Double,
    val knownGm: Double,
    val draught: Double,
    val f: Double,
    val timestamp: Long
)

data class ShipProfile(
    var name: String = "",
    var beam: Double = 0.0,             // B, moulded breadth [m]
    var draught: Double = 0.0,          // d, mean moulded draught [m] (current condition)
    var lwl: Double = 0.0,              // Lwl, waterline length [m]
    var minRequiredGm: Double = 0.0,    // from the stability booklet; 0 = not set
    var fSource: GmModel.FSource = GmModel.FSource.IS_CODE,
    var manualF: Double = 0.80,
    // The Master's forecast. Used ONLY to label peaks, warn about resonance and advise a
    // measurement window. It NEVER touches GM - see ForecastAdvisor.
    var seaHs: Double = 0.0, var seaTp: Double = 0.0, var seaFrom: Double = 0.0,
    var swellHs: Double = 0.0, var swellTp: Double = 0.0, var swellFrom: Double = 0.0,
    var calibrations: MutableList<CalPoint> = mutableListOf()
) {

    fun waveSystems(): List<ForecastAdvisor.WaveSystem> = listOf(
        ForecastAdvisor.WaveSystem("Sea", seaHs, seaTp, seaFrom),
        ForecastAdvisor.WaveSystem("Swell", swellHs, swellTp, swellFrom)
    ).filter { it.isSet() }

    /** Her expected roll period from the booklet GM. FOR WARNINGS ONLY - never for the estimate. */
    fun expectedPeriod(): Double =
        if (minRequiredGm > 0 && beam > 0) GmModel.periodFromGm(minRequiredGm, effectiveF(), beam)
        else Double.NaN

    /** The roll coefficient actually used, given the selected source. */
    fun effectiveF(): Double = when (fSource) {
        GmModel.FSource.IS_CODE -> GmModel.isCodeF(beam, draught, lwl)
        GmModel.FSource.MANUAL -> manualF
        GmModel.FSource.CALIBRATED ->
            if (calibrations.isEmpty()) GmModel.isCodeF(beam, draught, lwl)
            else calibrations.map { it.f }.average()
    }

    /** 1-sigma relative uncertainty on f. For CALIBRATED it comes from the operator's own scatter. */
    fun fUncertainty(): Double = when (fSource) {
        GmModel.FSource.CALIBRATED -> {
            if (calibrations.size < 2) GmModel.defaultFUncertainty(fSource, null)
            else {
                val fs = calibrations.map { it.f }
                val m = fs.average()
                val sd = sqrt(fs.sumOf { (it - m) * (it - m) } / (fs.size - 1))
                GmModel.defaultFUncertainty(fSource, if (m > 0) sd / m else null)
            }
        }
        else -> GmModel.defaultFUncertainty(fSource)
    }

    fun isValid(): Boolean = beam > 0 && draught > 0 && lwl > 0

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("beam", beam)
        put("draught", draught)
        put("lwl", lwl)
        put("minRequiredGm", minRequiredGm)
        put("fSource", fSource.name)
        put("manualF", manualF)
        put("seaHs", seaHs); put("seaTp", seaTp); put("seaFrom", seaFrom)
        put("swellHs", swellHs); put("swellTp", swellTp); put("swellFrom", swellFrom)
        put("calibrations", JSONArray().apply {
            calibrations.forEach { c ->
                put(JSONObject().apply {
                    put("label", c.label)
                    put("period", c.periodSeconds)
                    put("knownGm", c.knownGm)
                    put("draught", c.draught)
                    put("f", c.f)
                    put("ts", c.timestamp)
                })
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): ShipProfile {
            val cals = mutableListOf<CalPoint>()
            val arr = o.optJSONArray("calibrations") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                cals.add(
                    CalPoint(
                        label = c.optString("label"),
                        periodSeconds = c.optDouble("period", 0.0),
                        knownGm = c.optDouble("knownGm", 0.0),
                        draught = c.optDouble("draught", 0.0),
                        f = c.optDouble("f", 0.0),
                        timestamp = c.optLong("ts", 0L)
                    )
                )
            }
            return ShipProfile(
                name = o.optString("name"),
                beam = o.optDouble("beam", 0.0),
                draught = o.optDouble("draught", 0.0),
                lwl = o.optDouble("lwl", 0.0),
                minRequiredGm = o.optDouble("minRequiredGm", 0.0),
                fSource = runCatching {
                    GmModel.FSource.valueOf(o.optString("fSource", "IS_CODE"))
                }.getOrDefault(GmModel.FSource.IS_CODE),
                manualF = o.optDouble("manualF", 0.80),
                seaHs = o.optDouble("seaHs", 0.0),
                seaTp = o.optDouble("seaTp", 0.0),
                seaFrom = o.optDouble("seaFrom", 0.0),
                swellHs = o.optDouble("swellHs", 0.0),
                swellTp = o.optDouble("swellTp", 0.0),
                swellFrom = o.optDouble("swellFrom", 0.0),
                calibrations = cals
            )
        }
    }
}

/** Dead-simple SharedPreferences-backed store. No external dependencies. */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("gm_estimator", Context.MODE_PRIVATE)

    fun load(): ShipProfile {
        val s = prefs.getString("profile", null) ?: return ShipProfile()
        return runCatching { ShipProfile.fromJson(JSONObject(s)) }.getOrDefault(ShipProfile())
    }

    fun save(p: ShipProfile) {
        prefs.edit().putString("profile", p.toJson().toString()).apply()
    }
}
