package app.morphe.manager.patcher.runtime

import app.morphe.manager.data.room.apps.installed.SelectionPayload
import app.morphe.manager.domain.bundles.RemotePatchBundle
import app.morphe.manager.domain.repository.PatchBundleRepository
import app.morphe.manager.patcher.PatchRequest
import app.morphe.manager.patcher.patch.*
import app.morphe.manager.util.Options
import app.morphe.manager.util.PatchSelection
import app.morphe.manager.ui.model.declaresPackageName
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

internal data class FrozenPack(val request: PatchRequest.Bundle, val name: String, val version: String?,
    val seen: Set<String>, val renaming: Set<String>)

internal suspend fun freezePacks(repo: PatchBundleRepository, root: File, packageName: String,
    selected: PatchSelection, options: Options, expectedVersions: Map<Int,String?>): List<FrozenPack> {
    val snapshot=repo.bundleState.first { it is PatchBundleRepository.BundleState.Ready }
        as PatchBundleRepository.BundleState.Ready
    return freezePacks(snapshot,repo.blockedSources.value.keys,root,packageName,selected,options,expectedVersions)
}

/** One published catalogue state; source manifest getters may cache an older file's version. */
internal suspend fun freezePacks(snapshot: PatchBundleRepository.BundleState.Ready, blocked: Set<Int>,
    root: File, packageName: String, selected: PatchSelection, options: Options,
    expectedVersions: Map<Int,String?>): List<FrozenPack> {
    return selected.filterValues { it.isNotEmpty() }.map { (uid, names) ->
        val source=requireNotNull(snapshot.sources[uid]) { "Patch source $uid disappeared" }
        require(source.enabled && uid !in blocked) { "Patch source $uid is no longer enabled" }
        val bundle=requireNotNull(source.patchBundle) { "Patch source $uid is unavailable" }
        val metadata=requireNotNull(snapshot.info[uid]?.takeIf { it.enabled }) { "Patch source $uid is no longer enabled" }
        require(uid !in expectedVersions || metadata.version?.takeIf { it.isNotBlank() }==expectedVersions[uid]?.takeIf { it.isNotBlank() }) { "Selected patch source $uid changed; reopen selection" }
        val file=root.resolve("bundle-$uid.jar")
        copyFrozenInput(File(bundle.patchesJar),file,code=true)
        val actual=PatchBundle(file.path).manifestAttributes
        require(actual?.version==metadata.version) {
            "Patch source $uid changed before patching (selected ${metadata.version ?: "unknown"}, copied ${actual?.version ?: "unknown"}); reopen selection"
        }
        val scoped=metadata.forPackage(packageName,null)
        // A selected patch the source no longer lists is not a reason to refuse the run: the patcher
        // omits whatever its code does not load, and the retry patches the rest
        val published=if(source is RemotePatchBundle) source.installedPublicationTime else null
        FrozenPack(PatchRequest.Bundle(uid,file.path,names,options[uid].orEmpty().filterKeys { it in names }.mapValues { (_,values) ->
            values.mapValues { (_,value) -> optionValueToJson(value) }
        },published),source.displayTitle,actual?.version,
            scoped.patches.mapTo(linkedSetOf()) { it.name },
            scoped.patches.filter { it.declaresPackageName }.mapTo(linkedSetOf()) { it.name })
    }.sortedWith(compareByDescending<FrozenPack> { it.request.publishedAt ?: Long.MIN_VALUE }.thenBy { it.request.uid })
}

internal fun appliedRecipe(selection: PatchSelection, omissions: List<PatchOmission>, packs: List<FrozenPack>): AppliedPatchRecipe {
    val byUid=packs.associateBy { it.request.uid }
    val labelled=omissions.map { it.copy(sourceName=byUid[it.patch.sourceUid]?.name,sourceVersion=byUid[it.patch.sourceUid]?.version) }
    return AppliedPatchRecipe(selection,labelled,SelectionPayload(bundles=selection.map { (uid,keys) ->
        val pack=byUid.getValue(uid)
        SelectionPayload.BundleSelection(bundleUid=uid,patches=keys.toList(),
            options=pack.request.options.filterKeys { it in keys }.mapValues { (_,values) -> values.mapValues { (_,value) -> value.toString() } },
            bundleName=pack.name,bundleVersion=pack.version)
    }),packs.associate { it.request.uid to it.seen },selection.flatMapTo(linkedSetOf()) { (uid,keys) ->
        keys.filter { it in byUid.getValue(uid).renaming }.map { PatchKey(uid,it) }
    })
}

/** Read-only before writing code for ART14+; content checked against the admitted source. */
internal suspend fun copyFrozenInput(source: File, target: File, code: Boolean=false) {
    val hash=MessageDigest.getInstance("SHA-256")
    source.inputStream().use { input -> target.outputStream().use { output ->
        if(code) check(target.setReadOnly()) { "Could not protect the owned patch source" }
        val buffer=ByteArray(65536)
        while(true) { currentCoroutineContext().ensureActive(); val n=input.read(buffer); if(n<0) break
            output.write(buffer,0,n); hash.update(buffer,0,n)
        }
    } }
    val current=MessageDigest.getInstance("SHA-256")
    source.inputStream().use { input -> val buffer=ByteArray(65536); while(true) {
        currentCoroutineContext().ensureActive(); val n=input.read(buffer); if(n<0) break; current.update(buffer,0,n)
    } }
    require(hash.digest().contentEquals(current.digest())) { "Patching input changed while copying" }
    check(target.setReadOnly()) { "Could not protect the owned patching input" }
}

/** Only a fresh run-owned tree, after actual process exit; never follow patch-created symlinks. */
internal fun discardRuntimeAttempt(directory: File) {
    val root=directory.toPath()
    if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS)) return
    Files.walk(root).use { paths -> paths.forEach { if(!Files.isSymbolicLink(it)) it.toFile().setWritable(true) } }
    Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
}
