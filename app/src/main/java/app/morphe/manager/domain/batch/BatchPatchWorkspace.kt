package app.morphe.manager.domain.batch

import app.morphe.manager.patcher.PatchInputUse
import java.io.File
import java.util.UUID

/** Queue-owned imports survive retry; retired queues wait for their actual copy/read consumers. */
internal class BatchPatchWorkspace(private val root: File) {
    private var current = nextDirectory()
    private val consumers = mutableListOf<PatchInputUse>()
    val directory: File get() = synchronized(this) { current }
    private fun nextDirectory() = root.resolve("batch-${UUID.randomUUID()}")
    @Synchronized fun reserveImport(extension: String): Pair<File, PatchInputUse> {
        val safe = extension.takeIf { it in setOf("apk", "apkm", "apks", "xapk") } ?: "apk"
        val use = PatchInputUse().also { it.begin(); consumers.add(it) }
        return current.resolve("import-${UUID.randomUUID()}.$safe") to use
    }
    @Synchronized fun useImport(file: File): PatchInputUse? {
        if (file.parentFile != current) return null
        return PatchInputUse().also { it.begin(); consumers.add(it) }
    }
    @Synchronized fun retire(): suspend () -> Unit {
        val old = current
        val pending = consumers.toList()
        current = nextDirectory()
        consumers.clear()
        return {
            pending.forEach { it.awaitReleased() }
            old.deleteRecursively()
            Unit
        }
    }
}

internal fun BatchPatchItem.retryOriginalFile(): File? = when (val input = source) {
    is BatchApkSource.UserFile -> input.file.takeIf { it.isFile }
    is BatchApkSource.SavedOriginal -> input.file.takeIf { it.isFile }
    else -> null
}
