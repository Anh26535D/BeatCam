package com.beatcam.core

/** What the camera should look at for one analysis step. */
data class Step(val box: Box?, val leadX: Double = 0.0, val leadY: Double = 0.0, val widen: Double = 0.0)

/** Sticks to one track id; switches only after it has been gone for [patience] steps. */
class SubjectLock(private val patience: Int = 20) {
    private var id: Int? = null
    private var gone = 0

    fun pick(people: List<Detection>): Detection? {
        if (people.isEmpty()) return null
        people.firstOrNull { id != null && it.trackId == id }?.let { gone = 0; return it }
        gone++
        if (id == null || gone > patience) {
            val c = people.maxBy { it.score * it.box.w * it.box.h }
            id = c.trackId; gone = 0
            return c
        }
        return null
    }
}

/** Per-frame analysis state machine: detections in, camera target out (phases 1, 3 and 4). */
class Planner(fps: Double, private val pose: Boolean = false, private val sports: Boolean = false) {
    private val lock = SubjectLock()
    private val gesture = GestureAnalyzer()
    private val kalman = BallKalman(fps)
    private val intent = IntentPredictor(fps)

    fun step(dets: List<Detection>): Step {
        val people = dets.filter { it.label == Label.PERSON }
        val subj = lock.pick(people)
        var box: Box? = null
        var widen = 0.0
        if (sports) {
            val ball = dets.filter { it.label == Label.BALL }.maxByOrNull { it.score }
            val state = kalman.update(ball?.cx, ball?.cy)
            val plan = intent.update(state, people.filter { it.trackId != null }.associateBy { it.trackId!! })
            if (plan != null) { box = plan.box; widen = plan.widen }
        }
        if (box == null) box = subj?.box
        var lx = 0.0; var ly = 0.0
        if (pose && subj?.keypoints != null) {
            val c = gesture.update(subj.keypoints)
            lx = c.dx * subj.height; ly = c.dy * subj.height; widen += c.widen
        }
        return Step(box, lx, ly, widen)
    }
}

/** Camera path sampled at the analysis rate, with linear interpolation + beat punch at render time. */
class CropPath(private val crops: List<Crop>, private val analysisFps: Double, private val punch: PunchEnvelope?) {
    val durationSec get() = crops.size / analysisFps

    fun at(timeSec: Double): Crop {
        val f = (timeSec * analysisFps).coerceIn(0.0, (crops.size - 1).toDouble())
        val i = f.toInt(); val j = minOf(i + 1, crops.size - 1); val a = f - i
        val p = crops[i]; val q = crops[j]
        val w = p.w + a * (q.w - p.w); val h = p.h + a * (q.h - p.h)
        val cx = p.cx + a * (q.cx - p.cx); val cy = p.cy + a * (q.cy - p.cy)
        val s = punch?.scaleAt(timeSec) ?: 1.0
        return Crop(cx - w / s / 2, cy - h / s / 2, w / s, h / s)
    }

    companion object {
        fun build(steps: List<Step>, srcW: Int, srcH: Int, analysisFps: Double, cfg: CameraConfig, punch: PunchEnvelope?, savgolWindow: Int = 0): CropPath {
            val cam = VirtualCamera(srcW, srcH, analysisFps, cfg)
            var crops = steps.map { cam.update(it.box, it.leadX, it.leadY, it.widen) }
            if (savgolWindow > 0 && crops.size > savgolWindow) {
                fun s(sel: (Crop) -> Double) = savgol(crops.map(sel).toDoubleArray(), savgolWindow, 3)
                val x = s { it.x }; val y = s { it.y }; val w = s { it.w }; val h = s { it.h }
                crops = crops.indices.map { Crop(x[it].coerceIn(0.0, srcW - w[it]), y[it].coerceIn(0.0, srcH - h[it]), w[it], h[it]) }
            }
            return CropPath(crops, analysisFps, punch)
        }
    }
}
