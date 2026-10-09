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
/** Wall-clock time spent per stage of the analysis (milliseconds). */
class Timings {
    var decode = 0L; var detect = 0L; var embed = 0L; var pose = 0L; var plan = 0L; var frames = 0
    fun summary(onGpu: Boolean) =
        "Phân tích ${frames} khung (${if (onGpu) "GPU" else "CPU"}): giải mã ${decode} ms • nhận diện ${detect} ms • vector ${embed} ms" +
            (if (pose > 0) " • pose $pose ms" else "") + " • theo dõi $plan ms"
}

/**
 * [gpu] runs the models on the GPU delegate. A MediaPipe GPU task must be used on the thread that created it, so
 * create the analyzer and call [analyse] from the same thread (the preview analyzer therefore stays on the CPU).
 */
class FrameAnalyzer(
    private val ctx: Context, private val usePose: Boolean, private val sports: Boolean, private val gpu: Boolean = false,
) : AutoCloseable {
    val timings = Timings()
    var onGpu = false; private set
    private val detector: ObjectDetector = Delegates.create(gpu) { base ->
        ObjectDetector.createFromOptions(
            ctx,
            ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(base.setModelAssetPath("efficientdet_lite0.tflite").build())
                .setRunningMode(RunningMode.IMAGE).setMaxResults(20).setScoreThreshold(0.3f)
                .setCategoryAllowlist(listOf("person", "sports ball")).build(),
        )
    }!!.also { onGpu = Delegates.lastOnGpu }
    private val pose = if (usePose) Delegates.create(gpu) { base ->
        PoseLandmarker.createFromOptions(
            ctx,
            PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(base.setModelAssetPath("pose_landmarker_lite.task").build())
                .setRunningMode(RunningMode.IMAGE).setNumPoses(4).build(),
        )
    } else null
    private companion object { const val EMBED_EVERY = 2 }
    private val tracker = SimpleTracker()
    private val appearance = Appearance(ctx, gpu)
    private var frameNo = 0

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
    fun analyse(
        uri: Uri, info: VideoInfo, fps: Double, target: Box? = null, targetTimeMs: Long = 0,
        check: () -> Unit = {}, onProgress: (Float) -> Unit,
    ): List<Step> {
        val r = MediaMetadataRetriever().apply { setDataSource(ctx, uri) }
        val scale = minOf(1.0, 640.0 / maxOf(info.width, info.height))
        val aw = (info.width * scale).toInt(); val ah = (info.height * scale).toInt()
        val n = (info.durationMs / 1000.0 * fps).toInt().coerceAtLeast(1)
        // Pass A: detect everything once. Pass B (Planner.plan) then follows the chosen person forwards and backwards
        // in time over these stored detections, using position AND appearance vectors to re-find them if focus is lost.
        val frames = ArrayList<List<Detection>>(n)
        for (i in 0 until n) {
            check()
            frameNo = i
            val t0 = System.nanoTime()
            val bmp = r.getScaledFrameAtTime((i / fps * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST, aw, ah)
            timings.decode += (System.nanoTime() - t0) / 1_000_000
            frames += if (bmp == null) emptyList() else detect(bmp, 1.0 / scale)
            timings.frames++
            bmp?.recycle()
            if (i % 5 == 0) onProgress((i + 1f) / n)
        }
        r.release()
        val t1 = System.nanoTime()
        return Planner(fps, usePose, sports, target, (targetTimeMs / 1000.0 * fps).toInt()).plan(frames)
            .also { timings.plan = (System.nanoTime() - t1) / 1_000_000 }
    }

    /** A still frame at [timeMs] plus the people found in it (boxes in source-video pixels), for the "pick a person" screen. */
    fun peopleAt(uri: Uri, info: VideoInfo, timeMs: Long): Pair<Bitmap?, List<Box>> {
        val r = MediaMetadataRetriever().apply { setDataSource(ctx, uri) }
        val scale = minOf(1.0, 960.0 / maxOf(info.width, info.height))
        val bmp = r.getScaledFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, (info.width * scale).toInt(), (info.height * scale).toInt())
        r.release()
        if (bmp == null) return null to emptyList()
        val up = info.width.toDouble() / bmp.width
        val boxes = detector.detect(BitmapImageBuilder(bmp).build()).detections()
            .filter { it.categories().first().categoryName() == "person" }
            .map { d -> d.boundingBox().let { Box(it.left * up, it.top * up, it.right * up, it.bottom * up) } }
        return bmp to boxes
    }

    private fun detect(bmp: Bitmap, up: Double): List<Detection> {
        val img = BitmapImageBuilder(bmp).build()
        val people = ArrayList<Detection>(); val balls = ArrayList<Detection>()
        val t0 = System.nanoTime()
        val found = detector.detect(img).detections()
        timings.detect += (System.nanoTime() - t0) / 1_000_000
        val embedThisFrame = frameNo % EMBED_EVERY == 0 // appearance vectors every few frames are plenty for re-identification
        for (d in found) {
            val c = d.categories().first(); val b = d.boundingBox()
            val box = Box(b.left * up, b.top * up, b.right * up, b.bottom * up)
            if (c.categoryName() == "person") {
                val t1 = System.nanoTime()
                val feature = if (embedThisFrame && c.score() >= 0.4f && people.size < 8)
                    appearance.describe(bmp, Box(b.left.toDouble(), b.top.toDouble(), b.right.toDouble(), b.bottom.toDouble())) else null
                timings.embed += (System.nanoTime() - t1) / 1_000_000
                people += Detection(box, c.score().toDouble(), Label.PERSON, feature = feature)
            }
            else balls += Detection(box, c.score().toDouble(), Label.BALL)
        }
        val tracked = tracker.update(people).toMutableList()
        val t2 = System.nanoTime()
        val withPose = pose?.let { attachPose(it.detect(img).landmarks(), tracked, bmp, up) } ?: tracked
        timings.pose += (System.nanoTime() - t2) / 1_000_000
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
            out[i] = Detection(o.box, o.score, o.label, o.trackId, Kp.fromMediaPipe33(pts), o.feature)
        }
        return out
    }

    override fun close() { detector.close(); pose?.close(); appearance.close() }
}
