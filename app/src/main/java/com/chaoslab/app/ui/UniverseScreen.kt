package com.chaoslab.app.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chaoslab.app.data.ChaosSettings
import com.chaoslab.app.data.SettingsStore
import com.chaoslab.app.engine.ChaosEngine
import com.chaoslab.app.engine.ChaosSurfaceView
import com.chaoslab.app.engine.EngineStats
import com.chaoslab.app.engine.SelectedBody
import com.chaoslab.app.export.ScreenshotExporter
import com.chaoslab.app.render.Palette
import com.chaoslab.app.sim.Body
import com.chaoslab.app.sim.BodyKind
import com.chaoslab.app.sim.Preset
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ChaosLabApp(engine: ChaosEngine, store: SettingsStore) {
    val loaded by produceState<ChaosSettings?>(initialValue = null) { value = store.settings.first() }
    val initial = loaded
    if (initial == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
    } else {
        UniverseScreen(engine, store, initial)
    }
}

private fun fmt(n: Long): String = String.format(Locale.US, "%,d", n)
private fun fmt(n: Int): String = fmt(n.toLong())

@Composable
fun UniverseScreen(engine: ChaosEngine, store: SettingsStore, initial: ChaosSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Push persisted settings into the engine before the GL surface is created.
    remember(engine) {
        engine.showTrails = initial.trails
        engine.showGrid = initial.grid
        engine.collisions = initial.collisions
        engine.recycle = initial.recycle
        engine.autoQuality = initial.autoQuality
        engine.palette = Palette.byIndex(initial.palette)
        engine.setParticleBudget(initial.particleBudget)
        true
    }

    val stats by engine.stats.collectAsStateWithLifecycle()

    var gravity by remember { mutableFloatStateOf(engine.gravity) }
    var timeScale by remember { mutableFloatStateOf(engine.timeScale) }
    var budget by remember { mutableFloatStateOf(initial.particleBudget.toFloat()) }
    var trails by remember { mutableStateOf(initial.trails) }
    var grid by remember { mutableStateOf(initial.grid) }
    var collisions by remember { mutableStateOf(initial.collisions) }
    var recycle by remember { mutableStateOf(initial.recycle) }
    var autoQuality by remember { mutableStateOf(initial.autoQuality) }
    var paused by remember { mutableStateOf(false) }
    var cinematic by remember { mutableStateOf(false) }
    var brush by remember { mutableStateOf(engine.brush) }
    var panelOpen by remember { mutableStateOf(false) }
    var showHints by remember { mutableStateOf(!initial.hintsSeen) }
    var savedUri by remember { mutableStateOf<Uri?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<String?>(null) }

    // GL view lifecycle
    val lifecycleOwner = LocalLifecycleOwner.current
    var glView by remember { mutableStateOf<ChaosSurfaceView?>(null) }
    DisposableEffect(lifecycleOwner, glView) {
        val v = glView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> v?.onResume()
                Lifecycle.Event.ON_PAUSE -> v?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(stats.eventId) {
        val text = stats.eventText
        if (stats.eventId > 0 && text != null) {
            banner = text
            delay(2600)
            banner = null
        }
    }
    LaunchedEffect(savedUri) { if (savedUri != null) { delay(9000); savedUri = null } }
    LaunchedEffect(notice) { if (notice != null) { delay(3500); notice = null } }

    fun resetPhysicsSliders() {
        gravity = 1f
        engine.gravity = 1f
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> ChaosSurfaceView(ctx, engine).also { glView = it } },
            modifier = Modifier.fillMaxSize(),
        )

        Box(Modifier.fillMaxSize().safeDrawingPadding()) {

            // ---- Header ----
            Column(Modifier.align(Alignment.TopStart).padding(18.dp)) {
                Text("CHAOS LAB", color = ChaosColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
                Text(
                    "GRAVITY PLAYGROUND  ·  ${stats.sceneTitle.uppercase(Locale.US)}",
                    color = ChaosColors.Neon, fontSize = 11.sp, letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(6.dp))
                StatsLine(stats)
            }

            // ---- Top-right controls ----
            Row(
                Modifier.align(Alignment.TopEnd).padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(if (paused) "▶  PLAY" else "❚❚  PAUSE", active = paused, accent = ChaosColors.Ember) {
                    paused = !paused; engine.paused = paused
                }
                Pill("CINEMA", active = cinematic) { cinematic = !cinematic; engine.cinematic = cinematic }
                Pill(if (capturing) "SAVING…" else "◉  SNAPSHOT") {
                    if (!capturing) {
                        capturing = true
                        ScreenshotExporter.capture(engine, context, stats.sceneTitle) { uri ->
                            capturing = false
                            if (uri != null) savedUri = uri else notice = "Couldn't save the snapshot"
                        }
                    }
                }
                Box(
                    Modifier.size(42.dp).glass(radius = 21).clickable { panelOpen = !panelOpen },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (panelOpen) Icons.Filled.Close else Icons.Filled.Settings,
                        contentDescription = "Lab controls",
                        tint = if (panelOpen) ChaosColors.Neon else ChaosColors.Text,
                    )
                }
            }

            // ---- Event banner ----
            AnimatedVisibility(
                visible = banner != null,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp),
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
            ) {
                Text(
                    banner ?: "",
                    color = ChaosColors.Ember,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.glass(radius = 24).padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }

            // ---- Settings panel ----
            AnimatedVisibility(
                visible = panelOpen,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp, top = 70.dp, bottom = 120.dp),
                enter = fadeIn() + slideInHorizontally { it / 2 },
                exit = fadeOut() + slideOutHorizontally { it / 2 },
            ) {
                Column(
                    Modifier
                        .width(300.dp)
                        .heightIn(max = 560.dp)
                        .glass(strong = true)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                ) {
                    SectionLabel("Physics")
                    LabeledSlider("Gravity strength", String.format(Locale.US, "%.2f G", gravity), gravity, 0.1f..4f, {
                        gravity = it; engine.gravity = it
                    })
                    LabeledSlider(
                        "Time speed",
                        if (timeScale < 0.6f) String.format(Locale.US, "%.2f× slow-mo", timeScale) else String.format(Locale.US, "%.2f×", timeScale),
                        timeScale, 0.1f..4f, { timeScale = it; engine.timeScale = it },
                    )
                    ToggleRow("Body collisions & merging", collisions) {
                        collisions = it; engine.collisions = it; scope.launch { store.setCollisions(it) }
                    }
                    ToggleRow("Recycle absorbed particles", recycle) {
                        recycle = it; engine.recycle = it; scope.launch { store.setRecycle(it) }
                    }

                    SectionLabel("Visuals")
                    ToggleRow("Motion trails", trails) {
                        trails = it; engine.showTrails = it; scope.launch { store.setTrails(it) }
                    }
                    ToggleRow("Space-time grid", grid) {
                        grid = it; engine.showGrid = it; scope.launch { store.setGrid(it) }
                    }
                    Text("Particle colors", color = ChaosColors.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Palette.entries.forEach { p ->
                            Pill(p.label, active = stats.palette == p) {
                                engine.palette = p
                                scope.launch { store.setPalette(p.ordinal) }
                            }
                        }
                    }

                    SectionLabel("Performance")
                    LabeledSlider(
                        "Particle limit", fmt(budget.toInt()), budget, 1000f..20000f,
                        { budget = (it / 500f).toInt() * 500f },
                        {
                            val b = budget.toInt()
                            engine.setParticleBudget(b)
                            scope.launch { store.setBudget(b) }
                        },
                    )
                    ToggleRow("Adaptive quality", autoQuality) {
                        autoQuality = it; engine.autoQuality = it; scope.launch { store.setAutoQuality(it) }
                    }
                    if (stats.qualityReduced) {
                        Text(
                            "Running ${fmt(stats.budget)} particles to keep the frame rate smooth.",
                            color = ChaosColors.Ember, fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Pill("HOW TO PLAY", modifier = Modifier.fillMaxWidth()) { showHints = true; panelOpen = false }
                    Spacer(Modifier.height(12.dp))
                }
            }

            // ---- Body inspector ----
            val sel = stats.selected
            if (sel != null) {
                Inspector(
                    sel = sel,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp),
                    onMass = { engine.setSelectedMass(it) },
                    onKind = { engine.setSelectedKind(it) },
                    onDelete = { engine.deleteSelected() },
                    onClose = { engine.deselect() },
                )
            }

            // ---- Bottom dock ----
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("LONG-PRESS CREATES", color = ChaosColors.TextDim, fontSize = 11.sp, letterSpacing = 1.5.sp)
                    BodyKind.entries.forEach { k ->
                        Pill(k.label, active = brush == k, accent = kindAccent(k)) { brush = k; engine.brush = k }
                    }
                    Spacer(Modifier.width(10.dp))
                    Pill("CLEAR DUST") { engine.clearParticles() }
                    Pill("EMPTY SPACE") { engine.emptyUniverse() }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Preset.entries.forEach { p ->
                        Pill(p.label, active = stats.sceneTitle.startsWith(p.label)) {
                            resetPhysicsSliders(); engine.applyPreset(p)
                        }
                    }
                    SurpriseButton { resetPhysicsSliders(); engine.surprise() }
                }
            }

            // ---- Snapshot saved toast ----
            val uri = savedUri
            AnimatedVisibility(
                visible = uri != null || notice != null,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 120.dp),
                enter = fadeIn(), exit = fadeOut(),
            ) {
                Row(
                    Modifier.glass(strong = true).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(notice ?: "Saved to Pictures/ChaosLab", color = ChaosColors.Text, fontSize = 13.sp)
                    if (uri != null) {
                        Pill("SHARE", active = true) { ScreenshotExporter.share(context, uri) }
                    }
                }
            }

            stats.renderError?.let { err ->
                Text(
                    "Graphics error: $err\nThis device may not fully support OpenGL ES 3.0.",
                    color = ChaosColors.Danger,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).glass(strong = true).padding(20.dp),
                )
            }
        }

        if (showHints) {
            HintsOverlay {
                showHints = false
                scope.launch { store.setHintsSeen(true) }
            }
        }
    }
}

private fun kindAccent(k: BodyKind): Color = when (k) {
    BodyKind.PLANET -> ChaosColors.Neon
    BodyKind.STAR -> ChaosColors.Ember
    BodyKind.BLACK_HOLE -> ChaosColors.Violet
}

@Composable
private fun StatsLine(s: EngineStats) {
    val fpsColor = when {
        s.fps >= 50 -> Color(0xFF7CFFB2)
        s.fps >= 30 -> ChaosColors.Ember
        else -> ChaosColors.Danger
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${s.fps} FPS", color = fpsColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "  ·  ${fmt(s.particles)} particles  ·  ${s.bodies} bodies  ·  ${fmt(s.absorbed)} absorbed",
            color = ChaosColors.TextDim, fontSize = 12.sp,
        )
    }
}

@Composable
private fun Inspector(
    sel: SelectedBody,
    modifier: Modifier,
    onMass: (Float) -> Unit,
    onKind: (BodyKind) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    var mass by remember(sel.id, sel.kind) { mutableFloatStateOf(sel.mass) }
    val range = Body.massRange(sel.kind)
    Column(modifier.width(270.dp).glass(strong = true).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(sel.kind.label.uppercase(Locale.US), color = kindAccent(sel.kind), fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("Body #${sel.id}", color = ChaosColors.TextDim, fontSize = 11.sp)
            }
            Box(
                Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = ChaosColors.TextDim) }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BodyKind.entries.forEach { k ->
                Pill(if (k == BodyKind.BLACK_HOLE) "BH" else k.label, active = sel.kind == k, accent = kindAccent(k)) { onKind(k) }
            }
        }
        Spacer(Modifier.height(10.dp))
        LabeledSlider(
            "Mass", fmt(mass.toInt()),
            mass.coerceIn(range.start, range.endInclusive), range,
            { mass = it; onMass(it) },
        )
        Text("Drag to move · flick to throw · double-tap to delete", color = ChaosColors.TextDim, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        Pill("DELETE", accent = ChaosColors.Danger, active = true, modifier = Modifier.fillMaxWidth(), onClick = onDelete)
    }
}

@Composable
private fun HintsOverlay(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000005))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .padding(24.dp)
                .glass(radius = 24, strong = true)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("WELCOME TO CHAOS LAB", color = ChaosColors.Neon, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            Spacer(Modifier.height(6.dp))
            Text("Every particle here obeys real gravity. Break things.", color = ChaosColors.TextDim, fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            listOf(
                "Drag" to "orbit the camera",
                "Pinch / twist" to "zoom and rotate",
                "Long-press" to "create a planet, star or black hole",
                "Drag a body" to "move it — flick to throw it",
                "Tap" to "drop an orbiting dust cloud · tap a body to inspect it",
                "Double-tap" to "detonate an explosion · on a body: delete it",
            ).forEach { (g, d) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(g.uppercase(Locale.US), color = ChaosColors.Ember, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.width(140.dp))
                    Text(d, color = ChaosColors.Text, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(20.dp))
            Pill("START EXPERIMENTING", active = true, onClick = onDismiss)
        }
    }
}
