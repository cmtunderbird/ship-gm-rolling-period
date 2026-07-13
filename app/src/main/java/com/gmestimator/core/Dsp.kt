package com.gmestimator.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin signal processing primitives. No Android dependencies, so this file is
 * unit-testable on the JVM (see app/src/test/.../DspTest.kt).
 *
 * All angle series are in DEGREES, all times in SECONDS, all frequencies in Hz.
 */
object Dsp {

    // ---------------------------------------------------------------- basic stats

    fun mean(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        var s = 0.0
        for (v in x) s += v
        return s / x.size
    }

    fun std(x: DoubleArray): Double {
        if (x.size < 2) return 0.0
        val m = mean(x)
        var s = 0.0
        for (v in x) s += (v - m) * (v - m)
        return sqrt(s / (x.size - 1))
    }

    fun median(x: DoubleArray): Double {
        if (x.isEmpty()) return Double.NaN
        val c = x.copyOf()
        c.sort()
        val n = c.size
        return if (n % 2 == 1) c[n / 2] else 0.5 * (c[n / 2 - 1] + c[n / 2])
    }

    /** Median absolute deviation, scaled to be a consistent estimator of sigma for normal data. */
    fun mad(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        val med = median(x)
        val dev = DoubleArray(x.size) { abs(x[it] - med) }
        return 1.4826 * median(dev)
    }

    /** Remove the least-squares linear trend (kills constant heel/list and slow drift). */
    fun detrend(x: DoubleArray): DoubleArray {
        val n = x.size
        if (n < 2) return x.copyOf()
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in 0 until n) {
            val t = i.toDouble()
            sx += t; sy += x[i]; sxx += t * t; sxy += t * x[i]
        }
        val denom = n * sxx - sx * sx
        val slope = if (abs(denom) < 1e-12) 0.0 else (n * sxy - sx * sy) / denom
        val intercept = (sy - slope * sx) / n
        return DoubleArray(n) { x[it] - (intercept + slope * it) }
    }

    // ---------------------------------------------------------------- FFT

    fun nextPow2(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    /** In-place iterative radix-2 Cooley-Tukey FFT. re/im must have power-of-two length. */
    fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size
        require(n and (n - 1) == 0) { "FFT length must be a power of two" }
        if (n <= 1) return

        // bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }

        var len = 2
        while (len <= n) {
            val ang = 2.0 * PI / len * (if (inverse) 1.0 else -1.0)
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cwr = 1.0
                var cwi = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cwr - im[i + k + len / 2] * cwi
                    val vi = re[i + k + len / 2] * cwi + im[i + k + len / 2] * cwr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val nwr = cwr * wr - cwi * wi
                    cwi = cwr * wi + cwi * wr
                    cwr = nwr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            for (i in 0 until n) {
                // NOTE: `re[i] /= n` does NOT compile. Kotlin will not desugar an augmented
                // assignment on an array element into a set() call here - it reports
                // "No set method providing array access". Write the division out.
                re[i] = re[i] / n
                im[i] = im[i] / n
            }
        }
    }

    // ---------------------------------------------------------------- filtering

    /**
     * Zero-phase band-pass by FFT masking. The record is detrended and zero-padded to the next
     * power of two; bins outside [fLo, fHi] are zeroed and the signal transformed back.
     *
     * Zero-padding causes ringing near the record ends. The caller MUST discard an edge guard
     * at each end before any time-domain (zero-crossing) analysis. See PeriodEstimator.estimate,
     * which scales that guard to the MEASURED period.
     */
    fun bandpassFft(x: DoubleArray, fs: Double, fLo: Double, fHi: Double): DoubleArray {
        val n = x.size
        if (n < 8) return x.copyOf()
        val d = detrend(x)
        val nfft = nextPow2(n)
        val re = DoubleArray(nfft)
        val im = DoubleArray(nfft)
        System.arraycopy(d, 0, re, 0, n)
        fft(re, im, false)
        val df = fs / nfft
        for (k in 0..nfft / 2) {
            val f = k * df
            if (f < fLo || f > fHi) {
                re[k] = 0.0; im[k] = 0.0
                val m = (nfft - k) % nfft            // conjugate-symmetric partner
                re[m] = 0.0; im[m] = 0.0
            }
        }
        fft(re, im, true)
        return DoubleArray(n) { re[it] }
    }

    // ---------------------------------------------------------------- spectrum

    data class Psd(
        val freq: DoubleArray,
        val power: DoubleArray,
        val df: Double,
        val nSegments: Int
    )

    fun hann(n: Int): DoubleArray =
        DoubleArray(n) { 0.5 * (1.0 - cos(2.0 * PI * it / (n - 1).toDouble())) }

    /**
     * Welch power spectral density estimate.
     *
     * @param padFactor zero-pad each windowed segment by this factor before the FFT. Adds no
     *                  information, but gives a finer bin grid, which makes the parabolic peak
     *                  interpolation better conditioned.
     */
    fun welchPsd(
        x: DoubleArray,
        fs: Double,
        segLen: Int,
        overlap: Double = 0.5,
        padFactor: Int = 4
    ): Psd {
        val n = x.size
        // nextPow2(n) >= n, so it must be halved if it overshoots, otherwise L can exceed the
        // record length, no segment ever fits, and we silently return an empty spectrum.
        var lp = nextPow2(n)
        if (lp > n) lp = lp shr 1
        val L = minOf(nextPow2(segLen), lp).coerceAtLeast(2)
        if (L > n) return Psd(DoubleArray(0), DoubleArray(0), fs / L, 0)
        val step = maxOf(1, (L * (1.0 - overlap)).toInt())
        val nfft = L * padFactor
        val win = hann(L)
        var winPow = 0.0
        for (w in win) winPow += w * w
        val norm = 1.0 / (fs * winPow)      // one-sided PSD normalisation

        val nBins = nfft / 2 + 1
        val acc = DoubleArray(nBins)
        var segs = 0
        var start = 0
        while (start + L <= n) {
            val seg = DoubleArray(L) { x[start + it] }
            val d = detrend(seg)
            val re = DoubleArray(nfft)
            val im = DoubleArray(nfft)
            for (i in 0 until L) re[i] = d[i] * win[i]
            fft(re, im, false)
            for (k in 0 until nBins) {
                var p = (re[k] * re[k] + im[k] * im[k]) * norm
                if (k in 1 until nfft / 2) p *= 2.0
                acc[k] += p
            }
            segs++
            start += step
        }
        if (segs == 0) return Psd(DoubleArray(0), DoubleArray(0), fs / nfft, 0)
        for (k in 0 until nBins) acc[k] = acc[k] / segs      // see the note in fft(): not /=
        val df = fs / nfft
        val freq = DoubleArray(nBins) { it * df }
        return Psd(freq, acc, df, segs)
    }

    data class SpectralPeak(
        val freq: Double,          // Hz, sub-bin interpolated
        val power: Double,
        val prominence: Double,    // peak power / median power in band
        val halfPowerWidth: Double // Hz, -3 dB width
    )

    /** Dominant peak of `psd` inside [fLo, fHi], with parabolic (log-domain) interpolation. */
    fun dominantPeak(psd: Psd, fLo: Double, fHi: Double): SpectralPeak? {
        if (psd.freq.isEmpty()) return null
        val lo = psd.freq.indexOfFirst { it >= fLo }.let { if (it < 0) return null else it }
        val hi = psd.freq.indexOfLast { it <= fHi }
        if (hi <= lo + 2) return null

        var kMax = lo
        for (k in lo..hi) if (psd.power[k] > psd.power[kMax]) kMax = k
        if (kMax <= 0 || kMax >= psd.power.size - 1) return null

        val y0 = ln(maxOf(psd.power[kMax - 1], 1e-30))
        val y1 = ln(maxOf(psd.power[kMax], 1e-30))
        val y2 = ln(maxOf(psd.power[kMax + 1], 1e-30))
        val denom = (y0 - 2 * y1 + y2)
        val delta = if (abs(denom) < 1e-12) 0.0 else 0.5 * (y0 - y2) / denom
        val fPeak = (kMax + delta.coerceIn(-1.0, 1.0)) * psd.df

        val band = DoubleArray(hi - lo + 1) { psd.power[lo + it] }
        val med = median(band)
        val prominence = if (med <= 0.0) Double.MAX_VALUE else psd.power[kMax] / med

        val half = psd.power[kMax] / 2.0
        var kl = kMax
        while (kl > lo && psd.power[kl] > half) kl--
        var kr = kMax
        while (kr < hi && psd.power[kr] > half) kr++
        val width = (kr - kl) * psd.df

        return SpectralPeak(fPeak, psd.power[kMax], prominence, width)
    }

    /**
     * Strongest COMPETING peak in the band: a topographically prominent local maximum, separated
     * in frequency from the primary, with a genuine valley between the two.
     *
     * A ship in a regular swell rolls at the WAVE ENCOUNTER period, not her own. Feed that peak
     * into GM = (fB/T)^2 and you get a confidently wrong answer, in the dangerous direction:
     * a long swell makes a stiff ship look tender. Whenever a second peak carries more than ~25%
     * of the primary's power, one of the two IS THE SEA, and the spectrum alone cannot say which.
     *
     * The "valley" test stops the ragged shoulders of a single resonance from being mistaken for
     * a second mode.
     */
    fun secondaryPeak(
        psd: Psd,
        fLo: Double,
        fHi: Double,
        minSeparation: Double = 0.15,
        valleyRatio: Double = 0.5
    ): Pair<Double, Double>? {
        if (psd.freq.isEmpty()) return null
        val lo = psd.freq.indexOfFirst { it >= fLo }
        val hi = psd.freq.indexOfLast { it <= fHi }
        if (lo < 1 || hi <= lo + 2) return null

        var k1 = lo
        for (k in lo..hi) if (psd.power[k] > psd.power[k1]) k1 = k
        val p1 = psd.power[k1]
        if (p1 <= 0.0) return null

        var bestF = 0.0
        var bestR = 0.0
        for (k in (lo + 1) until hi) {
            if (!(psd.power[k] > psd.power[k - 1] && psd.power[k] >= psd.power[k + 1])) continue
            if (abs(psd.freq[k] - psd.freq[k1]) / psd.freq[k1] < minSeparation) continue
            val a = minOf(k, k1)
            val b = maxOf(k, k1)
            var vMin = Double.MAX_VALUE
            for (i in a..b) vMin = minOf(vMin, psd.power[i])
            if (vMin > valleyRatio * psd.power[k]) continue   // no real valley: same peak's shoulder
            val r = psd.power[k] / p1
            if (r > bestR) { bestR = r; bestF = psd.freq[k] }
        }
        return if (bestR > 0.0) Pair(bestF, bestR) else null
    }

    // ---------------------------------------------------------------- zero crossings

    data class CycleStats(
        val periods: DoubleArray,     // s, one entry per completed full roll cycle
        val amplitudes: DoubleArray,  // deg, max |phi| within each cycle
        val medianPeriod: Double,
        val meanPeriod: Double,
        val sdPeriod: Double,
        val nCycles: Int
    )

    /**
     * Classic roll-test period measurement: time between successive UPWARD zero crossings of the
     * band-passed roll angle. A hysteresis band must be traversed between crossings, which
     * rejects the spurious crossings noise produces near zero. Crossing instants are linearly
     * interpolated, giving sub-sample timing.
     */
    fun zeroCrossingCycles(
        phi: DoubleArray,
        fs: Double,
        hyst: Double,
        tMin: Double,
        tMax: Double
    ): CycleStats {
        val crossings = ArrayList<Double>()
        var armed = false
        for (i in 1 until phi.size) {
            if (phi[i] < -hyst) armed = true
            if (armed && phi[i - 1] <= 0.0 && phi[i] > 0.0) {
                val frac = if (phi[i] != phi[i - 1]) (0.0 - phi[i - 1]) / (phi[i] - phi[i - 1]) else 0.0
                crossings.add((i - 1 + frac) / fs)
                armed = false
            }
        }
        val per = ArrayList<Double>()
        val amp = ArrayList<Double>()
        for (k in 1 until crossings.size) {
            val t = crossings[k] - crossings[k - 1]
            if (t in tMin..tMax) {
                per.add(t)
                val i0 = (crossings[k - 1] * fs).toInt().coerceIn(0, phi.size - 1)
                val i1 = (crossings[k] * fs).toInt().coerceIn(0, phi.size - 1)
                var a = 0.0
                for (i in i0..i1) a = maxOf(a, abs(phi[i]))
                amp.add(a)
            }
        }
        val pa = per.toDoubleArray()
        val aa = amp.toDoubleArray()
        return CycleStats(pa, aa, median(pa), mean(pa), std(pa), pa.size)
    }

    // ---------------------------------------------------------------- free-decay

    data class DecayFit(
        val dampedPeriod: Double,     // s
        val naturalPeriod: Double,    // s, T_d * sqrt(1 - zeta^2)
        val zeta: Double,             // non-dimensional roll damping ratio
        val logDecrement: Double,
        val nExtrema: Int,
        val r2: Double
    )

    /**
     * Fit an exponentially decaying sinusoid to a free-decay (roll test) record.
     * Uses successive extrema: |phi| should decay as exp(-zeta * omega_n * t).
     */
    fun fitFreeDecay(phi: DoubleArray, fs: Double, tMin: Double, tMax: Double): DecayFit? {
        data class Ext(val t: Double, val v: Double)
        val ext = ArrayList<Ext>()
        for (i in 1 until phi.size - 1) {
            val a = phi[i - 1]; val b = phi[i]; val c = phi[i + 1]
            if ((b > a && b >= c) || (b < a && b <= c)) {
                val denom = a - 2 * b + c
                val d = if (abs(denom) < 1e-12) 0.0 else 0.5 * (a - c) / denom
                val t = (i + d.coerceIn(-1.0, 1.0)) / fs
                val v = b - 0.25 * (a - c) * d
                ext.add(Ext(t, v))
            }
        }
        val noise = 0.05 * (phi.maxOrNull() ?: 0.0).coerceAtLeast(abs(phi.minOrNull() ?: 0.0))
        val used = ArrayList<Ext>()
        for (e in ext) {
            if (abs(e.v) < noise) continue
            if (used.isNotEmpty() && used.last().v * e.v > 0) {
                if (abs(e.v) > abs(used.last().v)) used[used.size - 1] = e
                continue
            }
            used.add(e)
        }
        if (used.size < 5) return null

        val halves = ArrayList<Double>()
        for (k in 1 until used.size) halves.add(2.0 * (used[k].t - used[k - 1].t))
        val td = median(halves.toDoubleArray().filter { it in tMin..tMax }.toDoubleArray())
        if (td.isNaN()) return null

        val n = used.size
        val xs = DoubleArray(n) { it.toDouble() }
        val ys = DoubleArray(n) { ln(abs(used[it].v).coerceAtLeast(1e-6)) }
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in 0 until n) { sx += xs[i]; sy += ys[i]; sxx += xs[i] * xs[i]; sxy += xs[i] * ys[i] }
        val den = n * sxx - sx * sx
        val slope = if (abs(den) < 1e-12) 0.0 else (n * sxy - sx * sy) / den
        val intercept = (sy - slope * sx) / n
        var ssRes = 0.0; var ssTot = 0.0
        val ym = mean(ys)
        for (i in 0 until n) {
            val pred = intercept + slope * xs[i]
            ssRes += (ys[i] - pred) * (ys[i] - pred)
            ssTot += (ys[i] - ym) * (ys[i] - ym)
        }
        val r2 = if (ssTot <= 0.0) 0.0 else 1.0 - ssRes / ssTot

        // slope is per HALF cycle -> log decrement (per full cycle) = -2 * slope
        val delta = -2.0 * slope
        val zeta = (delta / sqrt(4.0 * PI * PI + delta * delta)).coerceIn(0.0, 0.9)
        val tn = td * sqrt(1.0 - zeta * zeta)   // T_n = T_d * sqrt(1 - zeta^2)

        return DecayFit(td, tn, zeta, delta, n, r2)
    }

    // ---------------------------------------------------------------- misc

    /** Principal axis (unit vector) of a zero-mean 2-D series, plus the eigenvalue ratio. */
    data class PrincipalAxis(val ux: Double, val uy: Double, val dominance: Double)

    fun principalAxis(ax: DoubleArray, ay: DoubleArray): PrincipalAxis {
        val n = minOf(ax.size, ay.size)
        if (n < 3) return PrincipalAxis(1.0, 0.0, 0.0)
        val mx = mean(ax); val my = mean(ay)
        var cxx = 0.0; var cyy = 0.0; var cxy = 0.0
        for (i in 0 until n) {
            val dx = ax[i] - mx
            val dy = ay[i] - my
            cxx += dx * dx; cyy += dy * dy; cxy += dx * dy
        }
        cxx /= n; cyy /= n; cxy /= n
        val tr = cxx + cyy
        val det = cxx * cyy - cxy * cxy
        val disc = sqrt(maxOf(0.0, tr * tr / 4.0 - det))
        val l1 = tr / 2.0 + disc
        val l2 = tr / 2.0 - disc
        var ux: Double
        var uy: Double
        if (abs(cxy) > 1e-12) {
            ux = l1 - cyy
            uy = cxy
        } else {
            if (cxx >= cyy) { ux = 1.0; uy = 0.0 } else { ux = 0.0; uy = 1.0 }
        }
        val norm = sqrt(ux * ux + uy * uy).coerceAtLeast(1e-12)
        ux /= norm; uy /= norm
        val dominance = if (l1 + l2 <= 0.0) 0.0 else l1 / (l1 + l2)
        return PrincipalAxis(ux, uy, dominance)
    }
}
