package com.beatcam.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** dx, dy as fractions of subject height (+y down); widen > 0 zooms out. */
data class GestureCue(
    val dx: Double = 0.0, val dy: Double = 0.0, val widen: Double = 0.0,
    val reachUp: Double = 0.0, val floorDrop: Double = 0.0, val armsSpread: Double = 0.0,
)

class GestureAnalyzer(private val conf: Double = 0.3, private val gain: Double = 0.35) {
    private val prevRel = HashMap<Int, DoubleArray>()
    private var standLeg: Double? = null
    private var vx = 0.0
    private var vy = 0.0

    private fun ok(kp: Array<DoubleArray>, vararg i: Int) = i.all { kp[it][2] >= conf }
    private fun clamp01(v: Double) = v.coerceIn(0.0, 1.0)

    fun update(kp: Array<DoubleArray>?): GestureCue {
        if (kp == null) return GestureCue()
        val sh = intArrayOf(Kp.L_SHO, Kp.R_SHO).filter { kp[it][2] >= conf }
        val hp = intArrayOf(Kp.L_HIP, Kp.R_HIP).filter { kp[it][2] >= conf }
        if (sh.isEmpty() || hp.isEmpty()) return GestureCue()
        val sx = sh.map { kp[it][0] }.average(); val sy = sh.map { kp[it][1] }.average()
        val hx = hp.map { kp[it][0] }.average(); val hy = hp.map { kp[it][1] }.average()
        val torso = max(hypot(sx - hx, sy - hy), 1e-6)

        val headY = if (ok(kp, Kp.NOSE)) kp[Kp.NOSE][1] else sy - 0.5 * torso
        val wr = intArrayOf(Kp.L_WRI, Kp.R_WRI).filter { ok(kp, it) }
        val reach = if (wr.isEmpty()) 0.0 else clamp01(((headY - wr.minOf { kp[it][1] }) / torso) / 0.8)

        var spread = 0.0
        if (ok(kp, Kp.L_WRI, Kp.R_WRI, Kp.L_SHO, Kp.R_SHO)) {
            val sw = max(abs(kp[Kp.L_SHO][0] - kp[Kp.R_SHO][0]), 0.35 * torso)
            spread = clamp01((abs(kp[Kp.L_WRI][0] - kp[Kp.R_WRI][0]) / sw - 2.0) / 2.0)
        }

        var drop = 0.0
        val kn = intArrayOf(Kp.L_KNE, Kp.R_KNE).filter { ok(kp, it) }
        if (kn.isNotEmpty()) {
            val leg = kn.map { abs(kp[it][1] - hy) }.average()
            val ref = standLeg?.let { max(leg, 0.995 * it + 0.005 * leg) } ?: leg
            standLeg = ref
            drop = if (leg > 0) min(clamp01(1.0 - leg / max(ref, 1e-6)) / 0.6, 1.0) else 0.0
        }

        // wrist motion relative to shoulder centre: global body/camera motion cancels out
        var ax = 0.0; var ay = 0.0
        for (i in wr) {
            val rel = doubleArrayOf((kp[i][0] - sx) / torso, (kp[i][1] - sy) / torso)
            prevRel[i]?.let { ax += rel[0] - it[0]; ay += rel[1] - it[1] }
            prevRel[i] = rel
        }
        vx = 0.6 * vx + 0.4 * ax; vy = 0.6 * vy + 0.4 * ay

        val dy = gain * (-0.6 * reach + 0.5 * drop) + (gain * vy).coerceIn(-0.3, 0.3)
        val dx = (gain * vx).coerceIn(-0.3, 0.3)
        return GestureCue(dx, dy, 0.35 * reach + 0.25 * spread + 0.2 * drop, reach, drop, spread)
    }
}
