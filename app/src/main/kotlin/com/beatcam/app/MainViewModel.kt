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
import com.beatcam.core.PunchEnvelope
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
enum class Tool(val label: String) { SUBJECT("Chủ thể"), FRAME("Khung hình"), BEAT("Nhịp nhạc"), POSE("Tư thế"), BALL("Bóng") }

data class UiState(
    val phase: Phase = Phase.IDLE,
    val source: Uri? = null,
    val thumb: Bitmap? = null,
    val info: VideoInfo? = null,
    val beat: Boolean = true,
    val pose: Boolean = false,
    val sports: Boolean = false,
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
            _state.update { it.copy(phase = Phase.READY, source = uri, thumb = thumb, info = info, people = emptyList(), selected = null, previewMs = 0, thumbs = emptyList(), thumbStepMs = step, tool = Tool.SUBJECT) }
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
    fun select(i: Int?) = _state.update { it.copy(selected = i) }
    fun setShape(v: FrameShape) = _state.update { it.copy(shape = v) }
    fun setZoom(v: Float) = _state.update { it.copy(zoom = v) }

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

    fun setBeat(v: Boolean) = _state.update { it.copy(beat = v) }
    fun setPose(v: Boolean) = _state.update { it.copy(pose = v) }
    fun setSports(v: Boolean) = _state.update { it.copy(sports = v) }

    fun reset() = _state.update { UiState(recents = listRecents(getApplication()), beat = it.beat, pose = it.pose, sports = it.sports, shape = it.shape, zoom = it.zoom) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun cancel() {
        job?.cancel()
        transformer?.cancel()
        _state.update { it.copy(phase = Phase.READY, message = "Đã huỷ") }
    }

    fun start() {
        val s = _state.value
        val src = s.source ?: return
        _state.update { it.copy(phase = Phase.WORKING, stage = "Đang phân tích âm thanh…", progress = 0f, message = null) }
        job = viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val fps = 15.0
                val (path, info) = withContext(Dispatchers.Default) {
                    val punch = if (s.beat) PunchEnvelope(AudioAnalyzer.onsets(ctx, src), 30.0) else null
                    _state.update { it.copy(stage = "Đang nhận diện chuyển động…", progress = 0.05f) }
                    FrameAnalyzer(ctx, s.pose, s.sports, gpu = true).use { fa ->
                        val info = fa.info(src)
                        val target = s.selected?.let { s.people.getOrNull(it) }?.box
                        val steps = fa.analyse(src, info, fps, target, s.previewMs, check = { ensureActive() }) { p ->
                            _state.update { it.copy(progress = 0.05f + 0.55f * p) }
                        }
                        _state.update { it.copy(stats = fa.timings.summary(fa.onGpu)) }
                        Log.i(TAG, fa.timings.summary(fa.onGpu))
                        CropPath.build(steps, info.width, info.height, fps, CameraConfig(aspect = s.shape.aspect, baseZoom = s.zoom.toDouble()), punch, savgolWindow = 7) to info
                    }
                }
                _state.update { it.copy(stage = "Đang xuất video ${s.shape.label}…", progress = 0.6f) }
                val dir = ctx.getExternalFilesDir(null)!!.also { it.mkdirs() }
                val out = File(dir, "beatcam_${System.currentTimeMillis()}.mp4")
                transformer = Reframer(ctx).export(src, out.path, path, info.width, info.height, s.shape,
                    onProgress = { p -> _state.update { if (it.phase == Phase.WORKING) it.copy(progress = 0.6f + 0.4f * p) else it } },
                    onDone = { r ->
                        _state.update {
                            r.fold(
                                { _ -> it.copy(phase = Phase.DONE, output = out, saved = false, progress = 1f, recents = listRecents(getApplication())) },
                                { e -> it.copy(phase = Phase.READY, message = "Xuất video thất bại: ${e.message}") },
                            )
                        }
                    })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "processing failed", e)
                val hint = if (e.message?.contains("asset", true) == true || e is java.io.FileNotFoundException)
                    " (thiếu model? chạy app/fetch_models.sh)" else ""
                _state.update { it.copy(phase = Phase.READY, message = "Lỗi: ${e.message}$hint") }
            }
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
