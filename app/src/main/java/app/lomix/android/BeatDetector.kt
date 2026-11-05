package app.lomix.android

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A detected beat grid: tempo plus where the first beat falls. */
data class BeatInfo(val bpm: Double, val offsetMs: Double)

/**
 * Automatic tempo + beat-phase detection for the Beats layer (v1.2),
 * a pure-Kotlin port of the algorithm prototyped and verified offline:
 *
 *  1. Decode the song to mono PCM (MediaExtractor + MediaCodec),
 *     resample to 22050 Hz, capped at the first ~3 minutes (tempo is
 *     constant in studio recordings; this bounds CPU and memory).
 *  2. Spectral-flux onset envelope: 2048-sample Hann windows, hop 512,
 *     sum of positive magnitude changes per frame.
 *  3. Autocorrelation over lags for 48-172 BPM, weighted by a broad
 *     log-tempo prior centred on 96 BPM (prefers the slower octave,
 *     which is the feel lofi drums want anyway).
 *  4. Joint refinement of period and phase on a fine grid (0.05-frame
 *     period steps, 0.25-frame phase steps) maximising the mean flux
 *     on the beat grid — this is what gives sub-0.1 BPM precision, so
 *     the grid does not drift against the song over minutes.
 *
 * Verified offline against the original Mann Ki Lagan (73.9 BPM) and
 * Mazeen's own lofi remix of it (68.0 BPM, grid phase constant to
 * within a millisecond across the whole song), plus synthetic tracks
 * at exactly 76/92 BPM (detected within 0.05 BPM).
 *
 * Returns null when there is too little audio or anything goes wrong;
 * the caller then falls back to a manual BPM.
 */
object BeatDetector {

    private const val SR = 22050
    private const val WIN = 2048
    private const val HOP = 512
    private const val FPS = SR.toDouble() / HOP
    private const val MAX_SECONDS = 180
    private const val MIN_BPM = 48.0
    private const val MAX_BPM = 172.0
    private const val PRIOR_BPM = 96.0

    fun detect(context: Context, uri: Uri): BeatInfo? {
        val pcm = try {
            decodeMonoResampled(context, uri)
        } catch (e: Exception) {
            null
        } ?: return null
        if (pcm.size < SR * 20) return null
        val flux = fluxEnvelope(pcm)
        if (flux.size < 200) return null
        return gridFromFlux(flux)
    }

    // ---- Stage 1: decode -------------------------------------------------

    /** Decode to mono float PCM at [SR]; stops after ~[MAX_SECONDS]. */
    private fun decodeMonoResampled(context: Context, uri: Uri): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    track = i
                    break
                }
            }
            if (track < 0) return null
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var srcRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            var channels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 1

            val maxNativeSamples = srcRate * MAX_SECONDS
            var mono = FloatArray(1 shl 20)
            var monoCount = 0

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone && monoCount < maxNativeSamples) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuffer = codec.getInputBuffer(inIndex) ?: continue
                        val size = extractor.readSampleData(inBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex >= 0 -> {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                        val outBuffer = codec.getOutputBuffer(outIndex)
                        if (outBuffer != null && bufferInfo.size > 0) {
                            outBuffer.position(bufferInfo.offset)
                            outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val shorts = outBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val frames = shorts.remaining() / maxOf(1, channels)
                            if (monoCount + frames > mono.size) {
                                var newSize = mono.size * 2
                                while (newSize < monoCount + frames) newSize *= 2
                                mono = mono.copyOf(newSize)
                            }
                            for (fr in 0 until frames) {
                                var sum = 0f
                                for (ch in 0 until channels) {
                                    sum += shorts.get(fr * channels + ch).toFloat()
                                }
                                mono[monoCount + fr] = sum / (channels * 32768f)
                            }
                            monoCount += frames
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                }
            }
            if (monoCount == 0) return null
            mono = mono.copyOf(monoCount)

            // Linear-interpolation resample to SR.
            val step = srcRate.toDouble() / SR
            val outLen = (monoCount / step).toInt()
            val out = FloatArray(outLen)
            for (i in 0 until outLen) {
                val pos = i * step
                val i0 = pos.toInt()
                val i1 = min(i0 + 1, monoCount - 1)
                val frac = (pos - i0).toFloat()
                out[i] = mono[i0] * (1 - frac) + mono[i1] * frac
            }
            return out
        } finally {
            try {
                codec?.stop()
            } catch (e: Exception) {
            }
            try {
                codec?.release()
            } catch (e: Exception) {
            }
            extractor.release()
        }
    }

    // ---- Stage 2: spectral flux ------------------------------------------

    private fun fluxEnvelope(pcm: FloatArray): DoubleArray {
        val frames = 1 + maxOf(0, (pcm.size - WIN) / HOP)
        val flux = DoubleArray(frames)
        val window = DoubleArray(WIN) { i -> 0.5 * (1.0 - cos(2.0 * Math.PI * i / (WIN - 1))) }
        val re = DoubleArray(WIN)
        val im = DoubleArray(WIN)
        var prevMag: DoubleArray? = null
        for (f in 0 until frames) {
            val base = f * HOP
            for (i in 0 until WIN) {
                re[i] = pcm[base + i] * window[i]
                im[i] = 0.0
            }
            fft(re, im)
            val mag = DoubleArray(WIN / 2 + 1) { k -> sqrt(re[k] * re[k] + im[k] * im[k]) }
            val prev = prevMag
            if (prev != null) {
                var sum = 0.0
                for (k in 0 until mag.size) {
                    val d = mag[k] - prev[k]
                    if (d > 0) sum += d
                }
                flux[f] = sum
            }
            prevMag = mag
        }
        return flux
    }

    /** Iterative radix-2 FFT, in place; arrays must be power-of-two sized. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            val half = len / 2
            var i = 0
            while (i < n) {
                var cwr = 1.0
                var cwi = 0.0
                for (k in 0 until half) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + half] * cwr - im[i + k + half] * cwi
                    val vi = re[i + k + half] * cwi + im[i + k + half] * cwr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + half] = ur - vr
                    im[i + k + half] = ui - vi
                    val nwr = cwr * wr - cwi * wi
                    cwi = cwr * wi + cwi * wr
                    cwr = nwr
                }
                i += len
            }
            len = len shl 1
        }
    }

    // ---- Stages 3+4: autocorrelation + comb refinement --------------------

    private fun gridFromFlux(flux: DoubleArray): BeatInfo? {
        val n = flux.size
        val mean = flux.average()
        val f = DoubleArray(n) { flux[it] - mean }

        var bestLag = -1
        var bestScore = Double.NEGATIVE_INFINITY
        var lag = 15
        while (lag < 100) {
            val bpm = FPS * 60.0 / lag
            if (bpm in MIN_BPM..MAX_BPM) {
                var dot = 0.0
                for (i in 0 until n - lag) dot += f[i] * f[i + lag]
                val ac = dot / (n - lag)
                val prior = exp(-0.5 * Math.pow(ln(bpm / PRIOR_BPM) / ln(2.0), 2.0))
                // note: prior sigma is 1 octave, as prototyped
                val score = ac * prior
                if (score > bestScore) {
                    bestScore = score
                    bestLag = lag
                }
            }
            lag++
        }
        if (bestLag < 0) return null

        var bestPeriod = bestLag.toDouble()
        var bestOffset = 0.0
        var bestComb = Double.NEGATIVE_INFINITY
        var period = bestLag - 1.5
        while (period <= bestLag + 1.5) {
            var offset = 0.0
            while (offset < period) {
                val s = combScore(flux, period, offset)
                if (s > bestComb) {
                    bestComb = s
                    bestPeriod = period
                    bestOffset = offset
                }
                offset += 0.25
            }
            period += 0.05
        }
        if (bestComb <= 0.0) return null
        return BeatInfo(
            bpm = FPS * 60.0 / bestPeriod,
            offsetMs = bestOffset / FPS * 1000.0
        )
    }

    /** Mean flux sampled on the beat grid (fractional, interpolated). */
    private fun combScore(flux: DoubleArray, period: Double, offset: Double): Double {
        val n = flux.size
        var sum = 0.0
        var count = 0
        var pos = offset
        while (pos < n - 1) {
            val i0 = pos.toInt()
            val frac = pos - i0
            sum += flux[i0] * (1 - frac) + flux[i0 + 1] * frac
            count++
            pos += period
        }
        return if (count > 0) sum / count else 0.0
    }
}
