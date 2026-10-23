package com.arena.voxel.ui

import com.arena.voxel.Game
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The single touch controller of the game, shared by both input paths.
 *
 * Android delivers the game touches to one of two places depending on the device/Compose version:
 *
 *  - [com.arena.voxel.render.GameSurfaceView.onTouchEvent] (the GLSurfaceView child), or
 *  - a Compose pointer-input layer, if the interop view does not take them first.
 *
 * Both call this object, so movement, look, jump, sneak, fly, the hotbar and the HUD buttons work
 * either way. Coordinates are in the same units the caller uses for [BedrockMetrics] (pixels in
 * the view, dp in Compose): every hit test compares ratios, so both are equivalent.
 */
object BedrockInput {

    private var joyPointer = -1L
    private var lookPointer = -1L
    private var joyOriginX = 0f
    private var joyOriginY = 0f
    private var lookLastX = 0f
    private var lookLastY = 0f
    private var lookMoved = false
    /** Distance of the current camera drag, as a fraction of the smaller screen side. */
    private var lookDistance = 0f

    private var jumpPointer = -1L
    private var sneakPointer = -1L
    private var flyUpPointer = -1L
    private var flyDownPointer = -1L

    private var lastJumpTap = 0L
    private var lastForwardCross = 0L
    private var forwardWasHigh = false

    /** Called on every pointer down. Returns true when the press landed on the game world. */
    fun down(metrics: BedrockMetrics, id: Long, x: Float, y: Float, timeMs: Long): Boolean {
        if (metrics.inJump(x, y)) {
            jumpPointer = id
            Game.jump = true
            if (timeMs - lastJumpTap < 320L && Game.creative) {
                Game.player?.let {
                    it.flying = !it.flying
                    it.vy = 0.0
                }
                lastJumpTap = 0L
            } else {
                lastJumpTap = timeMs
            }
            return true
        }
        if (metrics.inSneak(x, y)) {
            sneakPointer = id
            val flying = Game.player?.flying == true
            if (flying) Game.flyDown = true else Game.sneak = true
            return true
        }
        if (metrics.inFlyUp(x, y)) {
            flyUpPointer = id
            Game.flyUp = true
            return true
        }
        if (metrics.inFlyDown(x, y)) {
            flyDownPointer = id
            Game.flyDown = true
            return true
        }
        if (metrics.inFlyToggle(x, y)) {
            Game.player?.let {
                it.flying = !it.flying
                it.vy = 0.0
            }
            return true
        }
        // HUD plates last: in portrait the action buttons overlap the right end of the hotbar.
        if (metrics.inHotbar(x, y)) {
            val slot = metrics.hotbarIndex(x)
            Game.inventory.selected = slot
            Game.selectedSlot = slot
            Game.syncHotbar()
            return true
        }
        if (metrics.inInvButton(x, y)) {
            Game.uiRequest = "inventory"
            return true
        }
        if (metrics.inPause(x, y)) {
            Game.uiRequest = "pause"
            return true
        }
        if (metrics.inChat(x, y)) {
            Game.uiRequest = "chat"
            return true
        }
        if (joyPointer == -1L && metrics.inJoyZone(x, y)) {
            joyPointer = id
            joyOriginX = x
            joyOriginY = y
            Game.joyActive = true
            Game.joyAnchorX = x / metrics.w
            Game.joyAnchorY = y / metrics.h
            Game.joyKnobX = 0f
            Game.joyKnobY = 0f
            return true
        }
        if (lookPointer == -1L) {
            lookPointer = id
            lookLastX = x
            lookLastY = y
            lookMoved = false
            lookDistance = 0f
            Game.pressSeq++
            Game.pressActive = true
            return true
        }
        return false
    }

    fun move(metrics: BedrockMetrics, id: Long, x: Float, y: Float, timeMs: Long) {
        if (id == joyPointer) {
            val r = metrics.joyRadius
            var dx = x - joyOriginX
            var dy = y - joyOriginY
            val len = hypot(dx, dy)
            if (len > r && len > 0.0001f) {
                dx = dx / len * r
                dy = dy / len * r
            }
            Game.move[0] = (dx / r).coerceIn(-1f, 1f)
            Game.move[1] = (-dy / r).coerceIn(-1f, 1f)
            Game.joyKnobX = Game.move[0]
            Game.joyKnobY = -Game.move[1]
            Game.joyActive = true

            // Double tap forward = sprint, the Bedrock gesture.
            val high = Game.move[1] > 0.85f
            if (high && !forwardWasHigh) {
                if (timeMs - lastForwardCross < 400L) Game.sprint = true
                lastForwardCross = timeMs
            }
            forwardWasHigh = high
            if (Game.move[1] < 0.3f) Game.sprint = false
        } else if (id == lookPointer) {
            val dx = x - lookLastX
            val dy = y - lookLastY
            lookLastX = x
            lookLastY = y
            if (abs(dx) > 0.01f || abs(dy) > 0.01f) {
                // A finger always jitters a little; only a real drag counts as "looking around".
                lookDistance += hypot(dx, dy) / kotlin.math.min(metrics.w, metrics.h)
                lookMoved = lookDistance > 0.045f
                Game.addLook(dx, dy)
            }
        }
    }

    fun up(id: Long) {
        if (id == joyPointer) {
            joyPointer = -1L
            Game.move[0] = 0f
            Game.move[1] = 0f
            Game.sprint = false
            Game.joyActive = false
            Game.joyKnobX = 0f
            Game.joyKnobY = 0f
            forwardWasHigh = false
        }
        if (id == lookPointer) {
            lookPointer = -1L
            // The renderer reads this as "tap = place, long hold = mine". Dragging the camera is
            // neither: restarting the press counter throws that gesture away.
            if (lookMoved) Game.pressSeq++
            Game.pressActive = false
            lookMoved = false
        }
        if (id == jumpPointer) {
            jumpPointer = -1L
            Game.jump = false
        }
        if (id == sneakPointer) {
            sneakPointer = -1L
            Game.sneak = false
            Game.flyDown = false
        }
        if (id == flyUpPointer) {
            flyUpPointer = -1L
            Game.flyUp = false
        }
        if (id == flyDownPointer) {
            flyDownPointer = -1L
            Game.flyDown = false
        }
    }

    fun cancel() {
        joyPointer = -1L
        lookPointer = -1L
        jumpPointer = -1L
        sneakPointer = -1L
        flyUpPointer = -1L
        flyDownPointer = -1L
        Game.move[0] = 0f
        Game.move[1] = 0f
        Game.sprint = false
        Game.jump = false
        Game.sneak = false
        Game.flyUp = false
        Game.flyDown = false
        Game.joyActive = false
        Game.pressActive = false
        forwardWasHigh = false
        lookMoved = false
        lookDistance = 0f
    }
}
