package app.morphe.manager.patcher.runtime

import app.morphe.manager.data.room.apps.installed.SelectionPayload
import app.morphe.manager.patcher.patch.PatchKey
import app.morphe.manager.patcher.patch.PatchOmission
import app.morphe.manager.util.PatchSelection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Truth of the signed output, separate from the user's originally requested recipe. */
@Serializable
data class AppliedPatchRecipe(val selection: PatchSelection, val omissions: List<PatchOmission>,
    val payload: SelectionPayload, val seenPatches: Map<Int, Set<String>>,
    val renamingPatches: Set<PatchKey>)

@Serializable
private data class StoredPatchRecipe(val outputPath: String, val sha256: String, val recipe: AppliedPatchRecipe)

private fun File.recipeFile() = resolveSibling(name + ".recipe.json")
private fun File.recipeHash(): String {
    val digest=MessageDigest.getInstance("SHA-256")
    inputStream().use { input -> val bytes=ByteArray(65536); while(true) {
        val n=input.read(bytes); if(n<0) break; digest.update(bytes,0,n)
    } }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Atomic owned-workspace metadata, written only after signing; not an install-completion check. */
fun writeAppliedPatchRecipe(output: File, recipe: AppliedPatchRecipe) {
    require(output.isFile && recipe.selection.values.any { it.isNotEmpty() })
    val target=output.recipeFile(); val staging=target.resolveSibling(target.name+".part")
    val data=Json.encodeToString(StoredPatchRecipe.serializer(), StoredPatchRecipe(output.canonicalPath,output.recipeHash(),recipe))
    FileOutputStream(staging).use { stream -> stream.write(data.toByteArray(Charsets.UTF_8)); stream.fd.sync() }
    Files.move(staging.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING)
}

/** Bind recovery to these owned output bytes, never to an installed package/version guess. */
fun readAppliedPatchRecipe(output: File): AppliedPatchRecipe {
    val record=Json.decodeFromString(StoredPatchRecipe.serializer(),output.recipeFile().readText(Charsets.UTF_8))
    require(record.outputPath==output.canonicalPath && record.sha256==output.recipeHash()) { "Applied patch recipe does not match the output APK" }
    return record.recipe
}

fun clearAppliedPatchRecipe(output: File) {
    for (file in listOf(output.recipeFile(),output.recipeFile().resolveSibling(output.recipeFile().name+".part"))) {
        check(!file.exists() || file.delete()) { "Could not retire the previous output recipe" }
    }
}
