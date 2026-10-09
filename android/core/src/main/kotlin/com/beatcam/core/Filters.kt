package com.beatcam.core

import kotlin.math.PI
import kotlin.math.abs

/** Casiez et al. 2012: low cutoff when slow (kills jitter), higher when fast (kills lag). */
class OneEuroFilter(private val minCutoff: Double = 0.8, private val beta: Double = 0.01, private val dCutoff: Double = 1.0) {
    private var x: Double? = null
    private var dx = 0.0

    private fun alpha(cutoff: Double, dt: Double) = 1.0 / (1.0 + (1.0 / (2 * PI * cutoff)) / dt)

    fun reset(v: Double? = null) { x = v; dx = 0.0 }

    fun filter(v: Double, dt: Double): Double {
        val prev = x ?: run { x = v; return v }
        dx += alpha(dCutoff, dt) * ((v - prev) / dt - dx)
        val a = alpha(minCutoff + beta * abs(dx), dt)
        val out = prev + a * (v - prev)
        x = out
        return out
    }
}

/** Least-squares polynomial helpers (normal equations; tiny orders only). */
internal object Poly {
    fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val m = Array(n) { i -> a[i].copyOf(n + 1).also { it[n] = b[i] } }
        for (c in 0 until n) {
            val p = (c until n).maxByOrNull { abs(m[it][c]) }!!
            m[c] = m[p].also { m[p] = m[c] }
            for (r in 0 until n) if (r != c) {
                val f = m[r][c] / m[c][c]
                for (k in c..n) m[r][k] -= f * m[c][k]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    /** Coefficients (low->high) of the order-`order` fit of y over x. */
    fun fit(x: DoubleArray, y: DoubleArray, order: Int): DoubleArray {
        val k = order + 1
        val ata = Array(k) { i -> DoubleArray(k) { j -> x.sumOf { Math.pow(it, (i + j).toDouble()) } } }
        val aty = DoubleArray(k) { i -> x.indices.sumOf { Math.pow(x[it], i.toDouble()) * y[it] } }
        return solve(ata, aty)
    }

    fun eval(c: DoubleArray, x: Double): Double = c.indices.sumOf { c[it] * Math.pow(x, it.toDouble()) }
}

/** Savitzky-Golay smoothing (offline). Edges use a polynomial fit of the first/last window. */
fun savgol(x: DoubleArray, window: Int = 15, order: Int = 3): DoubleArray {
    val w = window or 1
    if (x.size < w || w <= order) return x.copyOf()
    val half = w / 2
    val k = DoubleArray(w) { (it - half).toDouble() }
    // centre-value filter weights: row 0 of pinv(A) = e0^T (A^T A)^-1 A^T
    val ata = Array(order + 1) { i -> DoubleArray(order + 1) { j -> k.sumOf { Math.pow(it, (i + j).toDouble()) } } }
    val e0 = Poly.solve(ata, DoubleArray(order + 1).also { it[0] = 1.0 })
    val coeff = DoubleArray(w) { t -> (0..order).sumOf { e0[it] * Math.pow(k[t], it.toDouble()) } }
    val out = DoubleArray(x.size)
    for (i in half until x.size - half) {
        var s = 0.0
        for (t in 0 until w) s += coeff[t] * x[i - half + t]
        out[i] = s
    }
    fun edge(range: IntRange, ref: IntRange) {
        val xs = DoubleArray(w) { (ref.first + it).toDouble() - ref.first }
        val c = Poly.fit(xs, DoubleArray(w) { x[ref.first + it] }, order)
        for (i in range) out[i] = Poly.eval(c, (i - ref.first).toDouble())
    }
    edge(0 until half, 0 until w)
    edge(x.size - half until x.size, x.size - w until x.size)
    return out
}
