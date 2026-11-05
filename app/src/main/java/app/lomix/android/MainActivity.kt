package app.lomix.android

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.graphics.Bitmap
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

// Approved palette (light, lively sky theme from the design mockup).
/**
 * Themes (v1.6): three palettes approved from the mockup — Aasmaan
 * (the original sky look, default), iPod Classic (silver/white) and
 * Poweramp Raat (dark). Every colour the UI uses is read through the
 * composable getters below from the active palette, so switching the
 * theme recolours the whole app at once.
 */
private data class LomixPalette(
    val ink: Color,
    val muted: Color,
    val accent: Color,
    val border: Color,
    val selectedTint: Color,
    val idleTint: Color,
    val card: Color,
    val strip: Color,
    val bg: List<Color>,
    val scaffold: Color
)

private val AasmaanPalette = LomixPalette(
    ink = Color(0xFF16233A), muted = Color(0xFF5A6B84), accent = Color(0xFF2F8FE8),
    border = Color(0xFFD9E9F8), selectedTint = Color(0xFFE1F0FE), idleTint = Color(0xFFF3F9FF),
    card = Color.White, strip = Color(0xFF16233A),
    bg = listOf(Color(0xFFBFE3FF), Color(0xFFDFF2FF), Color(0xFFFFF3D6)),
    scaffold = Color(0xFFEEF5FD)
)

private val IpodPalette = LomixPalette(
    ink = Color(0xFF1C1C1E), muted = Color(0xFF8A8A93), accent = Color(0xFF2F6FD0),
    border = Color(0xFFD8D8DE), selectedTint = Color(0xFFDCE9FA), idleTint = Color(0xFFEFEFF2),
    card = Color.White, strip = Color(0xFF1C1C1E),
    bg = listOf(Color(0xFFF4F4F6), Color(0xFFE9E9ED)),
    scaffold = Color(0xFFF4F4F6)
)

private val RaatPalette = LomixPalette(
    ink = Color(0xFFEDF0FA), muted = Color(0xFF8B93AD), accent = Color(0xFFFF7A45),
    border = Color(0xFF262D4A), selectedTint = Color(0xFF2A3358), idleTint = Color(0xFF1B2138),
    card = Color(0xFF151A2E), strip = Color(0xFF101527),
    bg = listOf(Color(0xFF141A30), Color(0xFF05070F)),
    scaffold = Color(0xFF0A0D18)
)

private val LocalPalette = staticCompositionLocalOf { AasmaanPalette }

private val Ink: Color
    @Composable get() = LocalPalette.current.ink
private val MutedInk: Color
    @Composable get() = LocalPalette.current.muted
private val AccentBlue: Color
    @Composable get() = LocalPalette.current.accent
private val CardBorder: Color
    @Composable get() = LocalPalette.current.border
private val SelectedTint: Color
    @Composable get() = LocalPalette.current.selectedTint
private val IdleTint: Color
    @Composable get() = LocalPalette.current.idleTint
private val CardSurface: Color
    @Composable get() = LocalPalette.current.card
private val StripBg: Color
    @Composable get() = LocalPalette.current.strip
private val ScaffoldBg: Color
    @Composable get() = LocalPalette.current.scaffold
private val SkyGradient: Brush
    @Composable get() = Brush.verticalGradient(LocalPalette.current.bg)

class MainActivity : ComponentActivity() {

    // The controller lives in PlaybackService (v1.1); the Activity only
    // borrows it while bound, so leaving the app never stops the music.
    private var boundController by mutableStateOf<PlayerController?>(null)
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            boundController = (service as? PlaybackService.LocalBinder)?.service()?.controller
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundController = null
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val serviceIntent = Intent(this, PlaybackService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        isBound = bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        setContent {
            val current = boundController
            if (current != null) {
                LomixScreen(current)
            } else {
                MaterialTheme {
                    Box(
                        modifier = Modifier.fillMaxSize().background(SkyGradient),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Lomix", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        super.onDestroy()
    }
}

@Composable
private fun LomixScreen(controller: PlayerController) {
    val context = LocalContext.current
    val appCtx = context.applicationContext
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // ---- UI state (starting values come from the controller, which keeps
    // the mix while the Activity is gone) ----------------------------------
    var songUri by remember { mutableStateOf(controller.songUri) }
    var songName by remember {
        mutableStateOf(if (controller.songName.isEmpty()) "No song picked" else controller.songName)
    }
    var isPlaying by remember { mutableStateOf(controller.isPlaying) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrubFraction by remember { mutableStateOf<Float?>(null) }

    var speed by remember { mutableFloatStateOf(controller.speed) }
    var garam by remember { mutableStateOf(controller.garam) }
    var naram by remember { mutableStateOf(controller.naram) }
    var geela by remember { mutableStateOf(controller.geela) }
    var vinyl by remember { mutableStateOf(controller.vinylOn) }
    var cassette by remember { mutableStateOf(controller.cassetteOn) }

    var rainChoice by remember { mutableStateOf(controller.rainChoice) }
    var rainVolume by remember { mutableIntStateOf(controller.rainVolumePercent) }

    // Ambient layers (v1.4): per-sound switch + volume.
    val ambientOn = remember {
        mutableStateMapOf<AmbientSound, Boolean>().apply {
            AmbientSound.values().forEach { put(it, controller.ambientOn(it)) }
        }
    }
    val ambientVol = remember {
        mutableStateMapOf<AmbientSound, Int>().apply {
            AmbientSound.values().forEach { put(it, controller.ambientVolume(it)) }
        }
    }

    // Tayyar ganay (stems, v1.4).
    var stemSets by remember { mutableStateOf(StemStore.loadSets(appCtx)) }
    var activeStemId by remember { mutableStateOf(controller.stemSet?.id) }
    var stemStatus by remember { mutableStateOf("") }
    val stemVolumes = remember {
        mutableStateMapOf<StemKind, Int>().apply { putAll(controller.stemVolumes) }
    }

    // Saved mixes (presets, v1.5).
    var presets by remember { mutableStateOf(MixPresetStore.load(appCtx)) }
    var showSaveMixDialog by remember { mutableStateOf(false) }
    var mixNameInput by remember { mutableStateOf("") }

    var sleepMinutes by remember { mutableIntStateOf(controller.sleepMinutes) }
    var sleepRemainingMs by remember { mutableLongStateOf(controller.sleepRemainingMs) }
    // Last sleep value this UI pushed into the controller; the poll below
    // only adopts the controller's value when it differs from this (i.e.
    // the timer fired on its own), never overwriting a fresh user tap.
    var lastSleepPushed by remember { mutableIntStateOf(controller.sleepMinutes) }

    // Themes (v1.6): Aasmaan default; the choice persists.
    var themeKey by remember {
        mutableStateOf(
            appCtx.getSharedPreferences("lomix_ui", Context.MODE_PRIVATE)
                .getString("theme", "AASMAAN") ?: "AASMAAN"
        )
    }
    val palette = when (themeKey) {
        "IPOD" -> IpodPalette
        "RAAT" -> RaatPalette
        else -> AasmaanPalette
    }

    fun setTheme(key: String) {
        themeKey = key
        appCtx.getSharedPreferences("lomix_ui", Context.MODE_PRIVATE)
            .edit().putString("theme", key).apply()
    }

    // Queue mirrors + library + playlists (v1.6). The poll keeps the
    // mirrors in step with the controller (auto-advance included).
    var queueMirror by remember { mutableStateOf(controller.queue) }
    var queueIndexMirror by remember { mutableIntStateOf(controller.queueIndex) }
    var repeatMirror by remember { mutableStateOf(controller.repeatMode) }
    var shuffleMirror by remember { mutableStateOf(controller.shuffle) }
    var library by remember { mutableStateOf(LibraryStore.load(appCtx)) }
    var playlists by remember { mutableStateOf(PlaylistStore.load(appCtx)) }

    // Export (v1.5).
    var exportRunning by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableIntStateOf(0) }
    var exportStatus by remember { mutableStateOf("") }

    var selectedTab by remember { mutableIntStateOf(0) }

    val stemMode = activeStemId != null
    val hasSource = songUri != null || stemMode
    val activeStemSet = stemSets.firstOrNull { it.id == activeStemId }
    val displayTitle = if (stemMode) (activeStemSet?.name ?: "Tayyar Gana") else songName

    // ---- Song picker ----------------------------------------------------
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Some providers don't offer persistable grants; the URI
                // still works for this session, which is enough.
            }
            val name = queryDisplayName(context, uri) ?: "Picked song"
            songName = name
            songUri = uri
            activeStemId = null
            isPlaying = false
            positionMs = 0L
            controller.setSong(uri, name)
            library = LibraryStore.add(appCtx, LibrarySong(uri.toString(), name))
        }
    }

    // ---- Tayyar gana (zip) picker ----------------------------------------
    val zipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            stemStatus = "Zip khul raha hai…"
            val zipName = queryDisplayName(context, uri) ?: "Tayyar Gana"
            Thread {
                val set = StemStore.importZip(appCtx, uri, zipName)
                mainHandler.post {
                    if (set != null) {
                        stemSets = StemStore.loadSets(appCtx)
                        controller.setStemSet(set)
                        activeStemId = set.id
                        isPlaying = false
                        positionMs = 0L
                        stemStatus = ""
                    } else {
                        stemStatus = "Is zip mein hisse nahi mile — MVSEP wala zip chunein"
                    }
                }
            }.start()
        }
    }

    // ---- Multi-picker: add many songs to the library at once (v1.6) -----
    val multiPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // As with the single picker: session access still works.
            }
            val name = queryDisplayName(context, uri) ?: "Gana"
            library = LibraryStore.add(appCtx, LibrarySong(uri.toString(), name))
        }
    }

    // ---- Declarative wiring: state -> controller ------------------------
    LaunchedEffect(speed) { controller.applySpeed(speed) }
    LaunchedEffect(garam, naram, geela) { controller.applyFx(garam, naram, geela) }
    LaunchedEffect(rainChoice, rainVolume) { controller.applyRain(rainChoice, rainVolume) }
    LaunchedEffect(vinyl) { controller.applyVinyl(vinyl) }
    LaunchedEffect(cassette) { controller.applyCassette(cassette) }
    LaunchedEffect(sleepMinutes) {
        lastSleepPushed = sleepMinutes
        controller.applySleepTimer(sleepMinutes)
    }
    LaunchedEffect(isPlaying) {
        if (hasSource) {
            if (isPlaying) controller.playAll() else controller.pauseAll()
        }
    }

    // ---- Progress polling ------------------------------------------------
    LaunchedEffect(Unit) {
        while (true) {
            durationMs = controller.activeDurationMs()
            positionMs = controller.activePositionMs()
            if (controller.activeEnded()) {
                controller.onActiveEnded()
                positionMs = 0L
            }
            // Keep the UI in step with changes made from the notification
            // (Play/Pause there, or the sleep timer firing).
            isPlaying = controller.isPlaying
            activeStemId = controller.stemSet?.id
            // Now-playing mirrors: after an auto-advance the loaded
            // song/stems change under the UI — follow the controller.
            songUri = controller.songUri
            if (controller.songName.isNotEmpty()) songName = controller.songName
            if (controller.stemMode && stemVolumes != controller.stemVolumes) {
                stemVolumes.clear()
                stemVolumes.putAll(controller.stemVolumes)
            }
            if (queueMirror != controller.queue) queueMirror = controller.queue
            queueIndexMirror = controller.queueIndex
            if (controller.sleepMinutes != lastSleepPushed) {
                sleepMinutes = controller.sleepMinutes
                lastSleepPushed = controller.sleepMinutes
            }
            sleepRemainingMs = controller.sleepRemainingMs
            delay(500)
        }
    }

    // ---- Mix summary ------------------------------------------------------
    val fxNames = listOfNotNull(
        if (garam) "Garam" else null,
        if (naram) "Naram" else null,
        if (geela) "Geela" else null,
        if (vinyl) "Vinyl" else null,
        if (cassette) "Cassette" else null,
        if (stemMode) "Hissay" else null
    ) + AmbientSound.values().filter { ambientOn[it] == true }.map { it.label }
    val mixText = buildString {
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

    // Applying a preset writes every piece of state; the LaunchedEffects
    // carry speed/FX/rain/vinyl/cassette into the controller, while the
    // ambient layers and stem volumes are applied directly.
    fun applyPreset(p: MixPreset) {
        speed = p.speed
        garam = p.garam
        naram = p.naram
        geela = p.geela
        vinyl = p.vinyl
        cassette = p.cassette
        rainChoice = runCatching { RainChoice.valueOf(p.rainChoice) }
            .getOrDefault(RainChoice.GENTLE)
        rainVolume = p.rainVolume
        AmbientSound.values().forEach { sound ->
            val (on, vol) = p.ambient[sound.name] ?: (false to 50)
            ambientOn[sound] = on
            ambientVol[sound] = vol
            controller.applyAmbient(sound, on, vol)
        }
        p.stemVolumes.forEach { (kindName, vol) ->
            val kind = StemKind.values().firstOrNull { it.name == kindName }
            if (kind != null) {
                stemVolumes[kind] = vol
                controller.applyStemVolume(kind, vol)
            }
        }
    }

    CompositionLocalProvider(LocalPalette provides palette) {
    MaterialTheme(
        colorScheme = if (themeKey == "RAAT") {
            darkColorScheme(
                primary = AccentBlue,
                onPrimary = Color(0xFF1A0E04),
                surface = CardSurface,
                onSurface = Ink,
                background = ScaffoldBg
            )
        } else {
            lightColorScheme(
                primary = AccentBlue,
                onPrimary = Color.White,
                surface = CardSurface,
                onSurface = Ink,
                background = ScaffoldBg
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SkyGradient)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(AccentBlue),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Lomix", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("rainy chill lofi mix", fontSize = 13.sp, color = MutedInk)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Theme switcher (v1.6)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple("AASMAAN", "Aasmaan", Color(0xFF7FC4F0)),
                    Triple("IPOD", "iPod", Color(0xFFC9C9D1)),
                    Triple("RAAT", "Raat", Color(0xFFFF7A45))
                ).forEach { (key, label, dot) ->
                    val active = themeKey == key
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (active) SelectedTint else IdleTint)
                            .border(
                                1.dp,
                                if (active) AccentBlue else CardBorder,
                                RoundedCornerShape(50)
                            )
                            .clickable { setTheme(key) }
                            .padding(horizontal = 11.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(dot)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Ink
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // Song card
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Album art banner (v1.6): the song's own embedded
                // picture, or its deterministic fallback gradient.
                if (hasSource) {
                    val artBitmap = rememberArtwork(if (stemMode) null else songUri)
                    val (gradA, gradB) = Artwork.fallbackFor(displayTitle)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .background(Brush.linearGradient(listOf(gradA, gradB)))
                    ) {
                        if (artBitmap != null) {
                            Image(
                                bitmap = artBitmap.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                Icons.Filled.MusicNote,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.75f),
                                modifier = Modifier
                                    .size(54.dp)
                                    .align(Alignment.Center)
                            )
                        }
                        Text(
                            if (stemMode) "TAYYAR GANA" else "ALBUM ART",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.92f),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(10.dp)
                                .background(
                                    Color.Black.copy(alpha = 0.28f),
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            displayTitle,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (stemMode) "Tayyar gana — har hissa alag control mein"
                            else if (hasSource) "Ready — set the mix and press play"
                            else "Pick a song from your phone to begin",
                            fontSize = 12.sp,
                            color = MutedInk
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (songUri != null) "Change" else "Pick")
                    }
                }

                // Play + progress
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(
                        onClick = { if (hasSource) isPlaying = !isPlaying },
                        enabled = hasSource,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(76.dp)
                    ) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    // Transport: shuffle / previous / next / repeat (v1.6)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                shuffleMirror = !shuffleMirror
                                controller.shuffle = shuffleMirror
                            },
                            enabled = hasSource
                        ) {
                            Icon(
                                Icons.Filled.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (shuffleMirror) AccentBlue else MutedInk,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                        IconButton(
                            onClick = { controller.playPrevious(); isPlaying = true },
                            enabled = hasSource
                        ) {
                            Icon(
                                Icons.Filled.SkipPrevious,
                                contentDescription = "Pichla gana",
                                tint = Ink,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        IconButton(
                            onClick = { controller.playNext(); isPlaying = true },
                            enabled = hasSource
                        ) {
                            Icon(
                                Icons.Filled.SkipNext,
                                contentDescription = "Agla gana",
                                tint = Ink,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                repeatMirror = when (repeatMirror) {
                                    RepeatMode.OFF -> RepeatMode.ALL
                                    RepeatMode.ALL -> RepeatMode.ONE
                                    RepeatMode.ONE -> RepeatMode.OFF
                                }
                                controller.repeatMode = repeatMirror
                            },
                            enabled = hasSource
                        ) {
                            Icon(
                                if (repeatMirror == RepeatMode.ONE) {
                                    Icons.Filled.RepeatOne
                                } else {
                                    Icons.Filled.Repeat
                                },
                                contentDescription = "Repeat",
                                tint = if (repeatMirror != RepeatMode.OFF) {
                                    AccentBlue
                                } else {
                                    MutedInk
                                },
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    val liveFraction = if (durationMs > 0) {
                        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    } else 0f
                    Slider(
                        value = scrubFraction ?: liveFraction,
                        onValueChange = { scrubFraction = it },
                        onValueChangeFinished = {
                            val target = ((scrubFraction ?: 0f) * durationMs).toLong()
                            controller.seekActive(target)
                            positionMs = target
                            scrubFraction = null
                        },
                        enabled = hasSource && durationMs > 0,
                        colors = SliderDefaults.colors(
                            thumbColor = AccentBlue,
                            activeTrackColor = AccentBlue
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(formatTime(positionMs), fontSize = 12.sp, color = MutedInk)
                        Spacer(Modifier.weight(1f))
                        Text(formatTime(durationMs), fontSize = 12.sp, color = MutedInk)
                    }

                    // Sleep timer (v1.1)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Timer, contentDescription = null, tint = MutedInk, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Neend timer", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Spacer(Modifier.weight(1f))
                        if (sleepMinutes > 0) {
                            val minsLeft = ((sleepRemainingMs + 59_999L) / 60_000L).toInt()
                            Text("~$minsLeft min baqi", fontSize = 12.sp, color = MutedInk)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Band", 15 to "15 min", 30 to "30 min", 60 to "60 min").forEach { (mins, label) ->
                            val active = sleepMinutes == mins
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (active) AccentBlue else IdleTint)
                                    .border(1.dp, if (active) AccentBlue else CardBorder, RoundedCornerShape(50))
                                    .clickable { sleepMinutes = mins }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    label,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (active) Color.White else Ink
                                )
                            }
                        }
                    }
                }
                    // Export the mix to the device (v1.5)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            val req = controller.buildExportRequest()
                            if (req != null && !exportRunning) {
                                exportRunning = true
                                exportProgress = 0
                                exportStatus = ""
                                Thread {
                                    try {
                                        val tmp = OfflineExporter.export(
                                            appCtx, req
                                        ) { p -> mainHandler.post { exportProgress = p } }
                                        val safeName = req.title
                                            .replace(Regex("[^A-Za-z0-9 _-]"), "")
                                            .trim()
                                            .ifEmpty { "Lomix mix" }
                                        val where = OfflineExporter.saveToMusic(
                                            appCtx, tmp, "$safeName (Lomix mix).m4a"
                                        )
                                        tmp.delete()
                                        mainHandler.post {
                                            exportRunning = false
                                            exportStatus = "Save ho gaya: $where"
                                        }
                                    } catch (e: Exception) {
                                        mainHandler.post {
                                            exportRunning = false
                                            exportStatus =
                                                "Export nahi ho saka — dobara koshish karein"
                                        }
                                    }
                                }.start()
                            }
                        },
                        enabled = hasSource && !exportRunning,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (exportRunning) "Mix ban raha hai… $exportProgress%"
                            else "Mix export karein"
                        )
                    }
                    if (exportStatus.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            exportStatus,
                            fontSize = 12.sp,
                            color = MutedInk,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(18.dp))

            // Big pill tabs
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillTab("Speed", Icons.Filled.Speed, selectedTab == 0, Modifier.weight(1f)) { selectedTab = 0 }
                PillTab("Lofi Rang", Icons.Filled.GraphicEq, selectedTab == 1, Modifier.weight(1f)) { selectedTab = 1 }
                PillTab("Mahaul", Icons.Filled.WaterDrop, selectedTab == 2, Modifier.weight(1f)) { selectedTab = 2 }
                PillTab("Hissay", Icons.Filled.LibraryMusic, selectedTab == 3, Modifier.weight(1f)) { selectedTab = 3 }
                PillTab("Playlists", Icons.Filled.QueueMusic, selectedTab == 4, Modifier.weight(1f)) { selectedTab = 4 }
            }

            Spacer(Modifier.height(14.dp))

            // Tab panel
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.padding(18.dp)) {
                    when (selectedTab) {
                        0 -> SpeedPanel(speed) { speed = it }
                        1 -> LofiPanel(
                            garam = garam, naram = naram, geela = geela, vinyl = vinyl,
                            cassette = cassette,
                            onToggle = { which ->
                                when (which) {
                                    "garam" -> garam = !garam
                                    "naram" -> naram = !naram
                                    "geela" -> geela = !geela
                                    "vinyl" -> vinyl = !vinyl
                                    "cassette" -> cassette = !cassette
                                }
                            }
                        )
                        2 -> MahaulPanel(
                            choice = rainChoice,
                            volume = rainVolume,
                            onChoice = { rainChoice = it },
                            onVolume = { rainVolume = it },
                            ambientOn = ambientOn,
                            ambientVol = ambientVol,
                            onAmbientToggle = { sound ->
                                val now = !(ambientOn[sound] ?: false)
                                ambientOn[sound] = now
                                controller.applyAmbient(sound, now, ambientVol[sound] ?: 50)
                            },
                            onAmbientVolume = { sound, vol ->
                                ambientVol[sound] = vol
                                controller.applyAmbient(sound, ambientOn[sound] ?: false, vol)
                            }
                        )
                        3 -> HissayPanel(
                            sets = stemSets,
                            activeId = activeStemId,
                            volumes = stemVolumes,
                            status = stemStatus,
                            onPickZip = {
                                zipPicker.launch(
                                    arrayOf(
                                        "application/zip",
                                        "application/x-zip-compressed",
                                        "application/octet-stream"
                                    )
                                )
                            },
                            onLoad = { set ->
                                controller.setStemSet(set)
                                activeStemId = set.id
                                isPlaying = false
                                positionMs = 0L
                            },
                            onUnload = {
                                controller.setStemSet(null)
                                activeStemId = null
                                isPlaying = false
                                positionMs = 0L
                            },
                            onDelete = { set ->
                                if (set.id == activeStemId) {
                                    controller.setStemSet(null)
                                    activeStemId = null
                                    isPlaying = false
                                }
                                StemStore.delete(appCtx, set)
                                stemSets = StemStore.loadSets(appCtx)
                            },
                            onVolume = { kind, vol ->
                                stemVolumes[kind] = vol
                                controller.applyStemVolume(kind, vol)
                            }
                        )
                        else -> PlaylistsPanel(
                            queue = queueMirror,
                            queueIndex = queueIndexMirror,
                            library = library,
                            stemSets = stemSets,
                            playlists = playlists,
                            onJumpQueue = { idx ->
                                controller.jumpToQueue(idx)
                                isPlaying = true
                            },
                            onRemoveQueue = { idx -> controller.removeFromQueue(idx) },
                            onAddSongs = { multiPicker.launch(arrayOf("audio/*")) },
                            onPlayLibrary = { idx ->
                                controller.playItems(
                                    library.map {
                                        QueueItem.Song(Uri.parse(it.uri), it.title)
                                    },
                                    idx
                                )
                                isPlaying = true
                            },
                            onEnqueueLibrary = { song ->
                                controller.enqueue(
                                    QueueItem.Song(Uri.parse(song.uri), song.title)
                                )
                            },
                            onRemoveLibrary = { song ->
                                library = LibraryStore.remove(appCtx, song.uri)
                            },
                            onCreatePlaylist = { name ->
                                playlists = PlaylistStore.create(appCtx, name)
                            },
                            onDeletePlaylist = { pl ->
                                playlists = PlaylistStore.delete(appCtx, pl.id)
                            },
                            onPlayPlaylist = { pl, idx ->
                                val items = pl.entries.mapNotNull { it.toQueueItem() }
                                if (items.isNotEmpty()) {
                                    controller.playItems(
                                        items,
                                        idx.coerceIn(0, items.lastIndex)
                                    )
                                    isPlaying = true
                                }
                            },
                            onSetPlaylistEntries = { pl, entries ->
                                playlists = PlaylistStore.setEntries(appCtx, pl.id, entries)
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Current mix strip
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = StripBg),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("ABHI KA MIX", fontSize = 10.sp, color = Color(0xFF9FB8D8), fontWeight = FontWeight.Bold)
                        Text(mixText, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Saved mixes (v1.5)
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = CardSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("MERAY MIX", fontSize = 10.sp, color = MutedInk, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            mixNameInput = ""
                            showSaveMixDialog = true
                        }) {
                            Text("Save karein", fontSize = 12.sp)
                        }
                    }
                    if (presets.isEmpty()) {
                        Text(
                            "Koi mix save nahi hai — jo mix acha lage, usay naam de kar save kar lein, phir aik tap mein wapas ajayega.",
                            fontSize = 12.sp,
                            color = MutedInk
                        )
                    } else {
                        Spacer(Modifier.height(8.dp))
                        presets.forEach { preset ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(IdleTint)
                                    .clickable { applyPreset(preset) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.LibraryMusic,
                                    contentDescription = null,
                                    tint = AccentBlue,
                                    modifier = Modifier.size(19.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    preset.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Ink,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete",
                                    tint = MutedInk,
                                    modifier = Modifier
                                        .size(19.dp)
                                        .clickable {
                                            presets = MixPresetStore.delete(appCtx, preset.name)
                                        }
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }

    if (showSaveMixDialog) {
        AlertDialog(
            onDismissRequest = { showSaveMixDialog = false },
            title = { Text("Mix save karein") },
            text = {
                OutlinedTextField(
                    value = mixNameInput,
                    onValueChange = { mixNameInput = it },
                    label = { Text("Mix ka naam") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = mixNameInput.trim()
                        if (name.isNotEmpty()) {
                            presets = MixPresetStore.add(
                                appCtx,
                                MixPreset(
                                    name = name,
                                    speed = speed,
                                    garam = garam,
                                    naram = naram,
                                    geela = geela,
                                    vinyl = vinyl,
                                    cassette = cassette,
                                    rainChoice = rainChoice.name,
                                    rainVolume = rainVolume,
                                    ambient = AmbientSound.values().associate { sound ->
                                        sound.name to (
                                            (ambientOn[sound] ?: false) to
                                                (ambientVol[sound] ?: 50)
                                            )
                                    },
                                    stemVolumes = stemVolumes.entries
                                        .associate { it.key.name to it.value }
                                )
                            )
                        }
                        showSaveMixDialog = false
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showSaveMixDialog = false }) { Text("Band") }
            }
        )
    }
    }
}

@Composable
private fun PillTab(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) AccentBlue else Color.White)
            .border(1.dp, if (selected) AccentBlue else CardBorder, RoundedCornerShape(50))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) Color.White else AccentBlue,
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) Color.White else Ink
            )
        }
    }
}

@Composable
private fun SpeedPanel(speed: Float, onSpeedChange: (Float) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("%.2fx".format(speed), fontSize = 42.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("speed — sur wohi rehta hai", fontSize = 12.sp, color = MutedInk)
        Spacer(Modifier.height(8.dp))
        Slider(
            value = speed,
            onValueChange = onSpeedChange,
            valueRange = 0.80f..1.00f,
            steps = 19,
            colors = SliderDefaults.colors(thumbColor = AccentBlue, activeTrackColor = AccentBlue),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.85f, 0.90f, 0.95f, 1.00f).forEach { chip ->
                val active = speed == chip
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (active) AccentBlue else IdleTint)
                        .border(1.dp, if (active) AccentBlue else CardBorder, RoundedCornerShape(50))
                        .clickable { onSpeedChange(chip) }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        "%.2fx".format(chip),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (active) Color.White else Ink
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MutedInk, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("Pitch locked — sirf speed badalti hai", fontSize = 12.sp, color = MutedInk)
        }
    }
}

@Composable
private fun LofiPanel(
    garam: Boolean,
    naram: Boolean,
    geela: Boolean,
    vinyl: Boolean,
    cassette: Boolean,
    onToggle: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FxToggleCard("Garam", "Warm — bass upar, top band (Lofi Dirt wala rang)", Icons.Filled.Whatshot, garam) { onToggle("garam") }
        FxToggleCard("Naram", "Soft — halki naram awaz, kaan par asan", Icons.Filled.Tune, naram) { onToggle("naram") }
        FxToggleCard("Geela", "Wet — medium room ki halki goonj", Icons.Filled.Opacity, geela) { onToggle("geela") }
        FxToggleCard("Vinyl", "Purani record ki khar-kharahat, ganay ke neeche", Icons.Filled.Album, vinyl) { onToggle("vinyl") }
        FxToggleCard("Cassette", "Purani tape ki halki hiss, saath sur ki naram laraz", Icons.Filled.Radio, cassette) { onToggle("cassette") }
        Text(
            "Rang aik saath bhi lag sakte hain — jo on hai, mix mein shamil hai.",
            fontSize = 12.sp,
            color = MutedInk
        )
    }
}

@Composable
private fun FxToggleCard(
    name: String,
    description: String,
    icon: ImageVector,
    on: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (on) SelectedTint else IdleTint)
            .clickable(onClick = onToggle)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (on) AccentBlue else Color.White),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = if (on) Color.White else AccentBlue, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(description, fontSize = 12.sp, color = MutedInk)
        }
        Switch(checked = on, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun MahaulPanel(
    choice: RainChoice,
    volume: Int,
    onChoice: (RainChoice) -> Unit,
    onVolume: (Int) -> Unit,
    ambientOn: Map<AmbientSound, Boolean>,
    ambientVol: Map<AmbientSound, Int>,
    onAmbientToggle: (AmbientSound) -> Unit,
    onAmbientVolume: (AmbientSound, Int) -> Unit
) {
    Column {
        Text("Baarish", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(8.dp))
        RainOptions(choice, volume, onChoice, onVolume)
        Spacer(Modifier.height(18.dp))
        Text("Aur mahaul ki awazein", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(
            "Ye awazein bhi ganay ke neeche loop par chalti hain — kai aik saath bhi chal sakti hain.",
            fontSize = 12.sp,
            color = MutedInk
        )
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AmbientLayerRow(
                "Aag", "Angeethi ki karhk — cozy mahaul", Icons.Filled.Whatshot,
                ambientOn[AmbientSound.FIRE] ?: false, ambientVol[AmbientSound.FIRE] ?: 50,
                { onAmbientToggle(AmbientSound.FIRE) },
                { onAmbientVolume(AmbientSound.FIRE, it) }
            )
            AmbientLayerRow(
                "Lehrein", "Samundar ki sust lehrein", Icons.Filled.Waves,
                ambientOn[AmbientSound.OCEAN] ?: false, ambientVol[AmbientSound.OCEAN] ?: 50,
                { onAmbientToggle(AmbientSound.OCEAN) },
                { onAmbientVolume(AmbientSound.OCEAN, it) }
            )
            AmbientLayerRow(
                "Jhingur", "Raat ke jhingur aur halki hawa", Icons.Filled.NightsStay,
                ambientOn[AmbientSound.CRICKETS] ?: false, ambientVol[AmbientSound.CRICKETS] ?: 50,
                { onAmbientToggle(AmbientSound.CRICKETS) },
                { onAmbientVolume(AmbientSound.CRICKETS, it) }
            )
            AmbientLayerRow(
                "Garaj", "Door ki garaj — barsaat ke saath kabhi kabhi", Icons.Filled.Thunderstorm,
                ambientOn[AmbientSound.THUNDER] ?: false, ambientVol[AmbientSound.THUNDER] ?: 50,
                { onAmbientToggle(AmbientSound.THUNDER) },
                { onAmbientVolume(AmbientSound.THUNDER, it) }
            )
        }
    }
}

@Composable
private fun AmbientLayerRow(
    name: String,
    description: String,
    icon: ImageVector,
    on: Boolean,
    volume: Int,
    onToggle: () -> Unit,
    onVolume: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (on) SelectedTint else IdleTint)
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (on) AccentBlue else Color.White),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = if (on) Color.White else AccentBlue, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text(description, fontSize = 12.sp, color = MutedInk)
            }
            Switch(checked = on, onCheckedChange = { onToggle() })
        }
        if (on) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.VolumeUp, contentDescription = null, tint = MutedInk, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = volume.toFloat(),
                    onValueChange = { onVolume(it.toInt()) },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(thumbColor = AccentBlue, activeTrackColor = AccentBlue),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text("$volume%", fontSize = 12.sp, color = MutedInk)
            }
        }
    }
}

@Composable
private fun RainOptions(
    choice: RainChoice,
    volume: Int,
    onChoice: (RainChoice) -> Unit,
    onVolume: (Int) -> Unit
) {
    Column {
        RainOption(RainChoice.NONE, "Koi Nahi", "Baarish band", Icons.Filled.VolumeOff, choice) { onChoice(RainChoice.NONE) }
        RainOption(RainChoice.GENTLE, "Halki Raat Wali", "Halki, sust raat wali baarish", Icons.Filled.WaterDrop, choice) { onChoice(RainChoice.GENTLE) }
        RainOption(RainChoice.TIN, "Tin Wali Chhat", "Tin ki chhat par boondon ki tez awaaz", Icons.Filled.Home, choice) { onChoice(RainChoice.TIN) }
        RainOption(RainChoice.HEAVY, "Tez Barsaat", "Tez baarish, zyada shor wali", Icons.Filled.Thunderstorm, choice) { onChoice(RainChoice.HEAVY) }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.VolumeUp, contentDescription = null, tint = MutedInk, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Text("Baarish ki awaz", fontSize = 13.sp, color = Ink, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("$volume%", fontSize = 13.sp, color = MutedInk)
        }
        Slider(
            value = volume.toFloat(),
            onValueChange = { onVolume(it.toInt()) },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(thumbColor = AccentBlue, activeTrackColor = AccentBlue),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun RainOption(
    value: RainChoice,
    name: String,
    description: String,
    icon: ImageVector,
    current: RainChoice,
    onSelect: () -> Unit
) {
    val selected = current == value
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) SelectedTint else IdleTint)
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Icon(icon, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(description, fontSize = 12.sp, color = MutedInk)
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun HissayPanel(
    sets: List<StemSet>,
    activeId: String?,
    volumes: Map<StemKind, Int>,
    status: String,
    onPickZip: () -> Unit,
    onLoad: (StemSet) -> Unit,
    onUnload: () -> Unit,
    onDelete: (StemSet) -> Unit,
    onVolume: (StemKind, Int) -> Unit
) {
    val active = sets.firstOrNull { it.id == activeId }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Jo gana MVSEP se torr kar zip banaya ho, woh yahan chunein. Har hissa alag slider se kam ya zyada hoga — bilkul Audio Eraser ki tarah.",
            fontSize = 12.sp,
            color = MutedInk
        )
        OutlinedButton(onClick = onPickZip, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("MVSEP zip chunein")
        }
        if (status.isNotEmpty()) {
            Text(status, fontSize = 12.sp, color = MutedInk)
        }
        sets.forEach { set ->
            val isActive = set.id == activeId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (isActive) SelectedTint else IdleTint)
                    .clickable { onLoad(set) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.LibraryMusic,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(set.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text(
                        if (isActive) "Chal raha hai · ${set.stems.size} hisse" else "${set.stems.size} hisse",
                        fontSize = 12.sp,
                        color = if (isActive) AccentBlue else MutedInk
                    )
                }
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MutedInk,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { onDelete(set) }
                )
            }
        }
        if (active != null) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Sab aam", "Karaoke", "Drum zyada").forEach { label ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(IdleTint)
                            .border(1.dp, CardBorder, RoundedCornerShape(50))
                            .clickable {
                                active.stems.keys.forEach { kind ->
                                    val v = when (label) {
                                        "Karaoke" -> if (kind == StemKind.VOCALS) 0 else 100
                                        "Drum zyada" -> when (kind) {
                                            StemKind.DRUMS -> 140
                                            StemKind.BASS -> 110
                                            StemKind.VOCALS -> 80
                                            else -> 100
                                        }
                                        else -> 100
                                    }
                                    onVolume(kind, v)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Ink)
                    }
                }
            }
            Text("Hissou ki awaz", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink)
            StemKind.values().filter { active.stems.containsKey(it) }.forEach { kind ->
                val vol = volumes[kind] ?: 100
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(kind.label, fontSize = 13.sp, color = Ink, modifier = Modifier.weight(1f))
                        Text("$vol%", fontSize = 12.sp, color = MutedInk)
                    }
                    Slider(
                        value = vol.toFloat(),
                        onValueChange = { onVolume(kind, it.toInt()) },
                        valueRange = 0f..150f,
                        colors = SliderDefaults.colors(thumbColor = AccentBlue, activeTrackColor = AccentBlue),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            OutlinedButton(onClick = onUnload, modifier = Modifier.fillMaxWidth()) {
                Text("Tayyar gana band karein")
            }
            Text(
                "Speed, Lofi Rang aur Mahaul — sab is par bhi chalte hain.",
                fontSize = 12.sp,
                color = MutedInk
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/** Display name for a picked document; falls back to the URI's last segment. */
private fun queryDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (e: Exception) {
        null
    } ?: uri.lastPathSegment
}

/**
 * Load a song's embedded album art off the main thread (v1.6).
 * Returns null while loading and for songs without art (the caller
 * draws the fallback gradient instead).
 */
@Composable
private fun rememberArtwork(uri: Uri?): Bitmap? {
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    val context = LocalContext.current
    val handler = remember { Handler(Looper.getMainLooper()) }
    LaunchedEffect(uri) {
        bitmap = null
        if (uri != null) {
            Thread {
                val loaded = Artwork.load(context.applicationContext, uri)
                if (loaded != null) handler.post { bitmap = loaded }
            }.start()
        }
    }
    return bitmap
}

/** Small album-art thumb used by the library / playlist rows. */
@Composable
private fun ArtThumb(title: String, uri: Uri?, sizeDp: Int) {
    val bitmap = rememberArtwork(uri)
    val (gradA, gradB) = Artwork.fallbackFor(title)
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(listOf(gradA, gradB))),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size((sizeDp / 2).dp)
            )
        }
    }
}

/**
 * The Playlists tab (v1.6): the live queue on top, the remembered
 * library of songs in the middle, and Poweramp-style named playlists
 * below. All data operations are delegated to the caller; this panel
 * only holds dialog/expansion state.
 */
@Composable
private fun PlaylistsPanel(
    queue: List<QueueItem>,
    queueIndex: Int,
    library: List<LibrarySong>,
    stemSets: List<StemSet>,
    playlists: List<Playlist>,
    onJumpQueue: (Int) -> Unit,
    onRemoveQueue: (Int) -> Unit,
    onAddSongs: () -> Unit,
    onPlayLibrary: (Int) -> Unit,
    onEnqueueLibrary: (LibrarySong) -> Unit,
    onRemoveLibrary: (LibrarySong) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onDeletePlaylist: (Playlist) -> Unit,
    onPlayPlaylist: (Playlist, Int) -> Unit,
    onSetPlaylistEntries: (Playlist, List<PlaylistEntry>) -> Unit
) {
    var expandedId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var addingToId by remember { mutableStateOf<String?>(null) }

    Column {
        // ---- Queue ----
        Text("ABHI KI QUEUE", fontSize = 10.sp, color = MutedInk, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        if (queue.isEmpty()) {
            Text(
                "Queue khaali hai — koi gana chalayein ya neeche se jodein.",
                fontSize = 12.sp,
                color = MutedInk
            )
        } else {
            queue.forEachIndexed { idx, item ->
                val current = idx == queueIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (current) SelectedTint else IdleTint)
                        .clickable { onJumpQueue(idx) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (current) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Text(
                            "${idx + 1}",
                            fontSize = 12.sp,
                            color = MutedInk,
                            modifier = Modifier.width(16.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        item.displayTitle,
                        fontSize = 13.sp,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        color = Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Queue se hatayein",
                        tint = MutedInk,
                        modifier = Modifier
                            .size(17.dp)
                            .clickable { onRemoveQueue(idx) }
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- Library ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MERAY GANAY", fontSize = 10.sp, color = MutedInk, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onAddSongs) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Ganay jodein", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        if (library.isEmpty()) {
            Text(
                "Abhi koi gana yaad nahi — gana chunein ya \"Ganay jodein\" se kai ganay aik saath daal dein, app inhein yaad rakh legi.",
                fontSize = 12.sp,
                color = MutedInk
            )
        } else {
            library.forEachIndexed { idx, song ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(IdleTint)
                        .clickable { onPlayLibrary(idx) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArtThumb(song.title, Uri.parse(song.uri), 38)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        song.title,
                        fontSize = 13.sp,
                        color = Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Filled.PlaylistAdd,
                        contentDescription = "Queue mein jodein",
                        tint = AccentBlue,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { onEnqueueLibrary(song) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Library se hatayein",
                        tint = MutedInk,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { onRemoveLibrary(song) }
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- Playlists ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PLAYLISTS", fontSize = 10.sp, color = MutedInk, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { newName = ""; creating = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Nayi playlist", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        if (playlists.isEmpty()) {
            Text(
                "Koi playlist nahi — apni pasand ke ganou ki list banayein, aik tap mein saari chalegi.",
                fontSize = 12.sp,
                color = MutedInk
            )
        } else {
            playlists.forEach { playlist ->
                val expanded = expandedId == playlist.id
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(IdleTint)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.LibraryMusic,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(19.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                playlist.name,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${playlist.entries.size} ganay",
                                fontSize = 11.sp,
                                color = MutedInk
                            )
                        }
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Playlist chalayein",
                            tint = AccentBlue,
                            modifier = Modifier
                                .size(22.dp)
                                .clickable { onPlayPlaylist(playlist, 0) }
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = "Kholain",
                            tint = MutedInk,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable {
                                    expandedId = if (expanded) null else playlist.id
                                }
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Playlist delete",
                            tint = MutedInk,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { onDeletePlaylist(playlist) }
                        )
                    }
                    if (expanded) {
                        Spacer(Modifier.height(8.dp))
                        playlist.entries.forEachIndexed { eIdx, entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(CardSurface)
                                    .clickable { onPlayPlaylist(playlist, eIdx) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    entry.title,
                                    fontSize = 12.sp,
                                    color = Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    Icons.Filled.ArrowUpward,
                                    contentDescription = "Upar",
                                    tint = MutedInk,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable {
                                            if (eIdx > 0) {
                                                val copy = playlist.entries.toMutableList()
                                                val tmp = copy[eIdx - 1]
                                                copy[eIdx - 1] = copy[eIdx]
                                                copy[eIdx] = tmp
                                                onSetPlaylistEntries(playlist, copy)
                                            }
                                        }
                                )
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    Icons.Filled.ArrowDownward,
                                    contentDescription = "Neeche",
                                    tint = MutedInk,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable {
                                            if (eIdx < playlist.entries.lastIndex) {
                                                val copy = playlist.entries.toMutableList()
                                                val tmp = copy[eIdx + 1]
                                                copy[eIdx + 1] = copy[eIdx]
                                                copy[eIdx] = tmp
                                                onSetPlaylistEntries(playlist, copy)
                                            }
                                        }
                                )
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Hatayein",
                                    tint = MutedInk,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable {
                                            onSetPlaylistEntries(
                                                playlist,
                                                playlist.entries.filterIndexed { i, _ -> i != eIdx }
                                            )
                                        }
                                )
                            }
                            Spacer(Modifier.height(5.dp))
                        }
                        OutlinedButton(
                            onClick = { addingToId = playlist.id },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Gana jodein", fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // Create-playlist dialog
    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Nayi playlist") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Playlist ka naam") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newName.trim()
                        if (name.isNotEmpty()) {
                            onCreatePlaylist(name)
                            creating = false
                        }
                    }
                ) { Text("Banayein") }
            },
            dismissButton = {
                OutlinedButton(onClick = { creating = false }) { Text("Band") }
            }
        )
    }

    // Add-entry dialog: every library song and tayyar gana, tap to add.
    val addingTo = playlists.firstOrNull { it.id == addingToId }
    if (addingTo != null) {
        AlertDialog(
            onDismissRequest = { addingToId = null },
            title = { Text("${addingTo.name} mein jodein") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (library.isEmpty() && stemSets.isEmpty()) {
                        Text(
                            "Library khaali hai — pehle ganay jodein.",
                            fontSize = 12.sp,
                            color = MutedInk
                        )
                    }
                    library.forEach { song ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSetPlaylistEntries(
                                        addingTo,
                                        addingTo.entries + PlaylistEntry.ofSong(song)
                                    )
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArtThumb(song.title, Uri.parse(song.uri), 34)
                            Spacer(Modifier.width(10.dp))
                            Text(song.title, fontSize = 13.sp, color = Ink, maxLines = 1)
                        }
                    }
                    stemSets.forEach { set ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSetPlaylistEntries(
                                        addingTo,
                                        addingTo.entries + PlaylistEntry.ofStem(set)
                                    )
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArtThumb(set.name, null, 34)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "${set.name} (tayyar gana)",
                                fontSize = 13.sp,
                                color = Ink,
                                maxLines = 1
                            )
                        }
                    }
                }
            },
            confirmButton = {
                OutlinedButton(onClick = { addingToId = null }) { Text("Band") }
            }
        )
    }
}
