package com.chaoslab.app.sim

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class Preset(val label: String) {
    BLACK_HOLE("Black Hole"),
    ORBITS("Solar Orbits"),
    BINARY("Binary Stars"),
    THREE_BODY("Three-Body"),
    COLLISION("Galaxy Collision"),
}

/** Description of a freshly built scene: title and a suggested camera framing. */
data class SceneInfo(val title: String, val cameraDistance: Float, val cameraPitch: Float)

/** Hand-tuned starting conditions. All assume gravity G = 1. */
object Presets {

    private val TWO_PI = (2.0 * PI).toFloat()

    fun build(sim: GravitySimulation, preset: Preset): SceneInfo {
        sim.clearAll()
        sim.gravity = 1f
        val budget = sim.capacity
        return when (preset) {
            Preset.BLACK_HOLE -> blackHole(sim, budget)
            Preset.ORBITS -> orbits(sim, budget)
            Preset.BINARY -> binary(sim, budget)
            Preset.THREE_BODY -> threeBody(sim, budget)
            Preset.COLLISION -> collision(sim, budget)
        }
    }

    private fun blackHole(sim: GravitySimulation, budget: Int): SceneInfo {
        val m = 5000f
        sim.addBody(BodyKind.BLACK_HOLE, m, 0f, 0f, 0f)
        sim.accretion = 0.03f
        sim.spawnDisk(0f, 0f, 0f, 0f, 0f, 0f, m, (budget * 0.92f).toInt(), 24f, 330f, 4f)
        // A distant companion star perturbs the disk and slowly feeds the hole.
        val r = 420f
        val v = sim.orbitalSpeed(m, r)
        sim.addBody(BodyKind.STAR, 260f, r, 0f, 0f, 0f, 0f, -v)
        return SceneInfo("Black Hole", 560f, 0.38f)
    }

    private fun orbits(sim: GravitySimulation, budget: Int): SceneInfo {
        val m = 1200f
        val star = sim.addBody(BodyKind.STAR, m, 0f, 0f, 0f)!!
        val v = FloatArray(3)
        for ((r, pm) in listOf(70f to 9f, 125f to 22f, 195f to 14f, 270f to 40f)) {
            val th = sim.rng.nextFloat() * TWO_PI
            val x = r * cos(th); val z = r * sin(th)
            sim.orbitVelocity(star, x, 0f, z, v)
            sim.addBody(BodyKind.PLANET, pm, x, 0f, z, v[0], v[1], v[2])
        }
        sim.spawnDisk(0f, 0f, 0f, 0f, 0f, 0f, m, (budget * 0.6f).toInt(), 26f, 320f, 2.5f)
        sim.spawnDisk(0f, 0f, 0f, 0f, 0f, 0f, m, (budget * 0.3f).toInt(), 150f, 168f, 1.2f)
        return SceneInfo("Solar Orbits", 640f, 0.6f)
    }

    private fun binary(sim: GravitySimulation, budget: Int): SceneInfo {
        val m = 800f
        val d = 90f
        val v = sqrt(sim.gravity * m / (2f * d))
        sim.addBody(BodyKind.STAR, m, d / 2f, 0f, 0f, 0f, 0f, -v)
        sim.addBody(BodyKind.STAR, m * 0.95f, -d / 2f, 0f, 0f, 0f, 0f, v)
        sim.spawnDisk(d / 2f, 0f, 0f, 0f, 0f, -v, m, (budget * 0.06f).toInt(), 9f, 24f, 1f)
        sim.spawnDisk(-d / 2f, 0f, 0f, 0f, 0f, v, m, (budget * 0.06f).toInt(), 9f, 24f, 1f)
        sim.spawnDisk(0f, 0f, 0f, 0f, 0f, 0f, 2 * m, (budget * 0.85f).toInt(), 140f, 400f, 5f)
        return SceneInfo("Binary Stars", 760f, 0.55f)
    }

    private fun threeBody(sim: GravitySimulation, budget: Int): SceneInfo {
        // Chenciner–Montgomery figure-eight choreography, scaled. Softening makes it
        // slowly drift into chaos — which is exactly the point.
        val l = 90f
        val m = 700f
        val vs = sqrt(sim.gravity * m / l)
        val p1x = 0.97000436f * l; val p1z = -0.24308753f * l
        val v3x = -0.93240737f * vs; val v3z = -0.86473146f * vs
        val v1x = -v3x / 2f; val v1z = -v3z / 2f
        sim.addBody(BodyKind.STAR, m, p1x, 0f, p1z, v1x, 0f, v1z)
        sim.addBody(BodyKind.STAR, m, -p1x, 0f, -p1z, v1x, 0f, v1z)
        sim.addBody(BodyKind.STAR, m, 0f, 0f, 0f, v3x, 0f, v3z)
        sim.spawnDisk(p1x, 0f, p1z, v1x, 0f, v1z, m, (budget * 0.14f).toInt(), 8f, 20f, 1f)
        sim.spawnDisk(-p1x, 0f, -p1z, v1x, 0f, v1z, m, (budget * 0.14f).toInt(), 8f, 20f, 1f)
        sim.spawnDisk(0f, 0f, 0f, v3x, 0f, v3z, m, (budget * 0.14f).toInt(), 8f, 20f, 1f)
        sim.spawnDisk(0f, 0f, 0f, 0f, 0f, 0f, 3 * m, (budget * 0.55f).toInt(), 230f, 380f, 6f)
        return SceneInfo("Three-Body Problem", 720f, 0.7f)
    }

    private fun collision(sim: GravitySimulation, budget: Int): SceneInfo {
        val m = 2500f
        sim.accretion = 0.01f
        sim.addBody(BodyKind.BLACK_HOLE, m, -280f, 0f, -90f, 1.7f, 0f, 0.15f)
        sim.addBody(BodyKind.BLACK_HOLE, m, 280f, 0f, 90f, -1.7f, 0f, -0.15f)
        sim.spawnDisk(-280f, 0f, -90f, 1.7f, 0f, 0.15f, m, (budget * 0.48f).toInt(), 14f, 175f, 3f, 0.25f, 0f, 1f, 2)
        sim.spawnDisk(280f, 0f, 90f, -1.7f, 0f, -0.15f, m, (budget * 0.48f).toInt(), 14f, 175f, 3f, -0.9f, 0.4f, -1f, 2)
        return SceneInfo("Galaxy Collision", 980f, 0.75f)
    }

    /** A random but always-interesting universe. */
    fun surprise(sim: GravitySimulation, seed: Long): SceneInfo {
        val r = Random(seed)
        sim.clearAll()
        sim.gravity = 1f
        val budget = sim.capacity
        val bh = r.nextFloat() < 0.45f
        val coreMass = if (bh) 2500f + r.nextFloat() * 4500f else 900f + r.nextFloat() * 1300f
        val core = sim.addBody(if (bh) BodyKind.BLACK_HOLE else BodyKind.STAR, coreMass, 0f, 0f, 0f)!!
        if (bh) sim.accretion = 0.02f + r.nextFloat() * 0.05f
        val arms = r.nextInt(4)
        val rOut = 170f + r.nextFloat() * 230f
        sim.spawnDisk(
            0f, 0f, 0f, 0f, 0f, 0f, coreMass, (budget * (0.45f + r.nextFloat() * 0.3f)).toInt(),
            core.radius * 4f, rOut, 2f + r.nextFloat() * 6f,
            (r.nextFloat() - 0.5f) * 0.4f, 0f, 1f, arms,
        )
        val sats = r.nextInt(5)
        val v = FloatArray(3)
        repeat(sats) {
            val dist = 150f + r.nextFloat() * 300f
            val th = r.nextFloat() * TWO_PI
            val x = dist * cos(th); val z = dist * sin(th)
            val isStar = r.nextFloat() < 0.35f
            val mass = if (isStar) 150f + r.nextFloat() * 450f else 5f + r.nextFloat() * 60f
            val dir = if (r.nextFloat() < 0.8f) 1f else -1f
            sim.orbitVelocity(core, x, 0f, z, v, dir)
            val b = sim.addBody(if (isStar) BodyKind.STAR else BodyKind.PLANET, mass, x, (r.nextFloat() - 0.5f) * 20f, z, v[0], v[1], v[2])
            if (b != null && isStar) {
                sim.spawnDisk(b.x, b.y, b.z, b.vx, b.vy, b.vz, mass, (budget * 0.06f).toInt(), b.radius * 2.5f, b.radius * 7f, 1f)
            }
        }
        // Sometimes add a rogue intruder on a collision course.
        if (r.nextFloat() < 0.35f) {
            val kind = if (r.nextFloat() < 0.5f) BodyKind.STAR else BodyKind.BLACK_HOLE
            val mass = if (kind == BodyKind.STAR) 600f else 1500f
            sim.addBody(kind, mass, -rOut * 1.8f, 30f, rOut * 0.4f, 2.4f, -0.1f, -0.3f)
        }
        val title = "Universe #" + (1000 + (seed and 0xFFFFL) % 9000)
        return SceneInfo(title, rOut * 2.4f, 0.35f + r.nextFloat() * 0.5f)
    }
}
