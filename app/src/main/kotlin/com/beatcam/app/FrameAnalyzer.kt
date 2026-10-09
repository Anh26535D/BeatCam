package com.beatcam.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.beatcam.core.Box
import com.beatcam.core.Detection
import com.beatcam.core.Kp
import com.beatcam.core.fusePeople
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
    private val pose = Delegates.create(gpu) { base ->
        PoseLandmarker.createFromOptions(
            ctx,
            PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(base.setModelAssetPath("pose_landmarker_lite.task").build())
                .setRunningMode(RunningMode.IMAGE).setNumPoses(6).build(),
        )
    }
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

    /** A still frame at [timeMs] plus the people in it (boxes in source-video pixels, with appearance vectors). */
    fun peopleAt(uri: Uri, info: VideoInfo, timeMs: Long): Pair<Bitmap?, List<Detection>> {
        val r = MediaMetadataRetriever().apply { setDataSource(ctx, uri) }
        val scale = minOf(1.0, 960.0 / maxOf(info.width, info.height))
        val bmp = r.getScaledFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, (info.width * scale).toInt(), (info.height * scale).toInt())
        r.release()
        if (bmp == null) return null to emptyList()
        frameNo = 0
        return bmp to detect(bmp, info.width.toDouble() / bmp.width).filter { it.label == Label.PERSON }
    }

    private fun detect(bmp: Bitmap, up: Double): List<Detection> {
        val img = BitmapImageBuilder(bmp).build()
        val detected = ArrayList<Detection>(); val balls = ArrayList<Detection>()
        val t0 = System.nanoTime()
        val found = detector.detect(img).detections()
        timings.detect += (System.nanoTime() - t0) / 1_000_000
        for (d in found) {
            val c = d.categories().first(); val b = d.boundingBox()
            val box = Box(b.left * up, b.top * up, b.right * up, b.bottom * up)
            if (c.categoryName() == "person") detected += Detection(box, c.score().toDouble(), Label.PERSON)
            else balls += Detection(box, c.score().toDouble(), Label.BALL)
        }
        // Skeletons are per person, so they separate people the box detector merged into one box.
        val t2 = System.nanoTime()
        val skeletons = pose?.let { poseDetections(it.detect(img).landmarks(), bmp, up) }.orEmpty()
        timings.pose += (System.nanoTime() - t2) / 1_000_000
        val fused = fusePeople(detected, skeletons)

        val embedThisFrame = frameNo % EMBED_EVERY == 0 // appearance vectors every few frames are plenty for re-identification
        val t1 = System.nanoTime()
        val people = fused.mapIndexed { i, p ->
            val feature = if (embedThisFrame && p.score >= 0.4 && i < 8)
                appearance.describe(bmp, Box(p.box.x1 / up, p.box.y1 / up, p.box.x2 / up, p.box.y2 / up)) else null
            Detection(p.box, p.score, Label.PERSON, p.trackId, p.keypoints, feature)
        }
        timings.embed += (System.nanoTime() - t1) / 1_000_000
        return tracker.update(people) + balls
    }

    /** One detection per skeleton: box from the landmarks (padded, extra headroom), keypoints in source pixels. */
    private fun poseDetections(poses: List<List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>>, bmp: Bitmap, up: Double): List<Detection> =
        poses.mapNotNull { lm ->
            val pts = Array(33) { i -> doubleArrayOf(lm[i].x() * bmp.width * up, lm[i].y() * bmp.height * up, lm[i].visibility().orElse(0f).toDouble()) }
            val vis = pts.filter { it[2] >= 0.3 }
            if (vis.size < 8) return@mapNotNull null
            val x1 = vis.minOf { it[0] }; val x2 = vis.maxOf { it[0] }; val y1 = vis.minOf { it[1] }; val y2 = vis.maxOf { it[1] }
            val w = x2 - x1; val h = y2 - y1
            val box = Box((x1 - 0.08 * w).coerceAtLeast(0.0), (y1 - 0.15 * h).coerceAtLeast(0.0),
                (x2 + 0.08 * w).coerceAtMost(bmp.width * up), (y2 + 0.05 * h).coerceAtMost(bmp.height * up))
            Detection(box, 0.9, Label.PERSON, keypoints = Kp.fromMediaPipe33(pts))
        }

    override fun close() { detector.close(); pose?.close(); appearance.close() }
}
