package com.chaoslab.app.render

/** Speed-to-color gradients for particles: slow → medium → fast. */
enum class Palette(val label: String, val c0: FloatArray, val c1: FloatArray, val c2: FloatArray) {
    PLASMA("Plasma", floatArrayOf(0.32f, 0.14f, 0.92f), floatArrayOf(1.00f, 0.30f, 0.62f), floatArrayOf(1.00f, 0.86f, 0.62f)),
    ICE("Ice", floatArrayOf(0.10f, 0.28f, 0.92f), floatArrayOf(0.20f, 0.80f, 1.00f), floatArrayOf(0.92f, 1.00f, 1.00f)),
    NEBULA("Nebula", floatArrayOf(0.58f, 0.10f, 0.80f), floatArrayOf(0.10f, 0.70f, 0.90f), floatArrayOf(0.80f, 1.00f, 0.62f)),
    SOLAR("Solar", floatArrayOf(0.90f, 0.18f, 0.05f), floatArrayOf(1.00f, 0.58f, 0.10f), floatArrayOf(1.00f, 0.96f, 0.72f));

    companion object {
        fun byIndex(i: Int): Palette = entries.getOrElse(i) { PLASMA }
    }
}
