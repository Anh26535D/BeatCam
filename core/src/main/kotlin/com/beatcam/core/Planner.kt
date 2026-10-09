package com.beatcam.core

import kotlin.math.hypot

/** What the camera should look at for one analysis step. */
data class Step(val box: Box?, val leadX: Double = 0.0, val leadY: Double = 0.0, val widen: Double = 0.0)

/**
 * Turns the detections of every analysis step into camera targets (phases 1, 3 and 4).
 *
 * The chosen person ([target], the box the user tapped at step [targetFrame]) is followed forward AND backward from
 * that step, so the camera is already on them from the first frame. Short gaps are interpolated instead of freezing.
 */
class Planner(
    private val fps: Double, private val pose: Boolean = false, private val sports: Boolean = false,
    private val target: Box? = null, private val targetFrame: Int = 0,
) {
    fun plan(frames: List<List<Detection>>): List<Step> {
        val n = frames.size
        if (n == 0) return emptyList()
        val people = frames.map { f -> f.filter { it.label == Label.PERSON } }
        val subj = trackChosen(people)
        val boxes = fillGaps(subj.map { it?.box }, maxGap = (2 * fps).toInt())

        val gesture = GestureAnalyzer()
        val kalman = BallKalman(fps)
        val intent = IntentPredictor(fps)
        return List(n) { i ->
            var box: Box? = boxes[i]
            var widen = 0.0
            if (sports) {
                val ball = frames[i].filter { it.label == Label.BALL }.maxByOrNull { it.score }
                val state = kalman.update(ball?.cx, ball?.cy)
                val plan = intent.update(state, people[i].filter { it.trackId != null }.associateBy { it.trackId!! })
                if (plan != null) { box = plan.box; widen = plan.widen }
            }
            var lx = 0.0; var ly = 0.0
            val s = subj[i]
            if (pose && s?.keypoints != null) {
                val c = gesture.update(s.keypoints)
                lx = c.dx * s.height; ly = c.dy * s.height; widen += c.widen
            }
            Step(box, lx, ly, widen)
        }
    }

    private fun trackChosen(people: List<List<Detection>>): Array<Detection?> {
        val n = people.size
        val out = arrayOfNulls<Detection>(n)
        val start = targetFrame.coerceIn(0, n - 1)
        // seed: the person at the selection step (or the first step after / before it that has anyone)
        val order = (start until n) + (start - 1 downTo 0)
        val seedIdx = order.firstOrNull { people[it].isNotEmpty() } ?: return out
        val c = people[seedIdx]
        val seed = if (target != null) c.maxWith(compareBy<Detection> { target.iou(it.box) }
            .thenBy { -hypot(it.cx - target.cx, it.cy - target.cy) }) else c.maxBy { it.score * it.box.w * it.box.h }
        out[seedIdx] = seed
        val fwd = TargetTracker(seed)
        for (i in seedIdx + 1 until n) out[i] = fwd.update(people[i])
        val bwd = TargetTracker(seed)
        for (i in seedIdx - 1 downTo 0) out[i] = bwd.update(people[i])
        return out
    }

    /** Linear interpolation across short gaps; longer gaps (and the ends) hold the nearest known box. */
    private fun fillGaps(known: List<Box?>, maxGap: Int): List<Box?> {
        val out = known.toMutableList()
        var i = 0
        while (i < known.size) {
            if (known[i] != null) { i++; continue }
            var j = i
            while (j < known.size && known[j] == null) j++
            val prev = if (i > 0) known[i - 1] else null
            val next = if (j < known.size) known[j] else null
            for (k in i until j) {
                out[k] = when {
                    prev != null && next != null && j - i <= maxGap -> lerp(prev, next, (k - i + 1).toDouble() / (j - i + 1))
                    prev != null -> prev
                    else -> next
                }
            }
            i = j
        }
        return out
    }

    private fun lerp(a: Box, b: Box, t: Double) = Box(
        a.x1 + t * (b.x1 - a.x1), a.y1 + t * (b.y1 - a.y1), a.x2 + t * (b.x2 - a.x2), a.y2 + t * (b.y2 - a.y2),
    )
}

/** Camera path sampled at the analysis rate, with linear interpolation + beat punch at render time. */
class CropPath(private val crops: List<Crop>, private val analysisFps: Double, private val punch: PunchEnvelope?) {
    val durationSec get() = crops.size / analysisFps

    fun at(timeSec: Double): Crop {
        if (crops.isEmpty()) return Crop(0.0, 0.0, 1.0, 1.0)
        val f = clampTo(timeSec * analysisFps, 0.0, (crops.size - 1).toDouble())
        val i = f.toInt(); val j = minOf(i + 1, crops.size - 1); val a = f - i
        val p = crops[i]; val q = crops[j]
        val w = p.w + a * (q.w - p.w); val h = p.h + a * (q.h - p.h)
        val cx = p.cx + a * (q.cx - p.cx); val cy = p.cy + a * (q.cy - p.cy)
        val s = punch?.scaleAt(timeSec) ?: 1.0
        return Crop(cx - w / s / 2, cy - h / s / 2, w / s, h / s)
    }

    companion object {
        /** The chosen subject's centre must stay inside this central part of the crop (fraction of crop size). */
        private const val KEEP_IN = 0.3

        fun build(steps: List<Step>, srcW: Int, srcH: Int, analysisFps: Double, cfg: CameraConfig, punch: PunchEnvelope?, savgolWindow: Int = 0): CropPath {
            fun run(list: List<Step>): List<Crop> {
                val cam = VirtualCamera(srcW, srcH, analysisFps, cfg)
                return list.map { cam.update(it.box, it.leadX, it.leadY, it.widen) }
            }
            // A causal filter always lags the subject. Running it forwards and backwards in time and averaging
            // gives a zero-lag path (we have the whole clip, so we can look ahead).
            val fwd = run(steps)
            val bwd = run(steps.reversed()).reversed()
            var crops = fwd.indices.map { i ->
                val a = fwd[i]; val b = bwd[i]
                Crop((a.x + b.x) / 2, (a.y + b.y) / 2, (a.w + b.w) / 2, (a.h + b.h) / 2)
            }
            crops = keepSubject(crops, steps, srcW, srcH)
            if (savgolWindow > 0 && crops.size > savgolWindow) {
                fun s(sel: (Crop) -> Double) = savgol(crops.map(sel).toDoubleArray(), savgolWindow, 3)
                val x = s { it.x }; val y = s { it.y }; val w = s { it.w }.map { minOf(it, srcW.toDouble()) }; val h = s { it.h }.map { minOf(it, srcH.toDouble()) }
                crops = crops.indices.map { Crop(clampTo(x[it], 0.0, srcW - w[it]), clampTo(y[it], 0.0, srcH - h[it]), w[it], h[it]) }
                crops = keepSubject(crops, steps, srcW, srcH)
            }
            return CropPath(crops, analysisFps, punch)
        }

        /** Hard guarantee: the subject's centre is never outside the central region of the crop (i.e. never "out of focus"). */
        private fun keepSubject(crops: List<Crop>, steps: List<Step>, srcW: Int, srcH: Int): List<Crop> =
            crops.mapIndexed { i, c ->
                val b = steps[i].box ?: return@mapIndexed c
                val x = clampTo(clampTo(c.x, b.cx - (1 - KEEP_IN) * c.w, b.cx - KEEP_IN * c.w), 0.0, srcW - c.w)
                val y = clampTo(clampTo(c.y, b.cy - (1 - KEEP_IN) * c.h, b.cy - KEEP_IN * c.h), 0.0, srcH - c.h)
                Crop(x, y, c.w, c.h)
            }
    }
}
