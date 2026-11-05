package app.lomix.android

import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Workaround B (v1.3.0): beats built from the song's OWN drums.
 *
 * The v1.2.1 loops copied Mann Ki Lagan's remix drums and sounded
 * wrong on every other song. This extractor does that same trick per
 * song, on-device: using the detected beat grid, it searches the
 * decoded PCM for the song's cleanest kick-like hit (strong low-band
 * thump at a kick slot, spectrally low) and snare-like hit (strong
 * mid-band hit at a 2 & 4 backbeat slot), cuts them out, and renders
 * the house universal pattern as a 2-bar WAV loop at the song's own
 * tempo, with a little generated reverb for goonj.
 *
 * Quality gates (strength + spectral shape, tuned on the offline
 * prototype against the Mann Ki Lagan original and Mazeen's remix):
 * if either hit is missing or too weak, this returns false and the
 * controller falls back to the built-in loops — results vary per
 * song by design, and a bad extraction must never reach the ears.
 */
object SelfBeats {

    private const val SR = BeatDetector.SR  // 22050
    private const val FFT_N = 2048

    private const val MIN_STRENGTH = 2.8
    private const val KICK_MIN_LOW = 22.0    // % of spectrum below 150 Hz
    private const val SNARE_MIN_MID = 20.0   // % of spectrum in 900-3400 Hz

    /** fullness: 0 = sparse (Smooth), 1 = standard, 2 = fuller (Dynamic). */
    fun buildLoop(pcm: FloatArray, info: BeatInfo, fullness: Int, outFile: File): Boolean {
        return try {
            val loop = renderLoop(pcm, info, fullness) ?: return false
            writeWav(outFile, loop)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun renderLoop(pcm: FloatArray, info: BeatInfo, fullness: Int): FloatArray? {
        val beatSec = 60.0 / info.bpm
        val stepSec = beatSec / 4.0
        val offsetSec = info.offsetMs / 1000.0
        val durationSec = pcm.size.toDouble() / SR
        if (durationSec < 30.0) return null

        val lowEnv = bandEnvelope(pcm, 30.0, 150.0)
        val midEnv = bandEnvelope(pcm, 900.0, 3400.0)

        val kickAt = searchHit(pcm, lowEnv, offsetSec, beatSec, stepSec,
            durationSec, intArrayOf(0, 4, 8, 10, 12, 14), isKick = true) ?: return null
        val snareAt = searchHit(pcm, midEnv, offsetSec, beatSec, stepSec,
            durationSec, intArrayOf(4, 12), isKick = false) ?: return null

        val kick = cutHit(pcm, kickAt, 0.34) ?: return null
        val snare = cutHit(pcm, snareAt, 0.42) ?: return null
        val tick = makeTick()

        val loopLen = (8.0 * beatSec * SR).roundToInt()
        val total = loopLen * 3
        val buf = FloatArray(total)
        val events = pattern(fullness, kick, snare, tick)
        for (rep in 0 until 3) {
            val base = rep * loopLen
            for ((step, sample, gain) in events) {
                val i0 = base + (step * stepSec * SR).roundToInt()
                val n = min(sample.size, total - i0)
                for (i in 0 until n) buf[i0 + i] += sample[i] * gain
            }
        }
        // Gentle goonj (Schroeder reverb), then take the middle loop so
        // pattern and tails wrap seamlessly, like the baked-in loops.
        val wet = reverb(buf)
        for (i in buf.indices) buf[i] += wet[i] * 0.35f
        val loop = buf.copyOfRange(loopLen, 2 * loopLen)
        var peak = 0f
        for (v in loop) peak = maxOf(peak, abs(v))
        if (peak <= 0f) return null
        val scale = 0.8f / peak
        for (i in loop.indices) loop[i] *= scale
        // One-sided seam crossfade: the tail leans into the head.
        val fadeN = (0.025 * SR).toInt()
        for (i in 0 until fadeN) {
            val w = (i + 1f) / (fadeN + 1f)
            loop[loopLen - fadeN + i] = loop[loopLen - fadeN + i] * (1 - w) + loop[i] * w
        }
        return loop
    }

    private fun pattern(fullness: Int, kick: FloatArray, snare: FloatArray, tick: FloatArray):
        List<Triple<Int, FloatArray, Float>> {
        val ev = mutableListOf<Triple<Int, FloatArray, Float>>()
        fun k(step: Int, g: Float) = ev.add(Triple(step, kick, g))
        fun s(step: Int, g: Float) = ev.add(Triple(step, snare, g))
        fun t(step: Int, g: Float) = ev.add(Triple(step, tick, g))
        when (fullness) {
            0 -> { // sparse
                for (o in intArrayOf(0, 16)) {
                    k(o + 0, 1.0f); s(o + 4, 1.0f); s(o + 12, 1.0f)
                    t(o + 6, 0.25f); t(o + 14, 0.25f)
                }
            }
            2 -> { // fuller
                for (o in intArrayOf(0, 16)) {
                    k(o + 0, 1.0f); k(o + 7, 0.6f); k(o + 10, 0.88f)
                    s(o + 4, 1.0f); s(o + 12, 1.0f); s(o + 15, 0.42f)
                    for (h in 0 until 16 step 2) t(o + h, if (h % 4 == 0) 0.34f else 0.26f)
                }
                k(23, 0.6f); s(31, 0.42f)
            }
            else -> { // standard
                for (o in intArrayOf(0, 16)) {
                    k(o + 0, 1.0f); k(o + 10, 0.88f)
                    s(o + 4, 1.0f); s(o + 12, 1.0f)
                    for (h in intArrayOf(2, 6, 10, 14)) t(o + h, 0.32f)
                }
                k(23, 0.5f)
            }
        }
        return ev
    }

    // ---- hit search --------------------------------------------------------

    private data class Hit(val timeSec: Double, val strength: Double)

    private fun searchHit(
        pcm: FloatArray, env: FloatArray, offsetSec: Double, beatSec: Double,
        stepSec: Double, durationSec: Double, slots: IntArray, isKick: Boolean
    ): Double? {
        // Local baseline: median envelope per 1 s block, median of the
        // ~5 blocks around a candidate — the prototype's ±2 s median.
        val blocks = maxOf(1, durationSec.toInt())
        val blockMedian = DoubleArray(blocks)
        for (b in 0 until blocks) {
            val seg = env.copyOfRange(b * SR, min(env.size, (b + 1) * SR))
            if (seg.isNotEmpty()) blockMedian[b] = median(seg)
        }
        fun localMedian(t: Double): Double {
            val c = t.toInt()
            val vals = mutableListOf<Double>()
            for (b in (c - 2)..(c + 2)) if (b in blockMedian.indices) vals.add(blockMedian[b])
            if (vals.isEmpty()) return 1e-6
            vals.sort()
            return vals[vals.size / 2]
        }

        val peakWin = (0.09 * SR).toInt()
        val tailA = (0.30 * SR).toInt()
        val tailB = (0.42 * SR).toInt()
        var best: Hit? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var bar = offsetSec
        val tMax = min(durationSec, 180.0) - 2.0
        while (bar < tMax) {
            for (slot in slots) {
                val t = bar + slot * stepSec
                if (t < 15.0 || t > tMax) continue
                val i0 = (t * SR).toInt()
                if (i0 + tailB >= env.size) continue
                var peak = 0f
                for (i in i0 until i0 + peakWin) peak = maxOf(peak, env[i])
                if (peak <= 0f) continue
                var tail = 0f
                for (i in i0 + tailA until i0 + tailB) tail = maxOf(tail, env[i])
                val strength = peak / (localMedian(t) + 1e-6)
                val isolation = 1.0 - tail / peak
                val (lowShare, midShare) = spectrumShares(pcm, i0)
                val specScore: Double
                if (isKick) {
                    if (lowShare < KICK_MIN_LOW) continue
                    specScore = min(lowShare / 40.0, 1.0)
                } else {
                    if (midShare < SNARE_MIN_MID) continue
                    specScore = min(midShare / 35.0, 1.0)
                }
                val score = strength * maxOf(isolation, 0.05) * (0.4 + 0.6 * specScore)
                if (score > bestScore) {
                    bestScore = score
                    best = Hit(t, strength)
                }
            }
            bar += 4.0 * beatSec
        }
        val hit = best ?: return null
        return if (hit.strength >= MIN_STRENGTH) hit.timeSec else null
    }

    /** Hann-windowed FFT spectrum shares: (% below 150 Hz, % in 900-3400 Hz). */
    private fun spectrumShares(pcm: FloatArray, start: Int): Pair<Double, Double> {
        if (start + FFT_N > pcm.size) return Pair(0.0, 0.0)
        val re = DoubleArray(FFT_N)
        val im = DoubleArray(FFT_N)
        for (i in 0 until FFT_N) {
            val w = 0.5 * (1.0 - cos(2.0 * Math.PI * i / (FFT_N - 1)))
            re[i] = pcm[start + i] * w
        }
        fft(re, im)
        val binHz = SR.toDouble() / FFT_N
        var low = 0.0
        var mid = 0.0
        var total = 0.0
        for (k in 1..FFT_N / 2) {
            val mag = sqrt(re[k] * re[k] + im[k] * im[k])
            val f = k * binHz
            total += mag
            if (f < 150.0) low += mag
            if (f in 900.0..3400.0) mid += mag
        }
        if (total <= 0.0) return Pair(0.0, 0.0)
        return Pair(low / total * 100.0, mid / total * 100.0)
    }

    private fun cutHit(pcm: FloatArray, t: Double, durSec: Double): FloatArray? {
        val winN = (0.09 * SR).toInt()
        val iStart = (t * SR).toInt()
        if (iStart + winN >= pcm.size) return null
        var peakIdx = iStart
        var peakVal = 0f
        for (i in iStart until iStart + winN) {
            if (abs(pcm[i]) > peakVal) {
                peakVal = abs(pcm[i])
                peakIdx = i
            }
        }
        if (peakVal <= 0f) return null
        val from = maxOf(0, peakIdx - (0.006 * SR).toInt())
        val n = (durSec * SR).toInt()
        if (from + n > pcm.size) return null
        val seg = pcm.copyOfRange(from, from + n)
        val fadeStart = (n * 0.8).toInt()
        for (i in fadeStart until n) {
            seg[i] *= 1f - (i - fadeStart).toFloat() / (n - fadeStart)
        }
        val scale = 0.95f / peakVal
        for (i in seg.indices) seg[i] *= scale
        return seg
    }

    private fun makeTick(): FloatArray {
        val n = (0.055 * SR).toInt()
        val rnd = Random(42)
        val out = FloatArray(n)
        var lp = 0f
        val a = 1.0 - exp(-2.0 * Math.PI * 2500.0 / SR)
        for (i in 0 until n) {
            val white = (rnd.nextFloat() * 2f - 1f)
            lp += (a * (white - lp)).toFloat()
            val hp = white - lp
            val t = i.toDouble() / SR
            out[i] = hp * exp(-t * 62.0).toFloat() * 0.30f
        }
        return out
    }

    // ---- envelopes / filters ------------------------------------------------

    /** Envelope of the band between [lo] and [hi] Hz (one-pole filters). */
    private fun bandEnvelope(pcm: FloatArray, lo: Double, hi: Double): FloatArray {
        val lowPassed = lowPass(pcm, hi)
        val sig = FloatArray(pcm.size)
        if (lo <= 31.0) {
            // Low band: just the low-passed signal itself.
            val lp2 = lowPass(lowPassed, hi)
            for (i in sig.indices) sig[i] = lp2[i]
        } else {
            val lowCut = lowPass(pcm, lo)
            for (i in sig.indices) sig[i] = lowPassed[i] - lowCut[i]
        }
        // |x| smoothed with a ~5 ms moving average via prefix sums.
        val env = FloatArray(pcm.size)
        val half = (0.0025 * SR).toInt()
        var run = 0.0
        val absSig = DoubleArray(pcm.size) { abs(sig[it]).toDouble() }
        val prefix = DoubleArray(pcm.size + 1)
        for (i in absSig.indices) prefix[i + 1] = prefix[i] + absSig[i]
        for (i in env.indices) {
            val a = maxOf(0, i - half)
            val b = min(env.size, i + half)
            run = (prefix[b] - prefix[a]) / (b - a)
            env[i] = run.toFloat()
        }
        return env
    }

    private fun lowPass(x: FloatArray, cutoffHz: Double): FloatArray {
        val out = FloatArray(x.size)
        val a = 1.0 - exp(-2.0 * Math.PI * cutoffHz / SR)
        var y = 0.0
        for (i in x.indices) {
            y += a * (x[i] - y)
            out[i] = y.toFloat()
        }
        return out
    }

    private fun median(a: FloatArray): Double {
        if (a.isEmpty()) return 0.0
        val s = a.sortedArray()
        return s[s.size / 2].toDouble()
    }

    // ---- reverb (Schroeder: 4 combs + 2 allpass) ----------------------------

    private fun reverb(input: FloatArray): FloatArray {
        var sig = FloatArray(input.size)
        val delays = intArrayOf(779, 809, 746, 711)
        for (d in delays) {
            val comb = comb(input, d, 0.76f, 0.30f)
            for (i in sig.indices) sig[i] += comb[i] * 0.25f
        }
        sig = allpass(sig, 278)
        sig = allpass(sig, 220)
        return sig
    }

    private fun comb(input: FloatArray, delay: Int, feedback: Float, damp: Float): FloatArray {
        val buf = FloatArray(delay)
        var idx = 0
        var store = 0f
        val out = FloatArray(input.size)
        for (i in input.indices) {
            val o = buf[idx]
            store = o * (1 - damp) + store * damp
            buf[idx] = input[i] + store * feedback
            out[i] = o
            idx = if (idx + 1 == delay) 0 else idx + 1
        }
        return out
    }

    private fun allpass(input: FloatArray, delay: Int): FloatArray {
        val buf = FloatArray(delay)
        var idx = 0
        val out = FloatArray(input.size)
        for (i in input.indices) {
            val o = buf[idx]
            buf[idx] = input[i] + o * 0.5f
            out[i] = o - input[i]
            idx = if (idx + 1 == delay) 0 else idx + 1
        }
        return out
    }

    // ---- FFT (same radix-2 as BeatDetector) ----------------------------------

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

    // ---- WAV writer (16-bit mono PCM) -----------------------------------------

    private fun writeWav(file: File, samples: FloatArray) {
        val dataSize = samples.size * 2
        val header = ByteArray(44)
        fun putInt(off: Int, v: Int) {
            header[off] = (v and 0xff).toByte()
            header[off + 1] = ((v shr 8) and 0xff).toByte()
            header[off + 2] = ((v shr 16) and 0xff).toByte()
            header[off + 3] = ((v shr 24) and 0xff).toByte()
        }
        fun putShort(off: Int, v: Int) {
            header[off] = (v and 0xff).toByte()
            header[off + 1] = ((v shr 8) and 0xff).toByte()
        }
        "RIFF".toByteArray().copyInto(header, 0)
        putInt(4, 36 + dataSize)
        "WAVE".toByteArray().copyInto(header, 8)
        "fmt ".toByteArray().copyInto(header, 12)
        putInt(16, 16)
        putShort(20, 1)            // PCM
        putShort(22, 1)            // mono
        putInt(24, SR)
        putInt(28, SR * 2)
        putShort(32, 2)
        putShort(34, 16)
        "data".toByteArray().copyInto(header, 36)
        putInt(40, dataSize)
        FileOutputStream(file).use { out ->
            out.write(header)
            val bytes = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val v = (samples[i].coerceIn(-1f, 1f) * 32767).toInt()
                bytes[i * 2] = (v and 0xff).toByte()
                bytes[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
            }
            out.write(bytes)
        }
    }
}
