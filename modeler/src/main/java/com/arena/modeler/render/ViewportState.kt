package com.arena.modeler.render

import com.arena.modeler.core.Quat
import com.arena.modeler.scene.AnimationClip
import com.arena.modeler.scene.SceneDocument
import com.arena.modeler.scene.SceneNode

/**
 * Everything the GL thread needs to know, written by the UI. Kept apart from the view so the
 * Compose layer can drive it without knowing anything about OpenGL.
 */
enum class ViewMode(val label: String) {
    SOLID("Sólido"),
    WIREFRAME("Alámbrico"),
    MATERIAL("Material"),
    SHADED("Sin texturas")
}

class ViewportState {

    @Volatile
    var doc: SceneDocument? = null

    @Volatile
    var selectedId: Int = -1

    /** Index of the clip being previewed (-1 = none). */
    @Volatile
    var clipIndex: Int = 0

    @Volatile
    var playing: Boolean = false

    @Volatile
    var animTime: Float = 0f

    @Volatile
    var autoRotate: Boolean = false

    @Volatile
    var showGrid: Boolean = true

    @Volatile
    var showWireframe: Boolean = false

    /** Modo de visualización del visor. */
    @Volatile
    var viewMode: ViewMode = ViewMode.SOLID

    /** Pincel de esculpido activo (radio en metros y signo de la fuerza). */
    @Volatile
    var sculpting: Boolean = false

    @Volatile
    var sculptRadius: Float = 0.25f

    @Volatile
    var sculptStrength: Float = 0.05f

    @Volatile
    var showBones: Boolean = true

    @Volatile
    var showGizmo: Boolean = true

    val camera = OrbitCamera()

    @Volatile
    var background = floatArrayOf(0.07f, 0.08f, 0.10f)

    var onSelect: ((Int) -> Unit)? = null
    var onTransform: ((SceneNode) -> Unit)? = null
    var onFrameNeeded: (() -> Unit)? = null

    fun selectedNode(): SceneNode? {
        val d = doc ?: return null
        if (selectedId < 0) return null
        return d.nodeById(selectedId)
    }

    fun currentClip(): AnimationClip? {
        val d = doc ?: return null
        if (clipIndex < 0 || clipIndex >= d.animations.size) return null
        return d.animations[clipIndex]
    }

    /** Snapshot of the rest pose, so stopping playback leaves the model as it was. */
    private val rest = HashMap<Int, FloatArray>()

    fun captureRest() {
        val d = doc ?: return
        rest.clear()
        for (n in d.allNodes()) {
            rest[n.id] = floatArrayOf(
                n.position.x, n.position.y, n.position.z,
                n.rotation.x, n.rotation.y, n.rotation.z, n.rotation.w,
                n.scale.x, n.scale.y, n.scale.z
            )
        }
    }

    fun restoreRest() {
        val d = doc ?: return
        for (n in d.allNodes()) {
            val r = rest[n.id] ?: continue
            n.position.set(r[0], r[1], r[2])
            n.rotation = Quat(r[3], r[4], r[5], r[6]).normalize()
            n.scale.set(r[7], r[8], r[9])
            n.markDirty()
        }
        animTime = 0f
    }
}
