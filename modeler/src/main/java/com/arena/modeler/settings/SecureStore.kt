package com.arena.modeler.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.arena.modeler.core.Text64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Almacenamiento de secretos (las API keys del usuario) cifrado con el **Android Keystore**:
 * una clave AES-256 no exportable generada en el dispositivo cifra cada valor con AES-GCM.
 *
 * Lo que esto protege y lo que no:
 * - Protege frente a leer el archivo desde una copia de seguridad, un root básico o un `adb pull`.
 * - **No** protege frente a malware con acceso al proceso de la app mientras se usa (nada puede).
 * - Si el Keystore falla (dispositivo sin él, emulador antiguo), se degrada a un archivo en el
 *   directorio privado y se marca [hardwareBacked] = false para que la UI lo advierta.
 */
object SecureStore {

    private const val TAG = "VoxelForge/Secure"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "voxelforge_master"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private var appContext: Context? = null
    private var key: SecretKey? = null
    private var degraded = false

    var hardwareBacked: Boolean = false
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
        if (key != null) return
        key = try {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val existing = store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
            existing?.secretKey ?: generateKey()
        } catch (e: Exception) {
            Log.w(TAG, "Keystore no disponible, se usa almacenamiento local: ${e.message}")
            degraded = true
            hardwareBacked = false
            null
        }
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // No se pide autenticación biométrica: la app debe funcionar con la pantalla bloqueada.
                .setUserAuthenticationRequired(false)
                .build()
        )
        val generated = generator.generateKey()
        hardwareBacked = true
        return generated
    }

    private fun fileFor(name: String): File {
        val ctx = appContext ?: throw IllegalStateException("SecureStore no se inicializó")
        return File(ctx.filesDir, "sec_${name.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.bin")
    }

    fun put(name: String, value: String) {
        val ctx = appContext ?: return
        if (value.isEmpty()) {
            remove(name)
            return
        }
        val file = fileFor(name)
        val k = key
        if (k == null || degraded) {
            // Sin Keystore: se guarda en el directorio privado (el aviso lo da la UI).
            runCatching {
                file.writeText("PLAIN:" + Text64.encode(value.toByteArray(Charsets.UTF_8)))
            }
            return
        }
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, k)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
            file.writeText("GCM:" + Text64.encode(combined))
            // Verificación de escritura: no se da por guardado algo que no se puede leer.
            if (get(name) != value) throw IllegalStateException("la verificación falló")
        }.onFailure {
            Log.e(TAG, "No se pudo cifrar «$name»: ${it.message}")
            runCatching { file.delete() }
        }
        ctx.let { Unit }
    }

    fun get(name: String): String? {
        val file = runCatching { fileFor(name) }.getOrNull() ?: return null
        if (!file.exists()) return null
        val raw = runCatching { file.readText() }.getOrNull() ?: return null
        return when {
            raw.startsWith("GCM:") -> {
                val k = key ?: return null
                runCatching {
                    val combined = Text64.decode(raw.removePrefix("GCM:"))
                    if (combined.size <= IV_BYTES) return null
                    val iv = combined.copyOfRange(0, IV_BYTES)
                    val data = combined.copyOfRange(IV_BYTES, combined.size)
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
                    String(cipher.doFinal(data), Charsets.UTF_8)
                }.getOrNull()
            }
            raw.startsWith("PLAIN:") -> runCatching {
                String(Text64.decode(raw.removePrefix("PLAIN:")), Charsets.UTF_8)
            }.getOrNull()
            else -> null
        }
    }

    fun remove(name: String) {
        runCatching { fileFor(name).delete() }
    }

    fun has(name: String): Boolean = get(name)?.isNotEmpty() == true

    /** Solo para las pruebas: borra la clave maestra (en producción no se llama). */
    internal fun resetForTests() {
        key = null
        degraded = false
        hardwareBacked = false
        runCatching {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            store.deleteEntry(ALIAS)
        }
    }
}
