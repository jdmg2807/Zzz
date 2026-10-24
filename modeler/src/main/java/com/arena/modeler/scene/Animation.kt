package com.arena.modeler.scene

import com.arena.modeler.core.Quat
import com.arena.modeler.core.V3

/** Property of a node an animation can drive. */
enum class AnimPath { TRANSLATION, ROTATION, SCALE }

/** How the values between two keys are interpolated. */
enum class Interpolation { LINEAR, STEP, CUBIC_SPLINE }

/** One animated property: times and packed values (3 or 4 floats per key). */
class AnimChannel(
    var nodeId: Int,
    var path: AnimPath,
    var times: FloatArray,
    var values: FloatArray,
    var interpolation: Interpolation = Interpolation.LINEAR
) {
    val keyCount: Int get() = times.size
    val stride: Int = when (path) {
        AnimPath.ROTATION -> 4
        else -> 3
    }

    fun sample(t: Float, out: FloatArray) {
        if (times.isEmpty()) return
        if (times.size == 1 || t <= times[0]) {
            System.arraycopy(values, 0, out, 0, stride)
            return
        }
        if (t >= times[times.size - 1]) {
            System.arraycopy(values, (times.size - 1) * stride, out, 0, stride)
            return
        }
        var i = 0
        while (i + 1 < times.size && times[i + 1] < t) i++
        val t0 = times[i]
        val t1 = times[i + 1]
        val span = (t1 - t0).coerceAtLeast(1e-6f)
        val f = if (interpolation == Interpolation.STEP) 0f else ((t - t0) / span).coerceIn(0f, 1f)
        val a = i * stride
        val b = (i + 1) * stride
        if (path == AnimPath.ROTATION) {
            val qa = Quat(values[a], values[a + 1], values[a + 2], values[a + 3])
            val qb = Quat(values[b], values[b + 1], values[b + 2], values[b + 3])
            val q = Quat.slerp(qa, qb, f)
            out[0] = q.x; out[1] = q.y; out[2] = q.z; out[3] = q.w
        } else {
            for (k in 0 until stride) out[k] = values[a + k] + (values[b + k] - values[a + k]) * f
        }
    }

    fun duration(): Float = if (times.isEmpty()) 0f else times[times.size - 1]
}

/** Named animation, the same idea as a glTF animation. */
class AnimationClip(var name: String = "Animación") {
    val channels = ArrayList<AnimChannel>()

    fun duration(): Float = channels.maxOfOrNull { it.duration() } ?: 0f

    fun channelFor(nodeId: Int, path: AnimPath): AnimChannel? =
        channels.firstOrNull { it.nodeId == nodeId && it.path == path }

    /** Adds or replaces one key at [time] for the node's current transform. */
    fun keyNode(node: SceneNode, time: Float, path: AnimPath) {
        val existing = channelFor(node.id, path)
        val values = when (path) {
            AnimPath.TRANSLATION -> floatArrayOf(node.position.x, node.position.y, node.position.z)
            AnimPath.SCALE -> floatArrayOf(node.scale.x, node.scale.y, node.scale.z)
            AnimPath.ROTATION -> floatArrayOf(node.rotation.x, node.rotation.y, node.rotation.z, node.rotation.w)
        }
        if (existing == null) {
            channels.add(AnimChannel(node.id, path, floatArrayOf(time), values))
            return
        }
        // Insert keeping the times sorted, replacing a key on the same frame.
        val idx = existing.times.indexOfFirst { kotlin.math.abs(it - time) < 1e-3f }
        val stride = existing.stride
        if (idx >= 0) {
            for (k in 0 until stride) existing.values[idx * stride + k] = values[k]
            return
        }
        val order = existing.times.indices.sortedBy { existing.times[it] }
        var insertAt = order.size
        for ((pos, key) in order.withIndex()) {
            if (existing.times[key] > time) {
                insertAt = pos
                break
            }
        }
        val newTimes = ArrayList<Float>(existing.times.size + 1)
        val newValues = ArrayList<Float>((existing.times.size + 1) * stride)
        for (pos in 0..order.size) {
            if (pos == insertAt) {
                newTimes.add(time)
                for (k in 0 until stride) newValues.add(values[k])
            }
            if (pos < order.size) {
                val key = order[pos]
                newTimes.add(existing.times[key])
                for (k in 0 until stride) newValues.add(existing.values[key * stride + k])
            }
        }
        existing.times = newTimes.toFloatArray()
        existing.values = newValues.toFloatArray()
    }

    /** Poses every node of [doc] at time [t]; nodes without channels keep their rest pose. */
    fun apply(doc: SceneDocument, t: Float) {
        val buf = FloatArray(4)
        for (ch in channels) {
            val node = doc.nodeById(ch.nodeId) ?: continue
            ch.sample(t, buf)
            when (ch.path) {
                AnimPath.TRANSLATION -> node.position.set(buf[0], buf[1], buf[2])
                AnimPath.SCALE -> node.scale.set(buf[0], buf[1], buf[2])
                AnimPath.ROTATION -> node.rotation = Quat(buf[0], buf[1], buf[2], buf[3]).normalize()
            }
            node.markDirty()
        }
    }

    companion object {
        /**
         * Builds a demo turntable + bob animation for the given node, so "Animar" gives something
         * to see immediately.
         */
        fun demo(doc: SceneDocument, node: SceneNode, seconds: Float = 4f): AnimationClip {
            val clip = AnimationClip("Giro")
            val half = Quat.axisAngle(0f, 1f, 0f, 180f)
            val full = Quat.axisAngle(0f, 1f, 0f, 360f)
            clip.channels.add(
                AnimChannel(
                    node.id, AnimPath.ROTATION,
                    floatArrayOf(0f, seconds * 0.5f, seconds),
                    floatArrayOf(
                        0f, 0f, 0f, 1f,
                        half.x, half.y, half.z, half.w,
                        full.x, full.y, full.z, full.w
                    )
                )
            )
            clip.channels.add(
                AnimChannel(
                    node.id, AnimPath.TRANSLATION,
                    floatArrayOf(0f, seconds * 0.25f, seconds * 0.5f, seconds * 0.75f, seconds),
                    floatArrayOf(
                        node.position.x, node.position.y, node.position.z,
                        node.position.x, node.position.y + 0.2f, node.position.z,
                        node.position.x, node.position.y, node.position.z,
                        node.position.x, node.position.y + 0.2f, node.position.z,
                        node.position.x, node.position.y, node.position.z
                    )
                )
            )
            doc.animations.add(clip)
            return clip
        }

        /** Interprets [path] from a loose string (glTF, AI JSON, FBX). */
        fun parsePath(raw: String): AnimPath? = when (raw.lowercase()) {
            "translation", "position", "location", "translate", "t", "pos" -> AnimPath.TRANSLATION
            "rotation", "rotate", "r", "quaternion" -> AnimPath.ROTATION
            "scale", "s", "scaling" -> AnimPath.SCALE
            else -> null
        }
    }
}
