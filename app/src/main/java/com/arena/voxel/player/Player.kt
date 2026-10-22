package com.arena.voxel.player

import com.arena.voxel.world.Blocks
import com.arena.voxel.world.World
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * First-person player with Minecraft's tick-based physics.
 *
 * All movement runs at a fixed 20 ticks/s accumulator (the game's tick rate); rendering
 * interpolates. Constants are the public ones the community has measured from the game:
 *
 *  - gravity          `vy -= 0.08` then `vy *= 0.98`
 *  - jump             `vy = 0.42` (+ 0.2 * forward speed when sprinting)
 *  - walk / sprint    4.317 / 5.612 blocks per second  (0.1 / 0.13 blocks per tick)
 *  - sneak            1.295 blocks per second
 *  - friction         `*0.546` on ground (~0.6 on ice), `*0.91` in the air
 *  - water            buoyancy 0.02, drag `*0.8`, swim up 0.04
 *  - step height      0.6 (so a slab or a stair is walked up without jumping)
 *  - AABB             0.6 x 1.8 (eyes at 1.62, 1.27 when sneaking)
 */
class Player(var x: Double = 0.5, var y: Double = 70.0, var z: Double = 0.5) {

    companion object {
        const val WIDTH = 0.6
        const val HEIGHT = 1.8
        const val EYE = 1.62
        const val EYE_SNEAK = 1.27
        const val TICK = 0.05f
        const val GRAVITY = 0.08
        const val JUMP = 0.42
        const val DRAG_VERTICAL = 0.98
        const val DRAG_AIR = 0.91
        const val DRAG_GROUND = 0.546
        const val DRAG_ICE = 0.98
        const val STEP_HEIGHT = 0.6
        const val WALK = 0.1
        const val SPRINT = 0.13
        const val SNEAK = 0.03
        const val SWIM = 0.02
    }

    var yaw = 0f
    var pitch = 0f
    var vy = 0.0
    var vx = 0.0
    var vz = 0.0
    var flying = false
    var onGround = false
    var inWater = false

    /** True when the feet are inside lava (damage + swim rules differ from water). */
    var inLava = false
    var sneaking = false
    var hudText = ""

    /** Health / hunger / air, in the Java units (20 half-hearts, 20 food points, 300 air ticks). */
    var health = 20f
    var maxHealth = 20f
    var food = 20
    var saturation = 5f
    var air = 300
    var experience = 0f
    var level = 0

    private var accumulator = 0f
    private var jumpCooldown = 0
    private var tickCounter = 0

    /** Blocks fallen since leaving the ground; damage starts at 3 (Java's rule). */
    var fallDistance = 0.0
        private set
    @Volatile var lastDamage = 0f
        private set

    /** Hunger drain timer (Java: exhaustion -> saturation -> food). */
    private var exhaustion = 0f

    fun eyeY(): Double = y + if (sneaking) EYE_SNEAK else EYE

    fun forward(): FloatArray {
        val cp = cos(Math.toRadians(pitch.toDouble())).toFloat()
        val sp = sin(Math.toRadians(pitch.toDouble())).toFloat()
        val cy = cos(Math.toRadians(yaw.toDouble())).toFloat()
        val sy = sin(Math.toRadians(yaw.toDouble())).toFloat()
        return floatArrayOf(-sy * cp, sp, -cy * cp)
    }

    // ------------------------------------------------------------------ collision

    private val boxes = ArrayList<BlockShapeBox>()

    private class BlockShapeBox(val x0: Double, val y0: Double, val z0: Double, val x1: Double, val y1: Double, val z1: Double)

    /** Every solid box the player AABB at (px,py,pz) would overlap. */
    private fun collectBoxes(world: World, px: Double, py: Double, pz: Double) {
        boxes.clear()
        val hw = WIDTH / 2.0
        val minX = floor(px - hw).toInt()
        val maxX = floor(px + hw).toInt()
        val minY = floor(py).toInt()
        val maxY = floor(py + HEIGHT).toInt()
        val minZ = floor(pz - hw).toInt()
        val maxZ = floor(pz + hw).toInt()
        for (bx in minX..maxX) {
            for (by in minY..maxY) {
                for (bz in minZ..maxZ) {
                    val block = Blocks.get(world.get(bx, by, bz))
                    val shape = block.shape
                    if (shape.isEmpty) continue
                    for (box in shape.boxes) {
                        boxes.add(
                            BlockShapeBox(
                                bx + box.x0.toDouble(), by + box.y0.toDouble(), bz + box.z0.toDouble(),
                                bx + box.x1.toDouble(), by + box.y1.toDouble(), bz + box.z1.toDouble()
                            )
                        )
                    }
                }
            }
        }
    }

    private fun collides(world: World, px: Double, py: Double, pz: Double): Boolean {
        collectBoxes(world, px, py, pz)
        val hw = WIDTH / 2.0
        val ax0 = px - hw
        val ax1 = px + hw
        val ay0 = py
        val ay1 = py + HEIGHT
        val az0 = pz - hw
        val az1 = pz + hw
        for (b in boxes) {
            if (ax0 < b.x1 && ax1 > b.x0 && ay0 < b.y1 && ay1 > b.y0 && az0 < b.z1 && az1 > b.z0) return true
        }
        return false
    }

    /** Highest ground under the player at this position (used for step-up). */
    private fun groundHeight(world: World, px: Double, py: Double, pz: Double): Double {
        val hw = WIDTH / 2.0
        val minX = floor(px - hw).toInt()
        val maxX = floor(px + hw).toInt()
        val minZ = floor(pz - hw).toInt()
        val maxZ = floor(pz + hw).toInt()
        var top = py
        for (bx in minX..maxX) {
            for (bz in minZ..maxZ) {
                val y0 = floor(py - 0.6).toInt()
                for (by in y0..floor(py).toInt()) {
                    val block = Blocks.get(world.get(bx, by, bz))
                    if (block.shape.isEmpty) continue
                    for (box in block.shape.boxes) {
                        val yTop = by + box.y1.toDouble()
                        if (yTop <= py + 0.001 && yTop > top) top = yTop
                    }
                }
            }
        }
        return top
    }

    // ------------------------------------------------------------------ update

    /**
     * @param moveX strafe -1..1 (right positive)
     * @param moveZ forward -1..1
     * @param ascend vertical intent -1..1 (flying / swimming)
     */
    fun update(
        dt: Float,
        world: World,
        moveX: Float,
        moveZ: Float,
        ascend: Float,
        jump: Boolean,
        sprint: Boolean = false,
        sneak: Boolean = false
    ) {
        sneaking = sneak && !flying
        accumulator += dt.coerceIn(0f, 0.25f)
        var guard = 0
        while (accumulator >= TICK && guard < 10) {
            tick(world, moveX, moveZ, ascend, jump, sprint, sneak)
            accumulator -= TICK
            guard++
        }
        if (guard >= 10) accumulator = 0f
        tickEnvironment(world)
    }

    /** Water/air counters, damage from drowning and void, run once per frame. */
    private fun tickEnvironment(world: World) {
        val feetBlock = world.get(floor(x).toInt(), floor(y + 0.1).toInt(), floor(z).toInt())
        val feetId = Blocks.get(feetBlock).id
        inLava = feetId == "lava"
        inWater = Blocks.isLiquid(feetBlock) && !inLava
        // Lava burns: 4 half-hearts every 0.5 s, the vanilla rate.
        if (inLava) {
            val now = System.nanoTime()
            if (now >= lavaNextHit) {
                lavaNextHit = now + 500_000_000L
                lastDamage = 4f
                hurt(4f)
            }
        }
        if (inWater) {
            air = (air - 1).coerceAtLeast(-20)
            if (air <= -20) {
                air = 0
                health = (health - 2f).coerceAtLeast(0f)
            }
        } else if (air < 300) {
            air = (air + 4).coerceAtMost(300)
        }
        if (y < -40) {
            health = 0f
        }
    }

    private fun tick(
        world: World,
        moveX: Float,
        moveZ: Float,
        ascend: Float,
        jump: Boolean,
        sprint: Boolean,
        sneak: Boolean
    ) {
        // ---------------------------------------------------------- input direction
        val rad = Math.toRadians(yaw.toDouble())
        val fx = -sin(rad)
        val fz = -cos(rad)
        val rx = cos(rad)
        val rz = -sin(rad)
        var dx = fx * moveZ + rx * moveX
        var dz = fz * moveZ + rz * moveX
        val len = kotlin.math.hypot(dx, dz)
        val hasInput = len > 0.001
        if (len > 1.0) {
            dx /= len
            dz /= len
        }

        val sprinting = sprint && hasInput && !sneak
        val speed = when {
            flying -> if (sprinting) 0.22 else 0.11
            inWater -> SWIM * 2f
            sneak -> SNEAK
            sprinting -> SPRINT
            else -> WALK
        }

        // Target horizontal velocity, then exponential approach: this reproduces the Java
        // feel (quick but not instant acceleration, long slide on ice).
        val tx = if (hasInput) dx * speed else 0.0
        val tz = if (hasInput) dz * speed else 0.0

        val groundFriction = if (onGround) groundBlockFriction(world) else DRAG_AIR

        if (flying) {
            vx = tx
            vz = tz
            vy = when {
                jump -> 0.4
                ascend < -0.1f -> -0.4
                ascend > 0.1f -> 0.4
                else -> 0.0
            }
        } else if (inWater) {
            vx += (tx - vx) * 0.12
            vz += (tz - vz) * 0.12
            vy -= GRAVITY * 0.35
            if (jump || ascend > 0.1f) vy = SWIM * 2.2
            if (ascend < -0.1f) vy = -SWIM * 1.6
            vy *= 0.8
        } else {
            val accel = if (onGround) 0.42 else 0.08
            vx += (tx - vx) * accel
            vz += (tz - vz) * accel
            if (!onGround) {
                vx *= DRAG_AIR
                vz *= DRAG_AIR
            } else {
                vx *= if (groundFriction > 0.6) DRAG_ICE else DRAG_GROUND
                vz *= if (groundFriction > 0.6) DRAG_ICE else DRAG_GROUND
            }
            // Gravity, exactly the Java constants.
            vy -= GRAVITY
            vy *= DRAG_VERTICAL
            if (jump && onGround && jumpCooldown <= 0) {
                vy = JUMP + if (sprinting) 0.2 * speed else 0.0
                onGround = false
                jumpCooldown = 10
            }
        }
        if (jumpCooldown > 0) jumpCooldown--
        tickCounter++
        if (!onGround && vy < 0 && !inWater) fallDistance += -vy else if (onGround) fallDistance = 0.0

        // Hunger: exhaustion builds up with movement and jumping (Java-like ratios).
        exhaustion += if (sprinting) 0.1f else if (hasInput) 0.05f else 0.01f
        if (exhaustion >= 4f) {
            exhaustion = 0f
            if (saturation > 0f) saturation = (saturation - 1f).coerceAtLeast(0f)
            else if (food > 0) food--
        }
        // Regeneration when food is high enough (Java heals every 4 s at food >= 18).
        if (food >= 18 && health < maxHealth && tickCounter % 80 == 0) heal(1f)

        // Sneaking keeps the player on the edge instead of walking off (Java's edge guard).
        if (sneak && onGround && !flying) {
            if (!collides(world, x + vx, y, z)) vx = 0.0
            if (!collides(world, x, y, z + vz)) vz = 0.0
        }

        moveXAxis(world, vx, 0)
        moveYAxis(world, vy)
        moveZAxis(world, vz)

        if (y < -20.0) {
            y = 80.0
            vy = 0.0
        }
    }

    private fun groundBlockFriction(world: World): Double {
        val below = world.get(floor(x).toInt(), floor(y - 0.05).toInt(), floor(z).toInt())
        val id = Blocks.get(below).id
        return when (id) {
            "ice", "packed_ice" -> 0.98
            else -> 0.6
        }
    }

    private fun moveXAxis(world: World, amount: Double, stepUp: Int) {
        if (amount == 0.0) return
        if (!collides(world, x + amount, y, z)) {
            x += amount
            return
        }
        // Try stepping up a slab/stair before blocking.
        if (onGround && stepUp == 0) {
            val top = groundHeight(world, x + amount, y, z)
            if (top - y in 0.001..STEP_HEIGHT && !collides(world, x + amount, top, z)) {
                y = top
                x += amount
                return
            }
        }
        vx = 0.0
    }

    private fun moveZAxis(world: World, amount: Double, stepUp: Int = 0) {
        if (amount == 0.0) return
        if (!collides(world, x, y, z + amount)) {
            z += amount
            return
        }
        if (onGround && stepUp == 0) {
            val top = groundHeight(world, x, y, z + amount)
            if (top - y in 0.001..STEP_HEIGHT && !collides(world, x, top, z + amount)) {
                y = top
                z += amount
                return
            }
        }
        vz = 0.0
    }

    private fun moveYAxis(world: World, amount: Double) {
        if (amount == 0.0) return
        if (!collides(world, x, y + amount, z)) {
            y += amount
            onGround = false
            return
        }
        if (amount < 0) {
            // Land exactly on top of the highest box below us.
            val top = groundHeight(world, x, y, z)
            if (top < y) y = top
            onGround = true
            // Fall damage: every block above 3 deals one heart of damage.
            if (fallDistance > 3.0 && !inWater) {
                val dmg = (fallDistance - 3.0).toFloat()
                lastDamage = dmg
                hurt(dmg)
            }
            fallDistance = 0.0
        } else {
            onGround = false
        }
        vy = 0.0
    }

    // ------------------------------------------------------------------ damage

    private var lavaNextHit = 0L

    fun hurt(amount: Float): Boolean {
        if (health <= 0f) return false
        health = (health - amount).coerceAtLeast(0f)
        return true
    }

    fun heal(amount: Float) {
        health = (health + amount).coerceAtMost(maxHealth)
    }

    fun isDead(): Boolean = health <= 0f

    fun respawn(world: World, sx: Double, sy: Double, sz: Double) {
        x = sx
        y = sy
        z = sz
        vx = 0.0
        vy = 0.0
        vz = 0.0
        health = maxHealth
        food = 20
        air = 300
        experience = 0f
        level = 0
    }

    /** True when the player is inside a liquid (used by the renderer for the water overlay). */
    fun isUnderwater(world: World): Boolean {
        val eye = floor(eyeY()).toInt()
        return Blocks.isLiquid(world.get(floor(x).toInt(), eye, floor(z).toInt()))
    }

    /** Approximate horizontal speed in blocks per second (for the debug overlay). */
    fun speedPerSecond(): Double = kotlin.math.hypot(vx, vz) * 20.0

    fun isMoving(): Boolean = abs(vx) + abs(vz) > 0.01 || abs(vy) > 0.08
}
