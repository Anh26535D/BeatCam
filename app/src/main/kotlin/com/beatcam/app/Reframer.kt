package com.beatcam.app

import android.content.Context
import android.graphics.Matrix
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.beatcam.core.CropPath

/** Pass 2: Media3 Transformer applies the per-timestamp crop (+punch zoom) on the GPU and re-encodes to 9:16. */
class Reframer(private val ctx: Context) {
    class CropEffect(private val path: CropPath, private val srcW: Int, private val srcH: Int) : MatrixTransformation {
        override fun getMatrix(presentationTimeUs: Long): Matrix {
            val c = path.at(presentationTimeUs / 1_000_000.0)
            val nx = c.cx / srcW * 2 - 1
            val ny = 1 - c.cy / srcH * 2          // NDC y points up
            return Matrix().apply {
                postTranslate(-nx.toFloat(), -ny.toFloat())
                postScale((srcW / c.w).toFloat(), (srcH / c.h).toFloat())
            }
        }
    }

    /** Must be called on a thread with a Looper (e.g. main). */
    fun export(
        uri: Uri, outPath: String, path: CropPath, srcW: Int, srcH: Int, outH: Int = 1920, hevc: Boolean = false,
        onProgress: (Float) -> Unit, onDone: (Result<ExportResult>) -> Unit,
    ) {
        val outW = (outH * 9 / 16) / 2 * 2
        val effects: List<Effect> = listOf(CropEffect(path, srcW, srcH), Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_STRETCH_TO_FIT))
        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(Effects(emptyList(), effects)).build()
        val transformer = Transformer.Builder(ctx)
            .setVideoMimeType(if (hevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: androidx.media3.transformer.Composition, r: ExportResult) = onDone(Result.success(r))
                override fun onError(composition: androidx.media3.transformer.Composition, r: ExportResult, e: ExportException) = onDone(Result.failure(e))
            }).build()
        transformer.start(item, outPath)
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val holder = ProgressHolder()
        h.post(object : Runnable {
            override fun run() {
                if (transformer.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) onProgress(holder.progress / 100f)
                if (transformer.getProgress(holder) != Transformer.PROGRESS_STATE_NO_TRANSFORMATION) h.postDelayed(this, 300)
            }
        })
    }
}
