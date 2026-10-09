package com.beatcam.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max

/** Cosine similarity of two unit-length appearance vectors (1 = same look). Unknown -> neutral 0.5. */
fun similarity(a: DoubleArray?, b: DoubleArray?): Double {
    if (a == null || b == null || a.size != b.size) return 0.5
    var s = 0.0
    for (i in a.indices) s += a[i] * b[i]
    return s
}

/**
 * Follows ONE chosen person through the detections of consecutive steps (forward or backward in time).
 *
 * Two cues are combined every step:
 *  - motion: where the person should be (last position + velocity), size and overlap;
 *  - appearance: cosine similarity of the candidate's embedding with a gallery of exemplars of the chosen person.
 *
 * Appearance is also used to RECOVER focus: when the person is not found near the prediction, every person in the
 * frame is compared with the gallery (no distance limit), and if the motion pick looks like someone else while another
 * candidate clearly matches the gallery, the tracker jumps back to the real person.
 */
class TargetTracker(
    seed: Detection, private val reidMin: Double = 0.75, private val margin: Double = 0.04,
    lostSteps: Int = 0,                  // how long ago [seed] was seen (e.g. after the user scrubbed the timeline)
    private val splitMerged: Boolean = true,
) {
    private var box = seed.box
    private var vx = 0.0
    private var vy = 0.0
    private val gallery = ArrayList<DoubleArray>().also { g -> seed.feature?.let { g += it } }
    private var accepted = 0
    var lost = lostSteps; private set

    private fun appearance(f: DoubleArray?): Double? =
        if (f == null || gallery.isEmpty()) null else gallery.maxOf { similarity(it, f) }

    /** Returns the detection that is the chosen person this step, or null if they are not visible. */
    fun update(people: List<Detection>): Detection? {
        val steps = lost + 1
        val h = max(box.h, 1.0)
        val px = box.cx + vx * steps; val py = box.cy + vy * steps
        val predicted = Box(px - box.w / 2, py - box.h / 2, px + box.w / 2, py + box.h / 2)
        val gate = 0.8 + 0.4 * minOf(lost, 15) // in person-heights, grows while lost
        val cands = if (splitMerged) people.map { unmerge(it, px, py) } else people
        val sims = cands.map { appearance(it.feature) }

        // 1) motion-gated candidate, scored by distance + size + overlap + appearance
        var moved: Int? = null
        var bestCost = Double.MAX_VALUE
        for ((i, d) in cands.withIndex()) {
            val dist = hypot(d.cx - px, d.cy - py) / h
            if (dist > gate) continue
            val app = sims[i] ?: 0.5
            if (sims[i] != null && app < reidMin - 0.3 && dist > 0.35) continue
            val size = abs(ln(max(d.height, 1.0) / h))
            val cost = dist + 0.6 * size + 1.2 * (1 - app) - 0.8 * predicted.iou(d.box)
            if (cost < bestCost) { bestCost = cost; moved = i }
        }

        // 2) appearance-only candidate anywhere in the frame (clear winner over the runner-up)
        val ranked = cands.indices.filter { sims[it] != null }.sortedByDescending { sims[it] }
        val top = ranked.firstOrNull()
        val second = ranked.getOrNull(1)
        val reid = top?.takeIf { sims[it]!! >= reidMin && (second == null || sims[it]!! - sims[second]!! >= margin) }

        val pick: Int? = when {
            moved == null -> reid                                           // lost: search by look, anywhere
            reid != null && reid != moved && (sims[moved] ?: 1.0) < reidMin - 0.15 -> reid // drifted onto someone else
            else -> moved
        }
        if (pick == null) { lost++; return null }
        val best = cands[pick]
        val jumped = pick != moved
        if (jumped) { vx = 0.0; vy = 0.0 } else {
            vx = 0.5 * vx + 0.5 * (best.cx - box.cx) / steps
            vy = 0.5 * vy + 0.5 * (best.cy - box.cy) / steps
        }
        box = best.box
        remember(best.feature, sims[pick])
        lost = 0
        return best
    }

    /**
     * The detector sometimes returns ONE box around two people standing close together. If that box holds the place
     * where our person should be but is much wider than them, cut out a person-sized box on the side where they were.
     */
    private fun unmerge(d: Detection, px: Double, py: Double): Detection {
        if (d.box.w <= 1.45 * box.w || d.height > 1.35 * box.h) return d
        if (px !in d.box.x1..d.box.x2 || py !in d.box.y1..d.box.y2) return d
        val w = box.w
        val cx = px.coerceIn(d.box.x1 + w / 2, d.box.x2 - w / 2)
        return Detection(Box(cx - w / 2, d.box.y1, cx + w / 2, d.box.y2), d.score, d.label, d.trackId, null, null)
    }

    /** Keep a small gallery of exemplars, only adding frames where we are confident it is the right person. */
    private fun remember(f: DoubleArray?, sim: Double?) {
        if (f == null) return
        accepted++
        if (gallery.isEmpty()) { gallery += f; return }
        if (accepted % 5 == 0 && (sim ?: 0.0) >= 0.85 && gallery.none { similarity(it, f) > 0.98 }) {
            if (gallery.size >= 12) gallery.removeAt(1.coerceAtMost(gallery.size - 1)) // keep the original exemplar (index 0)
            gallery += f
        }
    }
}
