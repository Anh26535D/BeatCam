package com.beatcam.app

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.transformer.Transformer
import com.beatcam.core.Box
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

data class UiState(
    val phase: Phase = Phase.IDLE,
    val source: Uri? = null,
    val thumb: Bitmap? = null,
    val info: VideoInfo? = null,
    val beat: Boolean = true,
    val pose: Boolean = false,
    val sports: Boolean = false,
    val shape: FrameShape = FrameShape.PORTRAIT_9_16,
    val zoom: Float = 1.25f,
    val people: List<Box> = emptyList(),
    val selected: Int? = null,
    val previewMs: Long = 0,
    val detecting: Boolean = false,
    val stage: String = "",
    val progress: Float = 0f,
    val output: File? = null,
    val saved: Boolean = false,
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var job: Job? = null
    private var previewJob: Job? = null
    private var previewer: FrameAnalyzer? = null
    private var transformer: Transformer? = null

    fun pick(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val (thumb, info) = withContext(Dispatchers.IO) { loadPreview(uri) }
            _state.update { it.copy(phase = Phase.READY, source = uri, thumb = thumb, info = info, people = emptyList(), selected = null, previewMs = 0) }
            seek(0)
        }
    }

    /** Show the frame at [ms] and detect the people in it so the user can tap one. */
    fun seek(ms: Long) {
        val src = _state.value.source ?: return
        val info = _state.value.info ?: return
        previewJob?.cancel()
        _state.update { it.copy(previewMs = ms, detecting = true) }
        previewJob = viewModelScope.launch {
            val (bmp, boxes) = try {
                withContext(Dispatchers.Default) {
                    val fa = previewer ?: FrameAnalyzer(getApplication(), false, false).also { previewer = it }
                    fa.peopleAt(src, info, ms)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _state.update { it.copy(detecting = false, message = "Không nhận diện được người: ${e.message}") }
                return@launch
            }
            _state.update {
                // default to the biggest person; the user taps to pick someone else
                val big = boxes.indices.maxByOrNull { i -> boxes[i].w * boxes[i].h }
                it.copy(thumb = bmp ?: it.thumb, people = boxes, selected = big, detecting = false)
            }
        }
    }

    fun previewTime(ms: Long) = _state.update { it.copy(previewMs = ms) }
    fun select(i: Int?) = _state.update { it.copy(selected = i) }
    fun setShape(v: FrameShape) = _state.update { it.copy(shape = v) }
    fun setZoom(v: Float) = _state.update { it.copy(zoom = v) }

    override fun onCleared() { previewer?.close() }

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

    fun reset() = _state.update { UiState(beat = it.beat, pose = it.pose, sports = it.sports, shape = it.shape, zoom = it.zoom) }
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
                    FrameAnalyzer(ctx, s.pose, s.sports).use { fa ->
                        val info = fa.info(src)
                        val target = s.selected?.let { s.people.getOrNull(it) }
                        val steps = fa.analyse(src, info, fps, target, s.previewMs, check = { ensureActive() }) { p ->
                            _state.update { it.copy(progress = 0.05f + 0.55f * p) }
                        }
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
                                { _ -> it.copy(phase = Phase.DONE, output = out, saved = false, progress = 1f) },
                                { e -> it.copy(phase = Phase.READY, message = "Xuất video thất bại: ${e.message}") },
                            )
                        }
                    })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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
