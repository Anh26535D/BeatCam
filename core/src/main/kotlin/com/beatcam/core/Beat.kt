package com.beatcam.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object Easing {
    fun outBounce(p: Double): Double {
        val n1 = 7.5625; val d1 = 2.75
        return when {
            p < 1 / d1 -> n1 * p * p
            p < 2 / d1 -> { val q = p - 1.5 / d1; n1 * q * q + 0.75 }
            p < 2.5 / d1 -> { val q = p - 2.25 / d1; n1 * q * q + 0.9375 }
            else -> { val q = p - 2.625 / d1; n1 * q * q + 0.984375 }
        }
    }
}

/**
 * scale(frame) = 1 + max over onsets of amp * strength * (1 - bounce(progress)).
 * Full punch lands on the first frame at/after the onset and releases over [frames] frames (3-5).
 * [latency] shifts audio vs video (seconds) to compensate output/display delay.
 */
class PunchEnvelope(
    onsets: List<Onset>, private val fps: Double, private val frames: Int = 4, latency: Double = 0.0,
    amp: Map<OnsetKind, Double> = mapOf(OnsetKind.KICK to 0.09, OnsetKind.SNARE to 0.06), minStrength: Double = 0.25,
) {
    private val events: List<Pair<Double, Double>> = onsets.filter { it.strength >= minStrength }
        .map { it.time + latency to (amp[it.kind] ?: 0.06) * (0.6 + 0.4 * it.strength) }.sortedBy { it.first }
    val count get() = events.size

    fun scaleAtFrame(frame: Int): Double {
        val t = frame / fps
        val half = 0.5 / fps; val span = frames / fps
        var best = 0.0
        for ((et, a) in events) {
            val dt = t - et
            if (dt < -half) break
            if (dt >= span) continue
            val p = min(max(dt, 0.0) * fps / frames, 1.0)
            best = max(best, a * (1 - Easing.outBounce(p)))
        }
        return 1 + best
    }

    /** Presentation-time lookup (video timestamps are quantised to the frame grid, as in the encoder). */
    fun scaleAt(timeSec: Double) = scaleAtFrame((timeSec * fps).roundToInt())
}
