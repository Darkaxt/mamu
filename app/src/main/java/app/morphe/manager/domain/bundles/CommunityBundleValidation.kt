package app.morphe.manager.domain.bundles

import java.io.File
import java.io.DataInputStream
import java.util.jar.JarFile

/** Validate a first-time catalogue download before creating a saved source. */
internal fun validateCommunityBundle(file: File): String? = JarFile(file).use { jar ->
    val dex = jar.entries().toList().filter { it.name.endsWith(".dex", ignoreCase = true) }
    require(dex.isNotEmpty()) { "Patch bundle has no dex entries" }
    dex.forEach { entry ->
        require(entry.size >= 112) { "Patch bundle has an incomplete dex entry" }
        val magic = ByteArray(8)
        DataInputStream(jar.getInputStream(entry)).use { it.readFully(magic) }
        require(magic.size == 8 && magic.take(4) == listOf(100, 101, 120, 10).map(Int::toByte)
            && magic[7] == 0.toByte()) { "Patch bundle has an invalid dex header" }
    }
    jar.manifest?.mainAttributes?.getValue("Name")?.trim()?.takeIf(String::isNotEmpty)
}
