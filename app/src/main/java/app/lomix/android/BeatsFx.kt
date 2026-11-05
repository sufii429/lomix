package app.lomix.android

import android.media.audiofx.Equalizer

/**
 * EQ character for the beats player, one curve per [BeatsStyle] (v1.2).
 *
 * The drum loops themselves are neutral; the styles Mazeen picks in the
 * UI are these 5-point target curves (low -> high, millibels) mapped
 * onto however many bands the device equalizer has — the same approach
 * as [FxController] for the song, kept separate because it lives on a
 * different audio session. A style also carries a small level factor
 * (applied to the player volume by the controller) so Bass Boost does
 * not clip and Smooth sits a little quieter.
 *
 * Everything is wrapped in try/catch: if a device refuses audiofx on
 * this session, the beats simply play flat, which is still fine.
 */
class BeatsFx {

    private var equalizer: Equalizer? = null
    private var attachedSessionId: Int = 0

    fun attach(sessionId: Int) {
        if (sessionId <= 0 || sessionId == attachedSessionId) return
        release()
        attachedSessionId = sessionId
        try {
            equalizer = Equalizer(0, sessionId)
        } catch (e: Exception) {
            equalizer = null
        }
    }

    fun apply(style: BeatsStyle) {
        val eq = equalizer ?: return
        try {
            val curve = CURVES[style] ?: return
            val bands = eq.numberOfBands.toInt()
            if (bands <= 0) {
                eq.enabled = false
                return
            }
            val range = eq.bandLevelRange
            val mapped = mapCurve(curve, bands)
            for (band in 0 until bands) {
                val clamped = mapped[band].coerceIn(range[0].toInt(), range[1].toInt())
                eq.setBandLevel(band.toShort(), clamped.toShort())
            }
            eq.enabled = true
        } catch (e: Exception) {
            // Device refused EQ control — beats play as recorded.
        }
    }

    fun release() {
        try {
            equalizer?.release()
        } catch (e: Exception) {
        }
        equalizer = null
        attachedSessionId = 0
    }

    companion object {
        // 5-point target curves, low -> high, in millibels.
        private val CURVES: Map<BeatsStyle, IntArray> = mapOf(
            BeatsStyle.BALANCED to intArrayOf(0, 0, 0, 0, 0),
            BeatsStyle.WARM to intArrayOf(250, 150, 0, -300, -700),
            BeatsStyle.BASS_BOOST to intArrayOf(750, 380, 0, -100, -300),
            BeatsStyle.SMOOTH to intArrayOf(100, 50, 0, -450, -900),
            BeatsStyle.DYNAMIC to intArrayOf(150, 50, 0, -150, -400)
        )

        /** Relative level per style, multiplied into the beats volume. */
        fun levelFactor(style: BeatsStyle): Float = when (style) {
            BeatsStyle.BALANCED -> 0.90f
            BeatsStyle.WARM -> 0.90f
            BeatsStyle.BASS_BOOST -> 0.80f
            BeatsStyle.SMOOTH -> 0.82f
            BeatsStyle.DYNAMIC -> 0.95f
        }

        private fun mapCurve(targets: IntArray, bands: Int): IntArray {
            if (bands == 1) return intArrayOf(targets[targets.size / 2])
            return IntArray(bands) { i ->
                val pos = i.toDouble() * (targets.size - 1) / (bands - 1)
                val lo = pos.toInt()
                val hi = minOf(lo + 1, targets.size - 1)
                val frac = pos - lo
                (targets[lo] + (targets[hi] - targets[lo]) * frac).toInt()
            }
        }
    }
}
