package com.beatcam.app

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beatcam.core.FrameShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import java.io.File
import com.beatcam.core.Box as SrcBox

// One UI-style palette: pure black canvas, dark-grey rounded surfaces, blue accent.
private val Black = Color(0xFF000000)
private val Surf = Color(0xFF1C1C1E)
private val Surf2 = Color(0xFF2C2C2E)
private val Blue = Color(0xFF3E91FF)
private val Gray = Color(0xFF8E8E93)
private val Pick = Color(0xFFFFD60A)

@Composable
fun BeatCamTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Blue, secondary = Blue, background = Black, surface = Surf, onSurface = Color.White),
    content = content,
)

private fun fmt(ms: Long): String { val s = ms / 1000; return "${s / 60}:${"%02d".format(s % 60)}" }

@Composable
fun BeatCamApp(vm: MainViewModel, onShare: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    BackHandler(enabled = s.phase != Phase.IDLE) { if (s.phase == Phase.WORKING) vm.cancel() else vm.reset() }
    Surface(Modifier.fillMaxSize(), color = Black) {
        Box(Modifier.fillMaxSize()) {
            when (s.phase) {
                Phase.IDLE -> HomeScreen(s, vm)
                Phase.READY, Phase.WORKING -> EditorScreen(s, vm)
                Phase.DONE -> ResultScreen(s, vm, onShare)
            }
            if (s.phase == Phase.WORKING) WorkingOverlay(s, vm::cancel)
            s.message?.let { msg ->
                Row(
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp)).background(Surf2).padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(msg, Modifier.weight(1f), color = Color.White, fontSize = 14.sp)
                    TextButton(vm::dismissMessage) { Text("Đóng", color = Blue) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Home

@Composable
private fun HomeScreen(s: UiState, vm: MainViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { vm.pick(it) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(56.dp))
        Text("Trình chỉnh sửa\nvideo", fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text("Tự bám theo chủ thể và giật zoom theo nhịp nhạc", color = Gray, fontSize = 14.sp)
        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Surf)
                .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }.padding(22.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(Blue), contentAlignment = Alignment.Center) { PlusIcon() }
            Column {
                Text("Dự án mới", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Chọn một video từ thư viện", color = Gray, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(28.dp))
        Text("Gần đây", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        if (s.recents.isEmpty()) {
            Text("Chưa có video nào. Video bạn xuất ra sẽ hiện ở đây.", color = Gray, fontSize = 14.sp)
        } else {
            LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(s.recents) { f -> RecentThumb(f) { vm.openOutput(f) } }
            }
        }
    }
}

@Composable
private fun RecentThumb(f: File, onClick: () -> Unit) {
    val bmp by produceState<Bitmap?>(null, f) {
        value = withContext(Dispatchers.IO) {
            runCatching { MediaMetadataRetriever().run { setDataSource(f.path); getFrameAtTime(0).also { release() } } }.getOrNull()
        }
    }
    Box(Modifier.aspectRatio(3f / 4).clip(RoundedCornerShape(16.dp)).background(Surf).clickable(onClick = onClick)) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

// ---------------------------------------------------------------- Editor

@Composable
private fun EditorScreen(s: UiState, vm: MainViewModel) {
    var playing by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    val info = s.info
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar("Dự án mới", onBack = vm::reset, action = "Xuất", onAction = { playing = false; vm.start() })
        if (info == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Không đọc được video này.", color = Gray) }
            return@Column
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(info.width.toFloat() / info.height).clip(RoundedCornerShape(12.dp)).background(Black)) {
                Preview(s, vm, playing, scrubMs) { playing = false }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { if (playing) vm.seek(s.previewMs); playing = !playing }, contentAlignment = Alignment.Center) {
                PlayIcon(playing)
            }
            Spacer(Modifier.weight(1f))
            Text("${fmt(s.previewMs)} / ${fmt(info.durationMs)}", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(44.dp))
        }
        Timeline(
            s.thumbs, s.thumbStepMs, info.durationMs, followMs = if (playing) s.previewMs else null,
            onScrub = { playing = false; scrubMs = it; vm.previewTime(it) },
            onSettle = { scrubMs = null; vm.seek(it) },
        )
        Spacer(Modifier.height(10.dp))
        s.tool?.let { ToolPanel(it, s, vm) }
        ToolBar(s, vm)
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit, action: String?, onAction: () -> Unit) = Row(
    Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) { BackIcon() }
    Text(title, Modifier.weight(1f).padding(horizontal = 4.dp), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    if (action != null) {
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(Blue).clickable(onClick = onAction).padding(horizontal = 20.dp, vertical = 9.dp),
        ) { Text(action, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun Preview(s: UiState, vm: MainViewModel, playing: Boolean, scrubMs: Long?, onEnded: () -> Unit) {
    val info = s.info ?: return
    if (playing) {
        var view by remember { mutableStateOf<VideoView?>(null) }
        AndroidView(factory = { ctx ->
            VideoView(ctx).apply {
                setVideoURI(s.source)
                setOnPreparedListener { seekTo(s.previewMs.toInt()); start() }
                setOnCompletionListener { onEnded() }
                view = this
            }
        }, modifier = Modifier.fillMaxSize())
        LaunchedEffect(view) {
            while (true) { view?.let { if (it.isPlaying) vm.previewTime(it.currentPosition.toLong()) }; delay(100) }
        }
        return
    }
    val frame = if (scrubMs != null && s.thumbs.isNotEmpty()) s.thumbs[(scrubMs / s.thumbStepMs).toInt().coerceIn(0, s.thumbs.size - 1)] else s.thumb
    frame?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
    if (scrubMs != null) return
    Canvas(Modifier.fillMaxSize().pointerInput(s.people, info) {
        detectTapGestures { p ->
            val x = p.x / size.width * info.width; val y = p.y / size.height * info.height
            // smallest box containing the tap wins, so people standing in front of others stay selectable
            s.people.withIndex().filter { (_, d) -> x in d.box.x1..d.box.x2 && y in d.box.y1..d.box.y2 }
                .minByOrNull { (_, d) -> d.box.w * d.box.h }?.let { vm.select(it.index) }
        }
    }) {
        val kx = size.width / info.width; val ky = size.height / info.height
        s.selected?.let { s.people.getOrNull(it) }?.let { selDet ->
            val c = cropAround(selDet.box, info.width, info.height, s.shape, s.zoom)
            val dim = Color(0xAA000000)
            val l = (c.x1 * kx).toFloat(); val t = (c.y1 * ky).toFloat(); val r = (c.x2 * kx).toFloat(); val b = (c.y2 * ky).toFloat()
            drawRect(dim, Offset.Zero, Size(size.width, t))
            drawRect(dim, Offset(0f, b), Size(size.width, size.height - b))
            drawRect(dim, Offset(0f, t), Size(l, b - t))
            drawRect(dim, Offset(r, t), Size(size.width - r, b - t))
            drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(2.dp.toPx()))
        }
        s.people.forEachIndexed { i, det ->
            val p = det.box
            val chosen = i == s.selected
            drawRect(if (chosen) Pick else Color.White.copy(alpha = 0.85f), Offset((p.x1 * kx).toFloat(), (p.y1 * ky).toFloat()),
                Size((p.w * kx).toFloat(), (p.h * ky).toFloat()), style = Stroke(if (chosen) 3.dp.toPx() else 1.5.dp.toPx()))
        }
    }
    if (s.detecting) Text("Đang tìm người…", Modifier.padding(10.dp), color = Color.White, fontSize = 12.sp)
}

/** Crop box (in source pixels) the camera would use when resting on [subject]; mirrors VirtualCamera.size. */
private fun cropAround(subject: SrcBox, srcW: Int, srcH: Int, shape: FrameShape, zoom: Float): SrcBox {
    val h = minOf(srcH / maxOf(zoom.toDouble(), 1.0), srcW / shape.aspect)
    val w = minOf(h * shape.aspect, srcW.toDouble())
    val x = (subject.cx - w / 2).coerceAtLeast(0.0).coerceAtMost(maxOf(srcW - w, 0.0))
    val y = (subject.cy - h / 2).coerceAtLeast(0.0).coerceAtMost(maxOf(srcH - h, 0.0))
    return SrcBox(x, y, x + w, y + h)
}

/** Thumbnail strip that scrolls under a fixed centre playhead, like the Samsung editor timeline. */
@Composable
private fun Timeline(
    thumbs: List<Bitmap>, stepMs: Long, durationMs: Long, followMs: Long?,
    onScrub: (Long) -> Unit, onSettle: (Long) -> Unit,
) {
    val scroll = rememberScrollState()
    val thumbW = 56.dp
    val pxPerStep = with(LocalDensity.current) { thumbW.toPx() }
    fun msAt(px: Int) = (px / pxPerStep * stepMs).toLong().coerceIn(0L, maxOf(durationMs, 0L))
    LaunchedEffect(scroll) {
        androidx.compose.runtime.snapshotFlow { scroll.value to scroll.isScrollInProgress }.collect { (v, moving) -> if (moving) onScrub(msAt(v)) }
    }
    LaunchedEffect(scroll) {
        androidx.compose.runtime.snapshotFlow { scroll.isScrollInProgress }.drop(1).collect { moving -> if (!moving) onSettle(msAt(scroll.value)) }
    }
    LaunchedEffect(followMs) {
        if (followMs != null && !scroll.isScrollInProgress) scroll.scrollTo((followMs / stepMs.toFloat() * pxPerStep).toInt())
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp)) {
        val full = maxWidth
        val half = full / 2
        Row(Modifier.horizontalScroll(scroll).padding(horizontal = half)) {
            thumbs.forEach { Image(it.asImageBitmap(), null, Modifier.width(thumbW).fillMaxHeight(), contentScale = ContentScale.Crop) }
            if (thumbs.isEmpty()) Box(Modifier.width(full).fillMaxHeight().background(Surf))
        }
        Box(Modifier.align(Alignment.Center).width(3.dp).fillMaxHeight().background(Color.White, RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun ToolBar(s: UiState, vm: MainViewModel) = Row(
    Modifier.fillMaxWidth().background(Black).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.SpaceEvenly,
) {
    Tool.values().forEach { t ->
        val on = when (t) { Tool.BEAT -> s.beat; Tool.POSE -> s.pose; Tool.BALL -> s.sports; else -> false }
        val color = if (s.tool == t) Blue else Color.White
        Column(
            Modifier.width(76.dp).clip(RoundedCornerShape(14.dp)).clickable { vm.setTool(if (s.tool == t) null else t) }.padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(contentAlignment = Alignment.TopEnd) {
                ToolIcon(t, color)
                if (on) Box(Modifier.size(8.dp).clip(CircleShape).background(Blue))
            }
            Text(t.label, color = color, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ToolPanel(tool: Tool, s: UiState, vm: MainViewModel) = Column(
    Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(24.dp)).background(Surf).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
) {
    when (tool) {
        Tool.SUBJECT -> {
            Text("Chọn người cần theo dõi", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                when {
                    s.detecting -> "Đang tìm người trong khung hình…"
                    s.people.isEmpty() -> "Không thấy ai ở thời điểm này. Kéo timeline sang đoạn có người."
                    s.selected == null -> "Chạm vào người trong video để chọn."
                    else -> "Đã chọn 1 người (khung vàng). Chạm người khác để đổi."
                },
                color = Gray, fontSize = 13.sp,
            )
        }
        Tool.FRAME -> {
            Text("Khung hình xuất ra", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FrameShape.values().forEach { sh ->
                    FilterChip(
                        selected = sh == s.shape, onClick = { vm.setShape(sh) }, label = { Text(sh.label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Blue, selectedLabelColor = Color.White),
                    )
                }
            }
            Text("Độ gần: ${"%.1f".format(s.zoom)}×", color = Color.White, fontSize = 14.sp)
            Slider(s.zoom, vm::setZoom, valueRange = 1f..3f, colors = SliderDefaults.colors(thumbColor = Blue, activeTrackColor = Blue))
        }
        Tool.BEAT -> SwitchRow("Giật zoom theo nhịp nhạc", "Zoom nhanh 5–10% ở mỗi nhịp trống kick và snare", s.beat, vm::setBeat)
        Tool.POSE -> SwitchRow("Nhận diện tư thế", "Mở rộng khung khi giơ tay, dang tay hoặc ngồi thấp", s.pose, vm::setPose)
        Tool.BALL -> SwitchRow("Theo bóng (thể thao)", "Theo bóng và đón đầu người nhận bóng", s.sports, vm::setSports)
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) = Row(
    Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
) {
    Column(Modifier.weight(1f)) {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(desc, color = Gray, fontSize = 13.sp)
    }
    Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Blue, checkedThumbColor = Color.White))
}

@Composable
private fun WorkingOverlay(s: UiState, onCancel: () -> Unit) = Box(
    Modifier.fillMaxSize().background(Color(0xCC000000)).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center,
) {
    Column(Modifier.padding(32.dp).clip(RoundedCornerShape(28.dp)).background(Surf).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Đang xử lý video", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(s.stage, color = Gray, fontSize = 14.sp)
        LinearProgressIndicator(
            progress = { s.progress }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Blue, trackColor = Surf2,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${(s.progress * 100).toInt()}%", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onCancel) { Text("Huỷ", color = Blue, fontWeight = FontWeight.SemiBold) }
        }
    }
}

// ---------------------------------------------------------------- Result

@Composable
private fun ResultScreen(s: UiState, vm: MainViewModel, onShare: () -> Unit) {
    val f = s.output ?: return
    val ar by produceState(9f / 16, f) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().run {
                    setDataSource(f.path)
                    val w = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toFloat()
                    val h = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toFloat()
                    release(); w / h
                }
            }.getOrDefault(9f / 16)
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar("Video của bạn", onBack = vm::reset, action = null, onAction = {})
        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(ar).clip(RoundedCornerShape(16.dp)).background(Black)) {
                AndroidView(factory = { ctx ->
                    VideoView(ctx).apply { setVideoPath(f.path); setOnPreparedListener { it.isLooping = true; start() } }
                }, modifier = Modifier.fillMaxSize())
            }
        }
        s.stats?.let { Text(it, Modifier.padding(horizontal = 20.dp), color = Gray, fontSize = 11.sp) }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(if (s.saved) "Đã lưu" else "Lưu vào thư viện", Blue, Modifier.weight(1f)) { if (!s.saved) vm.saveToGallery() }
            PillButton("Chia sẻ", Surf2, Modifier.weight(1f), onShare)
        }
    }
}

@Composable
private fun PillButton(text: String, bg: Color, modifier: Modifier, onClick: () -> Unit) = Box(
    modifier.height(52.dp).clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center,
) { Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
