package com.chaoslab.app.render

import android.opengl.GLES30
import com.chaoslab.app.sim.GravitySimulation
import java.util.Random
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws the universe with OpenGL ES 3.0:
 *  sky stars → body cores (depth) → gravity-well grid → motion trails → particles → body halos.
 * Particles and glows are additive; body cores write depth so a black hole correctly
 * hides the particles orbiting behind it.
 */
class UniverseRenderer(private val maxParticles: Int = GravitySimulation.MAX_PARTICLES) {

    class Options {
        var trails = true
        var grid = true
        var palette = Palette.PLASMA
        var selectedBodyId = -1
        var trailLength = 1.8f
    }

    private companion object {
        const val GRID_CELLS = 56
        const val GRID_EXTENT = 620f
        const val SKY_STARS = 1800
        const val PARTICLE_WORLD_SIZE = 1.7f
        const val BODY_HALO = 4f
    }

    // Programs
    private var particleProg = 0
    private var trailProg = 0
    private var bodyProg = 0
    private var gridProg = 0
    private var skyProg = 0

    // GL objects
    private val vao = IntArray(5)
    private val vbo = IntArray(6)
    private var gridIndexCount = 0
    private var maxPointSize = 256f

    // CPU staging
    private val particleData = FloatArray(maxParticles * 4)
    private val trailData = FloatArray(maxParticles * 8)
    private val bodyData = FloatArray(GravitySimulation.MAX_BODIES * 8)
    private val gridVerts = FloatArray((GRID_CELLS + 1) * (GRID_CELLS + 1) * 3)
    private val particleBuf = GlUtil.floatBuffer(maxParticles * 4)
    private val trailBuf = GlUtil.floatBuffer(maxParticles * 8)
    private val bodyBuf = GlUtil.floatBuffer(GravitySimulation.MAX_BODIES * 8)
    private val gridBuf = GlUtil.floatBuffer(gridVerts.size)

    private var speedRef = 4f

    // Uniform locations
    private var pMVP = 0; private var pScale = 0; private var pC0 = 0; private var pC1 = 0; private var pC2 = 0; private var pIntensity = 0
    private var tMVP = 0; private var tC0 = 0; private var tC1 = 0; private var tC2 = 0; private var tIntensity = 0
    private var bMVP = 0; private var bProj = 0; private var bHalo = 0; private var bMax = 0; private var bPass = 0
    private var gMVP = 0; private var gExtent = 0; private var gColor = 0
    private var sMVP = 0

    /** Must be called on the GL thread whenever a (new) context is created. */
    fun init() {
        particleProg = GlUtil.program(Shaders.PARTICLE_VS, Shaders.PARTICLE_FS)
        trailProg = GlUtil.program(Shaders.TRAIL_VS, Shaders.TRAIL_FS)
        bodyProg = GlUtil.program(Shaders.BODY_VS, Shaders.BODY_FS)
        gridProg = GlUtil.program(Shaders.GRID_VS, Shaders.GRID_FS)
        skyProg = GlUtil.program(Shaders.SKY_VS, Shaders.SKY_FS)

        pMVP = GLES30.glGetUniformLocation(particleProg, "uMVP")
        pScale = GLES30.glGetUniformLocation(particleProg, "uPointScale")
        pC0 = GLES30.glGetUniformLocation(particleProg, "uC0")
        pC1 = GLES30.glGetUniformLocation(particleProg, "uC1")
        pC2 = GLES30.glGetUniformLocation(particleProg, "uC2")
        pIntensity = GLES30.glGetUniformLocation(particleProg, "uIntensity")
        tMVP = GLES30.glGetUniformLocation(trailProg, "uMVP")
        tC0 = GLES30.glGetUniformLocation(trailProg, "uC0")
        tC1 = GLES30.glGetUniformLocation(trailProg, "uC1")
        tC2 = GLES30.glGetUniformLocation(trailProg, "uC2")
        tIntensity = GLES30.glGetUniformLocation(trailProg, "uIntensity")
        bMVP = GLES30.glGetUniformLocation(bodyProg, "uMVP")
        bProj = GLES30.glGetUniformLocation(bodyProg, "uProjScale")
        bHalo = GLES30.glGetUniformLocation(bodyProg, "uHalo")
        bMax = GLES30.glGetUniformLocation(bodyProg, "uMaxPoint")
        bPass = GLES30.glGetUniformLocation(bodyProg, "uPass")
        gMVP = GLES30.glGetUniformLocation(gridProg, "uMVP")
        gExtent = GLES30.glGetUniformLocation(gridProg, "uExtent")
        gColor = GLES30.glGetUniformLocation(gridProg, "uColor")
        sMVP = GLES30.glGetUniformLocation(skyProg, "uMVP")

        val range = FloatArray(2)
        GLES30.glGetFloatv(GLES30.GL_ALIASED_POINT_SIZE_RANGE, range, 0)
        maxPointSize = if (range[1] > 1f) range[1] else 64f

        GLES30.glGenVertexArrays(5, vao, 0)
        GLES30.glGenBuffers(6, vbo, 0)

        // 0: particles (x,y,z,speedT)
        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, maxParticles * 16, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, 16, 0)

        // 1: trails (x,y,z,w) w>=0 head speedT, w<0 tail
        GLES30.glBindVertexArray(vao[1])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[1])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, maxParticles * 32, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, 16, 0)

        // 2: bodies (x,y,z,radius | kind,r,g,b)
        GLES30.glBindVertexArray(vao[2])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[2])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, GravitySimulation.MAX_BODIES * 32, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, 32, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, 32, 16)

        // 3: gravity grid
        GLES30.glBindVertexArray(vao[3])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[3])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, gridVerts.size * 4, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        val side = GRID_CELLS + 1
        val idx = GlUtil.shortBuffer(GRID_CELLS * side * 4)
        for (row in 0 until side) for (c in 0 until GRID_CELLS) {
            idx.put((row * side + c).toShort()); idx.put((row * side + c + 1).toShort())
        }
        for (col in 0 until side) for (r in 0 until GRID_CELLS) {
            idx.put((r * side + col).toShort()); idx.put(((r + 1) * side + col).toShort())
        }
        gridIndexCount = idx.position()
        idx.position(0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, vbo[4])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, gridIndexCount * 2, idx, GLES30.GL_STATIC_DRAW)

        // 4: sky
        GLES30.glBindVertexArray(vao[4])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[5])
        val sky = GlUtil.floatBuffer(SKY_STARS * 4)
        val rnd = Random(42)
        repeat(SKY_STARS) {
            var x = rnd.nextGaussian().toFloat(); var y = rnd.nextGaussian().toFloat(); var z = rnd.nextGaussian().toFloat()
            val l = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-3f)
            x /= l; y /= l; z /= l
            val b = rnd.nextFloat()
            sky.put(x * 5000f); sky.put(y * 5000f); sky.put(z * 5000f); sky.put(b * b * 0.9f + 0.1f)
        }
        sky.position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, SKY_STARS * 16, sky, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, 16, 0)

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun draw(sim: GravitySimulation, cam: OrbitCamera, opt: Options) {
        GLES30.glViewport(0, 0, cam.width, cam.height)
        GLES30.glClearColor(0.008f, 0.010f, 0.028f, 1f)
        GLES30.glClearDepthf(1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val n = uploadParticles(sim, opt)
        val nb = uploadBodies(sim, opt)
        val projScale = cam.projScale

        // --- Sky ---
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glUseProgram(skyProg)
        GLES30.glUniformMatrix4fv(sMVP, 1, false, cam.skyVp, 0)
        GLES30.glBindVertexArray(vao[4])
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, SKY_STARS)

        // --- Body cores (opaque, write depth) ---
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        if (nb > 0) drawBodies(cam, projScale, nb, pass = 0, halo = 1f)

        // --- Additive layers, depth-tested, no depth writes ---
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glDepthMask(false)

        if (opt.grid) drawGrid(sim, cam)

        val pal = opt.palette
        if (opt.trails && n > 0) {
            GLES30.glUseProgram(trailProg)
            GLES30.glUniformMatrix4fv(tMVP, 1, false, cam.vp, 0)
            GLES30.glUniform3fv(tC0, 1, pal.c0, 0)
            GLES30.glUniform3fv(tC1, 1, pal.c1, 0)
            GLES30.glUniform3fv(tC2, 1, pal.c2, 0)
            GLES30.glUniform1f(tIntensity, 0.35f)
            GLES30.glBindVertexArray(vao[1])
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, n * 2)
        }

        if (n > 0) {
            GLES30.glUseProgram(particleProg)
            GLES30.glUniformMatrix4fv(pMVP, 1, false, cam.vp, 0)
            GLES30.glUniform1f(pScale, projScale * PARTICLE_WORLD_SIZE)
            GLES30.glUniform3fv(pC0, 1, pal.c0, 0)
            GLES30.glUniform3fv(pC1, 1, pal.c1, 0)
            GLES30.glUniform3fv(pC2, 1, pal.c2, 0)
            GLES30.glUniform1f(pIntensity, if (n > 12000) 0.42f else 0.55f)
            GLES30.glBindVertexArray(vao[0])
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, n)
        }

        if (nb > 0) drawBodies(cam, projScale, nb, pass = 1, halo = BODY_HALO)

        GLES30.glDepthMask(true)
        GLES30.glBindVertexArray(0)
    }

    private fun drawBodies(cam: OrbitCamera, projScale: Float, nb: Int, pass: Int, halo: Float) {
        GLES30.glUseProgram(bodyProg)
        GLES30.glUniformMatrix4fv(bMVP, 1, false, cam.vp, 0)
        GLES30.glUniform1f(bProj, projScale)
        GLES30.glUniform1f(bHalo, halo)
        GLES30.glUniform1f(bMax, maxPointSize)
        GLES30.glUniform1i(bPass, pass)
        GLES30.glBindVertexArray(vao[2])
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, nb)
    }

    private fun uploadParticles(sim: GravitySimulation, opt: Options): Int {
        val n = sim.count
        if (n == 0) return 0
        val px = sim.px; val py = sim.py; val pz = sim.pz
        val vx = sim.vx; val vy = sim.vy; val vz = sim.vz
        val invRef = 1f / speedRef
        val tl = opt.trailLength
        val trails = opt.trails
        var sum = 0f
        var k = 0
        var t = 0
        for (i in 0 until n) {
            val x = px[i]; val y = py[i]; val z = pz[i]
            val a = vx[i]; val b = vy[i]; val c = vz[i]
            val s = sqrt(a * a + b * b + c * c)
            sum += s
            val st = min(s * invRef, 1f)
            particleData[k] = x; particleData[k + 1] = y; particleData[k + 2] = z; particleData[k + 3] = st
            k += 4
            if (trails) {
                trailData[t] = x; trailData[t + 1] = y; trailData[t + 2] = z; trailData[t + 3] = st
                trailData[t + 4] = x - a * tl; trailData[t + 5] = y - b * tl; trailData[t + 6] = z - c * tl
                trailData[t + 7] = -1f - st
                t += 8
            }
        }
        val mean = sum / n
        speedRef += (mean * 2.2f - speedRef) * 0.05f
        if (speedRef < 0.3f) speedRef = 0.3f

        particleBuf.clear(); particleBuf.put(particleData, 0, k); particleBuf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, k * 4, particleBuf)
        if (trails) {
            trailBuf.clear(); trailBuf.put(trailData, 0, t); trailBuf.position(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[1])
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, t * 4, trailBuf)
        }
        return n
    }

    private fun uploadBodies(sim: GravitySimulation, opt: Options): Int {
        val list = sim.bodies
        val nb = min(list.size, GravitySimulation.MAX_BODIES)
        if (nb == 0) return 0
        var k = 0
        for (i in 0 until nb) {
            val b = list[i]
            bodyData[k] = b.x; bodyData[k + 1] = b.y; bodyData[k + 2] = b.z; bodyData[k + 3] = b.radius
            bodyData[k + 4] = b.kind.code + if (b.id == opt.selectedBodyId) 10f else 0f
            bodyData[k + 5] = b.r; bodyData[k + 6] = b.g; bodyData[k + 7] = b.b
            k += 8
        }
        bodyBuf.clear(); bodyBuf.put(bodyData, 0, k); bodyBuf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[2])
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, k * 4, bodyBuf)
        return nb
    }

    /** Space-time "rubber sheet": grid displaced by the summed gravitational potential. */
    private fun drawGrid(sim: GravitySimulation, cam: OrbitCamera) {
        val side = GRID_CELLS + 1
        val step = 2f * GRID_EXTENT / GRID_CELLS
        val bodies = sim.bodies
        val nb = bodies.size
        val g = sim.gravity
        var k = 0
        for (r in 0 until side) {
            val z = -GRID_EXTENT + r * step
            for (c in 0 until side) {
                val x = -GRID_EXTENT + c * step
                var phi = 0f
                for (j in 0 until nb) {
                    val b = bodies[j]
                    val dx = x - b.x; val dz = z - b.z
                    val s = 22f + b.radius * 2f
                    phi += g * b.mass / sqrt(dx * dx + dz * dz + s * s)
                }
                gridVerts[k] = x
                gridVerts[k + 1] = -min(phi * 0.45f, 260f) - 2f
                gridVerts[k + 2] = z
                k += 3
            }
        }
        gridBuf.clear(); gridBuf.put(gridVerts, 0, k); gridBuf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[3])
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, k * 4, gridBuf)

        GLES30.glUseProgram(gridProg)
        GLES30.glUniformMatrix4fv(gMVP, 1, false, cam.vp, 0)
        GLES30.glUniform1f(gExtent, GRID_EXTENT)
        GLES30.glUniform3f(gColor, 0.25f, 0.55f, 1.0f)
        GLES30.glBindVertexArray(vao[3])
        GLES30.glDrawElements(GLES30.GL_LINES, gridIndexCount, GLES30.GL_UNSIGNED_SHORT, 0)
    }
}
