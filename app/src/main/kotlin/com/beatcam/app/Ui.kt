package com.beatcam.app

import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.beatcam.core.Box as SrcBox
import com.beatcam.core.FrameShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val Bg = Color(0xFF0D0E12)
private val Card = Color(0xFF17181E)
private val Accent = Color(0xFFFF4D6D)
private val Accent2 = Color(0xFF7B5CFF)
private val Muted = Color(0xFF9A9CA8)

@Composable
fun BeatCamTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Accent, secondary = Accent2, background = Bg, surface = Card, onSurface = Color.White),
    content = content,
)

@Composable
fun BeatCamScreen(vm: MainViewModel, onShare: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { vm.pick(it) }
    val pickVideo = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }

    Surface(Modifier.fillMaxSize(), color = Bg) {
        Column(
            Modifier.statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header()
            when (s.phase) {
                Phase.IDLE -> PickCard(pickVideo)
                Phase.READY -> {
                    PersonPicker(s, vm)
                    ShapeCard(s, vm)
                    OptionsCard(s, vm)
                    PrimaryButton("Tạo video ${s.shape.label}", onClick = vm::start)
                    TextButton(pickVideo, Modifier.fillMaxWidth()) { Text("Chọn video khác", color = Muted) }
                }
                Phase.WORKING -> WorkingCard(s, vm::cancel)
                Phase.DONE -> ResultCard(s, vm, onShare)
            }
            s.message?.let { msg ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, Modifier.weight(1f), color = Color.White, fontSize = 14.sp)
                    TextButton(vm::dismissMessage) { Text("Đóng", color = Accent) }
                }
            }
        }
    }
}

@Composable
private fun Header() = Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text("BeatCam", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
    Text("Chọn một người, chọn khung hình, BeatCam tự bám theo người đó và giật zoom theo nhịp nhạc.", color = Muted, fontSize = 14.sp)
}

@Composable
private fun PickCard(onPick: () -> Unit) = Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card)
        .border(1.dp, Color(0xFF2A2C36), RoundedCornerShape(20.dp)).clickable(onClick = onPick).padding(vertical = 48.dp, horizontal = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
) {
    Box(Modifier.size(64.dp).clip(RoundedCornerShape(32.dp)).background(Brush.linearGradient(listOf(Accent, Accent2))),
        contentAlignment = Alignment.Center) { Text("+", fontSize = 34.sp, color = Color.White, fontWeight = FontWeight.Bold) }
    Text("Chọn video", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    Text("Chạm để chọn video từ thư viện", color = Muted, fontSize = 14.sp)
}

@Composable
private fun PersonPicker(s: UiState, vm: MainViewModel) = Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
) {
    Text("1. Chạm vào người cần theo dõi", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    val info = s.info
    if (info != null) {
        Box(Modifier.fillMaxWidth().aspectRatio(info.width.toFloat() / info.height).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
            s.thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
            Canvas(Modifier.fillMaxSize().pointerInput(s.people, info) {
                detectTapGestures { p ->
                    val x = p.x / size.width * info.width; val y = p.y / size.height * info.height
                    // smallest box containing the tap wins, so people standing in front of others stay selectable
                    val hit = s.people.withIndex().filter { (_, b) -> x in b.x1..b.x2 && y in b.y1..b.y2 }.minByOrNull { (_, b) -> b.w * b.h }
                    hit?.let { vm.select(it.index) }
                }
            }) {
                val kx = size.width / info.width; val ky = size.height / info.height
                val sel = s.selected?.let { s.people.getOrNull(it) }
                if (sel != null) {
                    val c = cropAround(sel, info.width, info.height, s.shape, s.zoom)
                    val dim = Color(0xAA000000)
                    val l = (c.x1 * kx).toFloat(); val t = (c.y1 * ky).toFloat(); val r = (c.x2 * kx).toFloat(); val b = (c.y2 * ky).toFloat()
                    drawRect(dim, Offset.Zero, Size(size.width, t))
                    drawRect(dim, Offset(0f, b), Size(size.width, size.height - b))
                    drawRect(dim, Offset(0f, t), Size(l, b - t))
                    drawRect(dim, Offset(r, t), Size(size.width - r, b - t))
                    drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(2.dp.toPx()))
                }
                s.people.forEachIndexed { i, p ->
                    val chosen = i == s.selected
                    drawRect(if (chosen) Accent else Color.White.copy(alpha = 0.8f), Offset((p.x1 * kx).toFloat(), (p.y1 * ky).toFloat()),
                        Size((p.w * kx).toFloat(), (p.h * ky).toFloat()), style = Stroke(if (chosen) 4.dp.toPx() else 2.dp.toPx()))
                }
            }
        }
        val sec = info.durationMs / 1000
        Slider(
            value = s.previewMs.toFloat(), onValueChange = { vm.previewTime(it.toLong()) }, onValueChangeFinished = { vm.seek(s.previewMs) },
            valueRange = 0f..maxOf(info.durationMs - 100, 1).toFloat(),
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
        )
        val status = when {
            s.detecting -> "Đang tìm người trong khung hình…"
            s.people.isEmpty() -> "Không thấy ai ở thời điểm này. Kéo thanh trượt sang đoạn có người."
            s.selected == null -> "Chạm vào khung để chọn người."
            else -> "Đã chọn 1 người • ${info.width}×${info.height} • ${sec / 60}:${"%02d".format(sec % 60)}"
        }
        Text(status, color = Muted, fontSize = 13.sp)
    } else Text("Không đọc được video này.", color = Muted)
}

/** Crop box (in source pixels) the camera would use when resting on [subject]; mirrors VirtualCamera.size. */
private fun cropAround(subject: SrcBox, srcW: Int, srcH: Int, shape: FrameShape, zoom: Float): SrcBox {
    val h = minOf(srcH / maxOf(zoom.toDouble(), 1.0), srcW / shape.aspect)
    val w = minOf(h * shape.aspect, srcW.toDouble())
    val x = (subject.cx - w / 2).coerceAtLeast(0.0).coerceAtMost(maxOf(srcW - w, 0.0))
    val y = (subject.cy - h / 2).coerceAtLeast(0.0).coerceAtMost(maxOf(srcH - h, 0.0))
    return SrcBox(x, y, x + w, y + h)
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ShapeCard(s: UiState, vm: MainViewModel) = Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
) {
    Text("2. Chọn khung hình xuất ra", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FrameShape.values().forEach { sh ->
            FilterChip(
                selected = sh == s.shape, onClick = { vm.setShape(sh) }, label = { Text(sh.label) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Color.White),
            )
        }
    }
    Text("Độ gần: ${"%.1f".format(s.zoom)}×", color = Color.White, fontSize = 14.sp)
    Slider(
        value = s.zoom, onValueChange = vm::setZoom, valueRange = 1f..3f,
        colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
    )
    Text("Khung sáng trên ảnh là vùng sẽ được giữ lại và bám theo người đã chọn.", color = Muted, fontSize = 13.sp)
}

@Composable
private fun OptionsCard(s: UiState, vm: MainViewModel) = Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(vertical = 6.dp),
) {
    OptionRow("Giật zoom theo nhạc", "Zoom nhanh 5–10% ở mỗi nhịp trống kick và snare", s.beat, vm::setBeat)
    OptionRow("Nhận diện tư thế", "Mở rộng khung khi bạn giơ tay, dang tay hoặc ngồi thấp", s.pose, vm::setPose)
    OptionRow("Thể thao (bóng)", "Theo bóng và đón đầu người nhận bóng", s.sports, vm::setSports)
}

@Composable
private fun OptionRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) = Row(
    Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
) {
    Column(Modifier.weight(1f)) {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(desc, color = Muted, fontSize = 13.sp)
    }
    Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Accent, checkedThumbColor = Color.White))
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) = Button(
    onClick, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp),
    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold) }

@Composable
private fun WorkingCard(s: UiState, onCancel: () -> Unit) = Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
) {
    Text(s.stage, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    LinearProgressIndicator(
        progress = { s.progress }, Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
        color = Accent, trackColor = Color(0xFF2A2C36),
    )
    Text("${(s.progress * 100).toInt()}% — đừng tắt app trong lúc xử lý", color = Muted, fontSize = 13.sp)
    OutlinedButton(onCancel, Modifier.fillMaxWidth()) { Text("Huỷ", color = Color.White) }
}

@Composable
private fun ResultCard(s: UiState, vm: MainViewModel, onShare: () -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Text("Xong rồi!", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(max = 280.dp).aspectRatio(9f / 16).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
            s.output?.let { f ->
                AndroidView(factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoPath(f.path)
                        setOnPreparedListener { it.isLooping = true; start() }
                    }
                }, modifier = Modifier.fillMaxSize())
            }
        }
    }
    PrimaryButton(if (s.saved) "Đã lưu vào thư viện" else "Lưu vào thư viện", onClick = { if (!s.saved) vm.saveToGallery() })
    OutlinedButton(onShare, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text("Chia sẻ", color = Color.White) }
    TextButton(vm::reset, Modifier.fillMaxWidth()) { Text("Làm video khác", color = Muted) }
}
