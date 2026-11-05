package app.lomix.android

import android.media.audiofx.Equalizer
import android.media.audiofx.PresetReverb
import kotlin.math.abs

/**
 * The "Lofi Rang" effects that live on the song player's audio session,
 * built on Android's audiofx APIs:
 *
 *  - Garam (warm) : strong low boost + hard top cut — the "Lofi Dirt" shape
 *                   Mazeen liked in BandLab (warm + muffled top).
 *  - Naram (soft) : gentle top cut + slight low trim.
 *  - Geela (wet)  : PresetReverb medium room.
 *
 * Vinyl is not here — it is a separate looping player in [PlayerController].
 *
 * Garam/Naram are drawn as 5-point target curves in millibels (low -> high)
 * and mapped onto however many bands the device's equalizer actually has
 * (see [mapCurve]). If both are on, the combine rule is simple: per band,
 * whichever preset asks for the stronger move (bigger absolute gain) wins.
 *
 * Everything is wrapped in try/catch: some devices refuse audiofx control
 * for some sessions, and the app must keep playing silently in that case.
 */
class FxController {

    private var equalizer: Equalizer? = null
    private var reverb: PresetReverb? = null
    private var attachedSessionId: Int = 0

    /** Create the effects for [sessionId]. No-op for the same session twice. */
    fun attach(sessionId: Int) {
        if (sessionId <= 0 || sessionId == attachedSessionId) return
        release()
        attachedSessionId = sessionId
        try {
            equalizer = Equalizer(0, sessionId)
        } catch (e: Exception) {
            equalizer = null
        }
        try {
            reverb = PresetReverb(0, sessionId)
        } catch (e: Exception) {
            reverb = null
        }
    }

    fun apply(garam: Boolean, naram: Boolean, geela: Boolean) {
        applyEqualizer(garam, naram)
        applyReverb(geela)
    }

    private fun applyEqualizer(garam: Boolean, naram: Boolean) {
        val eq = equalizer ?: return
        try {
            if (!garam && !naram) {
                eq.enabled = false
                return
            }
            val bands = eq.numberOfBands.toInt()
            if (bands <= 0) {
                eq.enabled = false
                return
            }
            val range = eq.bandLevelRange // [min, max] in millibels
            val garamCurve = mapCurve(GARAM_GAINS_MB, bands)
            val naramCurve = mapCurve(NARAM_GAINS_MB, bands)
            for (band in 0 until bands) {
                val g = if (garam) garamCurve[band] else 0
                val n = if (naram) naramCurve[band] else 0
                val target = if (abs(g) >= abs(n)) g else n
                val clamped = target.coerceIn(range[0].toInt(), range[1].toInt())
                eq.setBandLevel(band.toShort(), clamped.toShort())
            }
            eq.enabled = true
        } catch (e: Exception) {
            // Device refused EQ control — leave the song playing as-is.
        }
    }

    private fun applyReverb(geela: Boolean) {
        val rv = reverb ?: return
        try {
            if (geela) {
                rv.preset = PresetReverb.PRESET_MEDIUMROOM
                rv.enabled = true
            } else {
                rv.enabled = false
            }
        } catch (e: Exception) {
            // Same reasoning as the equalizer: never crash over an effect.
        }
    }

    fun release() {
        try {
            equalizer?.release()
        } catch (e: Exception) {
        }
        try {
            reverb?.release()
        } catch (e: Exception) {
        }
        equalizer = null
        reverb = null
        attachedSessionId = 0
    }

    companion object {
        // 5-point target curves, low -> high, in millibels.
        private val GARAM_GAINS_MB = intArrayOf(300, 150, 0, -350, -900)
        private val NARAM_GAINS_MB = intArrayOf(-150, -50, 0, -250, -500)

        /** Linear-interpolate a target curve onto [bands] device bands. */
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
