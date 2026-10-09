package com.beatcam.core

/**
 * Combine the object detector's people with skeleton (pose) detections.
 * Object detectors often merge two people standing close together into ONE box; skeletons are per person, so:
 *  - a detector box containing 2+ skeleton centres is replaced by those skeleton boxes;
 *  - a detector box containing exactly 1 skeleton keeps its box and gains the keypoints;
 *  - skeletons that no detector box explains are added (unless they overlap an existing person a lot).
 */
fun fusePeople(detected: List<Detection>, poses: List<Detection>): List<Detection> {
    val used = BooleanArray(poses.size)
    val out = ArrayList<Detection>()
    for (d in detected) {
        val inside = poses.indices.filter { !used[it] && poses[it].cx in d.box.x1..d.box.x2 && poses[it].cy in d.box.y1..d.box.y2 }
        when {
            inside.size >= 2 -> inside.forEach { used[it] = true; out += poses[it] }
            inside.size == 1 -> { used[inside[0]] = true; out += Detection(d.box, d.score, Label.PERSON, d.trackId, poses[inside[0]].keypoints, d.feature) }
            else -> out += d
        }
    }
    for (i in poses.indices) if (!used[i] && out.none { it.box.iou(poses[i].box) > 0.5 }) out += poses[i]
    return out
}
