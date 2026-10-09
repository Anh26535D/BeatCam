package com.beatcam.core

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Pre/post-processing for YOLOX ONNX models (Apache-2.0, https://github.com/Megvii-BaseDetection/YOLOX).
 * Input: BGR 0..255 float CHW, letterboxed (top-left, pad 114) to [SIZE]x[SIZE]. Output: [N, 85] rows of
 * (cx, cy, w, h) as offsets from the grid cell in stride units (w/h in log space), objectness, 80 class scores.
 */
object Yolox {
    const val SIZE = 640
    const val ROW = 85
    private val STRIDES = intArrayOf(8, 16, 32)

    /** Scale used to fit a w x h image inside the square input (aspect preserved). */
    fun ratio(w: Int, h: Int, size: Int = SIZE) = min(size.toDouble() / h, size.toDouble() / w)

    fun rows(size: Int = SIZE) = STRIDES.sumOf { (size / it) * (size / it) }

    /**
     * Decode person (COCO class 0) detections into pixel boxes of the ORIGINAL image.
     * [out] is the flattened [N*85] network output; [imgW]/[imgH] bound the boxes.
     */
    fun decodePersons(out: FloatArray, ratio: Double, imgW: Int, imgH: Int, size: Int = SIZE, scoreThr: Double = 0.25, nmsThr: Double = 0.45): List<Detection> {
        val cands = ArrayList<Detection>()
        var row = 0
        for (stride in STRIDES) {
            val n = size / stride
            for (gy in 0 until n) for (gx in 0 until n) {
                val o = row * ROW
                row++
                val score = out[o + 4] * out[o + 5] // objectness * P(person)
                if (score < scoreThr) continue
                val cx = (out[o] + gx) * stride; val cy = (out[o + 1] + gy) * stride
                val w = exp(out[o + 2].toDouble()) * stride; val h = exp(out[o + 3].toDouble()) * stride
                val box = Box(
                    ((cx - w / 2) / ratio).coerceIn(0.0, imgW.toDouble()), ((cy - h / 2) / ratio).coerceIn(0.0, imgH.toDouble()),
                    ((cx + w / 2) / ratio).coerceIn(0.0, imgW.toDouble()), ((cy + h / 2) / ratio).coerceIn(0.0, imgH.toDouble()),
                )
                if (box.w > 2 && box.h > 2) cands += Detection(box, score.toDouble(), Label.PERSON)
            }
        }
        return nms(cands, nmsThr)
    }

    fun nms(dets: List<Detection>, thr: Double): List<Detection> {
        val sorted = dets.sortedByDescending { it.score }
        val keep = ArrayList<Detection>()
        for (d in sorted) if (keep.none { it.box.iou(d.box) >= thr }) keep += d
        return keep
    }
}
