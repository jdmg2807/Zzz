package com.arena.voxel.player

import com.arena.voxel.world.Blocks
import com.arena.voxel.world.World
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * First-person player with Minecraft's tick-based physics.
 *
 * All movement runs at a fixed 20 ticks/s accumulator (the game's tick rate); rendering
 * interpolates. Constants are the public ones the community has measured from the game:
 *
 *  - gravity          `vy -= 0.08` then `vy *= 0.98`
 *  - jump             `vy = 0.42` (+ a 0.2 blocks/tick horizontal impulse when sprinting)
 *  - acceleration     `vel += heading * accel`, accel 0.1 (0.13 sprinting) on the ground,
 *                     0.02 in the air, the input normalised by `speed / max(speed, 1)`
 *  - inertia          `*0.546` on ground (0.6 slipperiness × 0.91, ice is 0.98), air `*0.91`
 *  - sneak            input ×0.3 and an edge guard that backs off in 0.05 steps
 *  - water / lava     inertia 0.8 / 0.5, buoyancy −0.02, swim up +0.04, out-of-water 0.3
 *  - ladder           horizontal clamp 0.15, climb 0.2
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
        /** Longest distance the AABB moves before testing collisions again (no tunnelling). */
        const val MAX_SUBSTEP = 0.4
        /**
         * The steady horizontal speeds that fall out of the model, in blocks per tick (20 ticks
         * per second). They describe the result of the physics, not a value the code assigns:
         *
         *   walk   4.317 b/s = 0.21585 b/tick   (accel 0.1, inertia 0.546)
         *   sprint 5.612 b/s = 0.28060 b/tick   (accel 0.13)
         *   sneak  1.295 b/s = 0.06475 b/tick   (input scaled by 0.3)
         *   swim   2.2   b/s = 0.11    b/tick
         *   fly    10.9  b/s = 0.545   b/tick (creative, double-tap jump)
         */
        const val WALK = 0.21585
        const val SPRINT = 0.28060
        const val SNEAK = 0.06475
        const val SWIM = 0.11
        const val FLY = 0.28
        const val FLY_FAST = 0.545
        /** Ladders/vines: horizontal speed clamp and the climb impulse (Java values). */
        const val LADDER_SIDE = 0.15
        const val LADDER_UP = 0.2
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
    /** True when the last tick was stopped by a wall (used by the ladder climb). */
    private var collidedHorizontally = false
    private var unstickTimer = 0
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

    /**
     * Highest block top around the player's feet column.
     *
     * The scan goes from just below the feet up to one step height above them, so the function
     * serves both callers: landing (snap down to the surface) and step-up (a slab or a stair one
     * step high). The old version started at `py`, which made every result equal to `py`: the
     * player could never step up a slab and — worse — never snapped to the ground, so spawning
     * inside a block left it permanently stuck.
     */
    private fun groundHeight(world: World, px: Double, py: Double, pz: Double): Double {
        val hw = WIDTH / 2.0
        val minX = floor(px - hw).toInt()
        val maxX = floor(px + hw).toInt()
        val minZ = floor(pz - hw).toInt()
        val maxZ = floor(pz + hw).toInt()
        val ceiling = py + STEP_HEIGHT
        var top = py
        val y0 = floor(py - 1.2).toInt()
        val y1 = floor(ceiling).toInt()
        for (bx in minX..maxX) {
            for (bz in minZ..maxZ) {
                for (by in y0..y1) {
                    val block = Blocks.get(world.get(bx, by, bz))
                    if (block.shape.isEmpty) continue
                    for (box in block.shape.boxes) {
                        val yTop = by + box.y1.toDouble()
                        if (yTop <= ceiling && yTop > top) top = yTop
                    }
                }
            }
        }
        return top
    }

    /**
     * Pushes the player out of a solid block.
     *
     * Worlds imported from Java (and worldgen with structures) can put the spawn point inside
     * terrain; without this the AABB is embedded, every axis collides and the player cannot move
     * at all, which is exactly the "no puedo moverme" report.
     */
    fun unstick(world: World) {
        if (!collides(world, x, y, z)) return
        var lift = 0.05
        while (lift <= 6.0) {
            if (!collides(world, x, y + lift, z)) {
                y += lift
                vy = 0.0
                return
            }
            lift += 0.05
        }
        // Still embedded: give up on this column and search upwards for free space.
        lift = 0.5
        while (lift <= 12.0) {
            if (!collides(world, x + lift, y + lift, z)) {
                x += lift
                y += lift
                vy = 0.0
                return
            }
            lift += 0.5
        }
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
        // Spawning or teleporting inside terrain used to freeze the AABB forever. Checked a few
        // times per second instead of every frame so the collision scan stays cheap.
        if (unstickTimer <= 0) {
            unstickTimer = 10
            unstick(world)
        } else {
            unstickTimer--
        }
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
        // Java reads the keyboard as ±0.98 and normalises the vector only when it is longer than
        // one; an analog stick keeps its magnitude, which makes slow walking possible.
        var strafe = moveX.toDouble()
        var forward = moveZ.toDouble()
        val inputLen = hypot(strafe, forward)
        if (inputLen > 1.0) {
            strafe /= inputLen
            forward /= inputLen
        }
        val hasInput = inputLen > 0.01
        val norm = kotlin.math.max(inputLen, 1.0)
        val dirX = if (hasInput) strafe / norm else 0.0
        val dirZ = if (hasInput) forward / norm else 0.0
        if (sneak) {
            strafe *= 0.3
            forward *= 0.3
        }
        val sprinting = sprint && forward > 0.0 && !sneak

        collidedHorizontally = false
        // Velocities below 0.003 blocks/tick are noise; the game zeroes them.
        if (abs(vx) < 0.003) vx = 0.0
        if (abs(vy) < 0.003) vy = 0.0
        if (abs(vz) < 0.003) vz = 0.0

        if (flying) {
            // Creative flight: 10.9 blocks/s with the sprint button, like the Java ability.
            val flySpeed = if (sprinting) FLY_FAST else FLY
            vx += (dirX * flySpeed - vx) * 0.55
            vz += (dirZ * flySpeed - vz) * 0.55
            vy = when {
                jump -> FLY
                ascend < -0.1f -> -FLY
                ascend > 0.1f -> FLY
                else -> 0.0
            }
            moveXAxis(world, vx, 0)
            moveYAxis(world, vy)
            moveZAxis(world, vz)
        } else {
            // Jump first, as LivingEntity.jumpFromGround does: 0.42 up and, while sprinting,
            // an extra 0.2 blocks/tick of horizontal impulse in the facing direction.
            if (jump && onGround && !inWater && !inLava) {
                vy = JUMP
                if (sprinting) {
                    vx += dirX * 0.2
                    vz += dirZ * 0.2
                }
                onGround = false
            }
            if (inWater && jump) vy += 0.04

            // Ladders, vines and scaffolding clamp the horizontal speed and allow climbing.
            val ladder = onLadder(world)
            if (ladder) {
                vx = vx.coerceIn(-LADDER_SIDE, LADDER_SIDE)
                vz = vz.coerceIn(-LADDER_SIDE, LADDER_SIDE)
                if (vy < -LADDER_SIDE) vy = -LADDER_SIDE
            }

            // Acceleration and inertia, exactly the values of the game's travel():
            //   ground  0.546 (= 0.6 * 0.91), accel = speedAttribute * 0.16277136 / inertia^3
            //   air     0.91, accel 0.02 (0.026 while sprinting)
            //   water   0.8, accel 0.02; lava 0.5
            var accel: Double
            var inertia: Double
            if (inWater) {
                accel = 0.02
                inertia = 0.8
            } else if (inLava) {
                accel = 0.02
                inertia = 0.5
            } else if (onGround) {
                inertia = friction(world) * 0.91
                val attribute = if (sprinting) 0.13 else 0.1
                accel = attribute * (0.1627714 / (inertia * inertia * inertia))
            } else {
                accel = if (sprinting) 0.026 else 0.02
                inertia = 0.91
            }

            applyHeading(strafe, forward, accel)
            moveXAxis(world, vx, 0)
            moveYAxis(world, vy)
            moveZAxis(world, vz)

            if (inWater || inLava) {
                vy *= inertia
                vy -= 0.02
                vx *= inertia
                vz *= inertia
            } else {
                vy -= GRAVITY
                vy *= DRAG_VERTICAL
                vx *= inertia
                vz *= inertia
            }

            if (ladder && (collidedHorizontally || jump)) vy = LADDER_UP
        }
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

        // Sneaking backs off from edges in 0.05 steps (the game's maybeBackOffFromEdge): the old
        // version zeroed the speed whenever there was no wall ahead, so sneaking could not move.
        if (sneak && onGround && !flying) {
            vx = backOffFromEdge(world, vx, 0.0)
            vz = backOffFromEdge(world, 0.0, vz)
        }

        moveXAxis(world, vx, 0)
        moveYAxis(world, vy)
        moveZAxis(world, vz)

        if (y < -20.0) {
            y = 80.0
            vy = 0.0
        }
    }

    /** Slipperiness of the block under the feet, from the community-measured Java values. */
    private fun friction(world: World): Double {
        val below = world.get(floor(x).toInt(), floor(y - 0.05).toInt(), floor(z).toInt())
        return when (Blocks.get(below).id) {
            "ice", "packed_ice", "frosted_ice" -> 0.98
            "blue_ice" -> 0.989
            "slime_block", "slime" -> 0.8
            else -> 0.6
        }
    }

    /**
     * Java's `applyHeading`: `vel += direction * accel`, where the acceleration is divided by the
     * input length so a diagonal push is not faster than a straight one (`speed / max(speed, 1)`).
     */
    private fun applyHeading(strafe: Double, forward: Double, accel: Double) {
        var speed = hypot(strafe, forward)
        if (speed < 0.01) return
        speed = accel / kotlin.math.max(speed, 1.0)
        val right = strafe * speed
        val ahead = forward * speed
        val rad = Math.toRadians(yaw.toDouble())
        vx += cos(rad) * right - sin(rad) * ahead
        vz += -sin(rad) * right - cos(rad) * ahead
    }

    /** True when the feet overlap a ladder, a vine or scaffolding. */
    private fun onLadder(world: World): Boolean {
        for (dy in 0..1) {
            val id = Blocks.get(world.get(floor(x).toInt(), floor(y + 0.5 + dy).toInt(), floor(z).toInt())).id
            if (id == "ladder" || id == "vine" || id == "scaffolding") return true
        }
        return false
    }

    /**
     * Snaking along an edge while sneaking must not drop the player off it. Java shrinks the
     * movement in 0.05 steps while the new position has no block under the feet.
     */
    private fun backOffFromEdge(world: World, dx: Double, dz: Double): Double {
        var d = if (dx != 0.0) dx else dz
        val alongX = dx != 0.0
        var guard = 0
        while (d != 0.0 && guard++ < 40) {
            val px = if (alongX) x + d else x
            val pz = if (alongX) z else z + d
            if (collides(world, px, y - 0.02, pz)) return d
            d = if (abs(d) <= 0.05) 0.0 else d - Math.signum(d) * 0.05
        }
        return d
    }

    /**
     * Sub-stepped axis moves.
     *
     * A single position test is only safe while the step is shorter than a block. Falling reaches
     * 3.92 blocks/tick (the terminal velocity) and flying 0.545, so every axis is walked in
     * chunks of at most [MAX_SUBSTEP]; otherwise the AABB jumps over a one-block-thick floor and
     * the player falls through the world.
     */
    private fun moveXAxis(world: World, amount: Double, stepUp: Int) {
        if (amount == 0.0) return
        var remaining = amount
        var guard = 0
        while (abs(remaining) > 1e-9 && guard++ < 32) {
            val step = remaining.coerceIn(-MAX_SUBSTEP, MAX_SUBSTEP)
            if (!collides(world, x + step, y, z)) {
                x += step
                remaining -= step
                continue
            }
            // Try stepping up a slab/stair before blocking.
            if (onGround && stepUp == 0) {
                val top = groundHeight(world, x + step, y, z)
                if (top - y in 0.001..STEP_HEIGHT && !collides(world, x + step, top, z)) {
                    y = top
                    x += step
                    return
                }
            }
            vx = 0.0
            collidedHorizontally = true
            return
        }
    }

    private fun moveZAxis(world: World, amount: Double, stepUp: Int = 0) {
        if (amount == 0.0) return
        var remaining = amount
        var guard = 0
        while (abs(remaining) > 1e-9 && guard++ < 32) {
            val step = remaining.coerceIn(-MAX_SUBSTEP, MAX_SUBSTEP)
            if (!collides(world, x, y, z + step)) {
                z += step
                remaining -= step
                continue
            }
            if (onGround && stepUp == 0) {
                val top = groundHeight(world, x, y, z + step)
                if (top - y in 0.001..STEP_HEIGHT && !collides(world, x, top, z + step)) {
                    y = top
                    z += step
                    return
                }
            }
            vz = 0.0
            collidedHorizontally = true
            return
        }
    }

    /**
     * Highest block top under the player's footprint that is at or below [py] (+1 mm).
     *
     * Used to land exactly on the surface instead of leaving the AABB a few floating point units
     * inside the block, which used to make every axis collide and froze the character in place.
     */
    private fun topSurface(world: World, px: Double, py: Double, pz: Double): Double {
        val hw = WIDTH / 2.0
        val minX = floor(px - hw).toInt()
        val maxX = floor(px + hw).toInt()
        val minZ = floor(pz - hw).toInt()
        val maxZ = floor(pz + hw).toInt()
        val ceiling = py + 0.001
        var top = Double.NEGATIVE_INFINITY
        for (bx in minX..maxX) {
            for (bz in minZ..maxZ) {
                for (by in (floor(py).toInt() - 3)..floor(py).toInt()) {
                    val block = Blocks.get(world.get(bx, by, bz))
                    if (block.shape.isEmpty) continue
                    for (box in block.shape.boxes) {
                        val yTop = by + box.y1.toDouble()
                        if (yTop <= ceiling && yTop > top) top = yTop
                    }
                }
            }
        }
        return top
    }

    private fun moveYAxis(world: World, amount: Double) {
        if (amount == 0.0) return
        var remaining = amount
        var guard = 0
        while (abs(remaining) > 1e-9 && guard++ < 32) {
            val step = remaining.coerceIn(-MAX_SUBSTEP, MAX_SUBSTEP)
            if (!collides(world, x, y + step, z)) {
                y += step
                remaining -= step
                onGround = false
                continue
            }
            land(world, step)
            return
        }
    }

    /** Resolves a blocked vertical step: landing on a surface, or stopping against a ceiling. */
    private fun land(world: World, amount: Double) {
        if (amount < 0) {
            // Land exactly on top of the highest box below us.
            val top = topSurface(world, x, y, z)
            if (top == Double.NEGATIVE_INFINITY) {
                // Fully blocked in a way the surface scan cannot explain: stop falling.
                vy = 0.0
                onGround = true
                return
            }
            // Snap up when the AABB was a hair inside the surface (or a block was placed at our
            // feet): landing always leaves the player exactly on top of the block.
            y = top
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

    /**
     * Eating, with the Java numbers: a piece of food restores `nutrition` food points and
     * `nutrition * saturationModifier * 2` saturation, capped at the food level.
     * Returns false when the player is already full (Java refuses to eat then).
     */
    fun eat(nutrition: Int, saturationModifier: Float): Boolean {
        if (food >= 20) return false
        food = (food + nutrition).coerceAtMost(20)
        saturation = (saturation + nutrition * saturationModifier * 2f)
            .coerceAtMost(food.toFloat())
        return true
    }

    /** XP orbs: Java's level curve (7 + 2·level below 16, 9 + 5·level up to 30, 9 + 9·level above). */
    fun addExperience(amount: Int) {
        if (amount <= 0) return
        experience += amount
        var guard = 0
        while (guard++ < 200) {
            val needed = when {
                level < 16 -> 7 + level * 2
                level < 31 -> 9 + level * 5
                else -> 9 + level * 9
            }
            if (experience < needed) break
            experience -= needed
            level++
        }
    }

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
        unstickTimer = 0
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
