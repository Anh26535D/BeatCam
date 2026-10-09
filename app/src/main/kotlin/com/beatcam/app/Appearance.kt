package com.beatcam.app

import android.content.Context
import android.graphics.Bitmap
import com.beatcam.core.Box
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import kotlin.math.sqrt

/**
 * Appearance vector of one person crop, used to re-identify the chosen person.
 * = [CNN embedding (MediaPipe ImageEmbedder, if the model asset is present), torso colour histogram], each L2-normalised
 * and scaled by 1/sqrt(2), so the dot product of two vectors is the mean of the two cosine similarities.
 */
class Appearance(ctx: Context) : AutoCloseable {
    private val embedder: ImageEmbedder? = runCatching {
        ImageEmbedder.createFromOptions(
            ctx,
            ImageEmbedder.ImageEmbedderOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("mobilenet_v3_small.tflite").build())
                .setRunningMode(RunningMode.IMAGE).setL2Normalize(true).build(),
        )
    }.getOrNull() // no model file -> colour histogram only

    /** [box] is in [bmp] pixel coordinates. */
    fun describe(bmp: Bitmap, box: Box): DoubleArray? {
        val x1 = box.x1.toInt().coerceIn(0, bmp.width - 1); val y1 = box.y1.toInt().coerceIn(0, bmp.height - 1)
        val x2 = box.x2.toInt().coerceIn(x1 + 1, bmp.width); val y2 = box.y2.toInt().coerceIn(y1 + 1, bmp.height)
        if (x2 - x1 < 12 || y2 - y1 < 24) return null
        val hist = histogram(bmp, x1, y1, x2, y2)
        val emb = embedder?.let { e ->
            runCatching {
                val crop = Bitmap.createBitmap(bmp, x1, y1, x2 - x1, y2 - y1)
                val v = e.embed(BitmapImageBuilder(crop).build()).embeddingResult().embeddings().first().floatEmbedding()
                crop.recycle()
                unit(DoubleArray(v.size) { v[it].toDouble() })
            }.getOrNull()
        }
        return if (emb == null) hist else {
            val k = 1 / sqrt(2.0)
            DoubleArray(emb.size + hist.size) { if (it < emb.size) emb[it] * k else hist[it - emb.size] * k }
        }
    }

    /** sqrt of a normalised 4x4x4 RGB histogram of the torso, so dot product == Bhattacharyya coefficient. */
    private fun histogram(bmp: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int): DoubleArray {
        val w = x2 - x1; val h = y2 - y1
        val tx1 = x1 + (w * 0.15).toInt(); val tx2 = x2 - (w * 0.15).toInt()
        val ty1 = y1 + (h * 0.15).toInt(); val ty2 = y1 + (h * 0.60).toInt()
        val bins = DoubleArray(64)
        var n = 0
        val sx = maxOf(1, (tx2 - tx1) / 24); val sy = maxOf(1, (ty2 - ty1) / 24)
        var y = ty1
        while (y < ty2) {
            var x = tx1
            while (x < tx2) {
                val p = bmp.getPixel(x.coerceIn(0, bmp.width - 1), y.coerceIn(0, bmp.height - 1))
                bins[((p shr 22) and 3) * 16 + ((p shr 14) and 3) * 4 + ((p shr 6) and 3)] += 1.0
                n++; x += sx
            }
            y += sy
        }
        return unit(DoubleArray(64) { sqrt(bins[it] / maxOf(n, 1)) })
    }

    private fun unit(v: DoubleArray): DoubleArray {
        val n = sqrt(v.sumOf { it * it }).takeIf { it > 1e-9 } ?: return v
        return DoubleArray(v.size) { v[it] / n }
    }

    override fun close() { embedder?.close() }
}
