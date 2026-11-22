package app.morphe.manager.domain.batch

import app.morphe.manager.patcher.runtime.AppliedPatchRecipe

/** Carry only the signed output's selections into persistence and every later install. */
internal fun BatchPatchItem.withAppliedRecipe(recipe: AppliedPatchRecipe): BatchPatchItem = copy(
    selection=recipe.selection,appliedRecipe=recipe,
    bundles=bundles.map { bundle ->
        val snapshot=recipe.payload.bundles.firstOrNull { it.bundleUid==bundle.uid }
        bundle.copy(name=snapshot?.bundleName ?: bundle.name,version=snapshot?.bundleVersion ?: bundle.version,
            patchNames=recipe.seenPatches[bundle.uid] ?: bundle.patchNames,
            renamingPatchNames=recipe.renamingPatches.filter { it.sourceUid==bundle.uid }.mapTo(linkedSetOf()) { it.key })
    })
