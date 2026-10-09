package com.beatcam.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Small line icons drawn with Canvas (keeps the APK free of the huge material-icons-extended artifact). */
@Composable
fun ToolIcon(tool: Tool, tint: Color, size: Dp = 26.dp) = Canvas(Modifier.size(size)) {
    val w = this.size.width
    val st = Stroke(width = w * 0.08f, cap = StrokeCap.Round)
    when (tool) {
        Tool.SUBJECT -> { // person inside corner brackets
            drawCircle(tint, w * 0.13f, Offset(w * 0.5f, w * 0.38f), style = st)
            drawArc(tint, 200f, 140f, false, Offset(w * 0.27f, w * 0.55f), Size(w * 0.46f, w * 0.4f), style = st)
            bracket(tint, st, w)
        }
        Tool.FRAME -> { // crop
            drawLine(tint, Offset(w * 0.3f, w * 0.08f), Offset(w * 0.3f, w * 0.7f), st.width, StrokeCap.Round)
            drawLine(tint, Offset(w * 0.3f, w * 0.7f), Offset(w * 0.92f, w * 0.7f), st.width, StrokeCap.Round)
            drawLine(tint, Offset(w * 0.7f, w * 0.92f), Offset(w * 0.7f, w * 0.3f), st.width, StrokeCap.Round)
            drawLine(tint, Offset(w * 0.7f, w * 0.3f), Offset(w * 0.08f, w * 0.3f), st.width, StrokeCap.Round)
        }
        Tool.BEAT -> { // equaliser bars
            listOf(0.25f to 0.55f, 0.4f to 0.85f, 0.55f to 0.4f, 0.7f to 0.7f, 0.85f to 0.5f).forEach { (x, h) ->
                drawLine(tint, Offset(w * x - w * 0.1f, w * (0.5f + h / 2)), Offset(w * x - w * 0.1f, w * (0.5f - h / 2)), st.width, StrokeCap.Round)
            }
        }
        Tool.POSE -> { // stick figure
            drawCircle(tint, w * 0.09f, Offset(w * 0.5f, w * 0.17f), style = st)
            drawLine(tint, Offset(w * 0.5f, w * 0.28f), Offset(w * 0.5f, w * 0.6f), st.width, StrokeCap.Round)
            drawLine(tint, Offset(w * 0.22f, w * 0.2f), Offset(w * 0.5f, w * 0.38f), st.width, StrokeCap.Round)
            drawLine(tint, Offset(w * 0.78f, w * 0.2f), Offset(w * 0.5f, w * 0.38f), st.width, StrokeCap.Round)
            drawLine(tint, Offset(w * 0.5f, w * 0.6f), Offset(w * 0.3f, w * 0.92f), st.width, StrokeCap.Round)
            drawLine(tint, Offset(w * 0.5f, w * 0.6f), Offset(w * 0.7f, w * 0.92f), st.width, StrokeCap.Round)
        }
        Tool.BALL -> {
            drawCircle(tint, w * 0.4f, Offset(w * 0.5f, w * 0.5f), style = st)
            drawArc(tint, 20f, 140f, false, Offset(w * 0.1f, w * -0.2f), Size(w * 0.8f, w * 0.8f), style = st)
            drawArc(tint, 200f, 140f, false, Offset(w * 0.1f, w * 0.4f), Size(w * 0.8f, w * 0.8f), style = st)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.bracket(tint: Color, st: Stroke, w: Float) {
    val l = w * 0.2f
    for ((cx, cy, dx, dy) in listOf(listOf(0.06f, 0.06f, 1f, 1f), listOf(0.94f, 0.06f, -1f, 1f), listOf(0.06f, 0.94f, 1f, -1f), listOf(0.94f, 0.94f, -1f, -1f))) {
        drawLine(tint, Offset(w * cx, w * cy), Offset(w * cx + dx * l, w * cy), st.width, StrokeCap.Round)
        drawLine(tint, Offset(w * cx, w * cy), Offset(w * cx, w * cy + dy * l), st.width, StrokeCap.Round)
    }
}

@Composable
fun BackIcon(tint: Color = Color.White, size: Dp = 24.dp) = Canvas(Modifier.size(size)) {
    val w = this.size.width
    val sw = w * 0.09f
    drawLine(tint, Offset(w * 0.62f, w * 0.18f), Offset(w * 0.3f, w * 0.5f), sw, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.3f, w * 0.5f), Offset(w * 0.62f, w * 0.82f), sw, StrokeCap.Round)
}

@Composable
fun PlayIcon(playing: Boolean, tint: Color = Color.White, size: Dp = 28.dp) = Canvas(Modifier.size(size)) {
    val w = this.size.width
    if (playing) {
        drawRect(tint, Offset(w * 0.25f, w * 0.2f), Size(w * 0.16f, w * 0.6f))
        drawRect(tint, Offset(w * 0.59f, w * 0.2f), Size(w * 0.16f, w * 0.6f))
    } else {
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.3f, w * 0.18f); lineTo(w * 0.82f, w * 0.5f); lineTo(w * 0.3f, w * 0.82f); close()
        }
        drawPath(p, tint)
    }
}

@Composable
fun PlusIcon(tint: Color = Color.White, size: Dp = 28.dp) = Canvas(Modifier.size(size)) {
    val w = this.size.width
    drawLine(tint, Offset(w * 0.5f, w * 0.15f), Offset(w * 0.5f, w * 0.85f), w * 0.09f, StrokeCap.Round)
    drawLine(tint, Offset(w * 0.15f, w * 0.5f), Offset(w * 0.85f, w * 0.5f), w * 0.09f, StrokeCap.Round)
}
