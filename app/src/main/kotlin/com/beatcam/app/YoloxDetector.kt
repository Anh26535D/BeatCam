package com.beatcam.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.beatcam.core.Detection
import com.beatcam.core.Yolox
import java.nio.FloatBuffer

/** Person detector: YOLOX (Apache-2.0) on ONNX Runtime. Boxes come back in the pixel coordinates of the bitmap given. */
class YoloxDetector(ctx: Context, model: String = "yolox_s.onnx", threads: Int = 4) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        ctx.assets.open(model).use { it.readBytes() },
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        },
    )
    private val inputName = session.inputNames.first()
    private val size = Yolox.SIZE
    private val input = FloatBuffer.allocate(3 * size * size)
    private val pixels = IntArray(size * size)

    fun detect(bmp: Bitmap, scoreThr: Double = 0.25): List<Detection> {
        val ratio = Yolox.ratio(bmp.width, bmp.height, size)
        val nw = (bmp.width * ratio).toInt().coerceIn(1, size)
        val nh = (bmp.height * ratio).toInt().coerceIn(1, size)
        val scaled = if (nw == bmp.width && nh == bmp.height) bmp else Bitmap.createScaledBitmap(bmp, nw, nh, true)
        scaled.getPixels(pixels, 0, nw, 0, 0, nw, nh)
        if (scaled !== bmp) scaled.recycle()

        // BGR, 0..255, CHW, padded with 114 at the bottom/right (letterbox anchored top-left, as YOLOX expects)
        val plane = size * size
        val a = input.array()
        java.util.Arrays.fill(a, 114f)
        for (y in 0 until nh) {
            val row = y * size
            for (x in 0 until nw) {
                val p = pixels[y * nw + x]
                a[row + x] = (p and 0xFF).toFloat()                 // B
                a[plane + row + x] = ((p shr 8) and 0xFF).toFloat() // G
                a[2 * plane + row + x] = ((p shr 16) and 0xFF).toFloat() // R
            }
        }
        input.rewind()
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, size.toLong(), size.toLong())).use { t ->
            session.run(mapOf(inputName to t)).use { res ->
                val fb = (res[0] as OnnxTensor).floatBuffer
                val out = FloatArray(fb.remaining()).also { fb.get(it) }
                return Yolox.decodePersons(out, ratio, bmp.width, bmp.height, size, scoreThr)
            }
        }
    }

    override fun close() { session.close() }
}
