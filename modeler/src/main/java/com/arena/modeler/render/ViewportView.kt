package com.arena.modeler.render

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import com.arena.modeler.core.Geom
import com.arena.modeler.core.M4
import com.arena.modeler.core.V3
import com.arena.modeler.scene.SceneDocument
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The 3D viewport: a [GLSurfaceView] plus the touch interactions of a modelling app.
 *
 * - One finger orbits, two fingers pan, pinch zooms.
 * - Tap selects the nearest object under the finger.
 * - Dragging one of the coloured arrows of the gizmo moves the selection along that axis.
 */
class ViewportView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    val state = ViewportState()
    private val renderer = SceneRenderer(state)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    var onNodeSelected: ((Int) -> Unit)? = null
    var onNodeTransformed: (() -> Unit)? = null
    /** Punto del mundo donde el usuario apoya el pincel de esculpido. */
    var onSculpt: ((FloatArray) -> Unit)? = null

    private var lastSculptAt = 0L

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                state.camera.zoom(1f / detector.scaleFactor.coerceIn(0.2f, 6f))
                requestRender()
                return true
            }
        }
    )

    private var mode = MODE_NONE
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var downTime = 0L

    private var gizmoAxis = -1
    private var gizmoStartLocal = V3(0f, 0f, 0f)
    private var gizmoOriginScreen = FloatArray(2)
    private var gizmoAxisScreen = FloatArray(2)
    private var gizmoWorldLength = 1f

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        state.onFrameNeeded = { requestRender() }
    }

    /** Attaches a document and frames it; called after opening a project or importing a file. */
    fun setDocument(doc: SceneDocument?, frame: Boolean = true) {
        state.doc = doc
        state.selectedId = -1
        if (frame && doc != null) frameAll()
        requestRender()
    }

    fun frameAll() {
        val doc = state.doc ?: return
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        synchronized(doc) {
            for (n in doc.allNodes()) {
                val b = n.worldBounds() ?: continue
                if (b[0] < minX) minX = b[0]
                if (b[1] < minY) minY = b[1]
                if (b[2] < minZ) minZ = b[2]
                if (b[3] > maxX) maxX = b[3]
                if (b[4] > maxY) maxY = b[4]
                if (b[5] > maxZ) maxZ = b[5]
            }
        }
        if (minX > maxX) {
            state.camera.target.set(0f, 0.9f, 0f)
            state.camera.distance = 4.2f
        } else {
            state.camera.frame(
                floatArrayOf(minX, minY, minZ, maxX, maxY, maxZ),
                height.toFloat() / width.coerceAtLeast(1).toFloat()
            )
        }
        requestRender()
    }

    fun refresh() = requestRender()

    /** Switches to continuous rendering while an animation plays or the turntable spins. */
    fun setAnimated(animated: Boolean) {
        renderMode = if (animated) RENDERMODE_CONTINUOUSLY else RENDERMODE_WHEN_DIRTY
        requestRender()
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (state.sculpting) {
                    sculptAt(event.x, event.y)
                    return true
                }
                lastX = event.x
                lastY = event.y
                downX = event.x
                downY = event.y
                moved = false
                downTime = System.currentTimeMillis()
                mode = if (grabGizmo(event.x, event.y)) MODE_GIZMO else MODE_ORBIT
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    mode = MODE_PAN
                    lastX = (event.getX(0) + event.getX(1)) * 0.5f
                    lastY = (event.getY(0) + event.getY(1)) * 0.5f
                    moved = true
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Back to one finger: restart from the remaining pointer so the view does not jump.
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining)
                lastY = event.getY(remaining)
                mode = MODE_ORBIT
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - downX, event.y - downY) > touchSlop * 2) moved = true
                if (state.sculpting) {
                    val now = System.currentTimeMillis()
                    // Se limita a ~12 pinceladas por segundo para no saturar la malla.
                    if (now - lastSculptAt > 80) {
                        lastSculptAt = now
                        sculptAt(event.x, event.y)
                    }
                    return true
                }
                when {
                    mode == MODE_GIZMO -> dragGizmo(event.x, event.y)
                    mode == MODE_PAN && event.pointerCount >= 2 -> {
                        val mx = (event.getX(0) + event.getX(1)) * 0.5f
                        val my = (event.getY(0) + event.getY(1)) * 0.5f
                        state.camera.pan(mx - lastX, my - lastY, height)
                        lastX = mx
                        lastY = my
                        requestRender()
                    }
                    !scaleDetector.isInProgress -> {
                        state.camera.orbit(event.x - lastX, event.y - lastY)
                        lastX = event.x
                        lastY = event.y
                        requestRender()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && System.currentTimeMillis() - downTime < 350 && mode == MODE_ORBIT) {
                    pick(event.x, event.y)
                }
                mode = MODE_NONE
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                mode = MODE_NONE
                return true
            }
        }
        return true
    }

    // ------------------------------------------------------------------ picking

    private fun pick(x: Float, y: Float) {
        val doc = state.doc ?: return
        val aspect = width.toFloat() / height.coerceAtLeast(1).toFloat()
        val origin = V3()
        val dir = V3()
        state.camera.ray(x / width * 2f - 1f, 1f - y / height * 2f, aspect, origin, dir)

        var bestId = -1
        var bestDistance = Float.MAX_VALUE
        synchronized(doc) {
            for (node in doc.allNodes()) {
                val mesh = node.mesh ?: continue
                if (!node.visible) continue
                val world = node.worldMatrix().copyOf()
                val inv = FloatArray(16)
                if (!M4.invert(inv, world)) continue
                val lo = M4.transformPoint(inv, origin.x, origin.y, origin.z)
                val ld = M4.transformDir(inv, dir.x, dir.y, dir.z)
                val b = mesh.bounds()
                val t = rayBox(
                    lo[0], lo[1], lo[2], ld[0], ld[1], ld[2],
                    b[0], b[1], b[2], b[3], b[4], b[5]
                )
                if (t < 0f) continue
                // Convert the local hit back to world space to rank by real distance.
                val hitWorld = M4.transformPoint(
                    world,
                    lo[0] + ld[0] * t, lo[1] + ld[1] * t, lo[2] + ld[2] * t
                )
                val dx = hitWorld[0] - origin.x
                val dy = hitWorld[1] - origin.y
                val dz = hitWorld[2] - origin.z
                val distance = sqrt(dx * dx + dy * dy + dz * dz)
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestId = node.id
                }
            }
        }
        state.selectedId = bestId
        requestRender()
        onNodeSelected?.invoke(bestId)
    }

    /** Slab test; returns the entry distance along the local ray, or -1 when it misses. */
    private fun rayBox(
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
        minX: Float, minY: Float, minZ: Float,
        maxX: Float, maxY: Float, maxZ: Float
    ): Float {
        var tmin = 0f
        var tmax = Float.MAX_VALUE
        val o = floatArrayOf(ox, oy, oz)
        val d = floatArrayOf(dx, dy, dz)
        val lo = floatArrayOf(minX, minY, minZ)
        val hi = floatArrayOf(maxX, maxY, maxZ)
        for (i in 0 until 3) {
            if (abs(d[i]) < 1e-8f) {
                if (o[i] < lo[i] || o[i] > hi[i]) return -1f
            } else {
                var t1 = (lo[i] - o[i]) / d[i]
                var t2 = (hi[i] - o[i]) / d[i]
                if (t1 > t2) {
                    val tmp = t1
                    t1 = t2
                    t2 = tmp
                }
                if (t1 > tmin) tmin = t1
                if (t2 < tmax) tmax = t2
                if (tmin > tmax) return -1f
            }
        }
        return tmin
    }

    // ------------------------------------------------------------------ esculpido

    /**
     * Lanza un rayo desde el dedo y aplica el pincel en el primer triángulo del objeto
     * seleccionado. El test se hace en el espacio local de la malla (con la inversa de la matriz
     * del nodo) y el punto se devuelve en coordenadas del mundo.
     */
    private fun sculptAt(x: Float, y: Float) {
        val node = state.selectedNode() ?: return
        val mesh = node.mesh ?: return
        val aspect = width.toFloat() / height.coerceAtLeast(1).toFloat()
        val origin = V3()
        val dir = V3()
        state.camera.ray(x / width * 2f - 1f, 1f - y / height * 2f, aspect, origin, dir)
        val world = node.worldMatrix()
        val inv = FloatArray(16)
        if (!M4.invert(inv, world)) return
        val lo = M4.transformPoint(inv, origin.x, origin.y, origin.z)
        val ld = M4.transformDir(inv, dir.x, dir.y, dir.z)
        val len = sqrt(ld[0] * ld[0] + ld[1] * ld[1] + ld[2] * ld[2])
        if (len < 1e-8f) return
        val dx = ld[0] / len
        val dy = ld[1] / len
        val dz = ld[2] / len
        val positions = mesh.positions
        val indices = mesh.indices
        var best = Float.MAX_VALUE
        var i = 0
        while (i + 2 < indices.size) {
            val a = indices[i] * 3
            val b = indices[i + 1] * 3
            val c = indices[i + 2] * 3
            val t = Geom.rayTriangle(
                lo[0], lo[1], lo[2], dx, dy, dz,
                positions[a], positions[a + 1], positions[a + 2],
                positions[b], positions[b + 1], positions[b + 2],
                positions[c], positions[c + 1], positions[c + 2]
            )
            if (t >= 0f && t < best) best = t
            i += 3
        }
        if (best == Float.MAX_VALUE) return
        val local = floatArrayOf(lo[0] + dx * best, lo[1] + dy * best, lo[2] + dz * best)
        val hit = M4.transformPoint(world, local[0], local[1], local[2])
        onSculpt?.invoke(hit)
    }

    // ------------------------------------------------------------------ gizmo

    private fun grabGizmo(x: Float, y: Float): Boolean {
        gizmoAxis = -1
        val node = state.selectedNode() ?: return false
        if (!state.showGizmo || node.mesh == null) return false
        val origin = Gizmo.origin(node)
        val len = Gizmo.length(state.camera)
        val screenOrigin = project(origin[0], origin[1], origin[2]) ?: return false
        val threshold = (touchSlop * 2.2f).coerceAtLeast(26f)
        for (axis in 0 until 3) {
            val ex = origin[0] + if (axis == 0) len else 0f
            val ey = origin[1] + if (axis == 1) len else 0f
            val ez = origin[2] + if (axis == 2) len else 0f
            val screenEnd = project(ex, ey, ez) ?: continue
            val dx = screenEnd[0] - screenOrigin[0]
            val dy = screenEnd[1] - screenOrigin[1]
            val lengthPx = hypot(dx, dy)
            if (lengthPx < 8f) continue
            if (distanceToSegment(x, y, screenOrigin[0], screenOrigin[1], screenEnd[0], screenEnd[1]) < threshold) {
                gizmoAxis = axis
                gizmoStartLocal = node.position.copy()
                gizmoOriginScreen = screenOrigin
                gizmoAxisScreen = floatArrayOf(dx, dy)
                gizmoWorldLength = len
                return true
            }
        }
        return false
    }

    private fun dragGizmo(x: Float, y: Float) {
        if (gizmoAxis < 0) return
        val node = state.selectedNode() ?: return
        val ax = gizmoAxisScreen[0]
        val ay = gizmoAxisScreen[1]
        val lengthPx = hypot(ax, ay).coerceAtLeast(1f)
        // Pixels the finger moved along the axis, converted to world units.
        val along = ((x - downX) * ax + (y - downY) * ay) / lengthPx
        val worldDelta = along / lengthPx * gizmoWorldLength
        val axisWorld = when (gizmoAxis) {
            0 -> V3(1f, 0f, 0f)
            1 -> V3(0f, 1f, 0f)
            else -> V3(0f, 0f, 1f)
        }
        val parent = node.parent
        val dirLocal = if (parent == null) axisWorld else {
            val inv = FloatArray(16)
            if (M4.invert(inv, parent.worldMatrix())) {
                val d = M4.transformDir(inv, axisWorld.x, axisWorld.y, axisWorld.z)
                V3(d[0], d[1], d[2])
            } else axisWorld
        }
        node.position.set(
            gizmoStartLocal.x + dirLocal.x * worldDelta,
            gizmoStartLocal.y + dirLocal.y * worldDelta,
            gizmoStartLocal.z + dirLocal.z * worldDelta
        )
        node.markDirty()
        requestRender()
        onNodeTransformed?.invoke()
    }

    /** World point to screen pixels, or null when behind the camera. */
    private fun project(x: Float, y: Float, z: Float): FloatArray? {
        val aspect = width.toFloat() / height.coerceAtLeast(1).toFloat()
        val vp = state.camera.viewProj(aspect)
        val cx = vp[0] * x + vp[4] * y + vp[8] * z + vp[12]
        val cy = vp[1] * x + vp[5] * y + vp[9] * z + vp[13]
        val cw = vp[3] * x + vp[7] * y + vp[11] * z + vp[15]
        if (cw <= 0.0001f) return null
        val sx = (cx / cw * 0.5f + 0.5f) * width
        val sy = (0.5f - cy / cw * 0.5f) * height
        return floatArrayOf(sx, sy)
    }

    private fun distanceToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-4f) return hypot(px - x1, py - y1)
        val t = (((px - x1) * dx + (py - y1) * dy) / lenSq).coerceIn(0f, 1f)
        return hypot(px - (x1 + dx * t), py - (y1 + dy * t))
    }

    private companion object {
        const val MODE_NONE = 0
        const val MODE_ORBIT = 1
        const val MODE_PAN = 2
        const val MODE_GIZMO = 3
    }
}
