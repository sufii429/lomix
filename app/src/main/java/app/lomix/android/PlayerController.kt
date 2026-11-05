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

/** Rain choices of the "Baarish" tab. */
enum class RainChoice { NONE, GENTLE, HEAVY, TIN }

/** Beats styles of the "Beats" tab; the EQ curves live in [BeatsFx]. */
enum class BeatsStyle { BALANCED, WARM, BASS_BOOST, SMOOTH, DYNAMIC }

/** Where the beats come from: the bundled loops, or the song itself (v1.3). */
enum class BeatsSource { BUILTIN, SELF }

/** Build state of the current song's own beats (see [SelfBeats]). */
enum class SelfBeatsState { IDLE, BUILDING, READY, MISSING }

/** Display label for a beats style (UI chips + notification summary). */
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
 *  - rainPlayer    : a looping bundled rain file (assets), own volume.
 *  - vinylPlayer   : a looping bundled crackle file, fixed low volume.
 *  - cassettePlayer: a looping bundled tape-hiss file for the Cassette
 *                    effect (which also wobbles the song's pitch slightly).
 *  - beatsPlayer   : a looping bundled drum loop (v1.2), time-stretched
 *                    to the detected song tempo and phase-locked to the
 *                    song's beat grid by a periodic alignment ticker;
 *                    its character comes from [BeatsFx] style curves.
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

    /** Looping drum loop for the Beats layer; rate follows the song tempo. */
    private val beatsPlayer: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build().apply {
        repeatMode = Player.REPEAT_MODE_ALL
    }

    /** Lofi effects attached to the song player's audio session. */
    val fx = FxController()

    /** Style EQ attached to the beats player's audio session. */
    private val beatsFx = BeatsFx()

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

    // ---- Beats state (v1.2) ------------------------------------------------
    var beatsOn: Boolean = false
        private set
    var beatsStyle: BeatsStyle = BeatsStyle.BALANCED
        private set
    var beatsVolumePercent: Int = 60
        private set

    /** Detected grid for the current song; null while unknown/failed. */
    var beatInfo: BeatInfo? = null
        private set

    /** True while the background tempo detection is running. */
    var beatsDetecting: Boolean = false
        private set

    /** Mazeen's manual tempo correction in BPM (the +/- in the Beats tab). */
    var bpmAdjust: Double = 0.0
        private set

    /** Which loop pattern asset is currently loaded into beatsPlayer. */
    private var beatsLoadedAsset: String? = null

    // ---- Self-beats state (v1.3, workaround B) ------------------------------
    var beatsSource: BeatsSource = BeatsSource.BUILTIN
        private set
    var selfBeatsState: SelfBeatsState = SelfBeatsState.IDLE
        private set

    /** Decoded PCM of the current song, kept so self-beats can be built
     *  (or rebuilt for another style) without decoding twice. */
    private var songPcm: FloatArray? = null

    /** The rendered self-beats loop for the current song, if built. */
    private var selfLoopFile: java.io.File? = null

    /** Tempo the self loop was rendered at (it is a native-tempo loop). */
    private var selfLoopBpm: Double = 0.0

    /** Incremented per song so stale detection results are dropped. */
    private var detectGeneration: Int = 0

    /** The tempo the beats must follow: detected (or fallback) + adjust. */
    fun effectiveBpm(): Double {
        val base = beatInfo?.bpm ?: FALLBACK_BPM
        return (base + bpmAdjust).coerceIn(40.0, 200.0)
    }

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
        startBeatDetection(uri)
        onStateChanged?.invoke()
    }

    /** Kick off tempo detection for [uri] on a background thread. */
    private fun startBeatDetection(uri: Uri) {
        val generation = ++detectGeneration
        beatInfo = null
        bpmAdjust = 0.0
        beatsDetecting = true
        songPcm = null
        selfLoopFile = null
        selfBeatsState =
            if (beatsSource == BeatsSource.SELF) SelfBeatsState.BUILDING else SelfBeatsState.IDLE
        stopBeatsTicker()
        beatsPlayer.pause()
        Thread {
            val pcm = BeatDetector.decodePcm(appContext, uri)
            val info = pcm?.let { BeatDetector.detectFromPcm(it) }
            handler.post {
                if (generation == detectGeneration && songUri == uri) {
                    beatsDetecting = false
                    beatInfo = info
                    songPcm = pcm
                    if (beatsOn) {
                        ensureBeatsReady()
                        if (isPlaying) {
                            beatsPlayer.play()
                            startBeatsTicker()
                        }
                    }
                    if (beatsSource == BeatsSource.SELF) maybeBuildSelfLoop()
                    onStateChanged?.invoke()
                }
            }
        }.start()
    }

    private fun fullnessFor(style: BeatsStyle): Int = when (style) {
        BeatsStyle.SMOOTH -> 0
        BeatsStyle.DYNAMIC -> 2
        else -> 1
    }

    /** True while the loaded beats are the current song's own loop. */
    private fun selfActive(): Boolean =
        beatsSource == BeatsSource.SELF && selfLoopFile != null

    /** The tempo the loaded loop was rendered at: 80 for the bundled
     *  loops, the song's own detected tempo for a self loop. */
    private fun beatsBaseBpm(): Double = if (selfActive()) selfLoopBpm else BASE_BEATS_BPM

    /**
     * (Re)build the current song's own beats from its stored PCM, on a
     * background thread. On failure the state becomes MISSING and the
     * bundled loops keep playing — a bad extraction never reaches the
     * ears.
     */
    private fun maybeBuildSelfLoop() {
        val pcm = songPcm
        val info = beatInfo
        val uri = songUri
        if (pcm == null || info == null || uri == null) {
            selfLoopFile = null
            selfBeatsState = SelfBeatsState.MISSING
            return
        }
        val generation = detectGeneration
        val style = beatsStyle
        selfBeatsState = SelfBeatsState.BUILDING
        onStateChanged?.invoke()
        Thread {
            val file = java.io.File(
                appContext.cacheDir, "selfbeats_${generation}_${style.name}.wav"
            )
            val ok = SelfBeats.buildLoop(pcm, info, fullnessFor(style), file)
            handler.post {
                if (generation == detectGeneration && songUri == uri &&
                    beatsSource == BeatsSource.SELF
                ) {
                    if (ok) {
                        selfLoopFile = file
                        selfLoopBpm = info.bpm
                        selfBeatsState = SelfBeatsState.READY
                    } else {
                        selfLoopFile = null
                        selfBeatsState = SelfBeatsState.MISSING
                    }
                    if (beatsOn) {
                        ensureBeatsReady()
                        if (isPlaying) beatsPlayer.play()
                    }
                    onStateChanged?.invoke()
                }
            }
        }.start()
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

    // ---- Beats layer (v1.2) ------------------------------------------------

    fun applyBeats(
        on: Boolean, style: BeatsStyle, volumePercent: Int, adjust: Double,
        source: BeatsSource
    ) {
        val sourceChanged = source != beatsSource
        val styleChanged = style != beatsStyle
        beatsOn = on
        beatsStyle = style
        beatsVolumePercent = volumePercent
        bpmAdjust = adjust
        beatsSource = source
        if (!on) {
            stopBeatsTicker()
            beatsPlayer.pause()
            onStateChanged?.invoke()
            return
        }
        // Self loops are rendered per style fullness from the song's own
        // drums: switching to that source, or changing style while it is
        // active, (re)builds the loop from the stored PCM. Also build
        // when the source was picked while beats were off (state IDLE).
        if (source == BeatsSource.SELF &&
            (sourceChanged || styleChanged ||
                (selfLoopFile == null && selfBeatsState == SelfBeatsState.IDLE))
        ) {
            maybeBuildSelfLoop()
        }
        ensureBeatsReady()
        if (isPlaying) {
            beatsPlayer.play()
            startBeatsTicker()
        } else {
            beatsPlayer.pause()
        }
        onStateChanged?.invoke()
    }

    /** Load the loop for the current style, set rate, volume and EQ. */
    private fun ensureBeatsReady() {
        // Source first: with "Ganay ki apni" active and a built loop,
        // that loop plays. Otherwise the bundled loops, where styles
        // pick the pattern's fullness (v1.2.1): Smooth is the sparsest,
        // Dynamic the fullest; the middle styles share the standard
        // pattern and differ only in EQ character.
        val key: String
        val uri: String
        if (selfActive()) {
            key = "self:" + selfLoopFile!!.absolutePath
            uri = selfLoopFile!!.toURI().toString()
        } else {
            val asset = when (beatsStyle) {
                BeatsStyle.SMOOTH -> BEATS_SPARSE_ASSET
                BeatsStyle.DYNAMIC -> BEATS_DYNAMIC_ASSET
                else -> BEATS_STANDARD_ASSET
            }
            key = asset
            uri = asset
        }
        if (beatsLoadedAsset != key) {
            beatsPlayer.setMediaItem(MediaItem.fromUri(uri))
            beatsPlayer.prepare()
            beatsLoadedAsset = key
            beatsFx.attach(beatsPlayer.audioSessionId)
        }
        beatsFx.apply(beatsStyle)
        beatsPlayer.volume = (beatsVolumePercent / 100f) * BeatsFx.levelFactor(beatsStyle)
        updateBeatsRate()
    }

    private fun updateBeatsRate() {
        // Playback rate (per real second) = song speed x tempo ratio.
        // The ratio's base is the tempo the loaded loop was rendered
        // at: 80 BPM for the bundled loops, the song's own tempo for a
        // self loop (so a self loop runs at plain song speed).
        val rate = (effectiveBpm() * speed / beatsBaseBpm()).toFloat()
        val current = beatsPlayer.playbackParameters.speed
        if (abs(current - rate) > 0.0005f) {
            beatsPlayer.playbackParameters = PlaybackParameters(rate, 1.0f)
        }
    }

    private var beatsTicker: Runnable? = null

    private fun startBeatsTicker() {
        stopBeatsTicker()
        val r = object : Runnable {
            override fun run() {
                if (!beatsOn) return
                alignBeats()
                handler.postDelayed(this, BEATS_TICK_MS)
            }
        }
        beatsTicker = r
        handler.post(r)
    }

    private fun stopBeatsTicker() {
        beatsTicker?.let { handler.removeCallbacks(it) }
        beatsTicker = null
    }

    /**
     * Phase-lock the loop to the song's beat grid: where the loop should
     * be is re-derived from the song's content position, and the loop is
     * nudged with a seek when it strays, so sync holds for the whole song
     * (and recovers by itself after the song is scrubbed).
     *
     * Both positions are CONTENT positions: the song's beats sit at
     * offset + k x (60000 / bpm) in its own timeline, and one loop spans
     * 8 beats, so content maps to content by the plain tempo ratio.
     */
    private fun alignBeats() {
        if (songUri == null) return
        updateBeatsRate()
        val effBpm = effectiveBpm()
        val loopSongMs = LOOP_BEATS * 60000.0 / effBpm
        val offset = beatInfo?.offsetMs ?: 0.0
        val songPos = songPlayer.currentPosition.toDouble()
        var phase = (songPos - offset) % loopSongMs
        if (phase < 0) phase += loopSongMs
        // Laid-back feel: in Mazeen's reference mix the added beats sit
        // a uniform ~35 ms BEHIND the song's grid — that slight lateness
        // is a big part of the relaxed lofi feel, so the loop runs the
        // same small lag behind the detected grid here.
        phase -= BEATS_LAG_MS
        if (phase < 0) phase += loopSongMs
        // The loaded loop spans 8 beats at its render tempo: 6000 ms
        // for the bundled loops, the song's own 8 beats for a self loop.
        val baseBpm = beatsBaseBpm()
        val beatsLoopMs = LOOP_BEATS * 60000.0 / baseBpm
        val expected = (phase * (effBpm / baseBpm)).toLong()
            .coerceIn(0L, (beatsLoopMs - 1).toLong())
        val current = beatsPlayer.currentPosition.toDouble()
        var diff = expected - current
        if (diff > beatsLoopMs / 2) diff -= beatsLoopMs
        if (diff < -beatsLoopMs / 2) diff += beatsLoopMs
        if (abs(diff) > BEATS_ALIGN_TOLERANCE_MS) {
            beatsPlayer.seekTo(expected)
        }
    }

    /** Master play: the song plus whichever layers are switched on. */
    fun playAll() {
        songPlayer.play()
        if (rainChoice != RainChoice.NONE) rainPlayer.play()
        if (vinylOn) vinylPlayer.play()
        if (cassetteOn) cassettePlayer.play()
        if (beatsOn) {
            ensureBeatsReady()
            beatsPlayer.play()
            startBeatsTicker()
        }
        onStateChanged?.invoke()
    }

    fun pauseAll() {
        songPlayer.pause()
        rainPlayer.pause()
        vinylPlayer.pause()
        cassettePlayer.pause()
        beatsPlayer.pause()
        stopBeatsTicker()
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
            if (cassetteOn) "Cassette" else null,
            if (beatsOn) "Beats " + beatsStyleLabel(beatsStyle) else null
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
        detectGeneration++
        applySleepTimer(0)
        stopWobble()
        stopBeatsTicker()
        fx.release()
        beatsFx.release()
        songPlayer.release()
        rainPlayer.release()
        vinylPlayer.release()
        cassettePlayer.release()
        beatsPlayer.release()
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
        private const val BEATS_STANDARD_ASSET = "asset:///beats_a.ogg"
        private const val BEATS_DYNAMIC_ASSET = "asset:///beats_b.ogg"
        private const val BEATS_SPARSE_ASSET = "asset:///beats_c.ogg"
        private const val BASE_BEATS_BPM = 80.0
        private const val FALLBACK_BPM = 90.0
        private const val LOOP_BEATS = 8
        private const val BEATS_LOOP_MS = 6000.0
        private const val BEATS_TICK_MS = 250L
        private const val BEATS_ALIGN_TOLERANCE_MS = 50.0
        private const val BEATS_LAG_MS = 30.0
    }
}
