package com.chaoslab.app.engine

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import kotlin.math.PI
import kotlin.math.atan2

/**
 * Full-screen GL canvas. Translates raw multi-touch into semantic engine commands
 * and queues them onto the GL thread, so the simulation is only ever touched there.
 *
 *  1 finger drag on space  → orbit camera
 *  1 finger drag on body   → move body; release with a flick to throw it
 *  tap space               → spawn an orbiting particle cloud
 *  tap body                → select / inspect
 *  double-tap space        → particle explosion
 *  double-tap body         → delete body
 *  long-press space        → create body (current brush)
 *  pinch                   → zoom
 *  two-finger twist        → rotate view
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ChaosSurfaceView(context: Context, private val engine: ChaosEngine) : GLSurfaceView(context) {

    private var lastX = 0f
    private var lastY = 0f
    private var multi = false
    private var prevAngle = 0f

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val x = e.x; val y = e.y
            queueEvent { engine.onTap(x, y) }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val x = e.x; val y = e.y
            queueEvent { engine.onDoubleTap(x, y) }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (multi) return
            val x = e.x; val y = e.y
            queueEvent { engine.onLongPress(x, y) }
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    })

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val f = detector.scaleFactor
            queueEvent { engine.onPinch(f) }
            return true
        }
    })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        engine.attach(this)
        setRenderer(engine)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private fun angle(ev: MotionEvent): Float =
        atan2(ev.getY(1) - ev.getY(0), ev.getX(1) - ev.getX(0))

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaler.onTouchEvent(ev)
        gestures.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multi = false
                lastX = ev.x; lastY = ev.y
                val x = ev.x; val y = ev.y; val t = ev.eventTime
                queueEvent { engine.onDown(x, y, t) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!multi) {
                    multi = true
                    queueEvent { engine.onCancelSingle() }
                }
                if (ev.pointerCount >= 2) prevAngle = angle(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!multi && ev.pointerCount == 1) {
                    val x = ev.x; val y = ev.y
                    val dx = x - lastX; val dy = y - lastY
                    lastX = x; lastY = y
                    val t = ev.eventTime
                    queueEvent { engine.onMove(x, y, dx, dy, t) }
                } else if (ev.pointerCount >= 2) {
                    val a = angle(ev)
                    var d = a - prevAngle
                    if (d > PI) d -= (2 * PI).toFloat()
                    if (d < -PI) d += (2 * PI).toFloat()
                    prevAngle = a
                    if (d != 0f) queueEvent { engine.onTwist(d) }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Re-anchor the twist angle on the remaining two pointers, if any.
                if (ev.pointerCount > 2) {
                    val remaining = (0 until ev.pointerCount).filter { it != ev.actionIndex }
                    prevAngle = atan2(
                        ev.getY(remaining[1]) - ev.getY(remaining[0]),
                        ev.getX(remaining[1]) - ev.getX(remaining[0]),
                    )
                }
            }
            MotionEvent.ACTION_UP -> {
                val t = ev.eventTime
                if (!multi) queueEvent { engine.onUp(t) } else queueEvent { engine.onGestureEnd() }
                multi = false
            }
            MotionEvent.ACTION_CANCEL -> {
                multi = false
                queueEvent { engine.onCancelSingle(); engine.onGestureEnd() }
            }
        }
        return true
    }
}
