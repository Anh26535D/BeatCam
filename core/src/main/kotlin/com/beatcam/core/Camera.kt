package com.beatcam.core

import kotlin.math.abs
import kotlin.math.min

/** Output frame shapes offered to the user (pixel size of the exported video). */
enum class FrameShape(val label: String, val outW: Int, val outH: Int) {
    PORTRAIT_9_16("9:16 dọc", 1080, 1920),
    PORTRAIT_4_5("4:5", 1080, 1350),
    SQUARE("1:1 vuông", 1080, 1080),
    LANDSCAPE_16_9("16:9 ngang", 1920, 1080);

    val aspect get() = outW.toDouble() / outH
}

/** Like coerceIn but never throws: if the range is empty (hi < lo, e.g. crop wider than frame by rounding) lo wins. */
internal fun clampTo(v: Double, lo: Double, hi: Double) = maxOf(lo, minOf(v, hi))

data class CameraConfig(
    val aspect: Double = 9.0 / 16,      // output width / height
    val baseZoom: Double = 1.25,        // >1 leaves room to pan horizontally and to widen
    val margin: Double = 0.10,          // dead-zone: fraction of crop size kept as safe border
    val minCutoff: Double = 1.2,
    val beta: Double = 0.03,
    val attack: Double = 0.35,          // lead-room smoothing per step when offset grows (fast)
    val release: Double = 0.03,         // ... and when it relaxes to centre (slow)
    val maxWiden: Double = 0.5,
    val minWiden: Double = -0.2,
)

data class Crop(val x: Double, val y: Double, val w: Double, val h: Double) {
    val cx get() = x + w / 2
    val cy get() = y + h / 2
}

/** Dead-zone gating + One-Euro smoothing + asymmetric lead-room damping. One `update` per analysis step. */
class VirtualCamera(private val srcW: Int, private val srcH: Int, fps: Double, val cfg: CameraConfig = CameraConfig()) {
    private val dt = 1.0 / fps
    private val fx = OneEuroFilter(cfg.minCutoff, cfg.beta)
    private val fy = OneEuroFilter(cfg.minCutoff, cfg.beta)
    private var c: DoubleArray? = null
    private var offX = 0.0
    private var offY = 0.0
    private var widen = 0.0

    fun size(widen: Double): Pair<Double, Double> {
        val h = min(min(srcH / maxOf(cfg.baseZoom, 1.0) * (1 + widen), srcH.toDouble()), srcW / cfg.aspect)
        return min(h * cfg.aspect, srcW.toDouble()) to h
    }

    private fun damp(cur: Double, target: Double) =
        cur + (if (abs(target) > abs(cur)) cfg.attack else cfg.release) * (target - cur)

    /** box: subject or null to hold. lead: desired offset in px. */
    fun update(box: Box?, leadX: Double = 0.0, leadY: Double = 0.0, widenTarget: Double = 0.0): Crop {
        var (w, h) = size(widen)
        val cur = c
        if (cur == null) {
            c = doubleArrayOf(box?.cx ?: srcW / 2.0, box?.cy ?: srcH / 2.0)
            fx.reset(c!![0]); fy.reset(c!![1])
        } else if (box != null) {
            c = gate(box, cur, w, h)
        }
        val cx = fx.filter(c!![0], dt)
        val cy = fy.filter(c!![1], dt)
        offX = damp(offX, leadX)
        offY = damp(offY, leadY)
        widen = damp(widen, widenTarget.coerceIn(cfg.minWiden, cfg.maxWiden))
        size(widen).let { w = it.first; h = it.second }
        return Crop(clampTo(cx + offX - w / 2, 0.0, srcW - w), clampTo(cy + offY - h / 2, 0.0, srcH - h), w, h)
    }

    /** Move the centre only as far as needed to bring the box back inside the safe region. */
    private fun gate(b: Box, c: DoubleArray, w: Double, h: Double): DoubleArray {
        val out = c.copyOf()
        for (ax in 0..1) {
            val lo = if (ax == 0) b.x1 else b.y1
            val hi = if (ax == 0) b.x2 else b.y2
            val size = if (ax == 0) w else h
            val m = cfg.margin * size
            val sLo = c[ax] - size / 2 + m
            val sHi = c[ax] + size / 2 - m
            when {
                hi - lo > sHi - sLo -> out[ax] = (lo + hi) / 2
                lo < sLo -> out[ax] += lo - sLo
                hi > sHi -> out[ax] += hi - sHi
            }
        }
        return out
    }
}
