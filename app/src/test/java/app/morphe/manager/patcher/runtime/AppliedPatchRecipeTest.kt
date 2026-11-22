package app.morphe.manager.patcher.runtime

import android.app.Application
import app.morphe.manager.domain.batch.*
import app.morphe.manager.domain.bundles.installedPatchPublicationTime
import app.morphe.manager.patcher.PatchRequest
import app.morphe.manager.patcher.patch.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,manifest=Config.NONE)
class AppliedPatchRecipeTest {
    @get:Rule val temp=TemporaryFolder()
    private fun recipe(): AppliedPatchRecipe {
        val packs=listOf(
            FrozenPack(PatchRequest.Bundle(1,"one.jar",setOf("Same","Clone"),mapOf("Same" to mapOf("value" to JsonPrimitive("kept")),
                "Clone" to mapOf("package" to JsonPrimitive("example.clone"))),20),"Duplicate","2",setOf("Same","Clone"),setOf("Clone")),
            FrozenPack(PatchRequest.Bundle(2,"two.jar",setOf("Same"),emptyMap(),10),"Duplicate","1",setOf("Same"),emptySet()))
        return appliedRecipe(mapOf(1 to setOf("Same"),2 to setOf("Same")),
            listOf(PatchOmission(PatchKey(1,"Clone"),"cannot rename","Clone")),packs)
    }
    @Test fun signed_recipe_survives_recovery_and_rejects_a_different_output() {
        val output=temp.newFile("signed.apk").apply { writeBytes(byteArrayOf(1,2,3)) }
        val recipe=recipe();writeAppliedPatchRecipe(output,recipe)
        assertEquals(recipe,readAppliedPatchRecipe(output))
        assertEquals("Duplicate",recipe.omissions.single().sourceName)
        assertEquals("2",recipe.omissions.single().sourceVersion)
        assertEquals(setOf(1,2),recipe.selection.keys)
        assertEquals(mapOf("Same" to mapOf("value" to "\"kept\"")),recipe.payload.bundles.first().options)
        assertTrue(recipe.renamingPatches.isEmpty())
        output.appendBytes(byteArrayOf(4))
        assertFailsWith<IllegalArgumentException> { readAppliedPatchRecipe(output) }
        clearAppliedPatchRecipe(output);assertTrue(output.exists())
        assertFalse(output.resolveSibling(output.name+".recipe.json").exists())
    }
    @Test fun batch_counts_clone_intent_and_saved_payload_use_only_applied_roots() {
        val requested=BatchPatchItem(target=BatchTarget("example.app"),appName="Example",source=null,
            selection=mapOf(1 to setOf("Same","Clone"),2 to setOf("Same")),options=emptyMap(),
            bundles=listOf(BatchBundleRef(1,"Duplicate","changed",setOf("Same","Clone"),setOf("Clone")),
                BatchBundleRef(2,"Duplicate","changed",setOf("Same"),emptySet())),state=BatchItemState.RUNNING)
        val actual=requested.withAppliedRecipe(recipe())
        assertEquals(2,actual.patchCount);assertFalse(actual.producesCloneAs("example.clone"))
        assertEquals(listOf("2","1"),actual.bundles.map { it.version })
        assertEquals(recipe().payload,actual.appliedRecipe!!.payload)
        assertEquals(3,requested.patchCount) // No mutation of original/retry intent.
    }
    @Test fun installed_release_time_never_uses_import_clocks_or_another_version() {
        val exact=Instant.parse("2026-11-09T12:34:56.123Z").toEpochMilli()
        assertEquals(exact,installedPatchPublicationTime("""{"version":"v2","day":"2026-11-09","publishedAt":$exact}""","v2"))
        val day=Instant.parse("2026-11-09T00:00:00Z").toEpochMilli()
        assertEquals(day,installedPatchPublicationTime("""{"version":"v2","day":"2026-11-09"}""","v2"))
        assertNull(installedPatchPublicationTime("""{"version":"v2","day":"2026-11-09"}""","v1"))
        assertNull(installedPatchPublicationTime(null,"v2"))
        assertNull(installedPatchPublicationTime("""{"version":"v2","day":"bad"}""","v2"))
    }
    @Test fun frozen_copies_are_independent_exact_and_readonly_cleanup_retires_only_owned_files() = runBlocking {
        val source=temp.newFile("import.apk").apply { writeBytes(ByteArray(100000) { (it%127).toByte() }) }
        val bytes=source.readBytes();val owned=temp.newFolder("owned")
        val target=owned.resolve("bundle.jar");copyFrozenInput(source,target,code=true)
        assertContentEquals(bytes,target.readBytes());source.appendBytes(byteArrayOf(1))
        assertContentEquals(bytes,target.readBytes());discardRuntimeAttempt(owned)
        assertFalse(owned.exists());assertTrue(source.exists())
    }
}
