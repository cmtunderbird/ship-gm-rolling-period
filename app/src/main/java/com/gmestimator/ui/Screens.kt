package com.gmestimator.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gmestimator.core.GmModel
import com.gmestimator.core.PeriodEstimator
import com.gmestimator.core.RollRecorder
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

private val Good = Color(0xFF2E7D32)
private val Warn = Color(0xFFE65100)
private val Bad = Color(0xFFC62828)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GmApp(vm: MainViewModel) {
    var tab by remember { mutableStateOf(0) }
    val titles = listOf("Ship", "Measure", "Result", "Calibrate")

    Scaffold(
        topBar = {
            Column {
                CenterAlignedTopAppBar(title = { Text("GM by Rolling Period") })
                TabRow(selectedTabIndex = tab) {
                    titles.forEachIndexed { i, t ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> ShipScreen(vm)
                1 -> MeasureScreen(vm) { tab = 2 }
                2 -> ResultScreen(vm)
                else -> CalibrateScreen(vm)
            }
        }
    }
}

// ---------------------------------------------------------------------------- Ship

@Composable
private fun ShipScreen(vm: MainViewModel) {
    val p = vm.profile
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Ship particulars", style = MaterialTheme.typography.titleMedium)

        Field("Ship name", p.name) { v -> vm.updateProfile { name = v } }
        NumField("Breadth  B  [m]", p.beam) { v -> vm.updateProfile { beam = v } }
        NumField("Mean moulded draught  d  [m]", p.draught) { v -> vm.updateProfile { draught = v } }
        NumField("Waterline length  Lwl  [m]", p.lwl) { v -> vm.updateProfile { lwl = v } }
        NumField("Required GM from booklet [m]  (optional)", p.minRequiredGm) { v ->
            vm.updateProfile { minRequiredGm = v }
        }

        HorizontalDivider()
        Text("Roll coefficient  f   in   T = f · B / √GM", style = MaterialTheme.typography.titleMedium)

        GmModel.FSource.entries.forEach { src ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = p.fSource == src,
                    onClick = { vm.updateProfile { fSource = src } }
                )
                Column(Modifier.padding(start = 4.dp)) {
                    Text(
                        when (src) {
                            GmModel.FSource.IS_CODE -> "IS Code 2008  (f = 2C, computed)"
                            GmModel.FSource.MANUAL -> "Manual  (from the stability booklet)"
                            GmModel.FSource.CALIBRATED -> "Calibrated  (${p.calibrations.size} point(s))"
                        },
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        when (src) {
                            GmModel.FSource.IS_CODE ->
                                "C = 0.373 + 0.023·B/d − 0.043·Lwl/100.  Typical scatter ±8% → ±16% on GM."
                            GmModel.FSource.MANUAL -> "Enter f directly. Note f = 2C."
                            GmModel.FSource.CALIBRATED ->
                                "f solved from a condition of known GM. By far the most accurate option."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (p.fSource == GmModel.FSource.MANUAL) {
            NumField("f  [-]   (typical 0.75 – 0.90)", p.manualF) { v -> vm.updateProfile { manualF = v } }
        }

        if (p.isValid()) {
            val f = p.effectiveF()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("f in use = ${"%.3f".format(f)}   (≡ C = ${"%.3f".format(f / 2)})",
                        fontWeight = FontWeight.Bold)
                    Text("Assumed 1σ uncertainty on f: ±${"%.0f".format(p.fUncertainty() * 100)} %  →  ±${
                        "%.0f".format(2 * p.fUncertainty() * 100)
                    } % on GM before the measurement even starts.",
                        style = MaterialTheme.typography.bodySmall)
                    if (p.minRequiredGm > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text("At the required GM of ${"%.2f".format(p.minRequiredGm)} m this ship should roll with a period of about ${
                            "%.1f".format(GmModel.periodFromGm(p.minRequiredGm, f, p.beam))
                        } s.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            Text("Enter B, d and Lwl to continue.", color = Warn)
        }
    }
}

// ---------------------------------------------------------------------------- Measure

@Composable
private fun MeasureScreen(vm: MainViewModel, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val src = vm.sensorSource
        val srcColor = when (src) {
            RollRecorder.Source.GAME_ROTATION_VECTOR, RollRecorder.Source.ROTATION_VECTOR -> Good
            RollRecorder.Source.GYRO_ACCEL -> Good
            RollRecorder.Source.ACCEL_ONLY -> Bad
            RollRecorder.Source.NONE -> Bad
        }
        Text("Sensor: $src", color = srcColor, fontWeight = FontWeight.Medium)
        if (src == RollRecorder.Source.ACCEL_ONLY) {
            Text(
                "No gyroscope found. Accelerometer-only tilt is corrupted by the ship's sway and " +
                    "by the roll's own centripetal acceleration, at the roll frequency. Results will be biased.",
                color = Bad, style = MaterialTheme.typography.bodySmall
            )
        }

        HorizontalDivider()
        Text("Measurement mode", style = MaterialTheme.typography.titleMedium)
        PeriodEstimator.Mode.entries.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = vm.mode == m, onClick = { vm.mode = m }, enabled = !vm.recording)
                Column(Modifier.padding(start = 4.dp)) {
                    Text(
                        if (m == PeriodEstimator.Mode.FREE_DECAY) "Free decay  (recommended)"
                        else "Seaway",
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (m == PeriodEstimator.Mode.FREE_DECAY)
                            "Calm water. Start the ship rolling (rudder cycling, weight shift, wash), then " +
                                "let her roll freely. This gives the true NATURAL period plus the roll damping. 3 min."
                        else
                            "Rolling in a seaway. A ship rolls at whatever period the WAVES push her at, which " +
                                "is usually not her own. The app checks the accelerometer and will refuse the " +
                                "record if the roll peak turns out to be a wave. Expect to be refused often.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        HorizontalDivider()
        Text("Phone orientation", style = MaterialTheme.typography.titleMedium)
        RollRecorder.AxisMode.entries.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = vm.axisMode == a, onClick = { vm.axisMode = a }, enabled = !vm.recording)
                Text(
                    when (a) {
                        RollRecorder.AxisMode.AUTO -> "Auto — find the roll axis from the motion itself"
                        RollRecorder.AxisMode.LONGITUDINAL -> "Top edge points fore/aft"
                        RollRecorder.AxisMode.TRANSVERSE -> "Top edge points port/starboard"
                    },
                    Modifier.padding(start = 4.dp)
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Placement", fontWeight = FontWeight.Bold)
                Text(
                    "Lay the phone flat, screen up, on a rigid horizontal surface — not on a cushion, " +
                        "not in a hand, not on a table that can slide. Anywhere in the ship will do: the " +
                        "gyroscope measures the ship's rotation, which is the same everywhere. Keep the " +
                        "screen on and do not touch the phone while recording.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        LiveTrace(vm)

        val target = vm.recommendedSeconds
        LinearProgressIndicator(
            progress = { (vm.elapsed / target).coerceIn(0.0, 1.0).toFloat() },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "${"%.0f".format(vm.elapsed)} s recorded  /  ${"%.0f".format(target)} s recommended",
            style = MaterialTheme.typography.bodySmall
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { vm.start() },
                enabled = !vm.recording && vm.profile.isValid() && src != RollRecorder.Source.NONE,
                modifier = Modifier.weight(1f)
            ) { Text("Start") }
            Button(
                onClick = { vm.stopAndAnalyse(); onDone() },
                // The estimator hard-fails below 2 * tMax = 90 s, so do not offer a button that
                // can only produce "record too short".
                enabled = vm.recording && vm.elapsed >= MainViewModel.MIN_RECORD_SECONDS,
                modifier = Modifier.weight(1f)
            ) { Text("Stop & analyse") }
        }
        if (vm.recording && vm.elapsed < MainViewModel.MIN_RECORD_SECONDS) {
            Text(
                "Recording… analysis needs at least ${MainViewModel.MIN_RECORD_SECONDS.toInt()} s " +
                    "(${(MainViewModel.MIN_RECORD_SECONDS - vm.elapsed).toInt()} s to go).",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun LiveTrace(vm: MainViewModel) {
    val pts = vm.liveTrace
    Box(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
    ) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            if (pts.size < 2) return@Canvas
            var amp = 0f
            for (v in pts) amp = max(amp, abs(v))
            if (amp < 0.5f) amp = 0.5f
            val w = size.width
            val h = size.height
            val mid = h / 2
            val path = Path()
            pts.forEachIndexed { i, v ->
                val x = w * i / (pts.size - 1).toFloat()
                val y = mid - (v / amp) * (h / 2 - 4)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawLine(Color.Gray, Offset(0f, mid), Offset(w, mid), strokeWidth = 1f)
            drawPath(path, Color(0xFF1565C0), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        }
        Text(
            "live tilt (uncalibrated axis)",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
        )
    }
}

// ---------------------------------------------------------------------------- Result

@Composable
private fun ResultScreen(vm: MainViewModel) {
    val r = vm.result
    val g = vm.gm
    val s = vm.series

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (r == null || s == null) {
            Text("No measurement yet. Go to Measure.")
            return@Column
        }

        val qColor = when (r.quality) {
            PeriodEstimator.Quality.EXCELLENT, PeriodEstimator.Quality.GOOD -> Good
            PeriodEstimator.Quality.FAIR -> Warn
            PeriodEstimator.Quality.POOR -> Bad
        }

        if (g != null) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = qColor.copy(alpha = 0.10f))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("GM", style = MaterialTheme.typography.labelLarge)
                    Text("${"%.2f".format(g.gm)} m", fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "1σ range  ${"%.2f".format(g.gmLow)} – ${"%.2f".format(g.gmHigh)} m " +
                            "(±${"%.0f".format(g.relUncertainty * 100)} %)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("${r.quality} — ${r.message}", color = qColor, fontWeight = FontWeight.Medium)
                    if (vm.profile.minRequiredGm > 0) {
                        Spacer(Modifier.height(8.dp))
                        val req = vm.profile.minRequiredGm
                        val verdict = when {
                            g.gmLow >= req -> "Above the required GM of ${"%.2f".format(req)} m" to Good
                            g.gmHigh < req -> "BELOW the required GM of ${"%.2f".format(req)} m" to Bad
                            else -> "Inconclusive — the required GM of ${"%.2f".format(req)} m lies inside the uncertainty band" to Warn
                        }
                        Text(verdict.first, color = verdict.second, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else if (r.period.isNaN()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Bad.copy(alpha = 0.10f))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Measurement rejected", fontWeight = FontWeight.Bold, color = Bad)
                    Text(r.message)
                }
            }
        } else {
            Text("Ship particulars incomplete — period measured, but GM cannot be computed.", color = Warn)
        }

        SeaCard(vm, r)

        RollPlot(r)
        SpectrumPlot(r)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Cross-check", fontWeight = FontWeight.Bold)
                Mono("Spectral peak       T = ${"%.2f".format(r.periodSpectral)} s")
                Mono("Zero-crossing       T = ${"%.2f".format(r.periodZeroCross)} s   (${r.nCycles} cycles)")
                Mono("Agreement             ${"%.1f".format(r.agreement * 100)} %")
                Mono("Adopted             T = ${"%.2f".format(r.period)} ± ${"%.2f".format(r.periodUncertainty)} s")
                Spacer(Modifier.height(6.dp))
                Mono("Roll amplitude        mean ${"%.2f".format(r.meanAmplitude)}°, max ${"%.2f".format(r.maxAmplitude)}°")
                Mono("Peak prominence       ${"%.1f".format(r.prominence)}×")
                Mono("Peak repeatability    ${"%.0f".format(r.consistency * 100)} %")
                if (!r.competingPeriod.isNaN()) {
                    Mono("Competing period      ${"%.2f".format(r.competingPeriod)} s at ${"%.0f".format(r.competingRatio * 100)} % power")
                }
                Mono("Axis dominance        ${"%.2f".format(r.axisDominance)}")
                Mono("Roll axis offset      ${"%.0f".format(s.headingOffsetDeg)}° from phone +Y")
                if (!r.zeta.isNaN()) Mono("Damping ratio ζ       ${"%.3f".format(r.zeta)}")
            }
        }

        if (g != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Where the error comes from", fontWeight = FontWeight.Bold)
                    Text(
                        "GM = (f·B/T)², so every relative error DOUBLES.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(6.dp))
                    Mono("period   u(T)/T = ${"%.1f".format(g.relUncertaintyT * 100)} %  →  ${"%.1f".format(2 * g.relUncertaintyT * 100)} % of GM")
                    Mono("coeff.   u(f)/f = ${"%.1f".format(g.relUncertaintyF * 100)} %  →  ${"%.1f".format(2 * g.relUncertaintyF * 100)} % of GM")
                    if (g.relUncertaintyF > g.relUncertaintyT * 1.5) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "The roll coefficient, not the phone, is what limits this measurement. " +
                                "Calibrate f against one condition of known GM and the accuracy roughly triples.",
                            style = MaterialTheme.typography.bodySmall, color = Warn
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = vm.note, onValueChange = { vm.note = it },
            label = { Text("Note (condition, sea state, location)") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.exportRecord() }, enabled = g != null, modifier = Modifier.weight(1f)) {
                Text("Save record")
            }
            OutlinedButton(
                onClick = { vm.share() },
                enabled = vm.lastFiles.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) { Text("Share") }
        }
        if (vm.lastFiles.isNotEmpty()) {
            Text("Saved: " + vm.lastFiles.joinToString("\n") { it.name },
                style = MaterialTheme.typography.bodySmall)
        }

        Text(
            "Estimate only. Assumes small-amplitude, lightly damped free roll, deep water, no significant " +
                "free surface and no active anti-roll device. Not a substitute for the approved loading " +
                "computer or an inclining experiment.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * What the sea was doing — and whether the "roll" we measured was actually the ship at all.
 *
 * A swell is an INPUT: it appears in the roll AND in the accelerometer.
 * A resonance is the SHIP: it appears ONLY in the roll.
 */
@Composable
private fun SeaCard(vm: MainViewModel, r: PeriodEstimator.Result) {
    val sea = r.sea
    val enc = vm.encounter

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                sea?.seaLocked == true -> Bad.copy(alpha = 0.10f)
                sea != null -> Good.copy(alpha = 0.08f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("The sea", fontWeight = FontWeight.Bold)

            if (sea == null) {
                Text(
                    "No heave signal — the sea could not be checked. Without the accelerometer " +
                        "there is no way to tell whether the roll peak is the ship or a wave.",
                    style = MaterialTheme.typography.bodySmall, color = Warn
                )
            } else {
                Mono("Dominant wave period   ${"%.1f".format(sea.wavePeakPeriod)} s")
                Mono("Vertical accel (RMS)   ${"%.2f".format(sea.rmsVerticalAcc)} m/s²")
                Mono("Indicative Hs          ${"%.1f".format(sea.indicativeHs)} m   (rough)")
                Mono("Wave energy at the roll peak: ${"%.1f".format(sea.excessAtRollPeakDb)} dB")
                Spacer(Modifier.height(4.dp))
                Text(
                    sea.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (sea.seaLocked) Bad else Good,
                    fontWeight = if (sea.seaLocked) FontWeight.Bold else FontWeight.Normal
                )
                if (sea.seaLocked) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "A ship rolls at whatever period the waves push her at. Only when she is " +
                            "left to roll FREELY does she roll at her own. Do a free-decay test in " +
                            "sheltered water.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Encounter test", fontWeight = FontWeight.Bold)
            Text(
                "Waves shift with heading and speed. Your ship's natural period does not. " +
                    "Record twice on different headings — the period that stays put is the ship.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Mono("This record: ${vm.gps.summary()}")

            if (vm.history.size >= 2) {
                Spacer(Modifier.height(4.dp))
                vm.history.forEach { h ->
                    Mono(
                        "${h.label}  T = ${"%.2f".format(h.period)} s   " +
                            if (h.cogDeg.isNaN()) "(no COG)"
                            else "SOG ${"%.1f".format(h.sogKn)} kn  COG ${"%.0f".format(h.cogDeg)}°"
                    )
                }
            }
            if (enc != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    enc.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enc.conclusive) Good else Warn,
                    fontWeight = FontWeight.Medium
                )
            } else if (vm.history.size < 2) {
                Text(
                    "Take a second record on a course at least 30° away to run this test.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (vm.history.isNotEmpty()) {
                TextButton(onClick = { vm.clearHistory() }) { Text("Clear record history") }
            }
        }
    }
}

@Composable
private fun RollPlot(r: PeriodEstimator.Result) {
    Column {
        Text("Band-passed roll angle", style = MaterialTheme.typography.labelMedium)
        Box(
            Modifier.fillMaxWidth().height(120.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
        ) {
            Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                val phi = r.phi
                if (phi.size < 2) return@Canvas
                val n = phi.size
                val stride = max(1, n / 1000)
                var amp = 0.0
                for (v in phi) amp = max(amp, abs(v))
                if (amp <= 0.0) return@Canvas
                val w = size.width; val h = size.height; val mid = h / 2
                val path = Path()
                var first = true
                var i = 0
                while (i < n) {
                    val x = w * i / (n - 1).toFloat()
                    val y = mid - (phi[i] / amp).toFloat() * (h / 2 - 4)
                    if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                    i += stride
                }
                drawLine(Color.Gray, Offset(0f, mid), Offset(w, mid), strokeWidth = 1f)
                drawPath(path, Color(0xFF1565C0), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            }
        }
        Text("±${"%.1f".format(r.phi.maxOfOrNull { abs(it) } ?: 0.0)}°  full scale",
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SpectrumPlot(r: PeriodEstimator.Result) {
    Column {
        Text("Roll spectrum (log power vs period)", style = MaterialTheme.typography.labelMedium)
        Box(
            Modifier.fillMaxWidth().height(120.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
        ) {
            Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                val f = r.psdFreq; val p = r.psdPower
                if (f.size < 4) return@Canvas
                val fLo = 1.0 / 45.0; val fHi = 1.0 / 3.0
                val lo = f.indexOfFirst { it >= fLo }.coerceAtLeast(1)
                val hi = f.indexOfLast { it <= fHi }
                if (hi <= lo) return@Canvas
                var pMax = 0.0
                for (k in lo..hi) pMax = max(pMax, p[k])
                if (pMax <= 0.0) return@Canvas
                val w = size.width; val h = size.height
                val path = Path()
                for (k in lo..hi) {
                    val x = w * (k - lo) / (hi - lo).toFloat()
                    val db = 10.0 * ln(max(p[k], pMax * 1e-4) / pMax) / ln(10.0)   // 0 .. -40 dB
                    val y = (h * (-db / 40.0)).toFloat().coerceIn(0f, h)
                    if (k == lo) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, Color(0xFF6A1B9A), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                val fPeak = 1.0 / r.period
                val kPeak = f.indexOfFirst { it >= fPeak }
                if (kPeak in lo..hi) {
                    val x = w * (kPeak - lo) / (hi - lo).toFloat()
                    drawLine(Color(0xFFC62828), Offset(x, 0f), Offset(x, h), strokeWidth = 2f)
                }
            }
        }
        Text("45 s  ←  period  →  3 s      red line = adopted T = ${"%.1f".format(r.period)} s",
            style = MaterialTheme.typography.labelSmall)
    }
}

// ---------------------------------------------------------------------------- Calibrate

@Composable
private fun CalibrateScreen(vm: MainViewModel) {
    var label by remember { mutableStateOf("") }
    var knownGm by remember { mutableStateOf("") }
    val r = vm.result

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Calibrate the roll coefficient", style = MaterialTheme.typography.titleMedium)
        Text(
            "This is the single most valuable thing you can do with this instrument. Take a " +
                "measurement in a condition whose GM you already trust — straight after an inclining " +
                "experiment, or in a condition the loading computer has a good GM for — and the app " +
                "solves f = T·√GM / B for YOUR ship. After that, every later measurement inherits " +
                "your ship's own coefficient instead of a generic formula, and the GM uncertainty drops " +
                "from roughly ±16 % to ±5 %.",
            style = MaterialTheme.typography.bodySmall
        )

        HorizontalDivider()

        if (r == null || r.period.isNaN()) {
            Text("Take a measurement first, then come back here.", color = Warn)
        } else {
            Text("Current measurement: T = ${"%.2f".format(r.period)} s (${r.quality})")
            if (r.quality == PeriodEstimator.Quality.POOR) {
                Text("Do not calibrate from a POOR-quality measurement.", color = Bad)
            }
            Field("Condition label", label) { label = it }
            OutlinedTextField(
                value = knownGm, onValueChange = { knownGm = it },
                label = { Text("Known GM for this condition [m]") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            val gmVal = knownGm.toDoubleOrNull() ?: 0.0
            if (gmVal > 0 && vm.profile.beam > 0) {
                val f = GmModel.fFromKnownGm(r.period, gmVal, vm.profile.beam)
                Text("→ f = ${"%.3f".format(f)}   (≡ C = ${"%.3f".format(f / 2)})", fontWeight = FontWeight.Bold)
                val isc = GmModel.isCodeF(vm.profile.beam, vm.profile.draught, vm.profile.lwl)
                val dev = (f - isc) / isc * 100
                Text("IS Code would have predicted f = ${"%.3f".format(isc)} — a ${"%.0f".format(abs(dev))} % ${
                    if (dev > 0) "under" else "over"
                }estimate, i.e. ${"%.0f".format(abs(2 * dev))} % on GM.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = { vm.addCalibration(label, gmVal); label = ""; knownGm = "" },
                enabled = gmVal > 0 && r.quality != PeriodEstimator.Quality.POOR
            ) { Text("Save calibration point") }
        }

        HorizontalDivider()
        Text("Stored calibration points", style = MaterialTheme.typography.titleMedium)
        if (vm.profile.calibrations.isEmpty()) {
            Text("None yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            vm.profile.calibrations.forEachIndexed { i, c ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(c.label.ifBlank { "point ${i + 1}" }, fontWeight = FontWeight.Medium)
                            Mono("T = ${"%.2f".format(c.periodSeconds)} s   GM = ${"%.2f".format(c.knownGm)} m   d = ${"%.2f".format(c.draught)} m")
                            Mono("f = ${"%.3f".format(c.f)}")
                        }
                        TextButton(onClick = { vm.removeCalibration(i) }) { Text("Delete") }
                    }
                }
            }
            val fs = vm.profile.calibrations.map { it.f }
            Text("Mean f = ${"%.3f".format(fs.average())}   spread = ±${
                "%.1f".format(vm.profile.fUncertainty() * 100)
            } %", fontWeight = FontWeight.Bold)
        }
    }
}

// ---------------------------------------------------------------------------- widgets

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = true, modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun NumField(label: String, value: Double, onChange: (Double) -> Unit) {
    // NOTE: remember must NOT be keyed on `value`. onChange pushes the parsed number straight
    // back into the profile, which returns as a new `value`; keying on it would rebuild the
    // buffer on every keystroke ("12.5" -> "1.02...") and make a decimal point impossible to
    // type, because "." parses to null -> 0.0 -> key changes -> buffer resets to "".
    var text by remember { mutableStateOf(if (value == 0.0) "" else value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it.toDoubleOrNull() ?: 0.0)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun Mono(s: String) = Text(s, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
