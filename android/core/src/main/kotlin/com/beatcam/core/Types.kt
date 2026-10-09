package com.beatcam.core

import kotlin.math.max
import kotlin.math.min

/** Axis-aligned box in source pixels. */
data class Box(val x1: Double, val y1: Double, val x2: Double, val y2: Double) {
    val w get() = x2 - x1
    val h get() = y2 - y1
    val cx get() = (x1 + x2) / 2
    val cy get() = (y1 + y2) / 2

    fun iou(o: Box): Double {
        val iw = max(0.0, min(x2, o.x2) - max(x1, o.x1))
        val ih = max(0.0, min(y2, o.y2) - max(y1, o.y1))
        val inter = iw * ih
        val u = w * h + o.w * o.h - inter
        return if (u > 0) inter / u else 0.0
    }

    operator fun plus(d: DoubleArray) = Box(x1 + d[0], y1 + d[1], x2 + d[2], y2 + d[3])

    companion object {
        fun union(boxes: List<Box>) = Box(
            boxes.minOf { it.x1 }, boxes.minOf { it.y1 }, boxes.maxOf { it.x2 }, boxes.maxOf { it.y2 },
        )
        fun around(x: Double, y: Double, r: Double) = Box(x - r, y - r, x + r, y + r)
    }
}

enum class Label { PERSON, BALL }

/** keypoints: 17 COCO points, each [x, y, confidence]. */
class Detection(
    val box: Box,
    val score: Double = 1.0,
    val label: Label = Label.PERSON,
    var trackId: Int? = null,
    val keypoints: Array<DoubleArray>? = null,
) {
    val cx get() = box.cx
    val cy get() = box.cy
    val height get() = box.h
}

/** COCO-17 keypoint indices. */
object Kp {
    const val NOSE = 0
    const val L_SHO = 5; const val R_SHO = 6
    const val L_WRI = 9; const val R_WRI = 10
    const val L_HIP = 11; const val R_HIP = 12
    const val L_KNE = 13; const val R_KNE = 14

    /** MediaPipe Pose (33 landmarks) -> COCO-17. `lm[i] = [x, y, visibility]` in pixels. */
    private val MP_TO_COCO = intArrayOf(0, 2, 5, 7, 8, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)
    fun fromMediaPipe33(lm: Array<DoubleArray>): Array<DoubleArray> =
        Array(17) { lm[MP_TO_COCO[it]].copyOf() }
}
