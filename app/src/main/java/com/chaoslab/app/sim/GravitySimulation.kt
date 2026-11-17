package com.chaoslab.app.sim

import java.util.Random
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time gravity simulation.
 *
 *  - Bodies (planets, stars, black holes) attract each other: full N-body, O(M²).
 *  - Particles are massless tracers attracted by every body: O(N·M).
 *  - Integration: semi-implicit (symplectic) Euler with adaptive sub-stepping,
 *    Plummer softening to keep close encounters finite.
 *  - Particles that enter a body are absorbed (counted) and optionally recycled
 *    into a fresh orbit so long-running experiments never run dry.
 *  - Bodies that touch merge with momentum conservation and throw out debris.
 *
 * Not thread-safe: owned and mutated only by the GL/render thread.
 */
class GravitySimulation(val maxParticles: Int = MAX_PARTICLES) {

    companion object {
        const val MAX_PARTICLES = 24000
        const val MAX_BODIES = 24
        const val WORLD_LIMIT = 3600f
        private const val TARGET_SUBSTEP = 0.1f
        private const val MAX_SUBSTEPS = 16
        private const val TWO_PI = (2.0 * PI).toFloat()
    }

    // ---- Particles (structure of arrays for cache-friendly loops) ----
    val px = FloatArray(maxParticles)
    val py = FloatArray(maxParticles)
    val pz = FloatArray(maxParticles)
    val vx = FloatArray(maxParticles)
    val vy = FloatArray(maxParticles)
    val vz = FloatArray(maxParticles)
    var count = 0
        private set

    // ---- Bodies ----
    val bodies = ArrayList<Body>()
    private var nextBodyId = 1

    // ---- Parameters ----
    var gravity = 1f
    var softening = 2f
    var collisions = true
    var recycle = true
    /** Black-hole accretion friction (0 = pure gravity). Makes particles spiral inward. */
    var accretion = 0f
    var budget = 8000
        set(value) { field = value.coerceIn(500, maxParticles) }

    // ---- Stats ----
    var absorbedTotal = 0L
        private set
    var mergesTotal = 0
        private set
    /** Short human-readable event for the UI (merges, collapses). Consumed by the engine. */
    var pendingEvent: String? = null

    val rng = Random()

    // Packed body data for the particle hot loop.
    private var nb = 0
    private val bX = FloatArray(MAX_BODIES)
    private val bY = FloatArray(MAX_BODIES)
    private val bZ = FloatArray(MAX_BODIES)
    private val bVX = FloatArray(MAX_BODIES)
    private val bVY = FloatArray(MAX_BODIES)
    private val bVZ = FloatArray(MAX_BODIES)
    private val bGM = FloatArray(MAX_BODIES)
    private val bR = FloatArray(MAX_BODIES)
    private val bR2 = FloatArray(MAX_BODIES)
    private val bBH = BooleanArray(MAX_BODIES)
    private val tAx = FloatArray(MAX_BODIES)
    private val tAy = FloatArray(MAX_BODIES)
    private val tAz = FloatArray(MAX_BODIES)

    // ------------------------------------------------------------------ setup

    fun clearParticles() { count = 0 }

    fun clearAll() {
        count = 0
        bodies.clear()
        absorbedTotal = 0
        mergesTotal = 0
        accretion = 0f
        pendingEvent = null
    }

    fun trimToBudget() { if (count > budget) count = budget }

    val capacity: Int get() = min(budget, maxParticles)

    fun addParticle(x: Float, y: Float, z: Float, vx0: Float, vy0: Float, vz0: Float): Boolean {
        if (count >= capacity) return false
        val i = count++
        px[i] = x; py[i] = y; pz[i] = z
        vx[i] = vx0; vy[i] = vy0; vz[i] = vz0
        return true
    }

    private fun removeParticle(i: Int) {
        val last = --count
        if (i != last) {
            px[i] = px[last]; py[i] = py[last]; pz[i] = pz[last]
            vx[i] = vx[last]; vy[i] = vy[last]; vz[i] = vz[last]
        }
    }

    fun addBody(
        kind: BodyKind, mass: Float,
        x: Float, y: Float, z: Float,
        vx0: Float = 0f, vy0: Float = 0f, vz0: Float = 0f,
    ): Body? {
        if (bodies.size >= MAX_BODIES) return null
        val b = Body(nextBodyId++, kind, mass, x, y, z, vx0, vy0, vz0)
        bodies.add(b)
        return b
    }

    fun removeBody(id: Int) { bodies.removeAll { it.id == id } }

    fun findBody(id: Int): Body? = bodies.firstOrNull { it.id == id }

    /** Body with the strongest pull at a point (m / d²). */
    fun dominantBody(x: Float, y: Float, z: Float, exclude: Body? = null): Body? {
        var best: Body? = null
        var bestPull = 0f
        for (b in bodies) {
            if (b === exclude) continue
            val dx = b.x - x; val dy = b.y - y; val dz = b.z - z
            val pull = b.mass / (dx * dx + dy * dy + dz * dz + 1f)
            if (pull > bestPull) { bestPull = pull; best = b }
        }
        return best
    }

    /** Circular orbital speed around [mass] at distance [r] for the softened potential. */
    fun orbitalSpeed(mass: Float, r: Float): Float {
        val e2 = softening * softening
        val d2 = r * r + e2
        return sqrt(gravity * mass * r * r / (d2 * sqrt(d2)))
    }

    /**
     * Velocity for a circular orbit around [center] at point (x,y,z), orbiting in the
     * plane perpendicular to the Y axis. Returns absolute velocity (includes center velocity).
     */
    fun orbitVelocity(center: Body, x: Float, y: Float, z: Float, out: FloatArray, dir: Float = 1f) {
        val rx = x - center.x; val rz = z - center.z
        val r = sqrt(rx * rx + rz * rz).coerceAtLeast(0.001f)
        val s = orbitalSpeed(center.mass, r) * dir
        // tangent = Y × r  =  (rz, 0, -rx)
        out[0] = center.vx + rz / r * s
        out[1] = center.vy
        out[2] = center.vz - rx / r * s
    }

    private fun gauss(): Float = rng.nextGaussian().toFloat()

    /**
     * Spawns a rotating disk of particles around a center of mass.
     * [arms] > 0 produces logarithmic spiral arms (galaxy look).
     */
    fun spawnDisk(
        cx: Float, cy: Float, cz: Float,
        cvx: Float, cvy: Float, cvz: Float,
        centralMass: Float, n: Int,
        rIn: Float, rOut: Float, thickness: Float,
        tiltX: Float = 0f, tiltZ: Float = 0f,
        dir: Float = 1f, arms: Int = 0,
    ): Int {
        val ctx = cos(tiltX); val stx = sin(tiltX)
        val ctz = cos(tiltZ); val stz = sin(tiltZ)
        var added = 0
        for (k in 0 until n) {
            val u = rng.nextFloat().pow(1.35f)
            val r = rIn + (rOut - rIn) * u
            val theta = if (arms > 0) {
                (k % arms) * TWO_PI / arms + ln(r / rIn) * 1.9f + gauss() * 0.32f
            } else rng.nextFloat() * TWO_PI
            val c = cos(theta); val s = sin(theta)
            val h = gauss() * thickness * (1f - 0.5f * u)
            var lx = r * c; var ly = h; var lz = r * s
            val sp = orbitalSpeed(centralMass, r) * (1f + gauss() * 0.025f) * dir
            var lvx = s * sp; var lvy = gauss() * 0.02f * sp; var lvz = -c * sp
            // tilt about X
            var t1 = ly * ctx - lz * stx; var t2 = ly * stx + lz * ctx; ly = t1; lz = t2
            t1 = lvy * ctx - lvz * stx; t2 = lvy * stx + lvz * ctx; lvy = t1; lvz = t2
            // tilt about Z
            t1 = lx * ctz - ly * stz; t2 = lx * stz + ly * ctz; lx = t1; ly = t2
            t1 = lvx * ctz - lvy * stz; t2 = lvx * stz + lvy * ctz; lvx = t1; lvy = t2
            if (!addParticle(cx + lx, cy + ly, cz + lz, cvx + lvx, cvy + lvy, cvz + lvz)) break
            added++
        }
        return added
    }

    /** A puff of particles placed into orbit around whatever body dominates that point. */
    fun spawnCloud(x: Float, y: Float, z: Float, n: Int, radius: Float): Int {
        val dom = dominantBody(x, y, z)
        val v = FloatArray(3)
        var added = 0
        for (k in 0 until n) {
            val qx = x + gauss() * radius
            val qy = y + gauss() * radius * 0.35f
            val qz = z + gauss() * radius
            if (dom != null) orbitVelocity(dom, qx, qy, qz, v) else { v[0] = 0f; v[1] = 0f; v[2] = 0f }
            if (!addParticle(qx, qy, qz, v[0] + gauss() * 0.15f, v[1] + gauss() * 0.05f, v[2] + gauss() * 0.15f)) break
            added++
        }
        return added
    }

    /** Radial burst of particles starting at [minRadius] from the center. */
    fun spawnExplosion(
        x: Float, y: Float, z: Float, n: Int, speed: Float,
        bvx: Float = 0f, bvy: Float = 0f, bvz: Float = 0f, minRadius: Float = 0.5f,
    ): Int {
        var added = 0
        for (k in 0 until n) {
            var dx = gauss(); var dy = gauss() * 0.6f; var dz = gauss()
            val len = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
            dx /= len; dy /= len; dz /= len
            val s = speed * (0.35f + 0.75f * rng.nextFloat())
            val r0 = minRadius * (1f + 0.3f * rng.nextFloat())
            if (!addParticle(x + dx * r0, y + dy * r0, z + dz * r0, bvx + dx * s, bvy + dy * s, bvz + dz * s)) break
            added++
        }
        return added
    }

    // ------------------------------------------------------------------ stepping

    fun step(dt: Float) {
        if (dt <= 0f) return
        val sub = ceil(dt / TARGET_SUBSTEP).toInt().coerceIn(1, MAX_SUBSTEPS)
        val h = dt / sub
        repeat(sub) {
            stepBodies(h)
            if (collisions) resolveBodyCollisions()
            packBodies()
            stepParticles(h)
        }
    }

    private fun stepBodies(h: Float) {
        val n = bodies.size
        if (n == 0) return
        val e2 = softening * softening
        for (i in 0 until n) {
            val bi = bodies[i]
            var ax = 0f; var ay = 0f; var az = 0f
            for (j in 0 until n) {
                if (i == j) continue
                val bj = bodies[j]
                val dx = bj.x - bi.x; val dy = bj.y - bi.y; val dz = bj.z - bi.z
                val d2 = dx * dx + dy * dy + dz * dz + e2
                val inv = 1f / (d2 * sqrt(d2))
                val f = gravity * bj.mass * inv
                ax += f * dx; ay += f * dy; az += f * dz
            }
            tAx[i] = ax; tAy[i] = ay; tAz[i] = az
        }
        for (i in 0 until n) {
            val b = bodies[i]
            if (b.pinned) continue
            b.vx += tAx[i] * h; b.vy += tAy[i] * h; b.vz += tAz[i] * h
            b.x += b.vx * h; b.y += b.vy * h; b.z += b.vz * h
        }
    }

    private fun packBodies() {
        nb = bodies.size
        for (j in 0 until nb) {
            val b = bodies[j]
            bX[j] = b.x; bY[j] = b.y; bZ[j] = b.z
            bVX[j] = b.vx; bVY[j] = b.vy; bVZ[j] = b.vz
            bGM[j] = gravity * b.mass
            val r = b.radius
            bR[j] = r; bR2[j] = r * r
            bBH[j] = b.kind == BodyKind.BLACK_HOLE
        }
    }

    private fun stepParticles(h: Float) {
        val n = nb
        val soft2 = softening * softening
        val acc = accretion
        val lim2 = WORLD_LIMIT * WORLD_LIMIT
        var i = 0
        while (i < count) {
            val x = px[i]; val y = py[i]; val z = pz[i]
            var vxx = vx[i]; var vyy = vy[i]; var vzz = vz[i]
            var ax = 0f; var ay = 0f; var az = 0f
            var dvx = 0f; var dvy = 0f; var dvz = 0f
            var dead = false
            for (j in 0 until n) {
                val dx = bX[j] - x; val dy = bY[j] - y; val dz = bZ[j] - z
                val r2 = dx * dx + dy * dy + dz * dz
                if (r2 < bR2[j]) { dead = true; break }
                val d2 = r2 + soft2
                val invD = 1f / sqrt(d2)
                val f = bGM[j] * invD * invD * invD
                ax += f * dx; ay += f * dy; az += f * dz
                if (acc > 0f && bBH[j]) {
                    val k = min(acc * h * bR[j] * invD, 0.05f)
                    dvx -= (vxx - bVX[j]) * k
                    dvy -= (vyy - bVY[j]) * k
                    dvz -= (vzz - bVZ[j]) * k
                }
            }
            if (!dead && x * x + y * y + z * z > lim2) dead = true
            if (dead) {
                absorbedTotal++
                if (!(recycle && respawn(i))) { removeParticle(i); continue }
                i++
                continue
            }
            vxx += ax * h + dvx; vyy += ay * h + dvy; vzz += az * h + dvz
            px[i] = x + vxx * h; py[i] = y + vyy * h; pz[i] = z + vzz * h
            vx[i] = vxx; vy[i] = vyy; vz[i] = vzz
            i++
        }
    }

    /** Re-inject particle [i] into a fresh orbit around a heavy body. */
    private fun respawn(i: Int): Boolean {
        if (bodies.isEmpty()) return false
        var total = 0f
        for (b in bodies) if (b.kind != BodyKind.PLANET) total += b.mass
        if (total <= 0f) return false
        var pick = rng.nextFloat() * total
        var host: Body = bodies[0]
        for (b in bodies) {
            if (b.kind == BodyKind.PLANET) continue
            pick -= b.mass
            host = b
            if (pick <= 0f) break
        }
        val r = host.radius * 6f + rng.nextFloat().pow(1.2f) * (120f + sqrt(host.mass) * 2.5f)
        val th = rng.nextFloat() * TWO_PI
        val c = cos(th); val s = sin(th)
        val sp = orbitalSpeed(host.mass, r)
        px[i] = host.x + r * c; py[i] = host.y + gauss() * 3f; pz[i] = host.z + r * s
        vx[i] = host.vx + s * sp; vy[i] = host.vy; vz[i] = host.vz - c * sp
        return true
    }

    private fun resolveBodyCollisions() {
        var restart = true
        var guard = 0
        while (restart && guard++ < 8) {
            restart = false
            loop@ for (i in 0 until bodies.size) {
                for (j in i + 1 until bodies.size) {
                    val a = bodies[i]; val b = bodies[j]
                    val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
                    val rr = (a.radius + b.radius) * 0.85f
                    if (dx * dx + dy * dy + dz * dz < rr * rr) {
                        merge(a, b)
                        restart = true
                        break@loop
                    }
                }
            }
        }
    }

    private fun merge(a: Body, b: Body) {
        val winner: Body
        val loser: Body
        if (a.kind.rank > b.kind.rank || (a.kind == b.kind && a.mass >= b.mass)) { winner = a; loser = b } else { winner = b; loser = a }
        val total = a.mass + b.mass
        val rvx = a.vx - b.vx; val rvy = a.vy - b.vy; val rvz = a.vz - b.vz
        val relV = sqrt(rvx * rvx + rvy * rvy + rvz * rvz)
        val nvx = (a.vx * a.mass + b.vx * b.mass) / total
        val nvy = (a.vy * a.mass + b.vy * b.mass) / total
        val nvz = (a.vz * a.mass + b.vz * b.mass) / total
        if (!winner.pinned) {
            if (loser.pinned) { winner.x = loser.x; winner.y = loser.y; winner.z = loser.z }
            else {
                winner.x = (a.x * a.mass + b.x * b.mass) / total
                winner.y = (a.y * a.mass + b.y * b.mass) / total
                winner.z = (a.z * a.mass + b.z * b.mass) / total
            }
        }
        winner.pinned = winner.pinned || loser.pinned
        winner.vx = nvx; winner.vy = nvy; winner.vz = nvz
        val loserKind = loser.kind
        winner.mass = total
        var event = when {
            winner.kind == BodyKind.BLACK_HOLE && loserKind == BodyKind.BLACK_HOLE -> "Black holes merged"
            winner.kind == BodyKind.BLACK_HOLE && loserKind == BodyKind.STAR -> "Black hole swallowed a star"
            winner.kind == BodyKind.BLACK_HOLE -> "Black hole swallowed a planet"
            winner.kind == BodyKind.STAR && loserKind == BodyKind.STAR -> "Stellar collision"
            winner.kind == BodyKind.STAR -> "Planet fell into a star"
            else -> "Planetary impact"
        }
        if (winner.kind == BodyKind.STAR && total > 3200f) {
            winner.kind = BodyKind.BLACK_HOLE
            event = "Stellar collapse — a black hole is born!"
        }
        if (winner.kind == BodyKind.PLANET && total > 160f) {
            winner.kind = BodyKind.STAR
            winner.mass = max(total, 200f)
            event = "Planets fused into a new star"
        }
        bodies.remove(loser)
        mergesTotal++
        pendingEvent = event

        // Debris: a fraction escapes, the rest falls back — looks great either way.
        val debris = when (loserKind) {
            BodyKind.PLANET -> 120
            BodyKind.STAR -> 420
            BodyKind.BLACK_HOLE -> 260
        } + (relV * 20f).toInt()
        val free = capacity - count
        val n = min(debris.coerceAtMost(800), free)
        if (n > 0) {
            val r = winner.radius
            val vEsc = sqrt(2f * gravity * winner.mass / (r * 1.4f))
            spawnExplosion(winner.x, winner.y, winner.z, n, vEsc * 0.95f, nvx, nvy, nvz, r * 1.4f)
        }
    }

    /** Mass-weighted center of all bodies (used by the camera to keep the action framed). */
    fun centerOfMass(out: FloatArray): Boolean {
        var m = 0f; var x = 0f; var y = 0f; var z = 0f
        for (b in bodies) { m += b.mass; x += b.x * b.mass; y += b.y * b.mass; z += b.z * b.mass }
        if (m <= 0f) return false
        out[0] = x / m; out[1] = y / m; out[2] = z / m
        return true
    }
}
