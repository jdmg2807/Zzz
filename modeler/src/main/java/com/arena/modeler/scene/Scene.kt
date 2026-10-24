package com.arena.modeler.scene

import com.arena.modeler.core.M4
import com.arena.modeler.core.Quat
import com.arena.modeler.core.V3
import kotlin.math.max
import kotlin.math.min

/**
 * Triangle mesh in the format the app works with: separated arrays, triangles only, normals and
 * (optional) one texture coordinate set. Everything that enters or leaves the app is converted to
 * this, which is what makes the format support possible.
 */
class MeshData(
    var positions: FloatArray,
    var indices: IntArray,
    var normals: FloatArray? = null,
    var uvs: FloatArray? = null,
    /** Per-vertex joint indices (4 per vertex) and weights, for skinned meshes. */
    var jointIndices: ShortArray? = null,
    var jointWeights: FloatArray? = null
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3

    fun copy(): MeshData = MeshData(
        positions.copyOf(), indices.copyOf(),
        normals?.copyOf(), uvs?.copyOf(),
        jointIndices?.copyOf(), jointWeights?.copyOf()
    )

    fun recomputeNormals() {
        val n = FloatArray(positions.size)
        var i = 0
        while (i + 2 < indices.size) {
            val a = indices[i] * 3
            val b = indices[i + 1] * 3
            val c = indices[i + 2] * 3
            val e1x = positions[b] - positions[a]
            val e1y = positions[b + 1] - positions[a + 1]
            val e1z = positions[b + 2] - positions[a + 2]
            val e2x = positions[c] - positions[a]
            val e2y = positions[c + 1] - positions[a + 1]
            val e2z = positions[c + 2] - positions[a + 2]
            val nx = e1y * e2z - e1z * e2y
            val ny = e1z * e2x - e1x * e2z
            val nz = e1x * e2y - e1y * e2x
            for (k in 0 until 3) {
                val idx = indices[i + k] * 3
                n[idx] += nx
                n[idx + 1] += ny
                n[idx + 2] += nz
            }
            i += 3
        }
        for (v in 0 until n.size / 3) {
            val x = n[v * 3]
            val y = n[v * 3 + 1]
            val z = n[v * 3 + 2]
            val len = kotlin.math.sqrt(x * x + y * y + z * z)
            if (len > 1e-8f) {
                n[v * 3] = x / len
                n[v * 3 + 1] = y / len
                n[v * 3 + 2] = z / len
            } else {
                n[v * 3 + 1] = 1f
            }
        }
        normals = n
    }

    /** Axis-aligned bounds in local space: [minX,minY,minZ, maxX,maxY,maxZ]. */
    fun bounds(): FloatArray {
        if (positions.isEmpty()) return floatArrayOf(-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f)
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        var i = 0
        while (i + 2 < positions.size) {
            val x = positions[i]
            val y = positions[i + 1]
            val z = positions[i + 2]
            minX = min(minX, x); maxX = max(maxX, x)
            minY = min(minY, y); maxY = max(maxY, y)
            minZ = min(minZ, z); maxZ = max(maxZ, z)
            i += 3
        }
        return floatArrayOf(minX, minY, minZ, maxX, maxY, maxZ)
    }

    /** A copy translated/scaled to sit on the origin with unit size (used for AI output). */
    fun normalizedToUnit(): MeshData {
        val b = bounds()
        val cx = (b[0] + b[3]) * 0.5f
        val cy = (b[1] + b[4]) * 0.5f
        val cz = (b[2] + b[5]) * 0.5f
        val size = max(b[3] - b[0], max(b[4] - b[1], b[5] - b[2]))
        val s = if (size > 1e-6f) 1f / size else 1f
        val p = positions.copyOf()
        var i = 0
        while (i + 2 < p.size) {
            p[i] = (p[i] - cx) * s
            p[i + 1] = (p[i + 1] - cy) * s
            p[i + 2] = (p[i + 2] - cz) * s
            i += 3
        }
        return MeshData(p, indices.copyOf(), normals?.copyOf(), uvs?.copyOf(), jointIndices, jointWeights)
    }
}

/** Physically-based material, the same fields glTF carries. */
class Material(
    var name: String = "Material",
    var baseColor: FloatArray = floatArrayOf(0.78f, 0.80f, 0.86f, 1f),
    var metallic: Float = 0.0f,
    var roughness: Float = 0.55f,
    var emission: FloatArray = floatArrayOf(0f, 0f, 0f),
    /** Name of a texture file kept in the project folder (null = flat colour). */
    var textureFile: String? = null,
    var doubleSided: Boolean = false
) {
    fun copy(): Material =
        Material(name, baseColor.copyOf(), metallic, roughness, emission.copyOf(), textureFile, doubleSided)
}

/** One joint of a skin: a node index plus the inverse bind matrix. */
class Joint(var nodeIndex: Int, var inverseBind: FloatArray = M4.identity())

/**
 * Node of the scene graph: a transform plus optional mesh/material, optional bones and optional
 * camera or light. Animations target nodes by [id].
 */
class SceneNode(
    var name: String,
    var id: Int
) {
    var parent: SceneNode? = null
    val children = ArrayList<SceneNode>()

    /** Animated transform (also the rest pose). */
    var position = V3(0f, 0f, 0f)
    var rotation = Quat()
    var scale = V3(1f, 1f, 1f)

    var mesh: MeshData? = null
        set(value) {
            field = value
            editableCache = null
        }
    var material: Material? = null

    /**
     * Versión editable (vértices + caras de n lados) de [mesh], para el motor de geometría.
     * Se construye la primera vez que se pide y se invalida al cambiar la malla.
     */
    private var editableCache: com.arena.modeler.geom.EditableMesh? = null

    fun editableMesh(): com.arena.modeler.geom.EditableMesh? {
        val current = mesh ?: return null
        editableCache?.let { return it }
        if (current.indices.isEmpty()) return null
        val built = com.arena.modeler.geom.EditableMesh.fromMeshData(current)
        editableCache = built
        return built
    }

    /** Reemplaza la malla por una editable, regenerando posiciones/normales/UV para el visor. */
    fun setEditableMesh(newMesh: com.arena.modeler.geom.EditableMesh, smooth: Boolean = false) {
        editableCache = newMesh
        mesh = if (newMesh.faces.isEmpty()) null else newMesh.toMeshData(smooth = smooth)
        markDirty()
    }

    /** Joints of the skin used by this mesh (bone space matrices). */
    var skin: List<Joint> = emptyList()

    var visible = true
    var isLight = false
    var lightColor = FloatArray(3) { 1f }
    var lightIntensity = 1f

    private val local = FloatArray(16)
    private val world = FloatArray(16)
    private var worldDirty = true

    fun markDirty() {
        if (!worldDirty) {
            worldDirty = true
            for (c in children) c.markDirty()
        }
    }

    fun localMatrix(): FloatArray {
        M4.compose(local, position, rotation, scale)
        return local
    }

    fun worldMatrix(): FloatArray {
        if (worldDirty) {
            val l = localMatrix()
            val p = parent
            if (p == null) {
                System.arraycopy(l, 0, world, 0, 16)
            } else {
                M4.multiply(world, p.worldMatrix(), l)
            }
            worldDirty = false
        }
        return world
    }

    fun add(child: SceneNode): SceneNode {
        child.parent = this
        children.add(child)
        child.markDirty()
        return child
    }

    fun removeFromParent() {
        parent?.children?.remove(this)
        parent = null
        markDirty()
    }

    /** Depth-first walk. */
    fun forEach(visitor: (SceneNode) -> Unit) {
        visitor(this)
        for (c in children) c.forEach(visitor)
    }

    /** World-space AABB of this node's mesh, or null when it has no mesh. */
    fun worldBounds(): FloatArray? {
        val m = mesh ?: return null
        val b = m.bounds()
        val w = worldMatrix()
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (i in 0 until 8) {
            val x = if (i and 1 == 0) b[0] else b[3]
            val y = if (i and 2 == 0) b[1] else b[4]
            val z = if (i and 4 == 0) b[2] else b[5]
            val p = M4.transformPoint(w, x, y, z)
            minX = min(minX, p[0]); maxX = max(maxX, p[0])
            minY = min(minY, p[1]); maxY = max(maxY, p[1])
            minZ = min(minZ, p[2]); maxZ = max(maxZ, p[2])
        }
        return floatArrayOf(minX, minY, minZ, maxX, maxY, maxZ)
    }
}

/** A whole document: nodes, materials and animation clips. */
class SceneDocument(var name: String = "Sin título") {
    val root = SceneNode("Scene", 0)
    val materials = ArrayList<Material>()
    val animations = ArrayList<AnimationClip>()

    private var nextId = 1

    fun newNode(name: String): SceneNode = SceneNode(name, nextId++).also { it.parent = root; root.children.add(it) }

    fun nodeById(id: Int): SceneNode? {
        var found: SceneNode? = null
        root.forEach { if (it.id == id) found = it }
        return found
    }

    fun allNodes(): List<SceneNode> {
        val out = ArrayList<SceneNode>()
        root.forEach { if (it !== root) out.add(it) }
        return out
    }

    fun nextNodeName(prefix: String): String {
        var i = 1
        val used = allNodes().map { it.name }.toHashSet()
        while (used.contains("$prefix.$i")) i++
        return "$prefix.$i"
    }

    fun materialFor(color: FloatArray? = null): Material {
        val m = Material("Material.${materials.size + 1}", baseColor = (color ?: floatArrayOf(0.78f, 0.8f, 0.86f, 1f)).copyOf())
        materials.add(m)
        return m
    }

    fun totalVertices(): Int = allNodes().sumOf { it.mesh?.vertexCount ?: 0 }
    fun totalTriangles(): Int = allNodes().sumOf { it.mesh?.triangleCount ?: 0 }
}
