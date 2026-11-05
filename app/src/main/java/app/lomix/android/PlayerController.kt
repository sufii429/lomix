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
import kotlin.math.abs
import kotlin.math.sin

/** Rain choices of the "Mahaul" tab (the Baarish section). */
enum class RainChoice { NONE, GENTLE, HEAVY, TIN }

/** Ambient layers of the "Mahaul" tab, below the rain. */
enum class AmbientSound(val label: String, val asset: String) {
    FIRE("Aag", "asset:///amb_fire.mp3"),
    OCEAN("Lehrein", "asset:///amb_ocean.mp3"),
    CRICKETS("Jhingur", "asset:///amb_crickets.mp3"),
    CAFE("Cafe", "asset:///amb_cafe.mp3")
}

// ---------------------------------------------------------------------------
// PARKED (v1.4): the Beats layer was removed from the app on Mazeen's call
// (2026-11-05) — its sound never satisfied, across three attempts. The
// detection / self-beats mechanism stays in the codebase (BeatDetector.kt,
// SelfBeats.kt, BeatsFx.kt) because tempo sync may be needed again (e.g.
// drum pads); these enums are kept so those parked files still compile.
// ---------------------------------------------------------------------------

/** Beats styles of the removed "Beats" tab; the EQ curves live in [BeatsFx]. */
enum class BeatsStyle { BALANCED, WARM, BASS_BOOST, SMOOTH, DYNAMIC }

/** Where the beats came from: the bundled loops, or the song itself (v1.3). */
enum class BeatsSource { BUILTIN, SELF }

/** Build state of a song's own beats (see [SelfBeats]). */
enum class SelfBeatsState { IDLE, BUILDING, READY, MISSING }

/** Display label for a beats style. */
fun beatsStyleLabel(style: BeatsStyle): String = when (style) {
    BeatsStyle.BALANCED -> "Balanced"
    BeatsStyle.WARM -> "Warm"
    BeatsStyle.BASS_BOOST -> "Bass Boost"
    BeatsStyle.SMOOTH -> "Smooth"
    BeatsStyle.DYNAMIC -> "Dynamic"
}

/**
 * Owns the ExoPlayers and keeps them in sync behind ONE master Play/Pause:
 *
 *  - songPlayer    : the picked song; carries speed + the audiofx effects.
 *  - stem players  : (v1.4) one player per stem of an imported "tayyar
 *                    gana" (a song pre-separated into vocals/drums/bass/…
 *                    by an outside tool such as MVSEP, imported as a zip).
 *                    All stems share speed + effects and are kept aligned
 *                    to the first stem by a periodic ticker — the parked
 *                    beats-sync idea, reused where it actually belongs.
 *  - rainPlayer    : a looping bundled rain file (assets), own volume.
 *  - ambientPlayers: (v1.4) looping bundled ambience files (fireplace,
 *                    ocean, crickets, cafe), each with own switch + volume.
 *  - vinylPlayer   : a looping bundled crackle file, fixed low volume.
 *  - cassettePlayer: a looping bundled tape-hiss file for the Cassette
 *                    effect (which also wobbles the pitch slightly).
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

    private val appContext: Context = context.applicationContext

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

    /** Ambient layer players, created lazily the first time one is used. */
    private val ambientPlayers = mutableMapOf<AmbientSound, ExoPlayer>()

    /** Stem players of the loaded tayyar gana; the FIRST entry is the
     *  master the others are aligned to (vocals when present). */
    private var stemPlayers: List<Pair<StemKind, ExoPlayer>> = emptyList()

    /** Lofi effects per stem player (the same settings as the song's). */
    private val stemFx = mutableMapOf<StemKind, FxController>()

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

    // ---- Ambient state (v1.4) ----------------------------------------------
    private val ambientOnMap = mutableMapOf<AmbientSound, Boolean>()
    private val ambientVolMap = mutableMapOf<AmbientSound, Int>()

    fun ambientOn(sound: AmbientSound): Boolean = ambientOnMap[sound] ?: false
    fun ambientVolume(sound: AmbientSound): Int = ambientVolMap[sound] ?: 50

    // ---- Tayyar gana (stems) state (v1.4) -----------------------------------
    var stemSet: StemSet? = null
        private set

    /** Per-stem volume percent (0..150); stems not present keep 100. */
    val stemVolumes = mutableMapOf<StemKind, Int>()

    val stemMode: Boolean get() = stemSet != null

    /** True when anything can be played: a picked song or a tayyar gana. */
    val hasActiveSource: Boolean get() = songUri != null || stemMode

    /** Title for the notification / song card. */
    val activeTitle: String get() = stemSet?.name ?: songName

    /** Which rain file is currently loaded into rainPlayer (null = none yet). */
    private var rainLoaded: RainChoice? = null

    private fun stemMaster(): ExoPlayer? = stemPlayers.firstOrNull()?.second

    /** Master playing state: the active source drives it, layers follow. */
    val isPlaying: Boolean
        get() = if (stemMode) stemMaster()?.isPlaying ?: false else songPlayer.isPlaying

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

    /** Set the pitch on the song and every stem (speed stays [speed]). */
    private fun setPitchAll(pitch: Float) {
        val params = PlaybackParameters(speed, pitch)
        if (songUri != null) songPlayer.playbackParameters = params
        stemPlayers.forEach { (_, p) -> p.playbackParameters = params }
    }

    private fun startWobble() {
        stopWobble()
        wobbleStartMs = SystemClock.uptimeMillis()
        val r = object : Runnable {
            override fun run() {
                if (!cassetteOn) return
                val t = (SystemClock.uptimeMillis() - wobbleStartMs) / 1000.0
                val pitch = 1.0 + WOBBLE_DEPTH * sin(2.0 * Math.PI * t / WOBBLE_PERIOD_S)
                setPitchAll(pitch.toFloat())
                handler.postDelayed(this, WOBBLE_STEP_MS)
            }
        }
        wobbleRunnable = r
        handler.post(r)
    }

    private fun stopWobble() {
        wobbleRunnable?.let { handler.removeCallbacks(it) }
        wobbleRunnable = null
        if (hasActiveSource) setPitchAll(1.0f)
    }

    init {
        vinylPlayer.setMediaItem(MediaItem.fromUri(VINYL_ASSET))
        vinylPlayer.prepare()
        cassettePlayer.setMediaItem(MediaItem.fromUri(CASSETTE_ASSET))
        cassettePlayer.prepare()
    }

    fun setSong(uri: Uri, name: String) {
        clearStemSet()
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

    // ---- Tayyar gana (stems) -------------------------------------------------

    /**
     * Load [set]'s stems as the active source (null clears back to the
     * song). Every stem gets its own player + its own copy of the lofi
     * effects, all sharing the current speed; a ticker then keeps every
     * stem aligned to the master so the parts never drift apart.
     */
    fun setStemSet(set: StemSet?) {
        releaseStemPlayers()
        stemSet = set
        if (set == null) {
            onStateChanged?.invoke()
            return
        }
        songPlayer.pause()
        val built = mutableListOf<Pair<StemKind, ExoPlayer>>()
        // Master first: vocals when present, else the first stored stem.
        val ordered = set.stems.entries.sortedBy { (kind, _) ->
            if (kind == StemKind.VOCALS) 0 else 1
        }
        for ((kind, fileName) in ordered) {
            val file = StemStore.stemFile(appContext, set, fileName)
            if (!file.exists()) continue
            val player = ExoPlayer.Builder(appContext).build()
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            player.volume = (stemVolumes[kind] ?: 100) / 100f
            player.playbackParameters = PlaybackParameters(speed, 1.0f)
            val fxOne = FxController()
            fxOne.attach(player.audioSessionId)
            fxOne.apply(garam, naram, geela)
            stemFx[kind] = fxOne
            built.add(kind to player)
        }
        stemPlayers = built
        if (cassetteOn) startWobble()
        onStateChanged?.invoke()
    }

    private fun clearStemSet() {
        if (stemSet == null && stemPlayers.isEmpty()) return
        releaseStemPlayers()
        stemSet = null
    }

    private fun releaseStemPlayers() {
        stopStemTicker()
        stemPlayers.forEach { (_, p) -> p.release() }
        stemPlayers = emptyList()
        stemFx.values.forEach { it.release() }
        stemFx.clear()
    }

    fun applyStemVolume(kind: StemKind, percent: Int) {
        stemVolumes[kind] = percent
        stemPlayers.firstOrNull { it.first == kind }?.second?.volume = percent / 100f
    }

    // Stem alignment ticker: every STEM_TICK_MS, any stem that has
    // drifted more than STEM_ALIGN_TOLERANCE_MS from the master is
    // seeked back onto it — the same trick that phase-locked the beats.
    private var stemTicker: Runnable? = null

    private fun startStemTicker() {
        stopStemTicker()
        val r = object : Runnable {
            override fun run() {
                if (!stemMode) return
                alignStems()
                handler.postDelayed(this, STEM_TICK_MS)
            }
        }
        stemTicker = r
        handler.post(r)
    }

    private fun stopStemTicker() {
        stemTicker?.let { handler.removeCallbacks(it) }
        stemTicker = null
    }

    private fun alignStems() {
        val master = stemMaster() ?: return
        val masterPos = master.currentPosition
        for ((_, p) in stemPlayers.drop(1)) {
            if (abs(p.currentPosition - masterPos) > STEM_ALIGN_TOLERANCE_MS) {
                p.seekTo(masterPos)
            }
        }
    }

    // ---- Active-source transport (song or stems) ----------------------------

    fun activePositionMs(): Long =
        if (stemMode) (stemMaster()?.currentPosition ?: 0L).coerceAtLeast(0L)
        else songPlayer.currentPosition.coerceAtLeast(0L)

    fun activeDurationMs(): Long {
        val d = if (stemMode) stemMaster()?.duration ?: 0L else songPlayer.duration
        return if (d > 0) d else 0L
    }

    fun activeEnded(): Boolean =
        if (stemMode) stemMaster()?.playbackState == Player.STATE_ENDED
        else songPlayer.playbackState == Player.STATE_ENDED

    fun seekActive(positionMs: Long) {
        if (stemMode) {
            stemPlayers.forEach { (_, p) -> p.seekTo(positionMs) }
        } else {
            songPlayer.seekTo(positionMs)
        }
    }

    /** Speed change with pitch locked at 1.0 (wobble re-applies itself). */
    fun applySpeed(newSpeed: Float) {
        speed = newSpeed
        if (!cassetteOn) setPitchAll(1.0f)
    }

    /** (Re)attach the audiofx to the song session and apply the toggles. */
    fun applyFx(newGaram: Boolean, newNaram: Boolean, newGeela: Boolean) {
        garam = newGaram
        naram = newNaram
        geela = newGeela
        ensureFxAttached()
        fx.apply(garam, naram, geela)
        stemFx.values.forEach { it.apply(garam, naram, geela) }
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

    /** Switch one ambient layer on/off and set its volume (v1.4). */
    fun applyAmbient(sound: AmbientSound, on: Boolean, volumePercent: Int) {
        ambientOnMap[sound] = on
        ambientVolMap[sound] = volumePercent
        val player = ambientPlayers.getOrPut(sound) {
            ExoPlayer.Builder(appContext).build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                setMediaItem(MediaItem.fromUri(sound.asset))
                prepare()
            }
        }
        player.volume = volumePercent / 100f
        if (on && isPlaying) player.play() else player.pause()
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

    /** Master play: the active source plus whichever layers are on. */
    fun playAll() {
        if (stemMode) {
            stemPlayers.forEach { (_, p) -> p.play() }
            startStemTicker()
        } else {
            songPlayer.play()
        }
        if (rainChoice != RainChoice.NONE) rainPlayer.play()
        if (vinylOn) vinylPlayer.play()
        if (cassetteOn) cassettePlayer.play()
        ambientPlayers.forEach { (sound, p) -> if (ambientOn(sound)) p.play() }
        onStateChanged?.invoke()
    }

    fun pauseAll() {
        songPlayer.pause()
        stemPlayers.forEach { (_, p) -> p.pause() }
        stopStemTicker()
        rainPlayer.pause()
        vinylPlayer.pause()
        cassettePlayer.pause()
        ambientPlayers.values.forEach { it.pause() }
        onStateChanged?.invoke()
    }

    fun togglePlayPause() {
        if (!hasActiveSource) return
        if (isPlaying) pauseAll() else playAll()
    }

    /** Active source finished: silence the layers and rewind for replay. */
    fun onActiveEnded() {
        pauseAll()
        seekActive(0)
    }

    /** Short text for the notification, e.g. "0.95x · Garam · Halki Raat Wali". */
    fun mixSummary(): String {
        val fxNames = listOfNotNull(
            if (garam) "Garam" else null,
            if (naram) "Naram" else null,
            if (geela) "Geela" else null,
            if (vinylOn) "Vinyl" else null,
            if (cassetteOn) "Cassette" else null,
            if (stemMode) "Hissay" else null
        ) + AmbientSound.values().filter { ambientOn(it) }.map { it.label }
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
        releaseStemPlayers()
        fx.release()
        stemFx.values.forEach { it.release() }
        songPlayer.release()
        rainPlayer.release()
        vinylPlayer.release()
        cassettePlayer.release()
        ambientPlayers.values.forEach { it.release() }
        ambientPlayers.clear()
    }

    companion object {
        private const val VINYL_VOLUME = 0.15f
        private const val CASSETTE_VOLUME = 0.10f
        private const val WOBBLE_DEPTH = 0.004
        private const val WOBBLE_PERIOD_S = 5.0
        private const val WOBBLE_STEP_MS = 150L
        private const val STEM_TICK_MS = 500L
        private const val STEM_ALIGN_TOLERANCE_MS = 90L
        private const val RAIN_GENTLE_ASSET = "asset:///rain_gentle.mp3"
        private const val RAIN_HEAVY_ASSET = "asset:///rain_heavy.mp3"
        private const val RAIN_TIN_ASSET = "asset:///rain_tin.mp3"
        private const val VINYL_ASSET = "asset:///vinyl_crackle.mp3"
        private const val CASSETTE_ASSET = "asset:///tape_hiss.mp3"
    }
}
