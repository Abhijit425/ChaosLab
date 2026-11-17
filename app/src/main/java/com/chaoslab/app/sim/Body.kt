package com.chaoslab.app.sim

import kotlin.math.sqrt

enum class BodyKind(val label: String, val code: Int) {
    PLANET("Planet", 0),
    STAR("Star", 1),
    BLACK_HOLE("Black hole", 2);

    /** Ordering used when two bodies merge: the "stronger" kind survives. */
    val rank: Int get() = code
}

/**
 * A massive gravitational body. Bodies attract each other and all particles.
 * Particles are treated as massless tracers (restricted N-body), which is what
 * lets us simulate thousands of them in real time on a tablet CPU.
 */
class Body(
    val id: Int,
    kind: BodyKind,
    mass: Float,
    var x: Float, var y: Float, var z: Float,
    var vx: Float = 0f, var vy: Float = 0f, var vz: Float = 0f,
) {
    var kind: BodyKind = kind
        set(value) { field = value; refreshColor() }
    var mass: Float = mass
        set(value) { field = value; refreshColor() }

    /** True while the user is holding the body with a finger. */
    var pinned = false

    var r = 1f; var g = 1f; var b = 1f
        private set

    val radius: Float get() = radiusFor(kind, mass)

    init { refreshColor() }

    private fun refreshColor() {
        when (kind) {
            BodyKind.PLANET -> {
                val c = PLANET_COLORS[id % PLANET_COLORS.size]
                r = c[0]; g = c[1]; b = c[2]
            }
            BodyKind.STAR -> {
                // Low-mass stars are orange/red, heavy stars blue-white.
                val t = ((mass - 200f) / 2800f).coerceIn(0f, 1f)
                if (t < 0.5f) {
                    val k = t * 2f
                    r = 1f; g = lerp(0.50f, 0.92f, k); b = lerp(0.28f, 0.72f, k)
                } else {
                    val k = (t - 0.5f) * 2f
                    r = lerp(1f, 0.72f, k); g = lerp(0.92f, 0.84f, k); b = lerp(0.72f, 1f, k)
                }
            }
            BodyKind.BLACK_HOLE -> { r = 1f; g = 0.62f; b = 0.25f }
        }
    }

    companion object {
        private val PLANET_COLORS = arrayOf(
            floatArrayOf(0.30f, 0.80f, 0.92f),
            floatArrayOf(0.88f, 0.48f, 0.30f),
            floatArrayOf(0.42f, 0.82f, 0.50f),
            floatArrayOf(0.62f, 0.50f, 0.96f),
            floatArrayOf(0.92f, 0.80f, 0.55f),
            floatArrayOf(0.90f, 0.40f, 0.62f),
        )

        fun radiusFor(kind: BodyKind, mass: Float): Float = when (kind) {
            BodyKind.PLANET -> 1.2f + Math.cbrt(mass.toDouble()).toFloat() * 0.45f
            BodyKind.STAR -> 3.5f + sqrt(mass) * 0.09f
            BodyKind.BLACK_HOLE -> 2.5f + mass / 900f
        }

        fun defaultMass(kind: BodyKind): Float = when (kind) {
            BodyKind.PLANET -> 14f
            BodyKind.STAR -> 900f
            BodyKind.BLACK_HOLE -> 4000f
        }

        fun massRange(kind: BodyKind): ClosedFloatingPointRange<Float> = when (kind) {
            BodyKind.PLANET -> 2f..120f
            BodyKind.STAR -> 150f..3200f
            BodyKind.BLACK_HOLE -> 1000f..14000f
        }

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    }
}
