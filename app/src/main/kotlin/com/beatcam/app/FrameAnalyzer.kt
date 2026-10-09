package com.beatcam.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.beatcam.core.Box
import com.beatcam.core.Detection
import com.beatcam.core.Kp
import com.beatcam.core.Label
import com.beatcam.core.Planner
import com.beatcam.core.SimpleTracker
import com.beatcam.core.Step
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker

data class VideoInfo(val width: Int, val height: Int, val durationMs: Long)

/** Pass 1: samples frames at [fps], runs on-device detection (+pose) and turns them into camera targets. */
class FrameAnalyzer(private val ctx: Context, private val usePose: Boolean, private val sports: Boolean) : AutoCloseable {
    private val detector = ObjectDetector.createFromOptions(
        ctx,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite0.tflite").build())
            .setRunningMode(RunningMode.IMAGE).setMaxResults(20).setScoreThreshold(0.3f)
            .setCategoryAllowlist(listOf("person", "sports ball")).build(),
    )
    private val pose = if (usePose) PoseLandmarker.createFromOptions(
        ctx,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build())
            .setRunningMode(RunningMode.IMAGE).setNumPoses(4).build(),
    ) else null
    private val tracker = SimpleTracker()

    fun info(uri: Uri): VideoInfo {
        val r = MediaMetadataRetriever().apply { setDataSource(ctx, uri) }
        fun k(key: Int) = r.extractMetadata(key)!!.toLong()
        var w = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
        var h = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
        if (rot == 90 || rot == 270) w = h.also { h = w }
        val d = k(MediaMetadataRetriever.METADATA_KEY_DURATION)
        r.release()
        return VideoInfo(w, h, d)
    }

    /** Returns one camera [Step] per sampled frame. [onProgress] gets 0..1. */
    fun analyse(uri: Uri, info: VideoInfo, fps: Double, check: () -> Unit = {}, onProgress: (Float) -> Unit): List<Step> {
        val r = MediaMetadataRetriever().apply { setDataSource(ctx, uri) }
        val planner = Planner(fps, usePose, sports)
        val scale = minOf(1.0, 640.0 / maxOf(info.width, info.height))
        val aw = (info.width * scale).toInt(); val ah = (info.height * scale).toInt()
        val n = (info.durationMs / 1000.0 * fps).toInt().coerceAtLeast(1)
        val steps = ArrayList<Step>(n)
        for (i in 0 until n) {
            check()
            val bmp = r.getScaledFrameAtTime((i / fps * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST, aw, ah)
            steps += planner.step(if (bmp == null) emptyList() else detect(bmp, 1.0 / scale))
            bmp?.recycle()
            if (i % 5 == 0) onProgress((i + 1f) / n)
        }
        r.release()
        return steps
    }

    private fun detect(bmp: Bitmap, up: Double): List<Detection> {
        val img = BitmapImageBuilder(bmp).build()
        val people = ArrayList<Detection>(); val balls = ArrayList<Detection>()
        for (d in detector.detect(img).detections()) {
            val c = d.categories().first(); val b = d.boundingBox()
            val box = Box(b.left * up, b.top * up, b.right * up, b.bottom * up)
            if (c.categoryName() == "person") people += Detection(box, c.score().toDouble(), Label.PERSON)
            else balls += Detection(box, c.score().toDouble(), Label.BALL)
        }
        val tracked = tracker.update(people).toMutableList()
        val withPose = pose?.let { attachPose(it.detect(img).landmarks(), tracked, bmp, up) } ?: tracked
        return withPose + balls
    }

    /** Matches each pose (via its landmark bounding box) to the tracked person with the highest IoU. */
    private fun attachPose(poses: List<List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>>, people: List<Detection>, bmp: Bitmap, up: Double): List<Detection> {
        val out = people.toMutableList()
        for (lm in poses) {
            val pts = Array(33) { i -> doubleArrayOf(lm[i].x() * bmp.width * up, lm[i].y() * bmp.height * up, (lm[i].visibility().orElse(0f)).toDouble()) }
            val pb = Box(pts.minOf { it[0] }, pts.minOf { it[1] }, pts.maxOf { it[0] }, pts.maxOf { it[1] })
            val i = out.indices.filter { out[it].label == Label.PERSON }.maxByOrNull { out[it].box.iou(pb) } ?: continue
            if (out[i].box.iou(pb) < 0.2) continue
            val o = out[i]
            out[i] = Detection(o.box, o.score, o.label, o.trackId, Kp.fromMediaPipe33(pts))
        }
        return out
    }

    override fun close() { detector.close(); pose?.close() }
}
