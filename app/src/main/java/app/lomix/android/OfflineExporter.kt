package app.lomix.android

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Where a piece of audio comes from for the exporter. */
sealed class AudioSource {
    data class UriSource(val uri: Uri) : AudioSource()
    data class FileSource(val file: File) : AudioSource()
    data class AssetSource(val path: String) : AudioSource()
}

/** One music source with its gain (a song, or one stem of a tayyar gana). */
data class MusicPart(val source: AudioSource, val gain: Float)

/** One looping layer (rain, ambience, vinyl, tape hiss) with its gain. */
data class LayerPart(val source: AudioSource, val gain: Float)

/** A snapshot of the whole mix, taken when Export is tapped. */
data class ExportRequest(
    val title: String,
    val music: List<MusicPart>,
    val speed: Float,
    val garam: Boolean,
    val naram: Boolean,
    val geela: Boolean,
    val layers: List<LayerPart>
)

/**
 * Renders the current mix to an .m4a file, fully offline (v1.5).
 *
 * The live app cannot simply be "recorded" — ExoPlayer gives no file
 * out — so the mix is rebuilt sample by sample:
 *
 *   1. every music source is decoded to a canonical 44.1 kHz stereo
 *      PCM temp file and the parts are summed into a music bus;
 *   2. the bus is time-stretched to the mix speed with WSOLA (the
 *      pitch stays put, like the live pitch-locked playback);
 *   3. the Lofi Rang EQ (two shelf biquads following the same curves
 *      as FxController) and, when Geela is on, a Schroeder reverb are
 *      applied to the bus;
 *   4. the looping layers (rain, ambience, vinyl, hiss) are added at
 *      their live volumes, and everything is AAC-encoded and muxed.
 *
 * One known difference from live playback: the Cassette pitch wobble
 * is not rendered (its hiss layer is) — a ±0.4% shimmer that no export
 * listener can hear missing.
 *
 * All intermediate files are float/int16 PCM in cacheDir/export and
 * are deleted when the render finishes or fails.
 */
object OfflineExporter {

    const val SR = 44100
    private const val CH = 2
    private const val BLOCK = 8192 // frames per processing block

    fun export(
        context: Context,
        req: ExportRequest,
        onProgress: (Int) -> Unit
    ): File {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        try {
            return render(context, req, dir, onProgress)
        } finally {
            dir.listFiles()?.forEach { if (it.extension != "m4a") it.delete() }
        }
    }

    private fun render(
        context: Context,
        req: ExportRequest,
        dir: File,
        onProgress: (Int) -> Unit
    ): File {
        // ---- 1. Decode + mix the music bus (progress 0..20)
        val busFile = File(dir, "bus.f32")
        var busFrames = 0L
        val canonical = req.music.mapIndexed { i, part ->
            val raw = File(dir, "raw$i.pcm")
            val (sr, ch, frames) = decodeToPcm(context, part.source, raw)
            val can = File(dir, "can$i.pcm")
            val canFrames = canonicalize(raw, sr, ch, can)
            raw.delete()
            Triple(can, canFrames, part.gain)
        }
        val maxFrames = canonical.maxOf { it.second }
        FloatWriter(busFile).use { writer ->
            val readers = canonical.map { PcmReader(it.first) }
            var pos = 0L
            val block = FloatArray(BLOCK * CH)
            while (pos < maxFrames) {
                val n = min(BLOCK.toLong(), maxFrames - pos).toInt()
                java.util.Arrays.fill(block, 0f)
                canonical.forEachIndexed { idx, (_, frames, gain) ->
                    if (pos < frames) {
                        val got = readers[idx].read(pos, min(n.toLong(), frames - pos).toInt(), block, gain, accumulate = true)
                        check(got >= 0)
                    }
                }
                writer.write(block, n)
                pos += n
                onProgress((20.0 * pos / maxFrames).toInt())
            }
            busFrames = maxFrames
        }
        canonical.forEach { it.first.delete() }

        // ---- 2. Time-stretch the bus to the mix speed (20..50)
        val stretchedFile = File(dir, "stretch.f32")
        val stretchFrames = if (req.speed < 0.999f) {
            wsolaStretch(busFile, busFrames, 1.0 / req.speed, stretchedFile) { p ->
                onProgress(20 + (30 * p).toInt())
            }
        } else {
            busFile.copyTo(stretchedFile, overwrite = true)
            busFrames
        }
        busFile.delete()

        // ---- 3. EQ + reverb on the bus (50..65)
        val processedFile = File(dir, "proc.f32")
        applyFxPass(stretchedFile, stretchFrames, processedFile, req)
        stretchedFile.delete()
        onProgress(65)

        // ---- 4. Decode the layers (65..75)
        val layerData = req.layers.mapIndexed { i, layer ->
            val raw = File(dir, "lraw$i.pcm")
            val (sr, ch, _) = decodeToPcm(context, layer.source, raw)
            val can = File(dir, "lcan$i.pcm")
            val frames = canonicalize(raw, sr, ch, can)
            raw.delete()
            LayerReader(PcmReader(can), frames, layer.gain)
        }
        onProgress(75)

        // ---- 5. Mix layers in and encode AAC (75..100)
        val outFile = File(dir, "mix.m4a")
        encodeMix(processedFile, stretchFrames, layerData, outFile) { p ->
            onProgress(75 + (25 * p).toInt())
        }
        processedFile.delete()
        layerData.forEach { it.reader.file.delete() }
        onProgress(100)
        return outFile
    }

    // ------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------

    /** Decode any source to raw interleaved int16 PCM; returns
     *  (sampleRate, channels, frames). */
    private fun decodeToPcm(context: Context, src: AudioSource, out: File): Triple<Int, Int, Long> {
        val extractor = MediaExtractor()
        when (src) {
            is AudioSource.UriSource -> extractor.setDataSource(context, src.uri, null)
            is AudioSource.FileSource -> extractor.setDataSource(src.file.absolutePath)
            is AudioSource.AssetSource -> {
                val fd = context.assets.openFd(src.path)
                extractor.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
        }
        var track = -1
        var trackFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                track = i
                trackFormat = f
                break
            }
        }
        check(track >= 0 && trackFormat != null) { "no audio track" }
        extractor.selectTrack(track)
        val mime = trackFormat!!.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(trackFormat, null, null, 0)
        codec.start()
        var sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var frames = 0L
        val info = MediaCodec.BufferInfo()
        val rawOut = out.outputStream().buffered()
        var sawInputEos = false
        var sawOutputEos = false
        try {
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(
                                inIdx, 0, size, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        if (info.size > 0) {
                            val bytes = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.get(bytes)
                            rawOut.write(bytes)
                            frames += info.size / (2 * channels)
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEos = true
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                }
            }
        } finally {
            rawOut.close()
            codec.stop()
            codec.release()
            extractor.release()
        }
        return Triple(sampleRate, channels, frames)
    }

    /** Convert a raw PCM file to canonical 44.1 kHz stereo int16.
     *  Returns the canonical frame count. Linear-interp resampling —
     *  most real sources are already 44.1 kHz, when this is a copy. */
    private fun canonicalize(raw: File, sr: Int, ch: Int, out: File): Long {
        val inFrames = raw.length() / (2L * ch)
        val outFrames = if (sr == SR) inFrames else (inFrames * SR / sr)
        val reader = RandomAccessFile(raw, "r")
        val writer = out.outputStream().buffered()
        val inBlock = ShortArray(BLOCK * ch)
        val byteBuf = ByteBuffer.allocate(BLOCK * 4).order(ByteOrder.LITTLE_ENDIAN)
        var inPosBlock = -1L
        fun sampleAt(frame: Long, channel: Int): Double {
            val blockStart = frame / BLOCK * BLOCK
            if (blockStart != inPosBlock) {
                inPosBlock = blockStart
                reader.seek(blockStart * 2L * ch)
                var read = 0
                val bytes = ByteArray(BLOCK * 2 * ch)
                val n = reader.read(bytes)
                if (n > 0) {
                    ByteBuffer.wrap(bytes, 0, n).order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer().get(inBlock, 0, min(inBlock.size, n / 2))
                    read = n / 2
                }
                if (read < inBlock.size) java.util.Arrays.fill(inBlock, read, inBlock.size, 0)
            }
            val idx = ((frame - blockStart) * ch + channel).toInt()
            return inBlock[idx].toDouble() / 32768.0
        }
        var outPos = 0L
        val outShorts = ShortArray(BLOCK * CH)
        while (outPos < outFrames) {
            val n = min(BLOCK.toLong(), outFrames - outPos).toInt()
            for (i in 0 until n) {
                val srcPos = (outPos + i).toDouble() * sr / SR
                val f0 = srcPos.toLong()
                val frac = srcPos - f0
                for (c in 0 until CH) {
                    val srcCh = min(c, ch - 1)
                    val a = sampleAt(f0, srcCh)
                    val b = sampleAt(min(f0 + 1, inFrames - 1), srcCh)
                    val v = a + (b - a) * frac
                    outShorts[i * CH + c] =
                        (v * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                }
            }
            byteBuf.clear()
            byteBuf.asShortBuffer().put(outShorts, 0, n * CH)
            writer.write(byteBuf.array(), 0, n * 4)
            outPos += n
        }
        writer.close()
        reader.close()
        return outFrames
    }

    // ------------------------------------------------------------------
    // WSOLA time-stretch
    // ------------------------------------------------------------------

    /** Stretch [inFile] (float stereo) by [factor] (>1 = longer/slower)
     *  into [outFile]; returns the output frame count. Classic WSOLA:
     *  Hann-windowed grains on a fixed synthesis hop, each grain's
     *  analysis position nudged to the best-correlating spot near its
     *  nominal position, so the waveform stays continuous (no pitch
     *  change). The correlation search is deliberately cheap — boundary
     *  region only, decimated — which is plenty at 0.80–1.00x speeds. */
    private fun wsolaStretch(
        inFile: File,
        inFrames: Long,
        factor: Double,
        outFile: File,
        onProgress: (Double) -> Unit
    ): Long {
        val w = 2048
        val hs = 512
        val maxDelta = 384
        val inReader = FloatReader(inFile)
        val outFrames = (inFrames * factor).toLong()
        val acc = FloatArray((w + hs) * CH)
        val norm = FloatArray(w + hs)
        val window = FloatArray(w) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / (w - 1))).toFloat() }
        val grain = FloatArray(w * CH)
        val tailLen = w - hs
        var aPos = 0.0
        var written = 0L
        FloatWriter(outFile).use { writer ->
            var grainCount = 0L
            while (written < outFrames) {
                // Find the best-correlating analysis position.
                var delta = 0
                if (grainCount > 0) {
                    var best = Double.NEGATIVE_INFINITY
                    var d = -maxDelta
                    while (d <= maxDelta) {
                        val start = (aPos + d).toLong()
                        var dot = 0.0
                        var i = 0
                        while (i < tailLen) {
                            val g = inReader.frame(start + i)
                            val a = g[0] + g[1]
                            val b = acc[i * CH] + acc[i * CH + 1]
                            dot += a * b
                            i += 16
                        }
                        if (dot > best) {
                            best = dot
                            delta = d
                        }
                        d += 8
                    }
                }
                val startFrame = (aPos + delta).toLong()
                for (i in 0 until w) {
                    val g = inReader.frame(startFrame + i)
                    grain[i * CH] = g[0]
                    grain[i * CH + 1] = g[1]
                }
                for (i in 0 until w) {
                    val win = window[i]
                    acc[i * CH] += grain[i * CH] * win
                    acc[i * CH + 1] += grain[i * CH + 1] * win
                    norm[i] += win
                }
                // Emit one synthesis hop, normalised by the window sum.
                val emit = FloatArray(hs * CH)
                for (i in 0 until hs) {
                    val nrm = if (norm[i] > 1e-6f) norm[i] else 1f
                    emit[i * CH] = acc[i * CH] / nrm
                    emit[i * CH + 1] = acc[i * CH + 1] / nrm
                }
                writer.write(emit, hs)
                written += hs
                // Shift the accumulators by one hop.
                System.arraycopy(acc, hs * CH, acc, 0, (w) * CH)
                java.util.Arrays.fill(acc, w * CH, acc.size, 0f)
                System.arraycopy(norm, hs, norm, 0, w)
                java.util.Arrays.fill(norm, w, norm.size, 0f)
                aPos += hs / factor
                grainCount++
                if (grainCount % 128 == 0L) {
                    onProgress((written.toDouble() / outFrames).coerceIn(0.0, 1.0))
                }
            }
        }
        return outFrames
    }

    // ------------------------------------------------------------------
    // EQ + reverb pass
    // ------------------------------------------------------------------

    private fun applyFxPass(inFile: File, frames: Long, outFile: File, req: ExportRequest) {
        // Combined target gains (millibels -> dB), FxController's rule:
        // per band, whichever enabled preset moves more wins.
        fun bandGain(band: Int): Double {
            val garam = intArrayOf(300, 150, 0, -350, -900)
            val naram = intArrayOf(-150, -50, 0, -250, -500)
            val g = if (req.garam) garam[band] else 0
            val n = if (req.naram) naram[band] else 0
            return (if (kotlin.math.abs(g) >= kotlin.math.abs(n)) g else n) / 100.0
        }
        val lowShelf = if (req.garam || req.naram) Biquad.lowShelf(200.0, bandGain(0)) else null
        val highShelf = if (req.garam || req.naram) Biquad.highShelf(4000.0, bandGain(4)) else null
        val reverb = if (req.geela) StereoReverb() else null
        val reader = FloatReader(inFile)
        FloatWriter(outFile).use { writer ->
            var pos = 0L
            val block = FloatArray(BLOCK * CH)
            while (pos < frames) {
                val n = min(BLOCK.toLong(), frames - pos).toInt()
                for (i in 0 until n) {
                    val f = reader.frame(pos + i)
                    block[i * CH] = f[0]
                    block[i * CH + 1] = f[1]
                }
                lowShelf?.process(block, n)
                highShelf?.process(block, n)
                reverb?.process(block, n)
                writer.write(block, n)
                pos += n
            }
        }
    }

    /** RBJ-cookbook shelf biquad, stereo, Direct Form I. */
    private class Biquad private constructor(
        private val b0: Double, private val b1: Double, private val b2: Double,
        private val a1: Double, private val a2: Double
    ) {
        private val x1 = DoubleArray(2)
        private val x2 = DoubleArray(2)
        private val y1 = DoubleArray(2)
        private val y2 = DoubleArray(2)

        fun process(block: FloatArray, frames: Int) {
            for (c in 0..1) {
                var lx1 = x1[c]; var lx2 = x2[c]; var ly1 = y1[c]; var ly2 = y2[c]
                for (i in 0 until frames) {
                    val x = block[i * 2 + c].toDouble()
                    val y = b0 * x + b1 * lx1 + b2 * lx2 - a1 * ly1 - a2 * ly2
                    block[i * 2 + c] = y.toFloat()
                    lx2 = lx1; lx1 = x; ly2 = ly1; ly1 = y
                }
                x1[c] = lx1; x2[c] = lx2; y1[c] = ly1; y2[c] = ly2
            }
        }

        companion object {
            fun lowShelf(f0: Double, gainDb: Double): Biquad {
                val a = Math.pow(10.0, gainDb / 40.0)
                val w = 2.0 * PI * f0 / SR
                val alpha = sin(w) / 2.0 * sqrt((a + 1.0 / a) * (1.0 / 1.0 - 1.0) + 2.0)
                val cosw = cos(w)
                val sq = 2.0 * sqrt(a) * alpha
                val b0 = a * ((a + 1) - (a - 1) * cosw + sq)
                val b1 = 2.0 * a * ((a - 1) - (a + 1) * cosw)
                val b2 = a * ((a + 1) - (a - 1) * cosw - sq)
                val a0 = (a + 1) + (a - 1) * cosw + sq
                val a1 = -2.0 * ((a - 1) + (a + 1) * cosw)
                val a2 = (a + 1) + (a - 1) * cosw - sq
                return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }

            fun highShelf(f0: Double, gainDb: Double): Biquad {
                val a = Math.pow(10.0, gainDb / 40.0)
                val w = 2.0 * PI * f0 / SR
                val alpha = sin(w) / 2.0 * sqrt((a + 1.0 / a) * (1.0 / 1.0 - 1.0) + 2.0)
                val cosw = cos(w)
                val sq = 2.0 * sqrt(a) * alpha
                val b0 = a * ((a + 1) + (a - 1) * cosw + sq)
                val b1 = -2.0 * a * ((a - 1) + (a + 1) * cosw)
                val b2 = a * ((a + 1) + (a - 1) * cosw - sq)
                val a0 = (a + 1) - (a - 1) * cosw + sq
                val a1 = 2.0 * ((a - 1) - (a + 1) * cosw)
                val a2 = (a + 1) - (a - 1) * cosw - sq
                return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }
        }
    }

    /** Schroeder reverb (4 combs + 2 allpass per channel), wet 0.30 —
     *  the same recipe SelfBeats renders with, delays scaled to 44.1k. */
    private class StereoReverb {
        private class Chain {
            val combDelays = intArrayOf(1558, 1618, 1492, 1422)
            val combs = combDelays.map { FloatArray(it) }
            val combIdx = IntArray(4)
            val combLp = FloatArray(4)
            val apDelays = intArrayOf(556, 440)
            val aps = apDelays.map { FloatArray(it) }
            val apIdx = IntArray(2)

            fun tick(x: Float): Float {
                var wet = 0f
                for (c in 0..3) {
                    val buf = combs[c]
                    val idx = combIdx[c]
                    combLp[c] = combLp[c] * 0.30f + buf[idx] * 0.70f
                    buf[idx] = x + combLp[c] * 0.76f
                    wet += buf[idx] * 0.25f
                    combIdx[c] = (idx + 1) % combDelays[c]
                }
                var y = wet
                for (a in 0..1) {
                    val buf = aps[a]
                    val idx = apIdx[a]
                    val outAp = buf[idx] - y * 0.5f
                    buf[idx] = y + outAp * 0.5f
                    y = outAp
                    apIdx[a] = (idx + 1) % apDelays[a]
                }
                return y
            }
        }

        private val left = Chain()
        private val right = Chain()

        fun process(block: FloatArray, frames: Int) {
            for (i in 0 until frames) {
                val l = block[i * 2]
                val r = block[i * 2 + 1]
                block[i * 2] = l + left.tick(l) * 0.30f
                block[i * 2 + 1] = r + right.tick(r) * 0.30f
            }
        }
    }

    // ------------------------------------------------------------------
    // Final mix + AAC encode
    // ------------------------------------------------------------------

    private class LayerReader(val reader: PcmReader, val frames: Long, val gain: Float) {
        fun fill(posFrame: Long, count: Int, into: FloatArray) {
            if (frames <= 0) return
            var done = 0
            while (done < count) {
                val at = (posFrame + done) % frames
                val n = min((count - done).toLong(), frames - at).toInt()
                reader.read(at, n, into, gain, accumulate = true, offsetFrames = done)
                done += n
            }
        }
    }

    private fun encodeMix(
        musicFile: File,
        musicFrames: Long,
        layers: List<LayerReader>,
        outFile: File,
        onProgress: (Double) -> Unit
    ) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SR, CH).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        encoder.start()
        val info = MediaCodec.BufferInfo()
        var track = -1
        var muxerStarted = false
        var inputEos = false
        var outputEos = false
        var pos = 0L
        val music = FloatReader(musicFile)
        val pcmBytes = ByteBuffer.allocate(BLOCK * 4).order(ByteOrder.LITTLE_ENDIAN)

        fun drain(end: Boolean) {
            while (true) {
                val idx = encoder.dequeueOutputBuffer(info, if (end) 100_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    idx >= 0 -> {
                        val buf = encoder.getOutputBuffer(idx)!!
                        if (info.size > 0 && muxerStarted &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        encoder.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEos = true
                            return
                        }
                    }
                    else -> return
                }
            }
        }

        try {
            while (!outputEos) {
                if (!inputEos) {
                    val inIdx = encoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val n = min(BLOCK.toLong(), musicFrames - pos).toInt()
                        if (n <= 0) {
                            val buf = encoder.getInputBuffer(inIdx)!!
                            buf.clear()
                            encoder.queueInputBuffer(
                                inIdx, 0, 0, pos * 1_000_000L / SR,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEos = true
                        } else {
                            val mix = FloatArray(n * CH)
                            for (i in 0 until n) {
                                val f = music.frame(pos + i)
                                mix[i * CH] = f[0]
                                mix[i * CH + 1] = f[1]
                            }
                            layers.forEach { it.fill(pos, n, mix) }
                            pcmBytes.clear()
                            for (i in 0 until n * CH) {
                                val v = (mix[i] * 0.9f).coerceIn(-1f, 1f)
                                pcmBytes.putShort((v * 32767f).toInt().toShort())
                            }
                            pcmBytes.flip()
                            val buf = encoder.getInputBuffer(inIdx)!!
                            buf.clear()
                            val toPut = min(buf.remaining(), pcmBytes.remaining())
                            val slice = pcmBytes.duplicate()
                            slice.limit(slice.position() + toPut)
                            buf.put(slice)
                            encoder.queueInputBuffer(
                                inIdx, 0, toPut, pos * 1_000_000L / SR, 0
                            )
                            pos += toPut / 4
                            onProgress((pos.toDouble() / musicFrames).coerceIn(0.0, 1.0))
                        }
                    }
                }
                drain(inputEos)
                if (inputEos && !outputEos) drain(true)
            }
        } finally {
            encoder.stop()
            encoder.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
        }
    }

    // ------------------------------------------------------------------
    // Saving into the device Music library
    // ------------------------------------------------------------------

    /** Copy the rendered file into Music/Lomix; returns where it went. */
    fun saveToMusic(context: Context, src: File, displayName: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Lomix")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            resolver.openOutputStream(uri)!!.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
            val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return "Music/Lomix/$displayName"
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "Lomix"
        ).apply { mkdirs() }
        val target = File(dir, displayName)
        src.copyTo(target, overwrite = true)
        return target.absolutePath
    }

    // ------------------------------------------------------------------
    // Small PCM file helpers
    // ------------------------------------------------------------------

    /** Sequential float writer (interleaved stereo, raw little-endian). */
    private class FloatWriter(file: File) : AutoCloseable {
        private val raf = RandomAccessFile(file, "rw")
        private val buf = ByteBuffer.allocate(BLOCK * 4 * 4).order(ByteOrder.LITTLE_ENDIAN)

        init {
            raf.setLength(0)
        }

        fun write(frames: FloatArray, count: Int) {
            var i = 0
            while (i < count * CH) {
                buf.clear()
                val n = min(buf.capacity() / 4, count * CH - i)
                for (j in 0 until n) buf.putFloat(frames[i + j])
                buf.flip()
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                raf.write(bytes)
                i += n
            }
        }

        override fun close() = raf.close()
    }

    /** Random-access float reader; out-of-range frames read as silence. */
    private class FloatReader(private val file: File) {
        private val raf = RandomAccessFile(file, "r")
        private val totalFrames = file.length() / 8L
        private var cacheStart = -1L
        private val cache = FloatArray(BLOCK * CH)
        private val pair = FloatArray(2)

        fun frame(at: Long): FloatArray {
            if (at < 0 || at >= totalFrames) {
                pair[0] = 0f
                pair[1] = 0f
                return pair
            }
            val blockStart = at / BLOCK * BLOCK
            if (blockStart != cacheStart) {
                cacheStart = blockStart
                raf.seek(blockStart * 8L)
                val bytes = ByteArray(BLOCK * 8)
                val n = raf.read(bytes)
                val bb = ByteBuffer.wrap(bytes, 0, maxOf(n, 0)).order(ByteOrder.LITTLE_ENDIAN)
                val fb = bb.asFloatBuffer()
                fb.get(cache, 0, min(cache.size, maxOf(n, 0) / 4))
                if (n in 0 until bytes.size) {
                    java.util.Arrays.fill(cache, n / 4, cache.size, 0f)
                }
            }
            val idx = ((at - blockStart) * CH).toInt()
            pair[0] = cache[idx]
            pair[1] = cache[idx + 1]
            return pair
        }
    }

    /** Random-access reader over a canonical int16 PCM file; reads into
     *  a float block scaled by [gain], accumulating or overwriting. */
    private class PcmReader(val file: File) {
        private val raf = RandomAccessFile(file, "r")

        fun read(
            atFrame: Long,
            count: Int,
            into: FloatArray,
            gain: Float,
            accumulate: Boolean,
            offsetFrames: Int = 0
        ): Int {
            raf.seek(atFrame * 4L)
            val bytes = ByteArray(count * 4)
            val n = raf.read(bytes)
            if (n <= 0) return 0
            val shorts = ByteBuffer.wrap(bytes, 0, n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val frames = n / 4
            for (i in 0 until frames) {
                val base = (offsetFrames + i) * CH
                val l = shorts.get(i * 2) / 32768f * gain
                val r = shorts.get(i * 2 + 1) / 32768f * gain
                if (accumulate) {
                    into[base] += l
                    into[base + 1] += r
                } else {
                    into[base] = l
                    into[base + 1] = r
                }
            }
            return frames
        }
    }
}
