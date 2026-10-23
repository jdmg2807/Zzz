package com.arena.voxel.render

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import com.arena.voxel.Game
import com.arena.voxel.ui.BedrockInput
import com.arena.voxel.ui.BedrockMetrics

/**
 * The GLSurfaceView of the game and one of the two touch entry points.
 *
 * Why this view handles touches itself: an `AndroidView` is a real child view of the Compose host,
 * so it can receive touches *before* any Compose pointer input in the same area. The old code had
 * `setOnTouchListener { _, _ -> true }`, which swallowed every event of the game screen — the
 * joystick never moved the player.
 *
 * Now the gestures are implemented in [BedrockInput] (shared with a Compose fallback layer, in
 * case a device delivers the events there instead) and this view only translates coordinates.
 * While a Compose panel is open the view returns `false`, so the panel becomes interactive.
 */
@SuppressLint("ViewConstructor")
class GameSurfaceView(context: Context) : GLSurfaceView(context) {

    val gameRenderer = Renderer()

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(gameRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusable = true
        isClickable = false
    }

    private fun metrics(): BedrockMetrics = BedrockMetrics(width.toFloat(), height.toFloat())

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Panels (inventory, pause, chat) live in Compose: let it have the events.
        if (Game.uiBlocking) {
            BedrockInput.cancel()
            return false
        }
        if (Game.state != Game.State.PLAYING) return false

        val m = metrics()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handleDown(event.getPointerId(0), event.x, event.y, event.eventTime, m)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                handleDown(event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime, m)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    BedrockInput.move(m, event.getPointerId(i).toLong(), event.getX(i), event.getY(i), event.eventTime)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                BedrockInput.up(event.getPointerId(event.actionIndex).toLong())
            }
            MotionEvent.ACTION_UP -> {
                BedrockInput.up(event.getPointerId(0).toLong())
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                BedrockInput.cancel()
            }
        }
        return true
    }

    /**
     * A press that lands on the HUD (an action button, the hotbar, a panel button) is handled and
     * forgotten; one that lands on the world starts the look/tap stream.
     */
    private fun handleDown(id: Int, x: Float, y: Float, timeMs: Long, m: BedrockMetrics) {
        BedrockInput.down(m, id.toLong(), x, y, timeMs)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
