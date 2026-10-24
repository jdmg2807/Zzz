package com.arena.modeler.core

import java.io.File

/**
 * Acceso a archivos desacoplado de Android: el almacenamiento real lo proporciona la app (que sí
 * conoce el `Context`), pero el catálogo, el chat y los ajustes solo piden "un archivo con este
 * nombre". Así el módulo de datos se puede probar en la JVM con un directorio temporal.
 */
interface ObjectStoreFiles {
    fun dir(): File
    fun file(name: String): File = File(dir(), name).also { it.parentFile?.mkdirs() }
}
