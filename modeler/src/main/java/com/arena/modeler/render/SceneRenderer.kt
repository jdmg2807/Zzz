package com.arena.modeler.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.arena.modeler.scene.SceneNode
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Length and origin of the translate gizmo, shared by the renderer and the touch handler. */
object Gizmo {
    fun length(camera: OrbitCamera): Float = (camera.distance * 0.14f).coerceIn(0.18f, 1.2f)

    /** World-space origin of [node] as `[x, y, z]`. */
    fun origin(node: SceneNode?): FloatArray {
        if (node == null) return floatArrayOf(0f, 0f, 0f)
        val w = node.worldMatrix()
        return floatArrayOf(w[12], w[13], w[14])
    }
}

/**
 * Draws the scene: grid, meshes, wireframe overlay, selection box, bones and gizmo. It runs on the
 * GL thread; the document is only read here, and always under the document's monitor so a change
 * made from the UI thread cannot corrupt a frame.
 */
class SceneRenderer(private val state: ViewportState) : GLSurfaceView.Renderer {

    private val lines = GLLine()
    private val meshes = MeshBatch()

    private var width = 1
    private var height = 1
    private var lastFrameNanos = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        GLES30.glFrontFace(GLES30.GL_CCW)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        // A new context means every GL name we had is gone.
        lines.reset()
        meshes.reset()
        lastFrameNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / 1_000_000_000.0).toFloat()
        lastFrameNanos = now
        val step = dt.coerceIn(0f, 0.1f)

        val doc = state.doc
        val aspect = width.toFloat() / height.toFloat()
        var wantsMore = false

        if (state.autoRotate) {
            state.camera.orbit(26f * step, 0f)
            wantsMore = true
        }

        val clip = state.currentClip()
        if (state.playing && clip != null && doc != null) {
            state.animTime += step
            val duration = clip.duration().coerceAtLeast(0.001f)
            if (state.animTime > duration) state.animTime = 0f
            synchronized(doc) { clip.apply(doc, state.animTime) }
            wantsMore = true
        }

        val bg = state.background
        GLES30.glClearColor(bg[0], bg[1], bg[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        val vp = state.camera.viewProj(aspect)
        val camera = state.camera.eye()

        if (state.showGrid) drawGrid(vp)

        if (doc != null) {
            synchronized(doc) {
                val nodes = doc.allNodes()
                for (n in nodes) {
                    if (!n.visible || n.mesh == null) continue
                    meshes.draw(n, vp, camera, n.id == state.selectedId, false)
                }
                if (state.showWireframe) drawWireframe(doc.allNodes(), vp)
                val selected = state.selectedNode()
                if (selected != null) {
                    drawSelection(selected, vp)
                    if (state.showGizmo) drawGizmo(selected, vp)
                }
                if (state.showBones) drawBones(doc.allNodes(), vp)
            }
        }

        if (wantsMore) state.onFrameNeeded?.invoke()
    }

    // ------------------------------------------------------------------ overlays

    private fun drawGrid(vp: FloatArray) {
        lines.begin()
        val half = 12f
        val step = 0.5f
        val count = (half / step).toInt()
        for (i in -count..count) {
            if (i == 0) continue
            val p = i * step
            val c = if (i % 4 == 0) 0.235f else 0.175f
            lines.line(-half, 0f, p, half, 0f, p, c, c + 0.02f, c + 0.06f, 0.9f)
            lines.line(p, 0f, -half, p, 0f, half, c, c + 0.02f, c + 0.06f, 0.9f)
        }
        // Axes: red for X, blue for Z, a short green stub for Y.
        lines.line(-half, 0f, 0f, half, 0f, 0f, 0.62f, 0.26f, 0.30f, 1f)
        lines.line(0f, 0f, -half, 0f, 0f, half, 0.26f, 0.38f, 0.70f, 1f)
        lines.line(0f, 0f, 0f, 0f, 0.6f, 0f, 0.30f, 0.62f, 0.34f, 0.85f)
        lines.draw(vp)
    }

    private fun drawWireframe(nodes: List<SceneNode>, vp: FloatArray) {
        lines.begin()
        var budget = 40_000
        for (n in nodes) {
            val mesh = n.mesh ?: continue
            if (!n.visible) continue
            val m = n.worldMatrix()
            val p = mesh.positions
            val idx = mesh.indices
            var i = 0
            val maxIndex = p.size / 3
            while (i + 2 < idx.size && budget > 0) {
                val a = idx[i]
                val b = idx[i + 1]
                val c = idx[i + 2]
                if (a < maxIndex && b < maxIndex && c < maxIndex) {
                    val ax = m[0] * p[a * 3] + m[4] * p[a * 3 + 1] + m[8] * p[a * 3 + 2] + m[12]
                    val ay = m[1] * p[a * 3] + m[5] * p[a * 3 + 1] + m[9] * p[a * 3 + 2] + m[13]
                    val az = m[2] * p[a * 3] + m[6] * p[a * 3 + 1] + m[10] * p[a * 3 + 2] + m[14]
                    val bx = m[0] * p[b * 3] + m[4] * p[b * 3 + 1] + m[8] * p[b * 3 + 2] + m[12]
                    val by = m[1] * p[b * 3] + m[5] * p[b * 3 + 1] + m[9] * p[b * 3 + 2] + m[13]
                    val bz = m[2] * p[b * 3] + m[6] * p[b * 3 + 1] + m[10] * p[b * 3 + 2] + m[14]
                    val cx2 = m[0] * p[c * 3] + m[4] * p[c * 3 + 1] + m[8] * p[c * 3 + 2] + m[12]
                    val cy2 = m[1] * p[c * 3] + m[5] * p[c * 3 + 1] + m[9] * p[c * 3 + 2] + m[13]
                    val cz2 = m[2] * p[c * 3] + m[6] * p[c * 3 + 1] + m[10] * p[c * 3 + 2] + m[14]
                    lines.line(ax, ay, az, bx, by, bz, 0.55f, 0.82f, 1f, 0.5f)
                    lines.line(bx, by, bz, cx2, cy2, cz2, 0.55f, 0.82f, 1f, 0.5f)
                    lines.line(cx2, cy2, cz2, ax, ay, az, 0.55f, 0.82f, 1f, 0.5f)
                    budget -= 3
                }
                i += 3
            }
            if (budget <= 0) break
        }
        lines.draw(vp)
    }

    private fun drawSelection(node: SceneNode, vp: FloatArray) {
        val b = node.worldBounds() ?: return
        lines.begin()
        lines.box(b[0], b[1], b[2], b[3], b[4], b[5], 0.35f, 0.72f, 1f, 0.95f)
        val w = node.worldMatrix()
        lines.cross(w[12], w[13], w[14], 0.05f, 0.35f, 0.72f, 1f)
        lines.draw(vp)
    }

    private fun drawGizmo(node: SceneNode, vp: FloatArray) {
        val origin = Gizmo.origin(node)
        val len = Gizmo.length(state.camera)
        val head = len * 0.16f
        lines.begin()
        // X
        lines.line(origin[0], origin[1], origin[2], origin[0] + len, origin[1], origin[2], 0.95f, 0.30f, 0.35f)
        lines.line(origin[0] + len, origin[1], origin[2], origin[0] + len - head, origin[1] + head * 0.5f, origin[2], 0.95f, 0.30f, 0.35f)
        lines.line(origin[0] + len, origin[1], origin[2], origin[0] + len - head, origin[1] - head * 0.5f, origin[2], 0.95f, 0.30f, 0.35f)
        // Y
        lines.line(origin[0], origin[1], origin[2], origin[0], origin[1] + len, origin[2], 0.40f, 0.92f, 0.45f)
        lines.line(origin[0], origin[1] + len, origin[2], origin[0] + head * 0.5f, origin[1] + len - head, origin[2], 0.40f, 0.92f, 0.45f)
        lines.line(origin[0], origin[1] + len, origin[2], origin[0] - head * 0.5f, origin[1] + len - head, origin[2], 0.40f, 0.92f, 0.45f)
        // Z
        lines.line(origin[0], origin[1], origin[2], origin[0], origin[1], origin[2] + len, 0.35f, 0.55f, 1f)
        lines.line(origin[0], origin[1], origin[2] + len, origin[0] + head * 0.5f, origin[1], origin[2] + len - head, 0.35f, 0.55f, 1f)
        lines.line(origin[0], origin[1], origin[2] + len, origin[0] - head * 0.5f, origin[1], origin[2] + len - head, 0.35f, 0.55f, 1f)
        lines.draw(vp)
    }

    private fun drawBones(nodes: List<SceneNode>, vp: FloatArray) {
        var any = false
        lines.begin()
        for (n in nodes) {
            if (n.skin.isEmpty()) continue
            for (joint in n.skin) {
                val jointNode = n.parent?.let { p -> p.children.firstOrNull { it.id == joint.nodeIndex } }
                    ?: nodes.firstOrNull { it.id == joint.nodeIndex }
                    ?: continue
                if (!jointNode.visible) continue
                val w = jointNode.worldMatrix()
                lines.cross(w[12], w[13], w[14], 0.035f, 1f, 0.80f, 0.25f)
                val parent = jointNode.parent
                if (parent != null) {
                    val pw = parent.worldMatrix()
                    lines.line(pw[12], pw[13], pw[14], w[12], w[13], w[14], 0.95f, 0.72f, 0.18f, 0.9f)
                }
                any = true
            }
        }
        if (any) lines.draw(vp) else lines.begin()
    }
}
