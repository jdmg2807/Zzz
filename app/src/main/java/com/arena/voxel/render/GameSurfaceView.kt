package com.arena.voxel.render

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import com.arena.voxel.Game
import com.arena.voxel.ui.BedrockMetrics
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The GLSurfaceView **and** the whole touch controller of the game.
 *
 * Why the input lives in the view instead of in Compose: an `AndroidView` is a real child view of
 * the Compose host, so it receives touches *before* any Compose pointer input in the same area.
 * The old code installed `setOnTouchListener { _, _ -> true }` on this view, which swallowed every
 * event of the game screen — that is the reason the joystick never moved the player.
 *
 * Now the view owns the gameplay touches (joystick, look, action buttons, hotbar) and returns
 * `false` while a Compose panel is open, so the panels themselves stay interactive.
 *
 * Multi-touch model, like Bedrock:
 *
 *  - one pointer drives the joystick (anywhere in the lower-left area),
 *  - any other pointer drags the camera,
 *  - tapping the world places a block, holding it mines (the renderer decides with pressActive),
 *  - double-tapping jump flies in creative, double-tapping forward sprints.
 */
@SuppressLint("ViewConstructor")
class GameSurfaceView(context: Context) : GLSurfaceView(context) {

    val gameRenderer = Renderer()

    private var joyPointer = -1
    private var lookPointer = -1
    private var joyOriginX = 0f
    private var joyOriginY = 0f
    private var lookLastX = 0f
    private var lookLastY = 0f

    private var jumpPointer = -1
    private var sneakPointer = -1
    private var flyUpPointer = -1
    private var flyDownPointer = -1

    private var lastJumpUp = 0L
    private var lastForwardCross = 0L
    private var forwardWasHigh = false

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(gameRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusable = true
        isClickable = false
    }

    private fun metrics(): BedrockMetrics = BedrockMetrics(width.toFloat(), height.toFloat())

    private fun stopMoving() {
        Game.move[0] = 0f
        Game.move[1] = 0f
        Game.sprint = false
        forwardWasHigh = false
    }

    private fun releasePlayerInput() {
        if (joyPointer != -1) {
            joyPointer = -1
            stopMoving()
        }
        if (lookPointer != -1) {
            lookPointer = -1
            if (Game.pressActive) {
                Game.pressActive = false
            }
        }
        if (jumpPointer != -1) {
            jumpPointer = -1
            Game.jump = false
        }
        if (sneakPointer != -1) {
            sneakPointer = -1
            Game.sneak = false
            Game.down = false
        }
        if (flyUpPointer != -1) {
            flyUpPointer = -1
            Game.flyUp = false
        }
        if (flyDownPointer != -1) {
            flyDownPointer = -1
            Game.flyDown = false
        }
    }

    private fun down(index: Int, id: Int, x: Float, y: Float, eventTime: Long): Boolean {
        val m = metrics()

        // Hotbar first: it sits inside the lower half and must not become a look drag.
        if (m.inHotbar(x, y)) {
            val slot = m.hotbarIndex(x)
            Game.inventory.selected = slot
            Game.selectedSlot = slot
            Game.syncHotbar()
            return true
        }
        if (m.inInvButton(x, y)) {
            Game.uiRequest = "inventory"
            return true
        }
        if (m.inPause(x, y)) {
            Game.uiRequest = "pause"
            return true
        }
        if (m.inChat(x, y)) {
            Game.uiRequest = "chat"
            return true
        }
        if (m.inJump(x, y)) {
            jumpPointer = id
            Game.jump = true
            val now = eventTime
            if (now - lastJumpUp < 320L && Game.creative) {
                val p = Game.player
                if (p != null) {
                    p.flying = !p.flying
                    p.vy = 0.0
                }
                lastJumpUp = 0L
            } else {
                lastJumpUp = now
            }
            return true
        }
        if (m.inSneak(x, y)) {
            sneakPointer = id
            val p = Game.player
            if (p != null && (p.flying || Game.creative)) {
                // In Bedrock the sneak button descends while flying.
                Game.flyDown = true
            } else {
                Game.sneak = true
            }
            return true
        }
        if (m.inFlyUp(x, y)) {
            flyUpPointer = id
            Game.flyUp = true
            return true
        }
        if (m.inFlyDown(x, y)) {
            flyDownPointer = id
            Game.flyDown = true
            return true
        }
        if (m.inFlyToggle(x, y)) {
            Game.player?.let {
                it.flying = !it.flying
                it.vy = 0.0
            }
            return true
        }
        if (m.inJoyZone(x, y) && joyPointer == -1) {
            joyPointer = id
            joyOriginX = x
            joyOriginY = y
            return true
        }
        if (lookPointer == -1) {
            lookPointer = id
            lookLastX = x
            lookLastY = y
            Game.pressSeq++
            Game.pressActive = true
            return true
        }
        return true
    }

    private fun move(id: Int, x: Float, y: Float, event: MotionEvent) {
        val m = metrics()
        if (id == joyPointer) {
            val r = m.joyRadius
            var dx = x - joyOriginX
            var dy = y - joyOriginY
            val len = hypot(dx, dy)
            if (len > r && len > 0.0001f) {
                dx = dx / len * r
                dy = dy / len * r
            }
            Game.move[0] = (dx / r).coerceIn(-1f, 1f)
            Game.move[1] = (-dy / r).coerceIn(-1f, 1f)
            // Normalised (0..1 of the screen) so Compose can redraw the stick in dp.
            Game.joyKnobX = Game.move[0]
            Game.joyKnobY = -Game.move[1]
            Game.joyActive = true
            Game.joyAnchorX = joyOriginX / width.toFloat()
            Game.joyAnchorY = joyOriginY / height.toFloat()

            // Double-tap forward = sprint, the Bedrock gesture.
            val high = Game.move[1] > 0.85f
            if (high && !forwardWasHigh) {
                val now = event.eventTime
                if (now - lastForwardCross < 400L) {
                    Game.sprint = true
                }
                lastForwardCross = now
            }
            forwardWasHigh = high
            if (Game.move[1] < 0.3f) Game.sprint = false
        } else if (id == lookPointer) {
            val dx = x - lookLastX
            val dy = y - lookLastY
            lookLastX = x
            lookLastY = y
            if (abs(dx) > 0.01f || abs(dy) > 0.01f) Game.addLook(dx, dy)
        }
    }

    private fun up(id: Int) {
        if (id == joyPointer) {
            joyPointer = -1
            stopMoving()
            Game.joyActive = false
        }
        if (id == lookPointer) {
            lookPointer = -1
            // The renderer turns pressActive = false into a tap (place) or a finished hold (mine).
            Game.pressActive = false
        }
        if (id == jumpPointer) {
            jumpPointer = -1
            Game.jump = false
        }
        if (id == sneakPointer) {
            sneakPointer = -1
            Game.sneak = false
            Game.flyDown = false
        }
        if (id == flyUpPointer) {
            flyUpPointer = -1
            Game.flyUp = false
        }
        if (id == flyDownPointer) {
            flyDownPointer = -1
            Game.flyDown = false
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Panels (inventory, pause, chat) live in Compose: hand the events over to it.
        if (Game.uiBlocking) {
            releasePlayerInput()
            return false
        }
        if (Game.state != Game.State.PLAYING) {
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down(0, event.getPointerId(0), event.x, event.y, event.eventTime)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                down(i, event.getPointerId(i), event.getX(i), event.getY(i), event.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    move(event.getPointerId(i), event.getX(i), event.getY(i), event)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                up(event.getPointerId(i))
            }
            MotionEvent.ACTION_UP -> {
                up(event.getPointerId(0))
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                releasePlayerInput()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
