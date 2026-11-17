package com.chaoslab.app.render

import android.opengl.Matrix
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Orbiting perspective camera with screen↔world helpers for touch picking. */
class OrbitCamera {
    var yaw = 0.6f
    var pitch = 0.4f
        set(value) { field = value.coerceIn(-1.45f, 1.45f) }
    var distance = 560f
        set(value) { field = value.coerceIn(30f, 3200f) }
    var tx = 0f; var ty = 0f; var tz = 0f

    var width = 1; private set
    var height = 1; private set
    val fovDeg = 50f

    val view = FloatArray(16)
    val proj = FloatArray(16)
    val vp = FloatArray(16)
    val skyVp = FloatArray(16)
    private val invVp = FloatArray(16)
    private val viewRot = FloatArray(16)
    private val tmpIn = FloatArray(4)
    private val tmpOut = FloatArray(4)

    var eyeX = 0f; var eyeY = 0f; var eyeZ = 0f; private set

    fun setViewport(w: Int, h: Int) { width = maxOf(1, w); height = maxOf(1, h) }

    /** Pixels per world unit at distance 1 (divide by clip-space w). */
    val projScale: Float get() = height / (2f * tan(Math.toRadians(fovDeg / 2.0).toFloat()))

    fun update() {
        val cp = cos(pitch)
        eyeX = tx + distance * cp * sin(yaw)
        eyeY = ty + distance * sin(pitch)
        eyeZ = tz + distance * cp * cos(yaw)
        Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ, tx, ty, tz, 0f, 1f, 0f)
        Matrix.perspectiveM(proj, 0, fovDeg, width.toFloat() / height, 1f, 12000f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        Matrix.invertM(invVp, 0, vp, 0)
        System.arraycopy(view, 0, viewRot, 0, 16)
        viewRot[12] = 0f; viewRot[13] = 0f; viewRot[14] = 0f
        Matrix.multiplyMM(skyVp, 0, proj, 0, viewRot, 0)
    }

    private fun unproject(sx: Float, sy: Float, ndcZ: Float, out: FloatArray) {
        tmpIn[0] = 2f * sx / width - 1f
        tmpIn[1] = 1f - 2f * sy / height
        tmpIn[2] = ndcZ
        tmpIn[3] = 1f
        Matrix.multiplyMV(tmpOut, 0, invVp, 0, tmpIn, 0)
        val w = if (abs(tmpOut[3]) < 1e-6f) 1e-6f else tmpOut[3]
        out[0] = tmpOut[0] / w; out[1] = tmpOut[1] / w; out[2] = tmpOut[2] / w
    }

    /**
     * Intersects the touch ray with the horizontal plane y = [planeY].
     * Falls back to a point at the target's depth if the plane is edge-on.
     */
    fun screenToPlane(sx: Float, sy: Float, planeY: Float, out: FloatArray) {
        val a = FloatArray(3); val b = FloatArray(3)
        unproject(sx, sy, -1f, a)
        unproject(sx, sy, 1f, b)
        val dx = b[0] - a[0]; val dy = b[1] - a[1]; val dz = b[2] - a[2]
        if (abs(dy) > 1e-4f) {
            val t = (planeY - a[1]) / dy
            if (t > 0f && t < 1f) {
                val px = a[0] + dx * t; val pz = a[2] + dz * t
                // Avoid absurdly distant hits near the horizon.
                val ddx = px - tx; val ddz = pz - tz
                if (ddx * ddx + ddz * ddz < (distance * 3f) * (distance * 3f)) {
                    out[0] = px; out[1] = planeY; out[2] = pz
                    return
                }
            }
        }
        val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
        out[0] = a[0] + dx / len * distance
        out[1] = a[1] + dy / len * distance
        out[2] = a[2] + dz / len * distance
    }

    /** Projects a world point to screen pixels. Returns clip w (<=0 means behind camera). */
    fun project(x: Float, y: Float, z: Float, out: FloatArray): Float {
        tmpIn[0] = x; tmpIn[1] = y; tmpIn[2] = z; tmpIn[3] = 1f
        Matrix.multiplyMV(tmpOut, 0, vp, 0, tmpIn, 0)
        val w = tmpOut[3]
        if (w <= 0f) return w
        out[0] = (tmpOut[0] / w * 0.5f + 0.5f) * width
        out[1] = (1f - (tmpOut[1] / w * 0.5f + 0.5f)) * height
        return w
    }
}
