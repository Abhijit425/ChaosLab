package com.chaoslab.app.engine

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.chaoslab.app.render.OrbitCamera
import com.chaoslab.app.render.Palette
import com.chaoslab.app.render.UniverseRenderer
import com.chaoslab.app.sim.Body
import com.chaoslab.app.sim.BodyKind
import com.chaoslab.app.sim.GravitySimulation
import com.chaoslab.app.sim.Preset
import com.chaoslab.app.sim.Presets
import com.chaoslab.app.sim.SceneInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.exp
import kotlin.math.sqrt

data class SelectedBody(val id: Int, val kind: BodyKind, val mass: Float)

data class EngineStats(
    val fps: Int = 0,
    val particles: Int = 0,
    val bodies: Int = 0,
    val absorbed: Long = 0,
    val budget: Int = 0,
    val qualityReduced: Boolean = false,
    val selected: SelectedBody? = null,
    val sceneTitle: String = "",
    val eventText: String? = null,
    val eventId: Long = 0,
    val palette: Palette = Palette.PLASMA,
    val renderError: String? = null,
)

/**
 * The heart of CHAOS LAB. Runs entirely on the GL thread: steps physics, handles
 * touch commands (queued from the UI thread), drives the camera and renders.
 * UI state is published through [stats]; UI → engine changes go through @Volatile
 * fields or [post] for structural edits.
 */
class ChaosEngine : GLSurfaceView.Renderer {

    companion object {
        /** Simulation time units per real second at time-scale 1. */
        const val SIM_RATE = 20f
        private const val TAG = "ChaosEngine"
    }

    val sim = GravitySimulation()
    val camera = OrbitCamera()
    private val renderer = UniverseRenderer()
    private val renderOpts = UniverseRenderer.Options()
    private var view: GLSurfaceView? = null

    fun attach(v: GLSurfaceView) { view = v }
    fun post(block: () -> Unit) { view?.queueEvent(block) }

    // ---------- UI-controlled parameters ----------
    @Volatile var gravity = 1f
    @Volatile var timeScale = 1f
    @Volatile var paused = false
    @Volatile var showTrails = true
    @Volatile var showGrid = true
    @Volatile var cinematic = false
    @Volatile var autoQuality = true
    @Volatile var collisions = true
    @Volatile var recycle = true
    @Volatile var palette = Palette.PLASMA
    @Volatile var brush = BodyKind.STAR
    @Volatile var userBudget = 8000
        private set

    fun setParticleBudget(value: Int) {
        userBudget = value
        post {
            sim.budget = value
            sim.trimToBudget()
            qualityReduced = false
            lastQualityChangeNs = System.nanoTime()
        }
    }

    private val _stats = MutableStateFlow(EngineStats())
    val stats: StateFlow<EngineStats> = _stats.asStateFlow()

    // ---------- Internal state (GL thread only) ----------
    private var initialized = false
    private var sceneTitle = ""
    private var lastNs = 0L
    private var frames = 0
    private var fpsWindowStart = 0L
    private var fps = 0
    private var lastStatsNs = 0L
    private var frameMsEma = 16.7f
    private var qualityReduced = false
    private var lastQualityChangeNs = 0L
    private var eventText: String? = null
    private var eventId = 0L
    private var renderError: String? = null
    private var selectedId = -1
    private val com = FloatArray(3)

    // Touch
    private var dragBody: Body? = null
    private var touchActive = false
    private var dragPlaneY = 0f
    private val hit = FloatArray(3)
    private val screen = FloatArray(2)
    private val histT = LongArray(6)
    private val histX = FloatArray(6)
    private val histZ = FloatArray(6)
    private var histN = 0

    @Volatile private var pendingShot: ((Bitmap) -> Unit)? = null

    // ================= Scene commands (call from any thread) =================

    fun applyPreset(p: Preset) = post { loadScene(Presets.build(sim, p)) }

    fun surprise() = post {
        val seed = System.nanoTime() xor sim.rng.nextLong()
        val info = Presets.surprise(sim, seed)
        palette = Palette.entries[(seed ushr 3).toInt().and(0x7fffffff) % Palette.entries.size]
        camera.yaw = sim.rng.nextFloat() * 6.28f
        loadScene(info)
    }

    fun clearParticles() = post { sim.clearParticles() }

    fun emptyUniverse() = post {
        sim.clearAll()
        selectedId = -1
        dragBody = null
        sceneTitle = "Empty Universe"
        emitEvent("Long-press anywhere to create a body")
    }

    fun deselect() = post { selectedId = -1 }

    fun deleteSelected() = post {
        if (selectedId >= 0) {
            sim.removeBody(selectedId)
            if (dragBody?.id == selectedId) dragBody = null
            selectedId = -1
        }
    }

    fun setSelectedMass(mass: Float) = post { sim.findBody(selectedId)?.mass = mass }

    fun setSelectedKind(kind: BodyKind) = post {
        val b = sim.findBody(selectedId) ?: return@post
        b.kind = kind
        val range = Body.massRange(kind)
        b.mass = b.mass.coerceIn(range.start, range.endInclusive)
    }

    fun requestScreenshot(cb: (Bitmap) -> Unit) { pendingShot = cb }

    private fun loadScene(info: SceneInfo) {
        sceneTitle = info.title
        selectedId = -1
        dragBody = null
        camera.distance = info.cameraDistance
        camera.pitch = info.cameraPitch
        camera.tx = 0f; camera.ty = 0f; camera.tz = 0f
        sim.gravity = gravity
    }

    private fun emitEvent(text: String) {
        eventText = text
        eventId++
    }

    // ================= Touch (GL thread, queued from ChaosSurfaceView) =================

    private fun pickBody(sx: Float, sy: Float): Body? {
        var best: Body? = null
        var bestD = Float.MAX_VALUE
        val ps = camera.projScale
        for (b in sim.bodies) {
            val w = camera.project(b.x, b.y, b.z, screen)
            if (w <= 0f) continue
            val dx = screen[0] - sx; val dy = screen[1] - sy
            val d = sqrt(dx * dx + dy * dy)
            val rad = maxOf(48f, b.radius * ps / w * 1.6f)
            if (d < rad && d < bestD) { bestD = d; best = b }
        }
        return best
    }

    fun onDown(sx: Float, sy: Float, t: Long) {
        touchActive = true
        val b = pickBody(sx, sy)
        dragBody = b
        histN = 0
        if (b != null) {
            dragPlaneY = b.y
            b.pinned = true
            b.vx = 0f; b.vy = 0f; b.vz = 0f
            pushHistory(t, b.x, b.z)
        }
    }

    fun onMove(sx: Float, sy: Float, dxPx: Float, dyPx: Float, t: Long) {
        val b = dragBody
        if (b != null && sim.bodies.contains(b)) {
            camera.screenToPlane(sx, sy, dragPlaneY, hit)
            b.x = hit[0]; b.y = hit[1]; b.z = hit[2]
            b.vx = 0f; b.vy = 0f; b.vz = 0f
            pushHistory(t, b.x, b.z)
        } else {
            camera.yaw -= dxPx * 0.005f
            camera.pitch += dyPx * 0.005f
        }
    }

    fun onUp(t: Long) {
        val b = dragBody
        if (b != null && sim.bodies.contains(b)) {
            b.pinned = false
            // Fling: convert finger velocity (world units per real second) into sim velocity.
            if (histN >= 2) {
                val last = (histN - 1) % histT.size
                var first = last
                for (k in 1 until minOf(histN, histT.size)) {
                    val idx = (histN - 1 - k) % histT.size
                    if (histT[last] - histT[idx] > 120) break
                    first = idx
                }
                val dtMs = (histT[last] - histT[first]).toFloat()
                if (dtMs > 5f && t - histT[last] < 80) {
                    val scale = 1000f / dtMs / (SIM_RATE * timeScale.coerceAtLeast(0.1f))
                    var fvx = (histX[last] - histX[first]) * scale
                    var fvz = (histZ[last] - histZ[first]) * scale
                    val sp = sqrt(fvx * fvx + fvz * fvz)
                    val maxSp = 25f
                    if (sp > maxSp) { fvx *= maxSp / sp; fvz *= maxSp / sp }
                    b.vx = fvx; b.vz = fvz
                }
            }
        }
        dragBody = null
        touchActive = false
    }

    fun onCancelSingle() {
        dragBody?.pinned = false
        dragBody = null
    }

    fun onGestureEnd() { touchActive = false }

    private fun pushHistory(t: Long, x: Float, z: Float) {
        val i = histN % histT.size
        histT[i] = t; histX[i] = x; histZ[i] = z
        histN++
    }

    fun onTap(sx: Float, sy: Float) {
        val b = pickBody(sx, sy)
        if (b != null) {
            selectedId = if (selectedId == b.id) -1 else b.id
            return
        }
        selectedId = -1
        camera.screenToPlane(sx, sy, 0f, hit)
        val n = sim.spawnCloud(hit[0], hit[1], hit[2], 260, 9f)
        if (n == 0) emitEvent("Particle limit reached — raise it in Settings")
    }

    fun onDoubleTap(sx: Float, sy: Float) {
        val b = pickBody(sx, sy)
        if (b != null) {
            sim.removeBody(b.id)
            if (selectedId == b.id) selectedId = -1
            emitEvent("${b.kind.label} removed")
            return
        }
        camera.screenToPlane(sx, sy, 0f, hit)
        val dom = sim.dominantBody(hit[0], hit[1], hit[2])
        val n = sim.spawnExplosion(hit[0], hit[1], hit[2], 700, 6f, dom?.vx ?: 0f, dom?.vy ?: 0f, dom?.vz ?: 0f)
        emitEvent(if (n > 0) "Boom! $n particles" else "Particle limit reached — raise it in Settings")
    }

    fun onLongPress(sx: Float, sy: Float) {
        if (dragBody != null) return
        if (pickBody(sx, sy) != null) return
        camera.screenToPlane(sx, sy, 0f, hit)
        val kind = brush
        val mass = Body.defaultMass(kind)
        val dom = sim.dominantBody(hit[0], hit[1], hit[2])
        var vx = 0f; var vy = 0f; var vz = 0f
        if (dom != null && dom.mass > mass) {
            val v = FloatArray(3)
            sim.orbitVelocity(dom, hit[0], hit[1], hit[2], v)
            vx = v[0]; vy = v[1]; vz = v[2]
        }
        val b = sim.addBody(kind, mass, hit[0], hit[1], hit[2], vx, vy, vz)
        if (b == null) { emitEvent("Maximum of ${GravitySimulation.MAX_BODIES} bodies"); return }
        if (kind == BodyKind.STAR) {
            sim.spawnDisk(b.x, b.y, b.z, b.vx, b.vy, b.vz, mass, 450, b.radius * 2.5f, b.radius * 12f, 1.2f)
        }
        selectedId = b.id
        emitEvent("${kind.label} created")
    }

    fun onPinch(scale: Float) {
        if (scale > 0f) camera.distance = camera.distance / scale
    }

    fun onTwist(radians: Float) { camera.yaw -= radians }

    // ================= Renderer =================

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            renderer.init()
            renderError = null
        } catch (e: RuntimeException) {
            Log.e(TAG, "GL init failed", e)
            renderError = e.message ?: "OpenGL initialisation failed"
        }
        if (!initialized) {
            sim.budget = userBudget
            loadScene(Presets.build(sim, Preset.BLACK_HOLE))
            initialized = true
        }
        lastNs = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        camera.setViewport(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val realDt = if (lastNs == 0L) 1f / 60f else ((now - lastNs) / 1e9f).coerceIn(0f, 0.05f)
        val frameMs = if (lastNs == 0L) 16.7f else (now - lastNs) / 1e6f
        lastNs = now

        sim.gravity = gravity
        sim.collisions = collisions
        sim.recycle = recycle

        if (!paused) sim.step(realDt * SIM_RATE * timeScale)
        sim.pendingEvent?.let { emitEvent(it); sim.pendingEvent = null }
        if (selectedId >= 0 && sim.findBody(selectedId) == null) selectedId = -1
        if (dragBody != null && !sim.bodies.contains(dragBody!!)) dragBody = null

        updateCamera(realDt)

        if (renderError == null) {
            renderOpts.trails = showTrails
            renderOpts.grid = showGrid
            renderOpts.palette = palette
            renderOpts.selectedBodyId = selectedId
            renderOpts.trailLength = 1.8f
            renderer.draw(sim, camera, renderOpts)
        } else {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }

        pendingShot?.let { cb ->
            pendingShot = null
            captureFrame()?.let(cb)
        }

        updateFps(now, frameMs)
        adaptQuality(now)
        publishStats(now)
    }

    private fun updateCamera(dt: Float) {
        if (cinematic && !touchActive) camera.yaw += dt * 0.07f
        if (!touchActive && dragBody == null && sim.centerOfMass(com)) {
            val k = 1f - exp(-dt * 0.8f)
            camera.tx += (com[0] - camera.tx) * k
            camera.ty += (com[1] - camera.ty) * k
            camera.tz += (com[2] - camera.tz) * k
        }
        camera.update()
    }

    private fun captureFrame(): Bitmap? {
        return try {
            val w = camera.width; val h = camera.height
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            buf.rewind()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)
            bmp
        } catch (t: Throwable) {
            Log.e(TAG, "Screenshot failed", t)
            null
        }
    }

    private fun updateFps(now: Long, frameMs: Float) {
        frames++
        frameMsEma += (frameMs - frameMsEma) * 0.05f
        if (fpsWindowStart == 0L) fpsWindowStart = now
        val el = now - fpsWindowStart
        if (el >= 500_000_000L) {
            fps = (frames * 1e9f / el).toInt()
            frames = 0
            fpsWindowStart = now
        }
    }

    /** Lowers the particle budget on slow devices; restores it when there is headroom. */
    private fun adaptQuality(now: Long) {
        if (!autoQuality) return
        if (now - lastQualityChangeNs < 2_000_000_000L) return
        if (frameMsEma > 24f && sim.budget > 1500) {
            sim.budget = (sim.budget * 0.85f).toInt()
            sim.trimToBudget()
            qualityReduced = true
            lastQualityChangeNs = now
        } else if (qualityReduced && frameMsEma < 17.5f && now - lastQualityChangeNs > 10_000_000_000L) {
            sim.budget = minOf(userBudget, (sim.budget * 1.1f).toInt() + 100)
            if (sim.budget >= userBudget) qualityReduced = false
            lastQualityChangeNs = now
        }
    }

    private fun publishStats(now: Long) {
        if (now - lastStatsNs < 250_000_000L) return
        lastStatsNs = now
        val sel = if (selectedId >= 0) sim.findBody(selectedId)?.let { SelectedBody(it.id, it.kind, it.mass) } else null
        _stats.value = EngineStats(
            fps = fps,
            particles = sim.count,
            bodies = sim.bodies.size,
            absorbed = sim.absorbedTotal,
            budget = sim.budget,
            qualityReduced = qualityReduced,
            selected = sel,
            sceneTitle = sceneTitle,
            eventText = eventText,
            eventId = eventId,
            palette = palette,
            renderError = renderError,
        )
    }
}
