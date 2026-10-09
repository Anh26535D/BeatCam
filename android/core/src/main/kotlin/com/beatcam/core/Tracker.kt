package com.beatcam.core

/** ByteTrack-style two-stage IoU tracker for detectors without built-in tracking. */
class SimpleTracker(
    private val high: Double = 0.5, private val low: Double = 0.1,
    private val iouHi: Double = 0.3, private val iouLo: Double = 0.5, private val maxLost: Int = 30,
) {
    private class Track(val id: Int, var box: Box) {
        var vel = DoubleArray(4)
        var lost = 0
        fun predicted() = box + vel
    }

    private var tracks = mutableListOf<Track>()
    private var next = 1

    private fun match(ts: List<Track>, ds: List<Detection>, thr: Double): Triple<List<Pair<Int, Int>>, List<Int>, List<Int>> {
        val cand = ArrayList<Triple<Double, Int, Int>>()
        for (ti in ts.indices) for (di in ds.indices) cand += Triple(ts[ti].predicted().iou(ds[di].box), ti, di)
        cand.sortByDescending { it.first }
        val ut = HashSet<Int>(); val ud = HashSet<Int>(); val out = ArrayList<Pair<Int, Int>>()
        for ((v, ti, di) in cand) {
            if (v < thr) break
            if (ti in ut || di in ud) continue
            ut += ti; ud += di; out += ti to di
        }
        return Triple(out, ts.indices.filter { it !in ut }, ds.indices.filter { it !in ud })
    }

    private fun apply(t: Track, d: Detection) {
        val n = d.box
        val old = t.box
        t.vel = doubleArrayOf(n.x1 - old.x1, n.y1 - old.y1, n.x2 - old.x2, n.y2 - old.y2)
            .mapIndexed { i, v -> 0.7 * t.vel[i] + 0.3 * v }.toDoubleArray()
        t.box = n; t.lost = 0; d.trackId = t.id
    }

    fun update(dets: List<Detection>): List<Detection> {
        val hi = dets.filter { it.score >= high }
        val lo = dets.filter { it.score >= low && it.score < high }
        val (m1, remT, remHi) = match(tracks, hi, iouHi)
        m1.forEach { (ti, di) -> apply(tracks[ti], hi[di]) }
        val left = remT.map { tracks[it] }
        val (m2, stillLost, _) = match(left, lo, iouLo)
        m2.forEach { (ti, di) -> apply(left[ti], lo[di]) }
        stillLost.forEach { left[it].lost++ }
        for (di in remHi) {
            val t = Track(next++, hi[di].box)
            hi[di].trackId = t.id
            tracks += t
        }
        tracks = tracks.filter { it.lost <= maxLost }.toMutableList()
        return (hi + lo).filter { it.trackId != null }
    }
}
