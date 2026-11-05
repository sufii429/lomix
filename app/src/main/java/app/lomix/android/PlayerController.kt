package app.lomix.android

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/** Rain choices of the "Baarish" tab. */
enum class RainChoice { NONE, GENTLE, HEAVY }

/**
 * Owns the three ExoPlayers and keeps them in sync behind ONE master
 * Play/Pause button:
 *
 *  - [songPlayer]  : the picked song; carries speed + the audiofx effects.
 *  - rainPlayer    : a looping bundled rain file (assets), own volume.
 *  - vinylPlayer   : a looping bundled crackle file, fixed low volume.
 *
 * Rain and vinyl follow the song's play/pause exactly; nothing plays
 * on its own. Bundled files are addressed as asset:/// URIs, which
 * ExoPlayer's default data source resolves from the app assets.
 */
class PlayerController(context: Context) {

    val songPlayer: ExoPlayer = ExoPlayer.Builder(context).build()

    private val rainPlayer: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
    }

    private val vinylPlayer: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
        volume = VINYL_VOLUME
    }

    /** Lofi effects attached to the song player's audio session. */
    val fx = FxController()

    /** Which rain file is currently loaded into rainPlayer (null = none yet). */
    private var rainLoaded: RainChoice? = null

    init {
        vinylPlayer.setMediaItem(MediaItem.fromUri(VINYL_ASSET))
        vinylPlayer.prepare()
    }

    fun setSong(uri: Uri) {
        songPlayer.setMediaItem(MediaItem.fromUri(uri))
        songPlayer.prepare()
        ensureFxAttached()
    }

    /** Speed change with pitch locked at 1.0 — Sonic keeps the pitch. */
    fun setSpeed(speed: Float) {
        songPlayer.playbackParameters = PlaybackParameters(speed, 1.0f)
    }

    /** (Re)attach the audiofx to the song session and apply the toggles. */
    fun applyFx(garam: Boolean, naram: Boolean, geela: Boolean) {
        ensureFxAttached()
        fx.apply(garam, naram, geela)
    }

    private fun ensureFxAttached() {
        // The audio session id only exists once the player is prepared;
        // FxController.attach() ignores invalid ids and reapplies safely,
        // and applyFx() is called again on every toggle and on Play.
        fx.attach(songPlayer.audioSessionId)
    }

    fun setRain(choice: RainChoice, volumePercent: Int, shouldPlay: Boolean) {
        if (choice == RainChoice.NONE) {
            rainPlayer.pause()
            return
        }
        if (rainLoaded != choice) {
            val asset = if (choice == RainChoice.GENTLE) RAIN_GENTLE_ASSET else RAIN_HEAVY_ASSET
            rainPlayer.setMediaItem(MediaItem.fromUri(asset))
            rainPlayer.prepare()
            rainLoaded = choice
        }
        rainPlayer.volume = volumePercent / 100f
        if (shouldPlay) rainPlayer.play() else rainPlayer.pause()
    }

    fun setRainVolume(volumePercent: Int) {
        rainPlayer.volume = volumePercent / 100f
    }

    fun setVinyl(on: Boolean, shouldPlay: Boolean) {
        if (on && shouldPlay) vinylPlayer.play() else vinylPlayer.pause()
    }

    /** Master play: the song plus whichever layers are switched on. */
    fun playAll(rainOn: Boolean, vinylOn: Boolean) {
        songPlayer.play()
        if (rainOn) rainPlayer.play()
        if (vinylOn) vinylPlayer.play()
    }

    fun pauseAll() {
        songPlayer.pause()
        rainPlayer.pause()
        vinylPlayer.pause()
    }

    /** Song finished: silence the layers and rewind the song for replay. */
    fun onSongEnded() {
        pauseAll()
        songPlayer.seekTo(0)
    }

    fun release() {
        fx.release()
        songPlayer.release()
        rainPlayer.release()
        vinylPlayer.release()
    }

    companion object {
        private const val VINYL_VOLUME = 0.15f
        private const val RAIN_GENTLE_ASSET = "asset:///rain_gentle.mp3"
        private const val RAIN_HEAVY_ASSET = "asset:///rain_heavy.mp3"
        private const val VINYL_ASSET = "asset:///vinyl_crackle.mp3"
    }
}
