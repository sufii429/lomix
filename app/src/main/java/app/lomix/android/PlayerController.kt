package app.lomix.android

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.sin

/** Rain choices of the "Baarish" tab. */
enum class RainChoice { NONE, GENTLE, HEAVY, TIN }

/**
 * Owns the ExoPlayers and keeps them in sync behind ONE master Play/Pause:
 *
 *  - songPlayer    : the picked song; carries speed + the audiofx effects.
 *  - rainPlayer    : a looping bundled rain file (assets), own volume.
 *  - vinylPlayer   : a looping bundled crackle file, fixed low volume.
 *  - cassettePlayer: a looping bundled tape-hiss file for the Cassette
 *                    effect (which also wobbles the song's pitch slightly).
 *
 * Since v1.1 this controller lives in [PlaybackService] (not the Activity),
 * so playback continues with the screen off or the app swiped away, and it
 * carries the whole mix state itself: the UI reads its starting values from
 * here and writes every change back, which is what lets a reopened Activity
 * find the mix exactly as it was left. It also owns the sleep timer.
 *
 * Bundled files are addressed as asset:/// URIs, which ExoPlayer's default
 * data source resolves from the app assets.
 */
class PlayerController(context: Context) {

    val songPlayer: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()

    private val rainPlayer: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
    }

    private val vinylPlayer: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
        volume = VINYL_VOLUME
    }

    private val cassettePlayer: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
        volume = CASSETTE_VOLUME
    }

    /** Lofi effects attached to the song player's audio session. */
    val fx = FxController()

    // ---- Mix state (the single source of truth since v1.1) ---------------
    var songUri: Uri? = null
        private set
    var songName: String = ""
        private set
    var speed: Float = 0.95f
        private set
    var garam: Boolean = true
        private set
    var naram: Boolean = false
        private set
    var geela: Boolean = false
        private set
    var vinylOn: Boolean = false
        private set
    var cassetteOn: Boolean = false
        private set
    var rainChoice: RainChoice = RainChoice.GENTLE
        private set
    var rainVolumePercent: Int = 40
        private set

    /** Which rain file is currently loaded into rainPlayer (null = none yet). */
    private var rainLoaded: RainChoice? = null

    /** Master playing state: the song player drives it, layers follow. */
    val isPlaying: Boolean get() = songPlayer.isPlaying

    /** Called whenever something the notification shows may have changed. */
    var onStateChanged: (() -> Unit)? = null

    // ---- Sleep timer ------------------------------------------------------
    private val handler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null
    var sleepMinutes: Int = 0
        private set
    private var sleepDeadlineMs: Long = 0L

    val sleepRemainingMs: Long
        get() = if (sleepMinutes > 0) {
            (sleepDeadlineMs - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        } else 0L

    fun applySleepTimer(minutes: Int) {
        sleepMinutes = minutes
        sleepRunnable?.let { handler.removeCallbacks(it) }
        sleepRunnable = null
        if (minutes > 0) {
            sleepDeadlineMs = SystemClock.uptimeMillis() + minutes * 60_000L
            val r = Runnable {
                sleepMinutes = 0
                sleepRunnable = null
                pauseAll()
            }
            sleepRunnable = r
            handler.postDelayed(r, minutes * 60_000L)
        }
    }

    // ---- Cassette pitch wobble --------------------------------------------
    private var wobbleRunnable: Runnable? = null
    private var wobbleStartMs: Long = 0L

    private fun startWobble() {
        stopWobble()
        wobbleStartMs = SystemClock.uptimeMillis()
        val r = object : Runnable {
            override fun run() {
                if (!cassetteOn) return
                val t = (SystemClock.uptimeMillis() - wobbleStartMs) / 1000.0
                val pitch = 1.0 + WOBBLE_DEPTH * sin(2.0 * Math.PI * t / WOBBLE_PERIOD_S)
                songPlayer.playbackParameters = PlaybackParameters(speed, pitch.toFloat())
                handler.postDelayed(this, WOBBLE_STEP_MS)
            }
        }
        wobbleRunnable = r
        handler.post(r)
    }

    private fun stopWobble() {
        wobbleRunnable?.let { handler.removeCallbacks(it) }
        wobbleRunnable = null
        if (songUri != null) {
            songPlayer.playbackParameters = PlaybackParameters(speed, 1.0f)
        }
    }

    init {
        vinylPlayer.setMediaItem(MediaItem.fromUri(VINYL_ASSET))
        vinylPlayer.prepare()
        cassettePlayer.setMediaItem(MediaItem.fromUri(CASSETTE_ASSET))
        cassettePlayer.prepare()
    }

    fun setSong(uri: Uri, name: String) {
        songUri = uri
        songName = name
        songPlayer.setMediaItem(MediaItem.fromUri(uri))
        songPlayer.prepare()
        ensureFxAttached()
        // Re-apply the current parameters on the fresh media item.
        songPlayer.playbackParameters = PlaybackParameters(speed, 1.0f)
        fx.apply(garam, naram, geela)
        onStateChanged?.invoke()
    }

    /** Speed change with pitch locked at 1.0 (wobble re-applies itself). */
    fun applySpeed(newSpeed: Float) {
        speed = newSpeed
        if (!cassetteOn) {
            songPlayer.playbackParameters = PlaybackParameters(speed, 1.0f)
        }
    }

    /** (Re)attach the audiofx to the song session and apply the toggles. */
    fun applyFx(newGaram: Boolean, newNaram: Boolean, newGeela: Boolean) {
        garam = newGaram
        naram = newNaram
        geela = newGeela
        ensureFxAttached()
        fx.apply(garam, naram, geela)
        onStateChanged?.invoke()
    }

    private fun ensureFxAttached() {
        // The audio session id only exists once the player is prepared;
        // FxController.attach() ignores invalid ids and reapplies safely,
        // and applyFx() is called again on every toggle and on Play.
        fx.attach(songPlayer.audioSessionId)
    }

    fun applyRain(choice: RainChoice, volumePercent: Int) {
        rainChoice = choice
        rainVolumePercent = volumePercent
        if (choice == RainChoice.NONE) {
            rainPlayer.pause()
            return
        }
        if (rainLoaded != choice) {
            val asset = when (choice) {
                RainChoice.GENTLE -> RAIN_GENTLE_ASSET
                RainChoice.HEAVY -> RAIN_HEAVY_ASSET
                RainChoice.TIN -> RAIN_TIN_ASSET
                RainChoice.NONE -> RAIN_GENTLE_ASSET // unreachable, handled above
            }
            rainPlayer.setMediaItem(MediaItem.fromUri(asset))
            rainPlayer.prepare()
            rainLoaded = choice
        }
        rainPlayer.volume = volumePercent / 100f
        if (isPlaying) rainPlayer.play() else rainPlayer.pause()
        onStateChanged?.invoke()
    }

    fun applyVinyl(on: Boolean) {
        vinylOn = on
        if (on && isPlaying) vinylPlayer.play() else vinylPlayer.pause()
        onStateChanged?.invoke()
    }

    fun applyCassette(on: Boolean) {
        cassetteOn = on
        if (on) {
            if (isPlaying) cassettePlayer.play()
            startWobble()
        } else {
            cassettePlayer.pause()
            stopWobble()
        }
        onStateChanged?.invoke()
    }

    /** Master play: the song plus whichever layers are switched on. */
    fun playAll() {
        songPlayer.play()
        if (rainChoice != RainChoice.NONE) rainPlayer.play()
        if (vinylOn) vinylPlayer.play()
        if (cassetteOn) cassettePlayer.play()
        onStateChanged?.invoke()
    }

    fun pauseAll() {
        songPlayer.pause()
        rainPlayer.pause()
        vinylPlayer.pause()
        cassettePlayer.pause()
        onStateChanged?.invoke()
    }

    fun togglePlayPause() {
        if (songUri == null) return
        if (isPlaying) pauseAll() else playAll()
    }

    /** Song finished: silence the layers and rewind the song for replay. */
    fun onSongEnded() {
        pauseAll()
        songPlayer.seekTo(0)
    }

    /** Short text for the notification, e.g. "0.95x · Garam · Halki Raat Wali". */
    fun mixSummary(): String {
        val fxNames = listOfNotNull(
            if (garam) "Garam" else null,
            if (naram) "Naram" else null,
            if (geela) "Geela" else null,
            if (vinylOn) "Vinyl" else null,
            if (cassetteOn) "Cassette" else null
        )
        return buildString {
            append("%.2f".format(speed)).append("x · ")
            append(if (fxNames.isEmpty()) "FX off" else fxNames.joinToString(" + "))
            append(" · ")
            append(
                when (rainChoice) {
                    RainChoice.NONE -> "No rain"
                    RainChoice.GENTLE -> "Halki Raat Wali"
                    RainChoice.HEAVY -> "Tez Barsaat"
                    RainChoice.TIN -> "Tin Wali Chhat"
                }
            )
        }
    }

    fun release() {
        applySleepTimer(0)
        stopWobble()
        fx.release()
        songPlayer.release()
        rainPlayer.release()
        vinylPlayer.release()
        cassettePlayer.release()
    }

    companion object {
        private const val VINYL_VOLUME = 0.15f
        private const val CASSETTE_VOLUME = 0.10f
        private const val WOBBLE_DEPTH = 0.004
        private const val WOBBLE_PERIOD_S = 5.0
        private const val WOBBLE_STEP_MS = 150L
        private const val RAIN_GENTLE_ASSET = "asset:///rain_gentle.mp3"
        private const val RAIN_HEAVY_ASSET = "asset:///rain_heavy.mp3"
        private const val RAIN_TIN_ASSET = "asset:///rain_tin.mp3"
        private const val VINYL_ASSET = "asset:///vinyl_crackle.mp3"
        private const val CASSETTE_ASSET = "asset:///tape_hiss.mp3"
    }
}
