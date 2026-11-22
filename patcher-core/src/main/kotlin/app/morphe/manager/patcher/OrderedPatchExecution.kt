/*
 * Copyright 2026 Morphe and Morphe Extended contributors.
 * Ordered integration of the pinned GPL Morphe patcher2.4.0 execution contract:
 * https://github.com/MorpheApp/morphe-patcher/blob/v2.4.0/src/main/kotlin/app/morphe/patcher/Patcher.kt
 * Upstream's public invoke sorts by name. Keep its initialization, dependency
 * execution and reverse finalization while honoring the manager's pack order.
 */
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "DEPRECATION", "DEPRECATION_ERROR")
package app.morphe.manager.patcher

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.dex.BytecodeMode
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.resource.ResourceMode
import java.util.IdentityHashMap

/** Only this version-pinned boundary accesses upstream internal execution APIs. */
internal suspend fun Patcher.executeInPackOrder(config: PatcherConfig, roots: List<Patch>,
    executing: (Patch) -> Unit, succeeded: suspend (Patch) -> Unit,
    rejected: suspend (Patch, Exception) -> Unit) {
    config.initializeTemporaryFilesDirectories()
    if (config.resourceMode != ResourceMode.NONE) {
        context.resourceContext.decodeResources(config.resourceMode)
        context.resourceContext.finishDecoding()
    } else context.resourceContext.decodeOnFirstUse = true
    if (config.bytecodeMode != BytecodeMode.NONE) context.bytecodeContext.decodeDexFiles()
    else context.bytecodeContext.decodeOnFirstUse = true

    val executed = IdentityHashMap<Patch, Boolean>()
    val finalizers = mutableListOf<Patch>()
    suspend fun execute(patch: Patch) {
        if (executed.containsKey(patch)) return
        for (dependency in patch.dependencies) execute(dependency)
        executing(patch)
        try { patch.execute(context) }
        catch (failure: Exception) { rejected(patch, failure); throw failure }
        executed[patch] = true
        if (patch.finalizeBlock != null) finalizers += patch
        else if (patch in roots) succeeded(patch)
    }
    for (root in roots) execute(root)
    for (patch in finalizers.asReversed()) {
        executing(patch)
        try { patch.finalize(context) }
        catch (failure: Exception) { rejected(patch, failure); throw failure }
        if (patch in roots) succeeded(patch)
    }
}
