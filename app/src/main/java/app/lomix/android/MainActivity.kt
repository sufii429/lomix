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
import android.os.IBinder
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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
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
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.media3.common.Player
import kotlinx.coroutines.delay

// Approved palette (light, lively sky theme from the design mockup).
private val Ink = Color(0xFF16233A)
private val MutedInk = Color(0xFF5A6B84)
private val AccentBlue = Color(0xFF2F8FE8)
private val CardBorder = Color(0xFFD9E9F8)
private val SelectedTint = Color(0xFFE1F0FE)
private val IdleTint = Color(0xFFF3F9FF)
private val SkyGradient = Brush.verticalGradient(
    listOf(Color(0xFFBFE3FF), Color(0xFFDFF2FF), Color(0xFFFFF3D6))
)

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

    var beatsOn by remember { mutableStateOf(controller.beatsOn) }
    var beatsStyle by remember { mutableStateOf(controller.beatsStyle) }
    var beatsVolume by remember { mutableIntStateOf(controller.beatsVolumePercent) }
    var bpmAdjust by remember { mutableDoubleStateOf(controller.bpmAdjust) }
    var beatsBpm by remember { mutableStateOf(controller.beatInfo?.bpm) }
    var beatsDetecting by remember { mutableStateOf(controller.beatsDetecting) }
    var beatsSource by remember { mutableStateOf(controller.beatsSource) }
    var selfBeatsState by remember { mutableStateOf(controller.selfBeatsState) }
    // Same guard as the sleep timer: the poll adopts the controller's
    // adjust only when it changed on its own (a new song resets it to 0).
    var lastBeatsAdjustPushed by remember { mutableDoubleStateOf(controller.bpmAdjust) }

    var rainChoice by remember { mutableStateOf(controller.rainChoice) }
    var rainVolume by remember { mutableIntStateOf(controller.rainVolumePercent) }

    var sleepMinutes by remember { mutableIntStateOf(controller.sleepMinutes) }
    var sleepRemainingMs by remember { mutableLongStateOf(controller.sleepRemainingMs) }
    // Last sleep value this UI pushed into the controller; the poll below
    // only adopts the controller's value when it differs from this (i.e.
    // the timer fired on its own), never overwriting a fresh user tap.
    var lastSleepPushed by remember { mutableIntStateOf(controller.sleepMinutes) }

    var selectedTab by remember { mutableIntStateOf(0) }

    val hasSong = songUri != null

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
                // still works for this session, which is enough for v1.
            }
            val name = queryDisplayName(context, uri) ?: "Picked song"
            songName = name
            songUri = uri
            isPlaying = false
            positionMs = 0L
            controller.setSong(uri, name)
        }
    }

    // ---- Declarative wiring: state -> controller ------------------------
    LaunchedEffect(speed) { controller.applySpeed(speed) }
    LaunchedEffect(garam, naram, geela) { controller.applyFx(garam, naram, geela) }
    LaunchedEffect(rainChoice, rainVolume) { controller.applyRain(rainChoice, rainVolume) }
    LaunchedEffect(vinyl) { controller.applyVinyl(vinyl) }
    LaunchedEffect(cassette) { controller.applyCassette(cassette) }
    LaunchedEffect(beatsOn, beatsStyle, beatsVolume, bpmAdjust, beatsSource) {
        lastBeatsAdjustPushed = bpmAdjust
        controller.applyBeats(beatsOn, beatsStyle, beatsVolume, bpmAdjust, beatsSource)
    }
    LaunchedEffect(sleepMinutes) {
        lastSleepPushed = sleepMinutes
        controller.applySleepTimer(sleepMinutes)
    }
    LaunchedEffect(isPlaying) {
        if (hasSong) {
            if (isPlaying) controller.playAll() else controller.pauseAll()
        }
    }

    // ---- Progress polling ------------------------------------------------
    LaunchedEffect(songUri) {
        while (true) {
            val p = controller.songPlayer
            durationMs = if (p.duration > 0) p.duration else 0L
            positionMs = p.currentPosition.coerceAtLeast(0L)
            if (p.playbackState == Player.STATE_ENDED) {
                controller.onSongEnded()
                positionMs = 0L
            }
            // Keep the UI in step with changes made from the notification
            // (Play/Pause there, or the sleep timer firing).
            isPlaying = controller.isPlaying
            if (controller.sleepMinutes != lastSleepPushed) {
                sleepMinutes = controller.sleepMinutes
                lastSleepPushed = controller.sleepMinutes
            }
            sleepRemainingMs = controller.sleepRemainingMs
            beatsBpm = controller.beatInfo?.bpm
            beatsDetecting = controller.beatsDetecting
            selfBeatsState = controller.selfBeatsState
            if (controller.bpmAdjust != lastBeatsAdjustPushed) {
                bpmAdjust = controller.bpmAdjust
                lastBeatsAdjustPushed = controller.bpmAdjust
            }
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
        if (beatsOn) "Beats " + beatsStyleLabel(beatsStyle) else null
    )
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

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = AccentBlue,
            onPrimary = Color.White,
            surface = Color.White,
            onSurface = Ink,
            background = Color(0xFFEEF5FD)
        )
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

            Spacer(Modifier.height(18.dp))

            // Song card
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            songName,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (hasSong) "Ready — set the mix and press play"
                            else "Pick a song from your phone to begin",
                            fontSize = 12.sp,
                            color = MutedInk
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (hasSong) "Change" else "Pick")
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
                        onClick = { if (hasSong) isPlaying = !isPlaying },
                        enabled = hasSong,
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
                    Spacer(Modifier.height(6.dp))
                    val liveFraction = if (durationMs > 0) {
                        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    } else 0f
                    Slider(
                        value = scrubFraction ?: liveFraction,
                        onValueChange = { scrubFraction = it },
                        onValueChangeFinished = {
                            val target = ((scrubFraction ?: 0f) * durationMs).toLong()
                            controller.songPlayer.seekTo(target)
                            positionMs = target
                            scrubFraction = null
                        },
                        enabled = hasSong && durationMs > 0,
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
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(18.dp))

            // Big pill tabs
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillTab("Speed", Icons.Filled.Speed, selectedTab == 0, Modifier.weight(1f)) { selectedTab = 0 }
                PillTab("Lofi Rang", Icons.Filled.GraphicEq, selectedTab == 1, Modifier.weight(1f)) { selectedTab = 1 }
                PillTab("Beats", Icons.Filled.MusicNote, selectedTab == 2, Modifier.weight(1f)) { selectedTab = 2 }
                PillTab("Baarish", Icons.Filled.WaterDrop, selectedTab == 3, Modifier.weight(1f)) { selectedTab = 3 }
            }

            Spacer(Modifier.height(14.dp))

            // Tab panel
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
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
                        2 -> BeatsPanel(
                            on = beatsOn,
                            style = beatsStyle,
                            volume = beatsVolume,
                            bpm = beatsBpm,
                            detecting = beatsDetecting,
                            adjust = bpmAdjust,
                            hasSong = hasSong,
                            source = beatsSource,
                            selfState = selfBeatsState,
                            onToggle = { beatsOn = !beatsOn },
                            onStyle = { beatsStyle = it },
                            onVolume = { beatsVolume = it },
                            onAdjust = { bpmAdjust = it },
                            onSource = { beatsSource = it }
                        )
                        else -> RainPanel(
                            choice = rainChoice,
                            volume = rainVolume,
                            onChoice = { rainChoice = it },
                            onVolume = { rainVolume = it }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Current mix strip
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Ink),
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

            Spacer(Modifier.height(12.dp))
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
private fun RainPanel(
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
private fun BeatsPanel(
    on: Boolean,
    style: BeatsStyle,
    volume: Int,
    bpm: Double?,
    detecting: Boolean,
    adjust: Double,
    hasSong: Boolean,
    source: BeatsSource,
    selfState: SelfBeatsState,
    onToggle: () -> Unit,
    onStyle: (BeatsStyle) -> Unit,
    onVolume: (Int) -> Unit,
    onAdjust: (Double) -> Unit,
    onSource: (BeatsSource) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FxToggleCard(
            "Beats", "Khud sync honay walay lofi drums, ganay ke neeche",
            Icons.Filled.MusicNote, on
        ) { onToggle() }

        val status = when {
            !hasSong -> "Pehle gana chunein — phir taal khud dhoondhi jayegi"
            detecting -> "Taal dhoond raha hai… chand second lag sakte hain"
            bpm != null -> "Taal mil gayi: %.1f BPM — beats khud sync hain".format((bpm ?: 0.0) + adjust)
            else -> "Taal khud nahi mili — neeche se BPM haath se theek karein"
        }
        Text(status, fontSize = 12.sp, color = MutedInk)

        Text("Beats ka source", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BeatsChip("App wali", source == BeatsSource.BUILTIN) { onSource(BeatsSource.BUILTIN) }
            BeatsChip("Ganay ki apni", source == BeatsSource.SELF) { onSource(BeatsSource.SELF) }
        }
        if (source == BeatsSource.SELF) {
            val selfStatus = when (selfState) {
                SelfBeatsState.BUILDING -> "Is ganay ke drum se beats bana raha hai…"
                SelfBeatsState.READY -> "Ye beats isi ganay ke apne drum se bani hain"
                SelfBeatsState.MISSING -> "Is ganay mein saaf drum nahi mile — App wali beats chalengi"
                SelfBeatsState.IDLE -> "Gana chunein — phir usi ke drum se beats banengi"
            }
            Text(selfStatus, fontSize = 12.sp, color = MutedInk)
        }

        Text("Style", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BeatsChip("Balanced", style == BeatsStyle.BALANCED) { onStyle(BeatsStyle.BALANCED) }
            BeatsChip("Warm", style == BeatsStyle.WARM) { onStyle(BeatsStyle.WARM) }
            BeatsChip("Bass Boost", style == BeatsStyle.BASS_BOOST) { onStyle(BeatsStyle.BASS_BOOST) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BeatsChip("Smooth", style == BeatsStyle.SMOOTH) { onStyle(BeatsStyle.SMOOTH) }
            BeatsChip("Dynamic", style == BeatsStyle.DYNAMIC) { onStyle(BeatsStyle.DYNAMIC) }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.VolumeUp, contentDescription = null, tint = MutedInk, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(8.dp))
            Text("Beats ki awaz", fontSize = 13.sp, color = Ink, fontWeight = FontWeight.Bold)
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

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Taal theek karein", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = { onAdjust(adjust - 0.5) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
            ) { Text("−") }
            Spacer(Modifier.width(8.dp))
            Text("%.1f".format((bpm ?: 90.0) + adjust), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { onAdjust(adjust + 0.5) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
            ) { Text("+") }
        }
        Text(
            "Agar beats ganay se aage ya peeche lagein to − / + se taal mila lein. Speed badalne par beats khud nayi speed se mil jati hain.",
            fontSize = 12.sp,
            color = MutedInk
        )
    }
}

@Composable
private fun BeatsChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) AccentBlue else IdleTint)
            .border(1.dp, if (active) AccentBlue else CardBorder, RoundedCornerShape(50))
            .clickable(onClick = onClick)
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
