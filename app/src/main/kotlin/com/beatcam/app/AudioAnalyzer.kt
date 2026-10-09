package com.beatcam.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.beatcam.core.Audio
import com.beatcam.core.Onset

/** Decodes the audio track to mono ~22 kHz floats (streaming, so long videos do not blow memory) and finds onsets. */
object AudioAnalyzer {
    fun onsets(ctx: Context, uri: Uri): List<Onset> {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
            ?: return emptyList<Onset>().also { ex.release() }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()

        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var factor = maxOf(1, rate / 22050)
        val out = FloatArrayBuilder()
        var acc = 0f; var accN = 0
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        while (!outDone) {
            if (!inDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            when {
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    factor = maxOf(1, rate / 22050)
                }
                o >= 0 -> {
                    val buf = codec.getOutputBuffer(o)!!
                    buf.position(info.offset).limit(info.offset + info.size)
                    val sb = buf.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                    while (sb.remaining() >= channels) {
                        var s = 0f
                        for (c in 0 until channels) s += sb.get()
                        acc += s / channels; accN++
                        if (accN == factor) { out.add(acc / (factor * 32768f)); acc = 0f; accN = 0 }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
        }
        codec.stop(); codec.release(); ex.release()
        return Audio.detectOnsets(out.toArray(), rate / factor)
    }

    private class FloatArrayBuilder {
        private var a = FloatArray(1 shl 20); private var n = 0
        fun add(v: Float) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun toArray() = a.copyOf(n)
    }
}
