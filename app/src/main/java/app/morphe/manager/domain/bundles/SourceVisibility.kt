package app.morphe.manager.domain.bundles

/** A display projection only; missing remotes remain stored and eligible for download/retry. */
internal fun sourceVisibleInManagement(isRemote: Boolean, isDefault: Boolean, isMissing: Boolean) =
    !isRemote || isDefault || !isMissing

internal fun PatchBundleSource.visibleInManagement() = sourceVisibleInManagement(
    isRemote = this is RemotePatchBundle,
    isDefault = uid == 0,
    isMissing = !patchesJarFile.isFile
)
