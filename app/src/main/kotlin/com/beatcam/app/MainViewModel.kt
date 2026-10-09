package com.beatcam.app

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.transformer.Transformer
import com.beatcam.core.Box
import com.beatcam.core.Detection
import com.beatcam.core.TargetTracker
import com.beatcam.core.CameraConfig
import com.beatcam.core.FrameShape
import com.beatcam.core.CropPath
import com.beatcam.core.Step
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Phase { IDLE, READY, WORKING, DONE }

/** Tools shown in the editor's bottom bar (like the Samsung editor's tool strip). */
enum class Tool(val label: String) { SUBJECT("Chủ thể"), FRAME("Khung hình") }

data class UiState(
    val phase: Phase = Phase.IDLE,
    val source: Uri? = null,
    val thumb: Bitmap? = null,
    val info: VideoInfo? = null,
    val steps: List<Step>? = null,          // tracking result (one step per analysis frame) once "Theo dõi" has run
    val path: CropPath? = null,             // camera path built from [steps] for the chosen frame shape / zoom
    val trackedPct: Int = 0,
    val tool: Tool? = Tool.SUBJECT,
    val thumbs: List<Bitmap> = emptyList(),
    val thumbStepMs: Long = 1000,
    val recents: List<File> = emptyList(),
    val shape: FrameShape = FrameShape.PORTRAIT_9_16,
    val zoom: Float = 1.25f,
    val people: List<Detection> = emptyList(),
    val selected: Int? = null,
    val previewMs: Long = 0,
    val detecting: Boolean = false,
    val stage: String = "",
    val progress: Float = 0f,
    val output: File? = null,
    val saved: Boolean = false,
    val stats: String? = null,
    val message: String? = null,
)

private fun listRecents(app: Application): List<File> =
    app.getExternalFilesDir(null)?.listFiles { f -> f.extension == "mp4" }?.sortedByDescending { it.lastModified() }.orEmpty()

private const val TAG = "BeatCam"
const val FPS = 15.0 // analysis rate

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState(recents = listRecents(app)))
    val state: StateFlow<UiState> = _state
    private var job: Job? = null
    private var previewJob: Job? = null
    private var previewer: FrameAnalyzer? = null
    private var transformer: Transformer? = null

    fun pick(uri: Uri?) {
        if (uri == null) { Log.i(TAG, "no video picked"); return }
        Log.i(TAG, "picked $uri")
        viewModelScope.launch {
            val (thumb, info) = withContext(Dispatchers.IO) { loadPreview(uri) }
            val step = maxOf(1000L, (info?.durationMs ?: 0) / 80)
            _state.update { it.copy(phase = Phase.READY, source = uri, thumb = thumb, info = info, people = emptyList(), selected = null, steps = null, path = null, previewMs = 0, thumbs = emptyList(), thumbStepMs = step, tool = Tool.SUBJECT) }
            seek(0)
            if (info != null) {
                val strip = withContext(Dispatchers.IO) { loadStrip(uri, info, step) }
                _state.update { it.copy(thumbs = strip) }
            }
        }
    }

    /** Show the frame at [ms] and detect the people in it so the user can tap one. */
    fun seek(ms: Long) {
        val src = _state.value.source ?: return
        val info = _state.value.info ?: return
        previewJob?.cancel()
        _state.update { it.copy(previewMs = ms, detecting = true) }
        previewJob = viewModelScope.launch {
            val prev = _state.value.selected?.let { _state.value.people.getOrNull(it) }
            val (bmp, found) = try {
                withContext(Dispatchers.Default) {
                    val fa = previewer ?: FrameAnalyzer(getApplication(), false, false, gpu = false).also { previewer = it }
                    fa.peopleAt(src, info, ms)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Log.e(TAG, "person detection failed at ${ms} ms", e)
                _state.update { it.copy(detecting = false, message = "Không nhận diện được người: ${e.message}") }
                return@launch
            }
            _state.update {
                // Keep the SAME person selected while scrubbing (match by position + appearance). Only the very first
                // frame defaults to the biggest person; if the chosen person is not visible here, nobody is selected.
                val sel = if (prev != null) {
                    TargetTracker(prev, lostSteps = 30, splitMerged = false).update(found)?.let { m -> found.indexOf(m).takeIf { i -> i >= 0 } }
                } else found.indices.maxByOrNull { i -> found[i].box.w * found[i].box.h }
                it.copy(thumb = bmp ?: it.thumb, people = found, selected = sel, detecting = false)
            }
        }
    }

    fun previewTime(ms: Long) = _state.update { it.copy(previewMs = ms) }
    /** Choosing another person invalidates the tracking result. */
    fun select(i: Int?) = _state.update { it.copy(selected = i, steps = null, path = null) }
    fun setShape(v: FrameShape) = _state.update { it.copy(shape = v, path = buildPath(it.steps, it.info, v, it.zoom)) }
    fun setZoom(v: Float) = _state.update { it.copy(zoom = v, path = buildPath(it.steps, it.info, it.shape, v)) }

    private fun buildPath(steps: List<Step>?, info: VideoInfo?, shape: FrameShape, zoom: Float): CropPath? =
        if (steps == null || info == null) null
        else CropPath.build(steps, info.width, info.height, FPS, CameraConfig(aspect = shape.aspect, baseZoom = zoom.toDouble()), null, savgolWindow = 7)

    override fun onCleared() { previewer?.close() }

    private fun loadStrip(uri: Uri, info: VideoInfo, step: Long): List<Bitmap> = runCatching {
        val r = MediaMetadataRetriever().apply { setDataSource(getApplication(), uri) }
        val h = 120; val w = maxOf(1, h * info.width / info.height)
        val out = (0 until info.durationMs step step).mapNotNull {
            r.getScaledFrameAtTime(it * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
        }
        r.release()
        out
    }.getOrDefault(emptyList())

    fun setTool(t: Tool?) = _state.update { it.copy(tool = t) }

    /** Open a previously exported video on the result screen. */
    fun openOutput(f: File) = _state.update { it.copy(phase = Phase.DONE, output = f, saved = false) }

    private fun loadPreview(uri: Uri): Pair<Bitmap?, VideoInfo?> = runCatching {
        val r = MediaMetadataRetriever().apply { setDataSource(getApplication(), uri) }
        val bmp = r.getFrameAtTime(0)
        var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
        var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
        if (rot == 90 || rot == 270) w = h.also { h = w }
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        r.release()
        bmp to VideoInfo(w, h, d)
    }.getOrDefault(null to null)

    fun reset() = _state.update { UiState(recents = listRecents(getApplication()), shape = it.shape, zoom = it.zoom) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun cancel() {
        job?.cancel()
        transformer?.cancel()
        _state.update { it.copy(phase = Phase.READY, message = "Đã huỷ") }
    }

    /** Step 1: follow the chosen person through the whole clip. Shows the result on the preview for checking. */
    fun track() {
        val s = _state.value
        val src = s.source ?: return
        val info = s.info ?: return
        val target = s.selected?.let { s.people.getOrNull(it) }?.box
        if (target == null) { _state.update { it.copy(message = "Chạm vào người cần theo dõi trước.") }; return }
        _state.update { it.copy(phase = Phase.WORKING, stage = "Đang theo dõi người đã chọn…", progress = 0f, message = null) }
        job = viewModelScope.launch {
            try {
                val steps = withContext(Dispatchers.Default) {
                    FrameAnalyzer(getApplication(), usePose = false, sports = false, gpu = true).use { fa ->
                        val out = fa.analyse(src, info, FPS, target, s.previewMs, check = { ensureActive() }) { p ->
                            _state.update { it.copy(progress = p) }
                        }
                        Log.i(TAG, fa.timings.summary(fa.onGpu))
                        _state.update { it.copy(stats = fa.timings.summary(fa.onGpu)) }
                        out
                    }
                }
                val pct = if (steps.isEmpty()) 0 else 100 * steps.count { it.found } / steps.size
                Log.i(TAG, "tracked $pct% of ${steps.size} steps")
                _state.update {
                    it.copy(phase = Phase.READY, steps = steps, trackedPct = pct, progress = 1f,
                        path = buildPath(steps, info, it.shape, it.zoom),
                        message = if (pct < 60) "Chỉ theo dõi được $pct% khung hình. Kiểm tra lại trên timeline, hoặc chọn người ở đoạn rõ hơn." else null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "tracking failed", e)
                val hint = if (e.message?.contains("asset", true) == true || e is java.io.FileNotFoundException)
                    " (thiếu model? chạy app/fetch_models.sh)" else ""
                _state.update { it.copy(phase = Phase.READY, message = "Lỗi: ${e.message}$hint") }
            }
        }
    }

    /** Step 2: render the video with the tracked camera path. */
    fun export() {
        val s = _state.value
        val src = s.source ?: return
        val info = s.info ?: return
        val path = s.path ?: return
        _state.update { it.copy(phase = Phase.WORKING, stage = "Đang xuất video ${s.shape.label}…", progress = 0f, message = null) }
        try {
            val ctx = getApplication<Application>()
            val dir = ctx.getExternalFilesDir(null)!!.also { it.mkdirs() }
            val out = File(dir, "beatcam_${System.currentTimeMillis()}.mp4")
            transformer = Reframer(ctx).export(src, out.path, path, info.width, info.height, s.shape,
                onProgress = { p -> _state.update { if (it.phase == Phase.WORKING) it.copy(progress = p) else it } },
                onDone = { r ->
                    _state.update {
                        r.fold(
                            { _ -> it.copy(phase = Phase.DONE, output = out, saved = false, progress = 1f, recents = listRecents(getApplication())) },
                            { e -> Log.e(TAG, "export failed", e); it.copy(phase = Phase.READY, message = "Xuất video thất bại: ${e.message}") },
                        )
                    }
                })
        } catch (e: Exception) {
            Log.e(TAG, "export failed", e)
            _state.update { it.copy(phase = Phase.READY, message = "Lỗi: ${e.message}") }
        }
    }

    fun saveToGallery() {
        val f = _state.value.output ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/BeatCam")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            val ok = uri != null && runCatching {
                ctx.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            }.isSuccess
            _state.update { it.copy(saved = ok, message = if (ok) "Đã lưu vào thư viện (Movies/BeatCam)" else "Không lưu được video") }
        }
    }

    fun shareIntent(): Intent? {
        val f = _state.value.output ?: return null
        val ctx = getApplication<Application>()
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Chia sẻ video")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
