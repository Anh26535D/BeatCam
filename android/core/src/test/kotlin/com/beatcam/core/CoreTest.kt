package com.beatcam.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoreTest {
    private fun box(x1: Double, y1: Double, x2: Double, y2: Double) = Box(x1, y1, x2, y2)

    @Test fun oneEuroReducesJitter() {
        val r = Random(0); val f = OneEuroFilter(0.5, 0.0)
        val raw = DoubleArray(300) { 100 + (r.nextDouble() - 0.5) * 10 }
        val out = DoubleArray(300) { f.filter(raw[it], 1.0 / 30) }
        fun sd(a: DoubleArray) = a.drop(30).let { l -> val m = l.average(); Math.sqrt(l.sumOf { (it - m) * (it - m) } / l.size) }
        assertTrue(sd(out) < sd(raw) / 2)
    }

    @Test fun savgolPreservesPolynomial() {
        val y = DoubleArray(100) { val t = it / 99.0; 3 * t * t - t }
        val s = savgol(y, 15, 3)
        for (i in y.indices) assertEquals(y[i], s[i], 1e-6)
    }

    @Test fun deadZoneHoldsThenFollows() {
        val cam = VirtualCamera(1920, 1080, 30.0)
        val x0 = cam.update(box(900.0, 400.0, 1000.0, 700.0)).x
        var r = cam
        for (dx in listOf(-10.0, 10.0, -5.0, 0.0)) assertEquals(x0, r.update(box(900 + dx, 400.0, 1000 + dx, 700.0)).x, 1e-6)
        var c = Crop(0.0, 0.0, 0.0, 0.0)
        repeat(60) { c = cam.update(box(1500.0, 400.0, 1600.0, 700.0)) }
        assertTrue(c.cx > 1400)
    }

    @Test fun cropStaysInFrameWithAspect() {
        val cam = VirtualCamera(1920, 1080, 30.0)
        repeat(100) {
            val c = cam.update(box(it * 19.0, 0.0, it * 19.0 + 50, 100.0), widenTarget = 0.5)
            assertTrue(c.x >= 0 && c.y >= 0 && c.x + c.w <= 1920 + 1e-6 && c.y + c.h <= 1080 + 1e-6)
            assertEquals(9.0 / 16, c.w / c.h, 1e-9)
        }
    }

    @Test fun asymmetricDamping() {
        val cam = VirtualCamera(1920, 1080, 30.0)
        val b = box(900.0, 400.0, 1000.0, 700.0)
        val mid = cam.update(b).cy
        repeat(3) { cam.update(b, leadY = -100.0) }
        val up = mid - cam.update(b, leadY = -100.0).cy
        assertTrue(up > 40)
        repeat(3) { cam.update(b, leadY = 0.0) }
        assertTrue(mid - cam.update(b, leadY = 0.0).cy > 0.5 * up)
    }

    @Test fun fftMatchesSine() {
        val n = 256; val re = DoubleArray(n) { sin(2 * PI * 8 * it / n) }; val im = DoubleArray(n)
        Audio.fft(re, im)
        val mags = DoubleArray(n / 2) { Math.hypot(re[it], im[it]) }
        assertEquals(8, mags.indices.maxBy { mags[it] })
    }

    @Test fun onsetsKickAndSnare() {
        val sr = 22050; val y = FloatArray(sr * 4); val len = (0.12 * sr).toInt()
        val rnd = Random(1)
        // snare-like burst: sum of sinusoids in 1-3 kHz with random phases
        val ph = DoubleArray(20) { rnd.nextDouble() * 2 * PI }
        for (s in listOf(0.5, 1.5, 2.5, 3.5)) for (i in 0 until len) {
            val t = i.toDouble() / sr; y[(s * sr).toInt() + i] += (sin(2 * PI * 60 * t) * exp(-t * 30)).toFloat()
        }
        for (s in listOf(1.0, 2.0, 3.0)) for (i in 0 until len) {
            val t = i.toDouble() / sr
            val v = ph.indices.sumOf { sin(2 * PI * (1050 + 100 * it) * t + ph[it]) } / 20 * exp(-t * 40)
            y[(s * sr).toInt() + i] += v.toFloat()
        }
        val on = Audio.detectOnsets(y, sr)
        for ((kind, exp) in listOf(OnsetKind.KICK to listOf(0.5, 1.5, 2.5, 3.5), OnsetKind.SNARE to listOf(1.0, 2.0, 3.0))) {
            val got = on.filter { it.kind == kind }.map { it.time }
            for (e in exp) assertTrue(got.any { abs(it - e) < 0.04 }, "$kind $e $got")
        }
    }

    @Test fun punchEnvelope() {
        val env = PunchEnvelope(listOf(Onset(1.0, 1.0, OnsetKind.KICK)), 30.0, 4)
        val peak = env.scaleAtFrame(30)
        assertTrue(peak in 1.05..1.10)
        assertEquals(1.0, env.scaleAtFrame(29)); assertEquals(1.0, env.scaleAtFrame(40))
        assertTrue(env.scaleAtFrame(30) > env.scaleAtFrame(32))
        assertEquals(peak, PunchEnvelope(listOf(Onset(1.0, 1.0, OnsetKind.KICK)), 30.0, 4, 1.0 / 30).scaleAtFrame(31))
    }

    private fun kp(wristY: Double = 0.0, kneeDy: Double = 1.0): Array<DoubleArray> {
        val k = Array(17) { doubleArrayOf(0.0, 0.0, 1.0) }
        k[0] = doubleArrayOf(100.0, 20.0, 1.0); k[5] = doubleArrayOf(80.0, 50.0, 1.0); k[6] = doubleArrayOf(120.0, 50.0, 1.0)
        k[11] = doubleArrayOf(85.0, 130.0, 1.0); k[12] = doubleArrayOf(115.0, 130.0, 1.0)
        k[13] = doubleArrayOf(85.0, 130 + 70 * kneeDy, 1.0); k[14] = doubleArrayOf(115.0, 130 + 70 * kneeDy, 1.0)
        k[9] = doubleArrayOf(70.0, 100 + wristY, 1.0); k[10] = doubleArrayOf(130.0, 100 + wristY, 1.0)
        return k
    }

    @Test fun gestures() {
        val g = GestureAnalyzer()
        var base = GestureCue()
        repeat(5) { base = g.update(kp()) }
        assertEquals(0.0, base.reachUp); assertEquals(0.0, base.floorDrop)
        assertTrue(g.update(kp(wristY = -130.0)).reachUp > 0.5)
        assertTrue(g.update(kp(kneeDy = 0.3)).floorDrop > 0.5)
        val spread = kp().also { it[9][0] = 0.0; it[10][0] = 200.0 }
        assertTrue(g.update(spread).armsSpread > 0.5)
    }

    @Test fun mediaPipeMapping() {
        val lm = Array(33) { doubleArrayOf(it.toDouble(), 0.0, 1.0) }
        val c = Kp.fromMediaPipe33(lm)
        assertEquals(11.0, c[Kp.L_SHO][0]); assertEquals(24.0, c[Kp.R_HIP][0]); assertEquals(28.0, c[16][0])
    }

    @Test fun ballKalmanCoasts() {
        val k = BallKalman(30.0)
        repeat(20) { k.update(100.0 + 10 * it, 50.0) }
        val s = k.update(null, null)!!
        assertTrue(abs(s[0] - 300) < 15 && abs(s[2] - 300) < 40)
        var last: DoubleArray? = s
        repeat(20) { last = k.update(null, null) }
        assertEquals(null, last)
    }

    @Test fun intentPrediction() {
        fun p(id: Int, x: Double) = Detection(box(x - 20, 300.0, x + 20, 400.0), 1.0, trackId = id)
        val players = mapOf(1 to p(1, 100.0), 2 to p(2, 900.0), 3 to p(3, 500.0))
        val ip = IntentPredictor(30.0)
        ip.update(doubleArrayOf(110.0, 380.0, 0.0, 0.0), players)
        assertEquals(1, ip.possessor)
        val rel = ip.update(doubleArrayOf(160.0, 380.0, 900.0, 0.0), players)!!
        assertTrue(rel.receiver in listOf(2, 3) && rel.phase == Phase.RELEASE && rel.widen > 0)
        val app = ip.update(doubleArrayOf(850.0, 380.0, 300.0, 0.0), players)!!
        assertTrue(app.phase == Phase.APPROACH && app.receiver == 2 && app.widen < 0)
    }

    @Test fun trackerKeepsId() {
        val tr = SimpleTracker()
        val ids = (0 until 10).map { tr.update(listOf(Detection(box(10.0 + 5 * it, 10.0, 60.0 + 5 * it, 110.0), 0.9))).first().trackId }
        assertEquals(1, ids.toSet().size)
    }

    @Test fun cropPathAppliesPunch() {
        val steps = List(60) { Step(box(900.0, 400.0, 1000.0, 700.0)) }
        val punch = PunchEnvelope(listOf(Onset(1.0, 1.0, OnsetKind.KICK)), 30.0)
        val path = CropPath.build(steps, 1920, 1080, 30.0, CameraConfig(), punch)
        assertTrue(path.at(1.0).h < path.at(0.5).h * 0.95)
        assertEquals(path.at(0.5).cx, path.at(1.0).cx, 1e-6)
    }
}
