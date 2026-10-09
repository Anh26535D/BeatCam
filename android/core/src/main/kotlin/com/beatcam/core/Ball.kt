package com.beatcam.core

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max

/** Constant-velocity Kalman filter for the ball; coasts through motion-blur dropouts. State: [x, y, vx, vy]. */
class BallKalman(fps: Double, q: Double = 400.0, r: Double = 4.0, private val maxMiss: Int = 12, private val gate: Double = 120.0) {
    private val dt = 1.0 / fps
    private val f = arrayOf(doubleArrayOf(1.0, 0.0, dt, 0.0), doubleArrayOf(0.0, 1.0, 0.0, dt), doubleArrayOf(0.0, 0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 0.0, 1.0))
    private val qm = diag(q * dt * dt, q * dt * dt, q, q)
    private val r = r
    private var x: DoubleArray? = null
    private var p = diag(100.0, 100.0, 100.0, 100.0)
    private var miss = 0

    fun update(mx: Double?, my: Double?): DoubleArray? {
        val prev = x
        if (prev == null) {
            if (mx == null || my == null) return null
            x = doubleArrayOf(mx, my, 0.0, 0.0); p = diag(10.0, 10.0, 1e4, 1e4); miss = 0
            return x!!.copyOf()
        }
        var s: DoubleArray = mul(f, prev)
        p = add(mul(mul(f, p), t(f)), qm)
        if (mx != null && my != null && hypot(mx - s[0], my - s[1]) <= gate + 30 * miss) {
            // H selects position: S = P[0:2,0:2] + R*I, K = P[:,0:2] S^-1
            val a = p[0][0] + r; val b = p[0][1]; val c = p[1][0]; val d = p[1][1] + r
            val det = a * d - b * c
            val inv = arrayOf(doubleArrayOf(d / det, -b / det), doubleArrayOf(-c / det, a / det))
            val k = Array(4) { i -> doubleArrayOf(p[i][0] * inv[0][0] + p[i][1] * inv[1][0], p[i][0] * inv[0][1] + p[i][1] * inv[1][1]) }
            val y0 = mx - s[0]; val y1 = my - s[1]
            val prior = s
            s = DoubleArray(4) { prior[it] + k[it][0] * y0 + k[it][1] * y1 }
            val np = Array(4) { i -> DoubleArray(4) { j -> p[i][j] - k[i][0] * p[0][j] - k[i][1] * p[1][j] } }
            p = np
            miss = 0
        } else if (++miss > maxMiss) {
            x = null
            return null
        }
        x = s
        return s.copyOf()
    }

    private fun diag(vararg v: Double) = Array(4) { i -> DoubleArray(4) { j -> if (i == j) v[i] else 0.0 } }
    private fun mul(a: Array<DoubleArray>, v: DoubleArray) = DoubleArray(4) { i -> (0..3).sumOf { a[i][it] * v[it] } }
    private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) = Array(4) { i -> DoubleArray(4) { j -> (0..3).sumOf { a[i][it] * b[it][j] } } }
    private fun t(a: Array<DoubleArray>) = Array(4) { i -> DoubleArray(4) { j -> a[j][i] } }
    private fun add(a: Array<DoubleArray>, b: Array<DoubleArray>) = Array(4) { i -> DoubleArray(4) { j -> a[i][j] + b[i][j] } }
}

/** Ball position after [t] seconds with exponential velocity decay (drag per second). */
fun extrapolate(x: Double, y: Double, vx: Double, vy: Double, t: Double, drag: Double = 0.6): DoubleArray {
    val k = max(drag, 1e-6)
    val g = (1 - exp(-k * t)) / k
    return doubleArrayOf(x + vx * g, y + vy * g)
}

enum class Phase { FOLLOW, RELEASE, APPROACH }
data class FramingPlan(val phase: Phase, val box: Box, val widen: Double = 0.0, val receiver: Int? = null)

/**
 * Picks the framing target from ball kinematics.
 * RELEASE: ball just left the passer -> loosen framing so the pass is visible.
 * APPROACH: ball about to reach the receiver -> pre-frame and tighten on them.
 */
class IntentPredictor(
    private val fps: Double, private val passSpeed: Double = 3.0, private val horizon: Double = 2.0,
    private val preFrameS: Double = 0.5, private val reach: Double = 1.2, private val drag: Double = 0.6,
) {
    var possessor: Int? = null; private set
    var receiver: Int? = null; private set

    /** ball: [x, y, vx, vy] or null. players: by track id. */
    fun update(ball: DoubleArray?, players: Map<Int, Detection>): FramingPlan? {
        if (players.isEmpty()) return null
        val h = players.values.map { it.height }.sorted()[players.size / 2].takeIf { it > 0 } ?: 1.0
        if (ball == null) { receiver = null; return follow(players, null) }
        val speed = hypot(ball[2], ball[3]) / h
        val near = players.values.minBy { hypot(it.cx - ball[0], it.cy - ball[1]) }
        val nearD = hypot(near.cx - ball[0], near.cy - ball[1]) / h

        if (speed < passSpeed) {
            receiver = null
            if (nearD < reach) possessor = near.trackId
            return follow(players, ball)
        }
        val passer = possessor?.let { players[it] }
        fun minDist(p: Detection): Pair<Double, Double> {
            var bd = Double.MAX_VALUE; var bt = 0.0; var t = 0.0
            while (t < horizon) {
                val q = extrapolate(ball[0], ball[1], ball[2], ball[3], t, drag)
                val d = hypot(p.cx - q[0], p.cy - q[1]) / h
                if (d < bd) { bd = d; bt = t }
                t += 1.0 / fps
            }
            return bd to bt
        }
        var best: Int? = null; var bestD = Double.MAX_VALUE; var bestT = 0.0
        for ((id, p) in players) {
            if (id == possessor) continue
            val (d, t) = minDist(p)
            if (d < bestD) { best = id; bestD = d; bestT = t }
        }
        if (best == null || bestD > reach * 1.5) return follow(players, ball)
        val locked = receiver
        if (locked != null && locked != best && players[locked] != null && minDist(players[locked]!!).first < bestD + 0.3) {
            best = locked; bestT = minDist(players[locked]!!).second
        }
        receiver = best
        val rcv = players.getValue(best!!)
        if (bestT <= preFrameS) return FramingPlan(Phase.APPROACH, rcv.box, -0.15, best)
        val boxes = listOfNotNull(rcv.box, passer?.box, Box.around(ball[0], ball[1], 0.2 * h))
        return FramingPlan(Phase.RELEASE, Box.union(boxes), 0.3, best)
    }

    private fun follow(players: Map<Int, Detection>, ball: DoubleArray?): FramingPlan {
        val p = possessor?.let { players[it] } ?: players.values.first()
        val box = if (ball == null) p.box else Box.union(listOf(p.box, Box.around(ball[0], ball[1], 5.0)))
        return FramingPlan(Phase.FOLLOW, box)
    }
}
