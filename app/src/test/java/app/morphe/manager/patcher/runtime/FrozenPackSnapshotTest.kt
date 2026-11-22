package app.morphe.manager.patcher.runtime

import android.app.Application
import app.morphe.manager.domain.bundles.LocalPatchBundle
import app.morphe.manager.domain.repository.PatchBundleRepository.BundleState
import app.morphe.manager.patcher.patch.*
import kotlinx.collections.immutable.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.jar.*
import kotlin.test.*

/** Real source objects/cache/JAR copies at the production pre-run boundary; no patch simulation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,manifest=Config.NONE)
class FrozenPackSnapshotTest {
    @get:Rule val temp=TemporaryFolder()
    private val packageName="com.google.android.apps.youtube.music"
    private fun jar(file:File,version:String):File = file.apply {
        val manifest=Manifest().apply { mainAttributes[Attributes.Name.MANIFEST_VERSION]="1.0"
            mainAttributes.putValue("Name","Morphe Patches");mainAttributes.putValue("Version",version) }
        JarOutputStream(outputStream(),manifest).use { output ->
            output.putNextEntry(JarEntry("classes.dex"));output.write(byteArrayOf(1,2,3));output.closeEntry()
        }
    }
    private fun source(directory:File,uid:Int=0,enabled:Boolean=true)=LocalPatchBundle(
        "Morphe Patches",uid,null,null,null,null,directory,enabled)
    private fun info(uid:Int,version:String,enabled:Boolean=true)=PatchBundleInfo.Global("Morphe Patches",version,uid,enabled,
        listOf(PatchInfo("Exact patch",null,true,persistentListOf(CompatiblePackage(packageName,persistentSetOf("9.30.53"))),null)))
    private fun snapshot(source:LocalPatchBundle,version:String)=BundleState.Ready(
        persistentMapOf(source.uid to source),persistentMapOf(source.uid to info(source.uid,version)))
    private fun sha(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test fun replaced_file_does_not_invalidate_fresh_metadata_because_a_source_manifest_is_cached() = runBlocking {
        val directory=temp.newFolder();val file=jar(directory.resolve("patches.jar"),"old")
        val source=source(directory);assertEquals("old",source.version)
        val replacement=jar(directory.resolve("replacement.jar"),"new")
        Files.move(replacement.toPath(),file.toPath(),REPLACE_EXISTING)
        assertEquals("old",source.version) // Real lazy manifest cache; former guard rejects this.
        assertEquals("new",PatchBundle(file.path).manifestAttributes!!.version)
        val root=temp.newFolder()
        try {
            val frozen=freezePacks(snapshot(source,"new"),emptySet(),root,packageName,
                mapOf(0 to setOf("Exact patch")),emptyMap(),mapOf(0 to "new")).single()
            assertEquals("new",frozen.version);assertEquals(sha(file),sha(File(frozen.request.patchesFile)))
            assertEquals(setOf("Exact patch"),frozen.request.patches)
        } finally { discardRuntimeAttempt(root) }
        assertTrue(file.isFile);assertEquals("old",source.version)
    }

    @Test fun genuine_selected_metadata_or_archive_version_changes_still_stop() = runBlocking {
        val directory=temp.newFolder();jar(directory.resolve("patches.jar"),"actual")
        val source=source(directory)
        for((published,selected) in listOf("published" to "published","actual" to "selected")) {
            val root=temp.newFolder()
            try {
                val error=assertFailsWith<IllegalArgumentException> {
                    freezePacks(snapshot(source,published),emptySet(),root,packageName,
                        mapOf(0 to setOf("Exact patch")),emptyMap(),mapOf(0 to selected))
                }
                assertTrue(error.message.orEmpty().contains("changed"))
                if(published==selected) assertTrue(error.message.orEmpty().contains("copied actual"))
            } finally { discardRuntimeAttempt(root) }
        }
    }

    @Test fun missing_disabled_blocked_or_unknown_patch_sources_are_not_admitted() = runBlocking<Unit> {
        val directory=temp.newFolder();jar(directory.resolve("patches.jar"),"same")
        val enabled=source(directory);val disabled=source(directory,enabled=false)
        val cases=listOf(BundleState.Ready() to emptySet(),snapshot(disabled,"same") to emptySet(),
            snapshot(enabled,"same") to setOf(0),
            BundleState.Ready(persistentMapOf(0 to enabled),persistentMapOf(0 to info(0,"same",enabled=false))) to emptySet())
        for((state,blocked) in cases) {
            val root=temp.newFolder()
            try { assertFailsWith<IllegalArgumentException> {
                freezePacks(state,blocked,root,packageName,mapOf(0 to setOf("Exact patch")),emptyMap(),mapOf(0 to "same"))
            } } finally { discardRuntimeAttempt(root) }
        }
        val root=temp.newFolder()
        try { assertFailsWith<IllegalArgumentException> {
            freezePacks(snapshot(enabled,"same"),emptySet(),root,packageName,
                mapOf(0 to setOf("Unknown patch")),emptyMap(),mapOf(0 to "same"))
        } } finally { discardRuntimeAttempt(root) }
    }

    @Test fun duplicate_uids_have_independent_copies_and_options_with_deterministic_ties() = runBlocking {
        val directory=temp.newFolder();jar(directory.resolve("patches.jar"),"same")
        val state=BundleState.Ready(persistentMapOf(4 to source(directory,4),0 to source(directory)),
            persistentMapOf(4 to info(4,"same"),0 to info(0,"same")))
        val root=temp.newFolder()
        try {
            val packs=freezePacks(state,emptySet(),root,packageName,
                mapOf(4 to setOf("Exact patch"),0 to setOf("Exact patch")),
                mapOf(0 to mapOf("Exact patch" to mapOf("value" to "first")),
                    4 to mapOf("Exact patch" to mapOf("value" to "second"))),mapOf(0 to "same",4 to "same"))
            assertEquals(listOf(0,4),packs.map { it.request.uid })
            assertEquals(listOf(JsonPrimitive("first"),JsonPrimitive("second")),packs.map { it.request.options.getValue("Exact patch").getValue("value") })
            assertNotEquals(packs[0].request.patchesFile,packs[1].request.patchesFile)
            assertEquals(sha(File(packs[0].request.patchesFile)),sha(File(packs[1].request.patchesFile)))
        } finally { discardRuntimeAttempt(root) }
    }

    @Test fun exact_official_morphe_dev2_bundle_named_by_the_tablet_is_admitted() = runBlocking {
        val evidence=System.getenv("MORPHE_LIBRARY_EVIDENCE")?.let(::File)
        val fixture=evidence?.parentFile?.parentFile?.resolve("source-regression/patches-2.2.0-dev.2.mpp")
        assumeTrue("External official-bundle qualification fixture",fixture?.isFile==true)
        val official=requireNotNull(fixture)
        assertEquals("36c3991d896317a470ce8f35c5971809a9e57e09943f72e2dded0d942b0f3c54",sha(official))
        val directory=temp.newFolder();val file=jar(directory.resolve("patches.jar"),"2.2.0-dev.1")
        val source=source(directory);assertEquals("2.2.0-dev.1",source.version)
        val staging=directory.resolve("admitted.mpp");official.copyTo(staging)
        Files.move(staging.toPath(),file.toPath(),REPLACE_EXISTING)
        val root=temp.newFolder()
        try {
            val copied=freezePacks(snapshot(source,"2.2.0-dev.2"),emptySet(),root,packageName,
                mapOf(0 to setOf("Exact patch")),emptyMap(),mapOf(0 to "2.2.0-dev.2")).single()
            assertEquals("2.2.0-dev.2",copied.version)
            assertEquals(sha(official),sha(File(copied.request.patchesFile)))
            assertEquals("2.2.0-dev.1",source.version)
        } finally { discardRuntimeAttempt(root) }
    }
}
