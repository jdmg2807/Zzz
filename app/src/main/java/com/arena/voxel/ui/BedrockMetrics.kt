package com.arena.voxel.ui

import kotlin.math.min
import kotlin.math.hypot

/**
 * Bedrock Edition touch layout.
 *
 * All values are *ratios of the screen*, so the same numbers work in three places:
 *
 *  - the GL view, which receives the touches (units = pixels),
 *  - the Compose HUD, which draws the widgets (units = dp).
 *
 * Because both are the same fraction of the screen, a widget drawn by Compose is exactly where
 * the view expects the finger. That is what the old Java-style HUD got wrong: the joystick was a
 * fixed 150 dp box in the corner and the look layer covered the whole screen, so on a phone the
 * thumb landed outside the 150 dp box and nothing moved.
 *
 * Bedrock reference layout (from the touch HUD of the game):
 *
 * ```
 *  (pause) (chat)                                       <- top right
 *                                                   (fly up)
 *  joystick zone            [hotbar 9 slots] [inv]   (jump)
 *                                                      (sneak)
 * ```
 */
class BedrockMetrics(val w: Float, val h: Float) {

    /** Hotbar slot size: big enough for a thumb, never wider than the screen. */
    val slot: Float = min(h * 0.085f, w * 0.078f)

    val hotbarW: Float = slot * 9f
    val hotbarH: Float = slot
    val hotbarLeft: Float = (w - hotbarW) / 2f
    val hotbarBottom: Float = h - slot * 1.15f
    val hotbarTop: Float = hotbarBottom - slot

    /** The "..." inventory button Bedrock puts at the right end of the hotbar. */
    val invSize: Float = slot * 0.86f
    val invLeft: Float = hotbarLeft + hotbarW + slot * 0.18f
    val invTop: Float = hotbarBottom + (slot - invSize) / 2f

    // ---- action buttons (bottom right) ------------------------------------------------
    val action: Float = min(w, h) * 0.165f
    val jumpCx: Float = w - action * 0.82f
    val jumpCy: Float = h - action * 1.62f
    val sneakCx: Float = w - action * 1.95f
    val sneakCy: Float = h - action * 0.85f
    val flyUpCx: Float = w - action * 0.82f
    val flyUpCy: Float = h - action * 2.85f
    val flyDownCx: Float = w - action * 1.95f
    val flyDownCy: Float = h - action * 1.95f
    val flyToggleCx: Float = w - action * 3.05f
    val flyToggleCy: Float = h - action * 0.95f

    // ---- top right --------------------------------------------------------------------
    val small: Float = min(w, h) * 0.085f
    val pauseCx: Float = w - small * 0.95f
    val pauseCy: Float = small * 1.15f
    val chatCx: Float = w - small * 2.15f
    val chatCy: Float = small * 1.15f

    // ---- joystick ---------------------------------------------------------------------
    /** Where the joystick rests when no finger is on it. */
    val joyCx: Float = w * 0.155f
    val joyCy: Float = h * 0.735f
    val joyRadius: Float = min(w, h) * 0.135f
    val knobRadius: Float = joyRadius * 0.42f

    /** The whole lower-left area is a joystick: the finger never has to find a small circle. */
    val joyZoneRight: Float = w * 0.46f
    val joyZoneTop: Float = h * 0.28f

    fun inJoyZone(x: Float, y: Float): Boolean = x < joyZoneRight && y > joyZoneTop

    fun inHotbar(x: Float, y: Float): Boolean =
        x >= hotbarLeft && x <= hotbarLeft + hotbarW && y >= hotbarTop && y <= hotbarBottom + slot * 0.1f

    fun hotbarIndex(x: Float): Int =
        (((x - hotbarLeft) / slot).toInt()).coerceIn(0, 8)

    fun inInvButton(x: Float, y: Float): Boolean =
        x >= invLeft && x <= invLeft + invSize && y >= invTop && y <= invTop + invSize

    fun inPause(x: Float, y: Float): Boolean = circle(x, y, pauseCx, pauseCy, small * 0.72f)

    fun inChat(x: Float, y: Float): Boolean = circle(x, y, chatCx, chatCy, small * 0.72f)

    fun inJump(x: Float, y: Float): Boolean = circle(x, y, jumpCx, jumpCy, action * 0.58f)

    fun inSneak(x: Float, y: Float): Boolean = circle(x, y, sneakCx, sneakCy, action * 0.48f)

    fun inFlyUp(x: Float, y: Float): Boolean = circle(x, y, flyUpCx, flyUpCy, action * 0.48f)

    fun inFlyDown(x: Float, y: Float): Boolean = circle(x, y, flyDownCx, flyDownCy, action * 0.48f)

    fun inFlyToggle(x: Float, y: Float): Boolean = circle(x, y, flyToggleCx, flyToggleCy, action * 0.42f)

    private fun circle(x: Float, y: Float, cx: Float, cy: Float, r: Float): Boolean =
        hypot(x - cx, y - cy) <= r
}

/** What a touch landed on. */
enum class BedrockHit { NONE, LOOK, MOVE, JUMP, SNEAK, FLY_UP, FLY_DOWN, FLY_TOGGLE, PAUSE, CHAT, INVENTORY, HOTBAR }
