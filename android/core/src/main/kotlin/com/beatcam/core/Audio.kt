package com.beatcam.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

enum class OnsetKind { KICK, SNARE }

/** strength is 0..1 relative to the strongest onset of its band. */
data class Onset(val time: Double, val strength: Double, val kind: OnsetKind)

object Audio {
    val BANDS = mapOf(OnsetKind.KICK to (20.0 to 120.0), OnsetKind.SNARE to (1000.0 to 3000.0))

    /** Interleaved PCM16 -> mono float, decimated by box-averaging to ~targetRate. Returns samples and the actual rate. */
    fun toMono(pcm: ShortArray, channels: Int, srcRate: Int, targetRate: Int = 22050): Pair<FloatArray, Int> {
        val factor = max(1, srcRate / targetRate)
        val frames = pcm.size / channels
        val out = FloatArray(frames / factor)
        for (i in out.indices) {
            var s = 0f
            for (k in 0 until factor) for (c in 0 until channels) s += pcm[((i * factor + k) * channels) + c]
            out[i] = s / (factor * channels * 32768f)
        }
        return out to srcRate / factor
    }

    /** In-place iterative radix-2 FFT. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            for (i in 0 until n step len) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci; val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
            }
            len = len shl 1
        }
    }

    private fun mel(f: Double) = 2595 * log10(1 + f / 700)
    private fun invMel(m: Double) = 700 * (10.0.pow(m / 2595) - 1)

    /** Triangular mel filters between fmin..fmax (n_mels rows over the rfft bins). */
    fun melFilters(sr: Int, nFft: Int, nMels: Int, fmin: Double, fmax: Double): Array<DoubleArray> {
        val pts = DoubleArray(nMels + 2) { invMel(mel(fmin) + (mel(fmax) - mel(fmin)) * it / (nMels + 1)) }
        val bins = nFft / 2 + 1
        return Array(nMels) { i ->
            DoubleArray(bins) { b ->
                val f = b * sr.toDouble() / nFft
                max(0.0, min((f - pts[i]) / max(pts[i + 1] - pts[i], 1e-9), (pts[i + 2] - f) / max(pts[i + 2] - pts[i + 1], 1e-9)))
            }
        }
    }

    /** Half-wave-rectified log spectral flux limited to [band]; frames are centred (frame i at i*hop). */
    fun bandFlux(y: FloatArray, sr: Int, band: Pair<Double, Double>, nFft: Int = 2048, hop: Int = 256): Pair<DoubleArray, DoubleArray> {
        val frames = 1 + y.size / hop
        val bins = nFft / 2 + 1
        val fb: Array<DoubleArray> = if (band.second < 500) {
            arrayOf(DoubleArray(bins) { val f = it * sr.toDouble() / nFft; if (f in band.first..band.second) 1.0 else 0.0 })
        } else melFilters(sr, nFft, 8, band.first, band.second)
        val win = DoubleArray(nFft) { 0.5 - 0.5 * cos(2 * PI * it / nFft) }
        val re = DoubleArray(nFft); val im = DoubleArray(nFft)
        var prev: DoubleArray? = null
        val flux = DoubleArray(frames)
        for (f in 0 until frames) {
            val start = f * hop - nFft / 2
            for (i in 0 until nFft) {
                val s = start + i
                re[i] = (if (s in y.indices) y[s].toDouble() else 0.0) * win[i]; im[i] = 0.0
            }
            fft(re, im)
            val cur = DoubleArray(fb.size) { m ->
                var e = 0.0
                for (b in 0 until bins) if (fb[m][b] != 0.0) e += fb[m][b] * (re[b] * re[b] + im[b] * im[b])
                ln(1 + 1e3 * e / nFft)
            }
            val p = prev
            flux[f] = if (p == null) 0.0 else cur.indices.sumOf { max(0.0, cur[it] - p[it]) }
            prev = cur
        }
        return flux to DoubleArray(frames) { it * hop.toDouble() / sr }
    }

    /** Adaptive-threshold (local median + k*MAD) peak picking. */
    fun pickPeaks(flux: DoubleArray, times: DoubleArray, k: Double = 3.0, win: Int = 86, minGap: Double = 0.12): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        val fmax = flux.maxOrNull() ?: return out
        var last = -1e9
        for (i in 1 until flux.size - 1) {
            if (!(flux[i] > flux[i - 1] && flux[i] >= flux[i + 1])) continue
            val seg = flux.copyOfRange(max(0, i - win), min(flux.size, i + win))
            val med = median(seg)
            val thr = med + k * median(DoubleArray(seg.size) { abs(seg[it] - med) }) + 1e-6
            if (flux[i] <= thr || flux[i] < 0.05 * fmax) continue
            if (times[i] - last < minGap) {
                if (out.isNotEmpty() && flux[i] > out.last().second) { out[out.size - 1] = times[i] to flux[i]; last = times[i] }
                continue
            }
            out += times[i] to flux[i]; last = times[i]
        }
        return out
    }

    private fun median(a: DoubleArray): Double { val s = a.sortedArray(); return s[s.size / 2] }

    fun detectOnsets(y: FloatArray, sr: Int): List<Onset> {
        if (y.size < 4096) return emptyList()
        val res = ArrayList<Onset>()
        for ((kind, band) in BANDS) {
            val (flux, times) = bandFlux(y, sr, band)
            val peaks = pickPeaks(flux, times)
            val top = peaks.maxOfOrNull { it.second } ?: continue
            peaks.forEach { res += Onset(it.first, it.second / top, kind) }
        }
        return res.sortedBy { it.time }
    }
}
