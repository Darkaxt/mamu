@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package app.morphe.manager.patcher

import app.morphe.manager.patcher.host.HostEvent
import app.morphe.manager.patcher.logger.*
import app.morphe.manager.patcher.patch.*
import app.morphe.manager.patcher.split.SplitPreparationEvent
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.*
import com.android.tools.smali.dexlib2.*
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.*
import com.android.tools.smali.dexlib2.immutable.instruction.*
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.*
import kotlin.test.*

/** Exercises production loading, dependency execution, finalizers, assembly and wire identities. */
class OrderedPatchRetryIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private val oldTemp = System.getProperty("java.io.tmpdir")
    @After fun restoreTemp() { if(oldTemp==null) System.clearProperty("java.io.tmpdir") else System.setProperty("java.io.tmpdir",oldTemp) }
    private val logger = object : Logger() { override fun log(level: LogLevel, message: String) {} }
    private fun fingerprint() = Fingerprint(returnType="Z", parameters=listOf("L"), strings=listOf("X509", "isPartnerSHAFingerprint"))
    private fun original(): File {
        val method = ImmutableMethod("Lexample/Certificate;", "check",
            listOf(ImmutableMethodParameter("Ljava/lang/Object;", emptySet(), null)), "Z",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, emptySet(), emptySet(),
            ImmutableMethodImplementation(2,listOf(
                ImmutableInstruction21c(Opcode.CONST_STRING,0,ImmutableStringReference("X509")),
                ImmutableInstruction21c(Opcode.CONST_STRING,0,ImmutableStringReference("isPartnerSHAFingerprint")),
                ImmutableInstruction11n(Opcode.CONST_4,0,0),ImmutableInstruction11x(Opcode.RETURN,0)),emptyList(),emptyList()))
        val cls=ImmutableClassDef("Lexample/Certificate;",1,"Ljava/lang/Object;",emptyList<String>(),null,emptySet(),emptyList(),listOf(method))
        val store=MemoryDataStore()
        val bytes=try { DexPool.writeTo(store,ImmutableDexFile(Opcodes.getDefault(),listOf(cls))); store.data } finally { store.close() }
        return temp.newFile().also { file -> ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(AndroidManifestBlock().apply {
                packageName="example.app";versionName="1.0";versionCode=1
                manifestElement.newElement("application")
                refresh()
            }.bytes)
            zip.closeEntry();zip.putNextEntry(ZipEntry("classes.dex"));zip.write(bytes);zip.closeEntry()
        } }
    }
    private fun literal(apk: File): Int = ZipFile(apk).use { zip ->
        val bytes=zip.getInputStream(zip.getEntry("classes.dex")).use { it.readBytes() }
        (DexBackedDexFile(Opcodes.getDefault(),java.nio.ByteBuffer.wrap(bytes)).classes.single().methods.single().implementation!!.instructions
            .elementAt(2) as NarrowLiteralInstruction).narrowLiteral
    }
    private open class Listener : PatchingListener {
        val rejected=mutableListOf<PatchRejection>()
        val succeeded=mutableListOf<PatchKey>()
        override suspend fun stepCompleted() {}
        override suspend fun splitPreparation(event: SplitPreparationEvent) {}
        override suspend fun patchesStarted() {}
        override suspend fun patchSucceeded(name: String) {}
        override suspend fun patchFailed(name: String?,stackTrace: String) {}
        override suspend fun patchesFinished(count: Int) {}
        override suspend fun patchOutcome(bundleUid: Int,patchKey: String,failure: String?) { if(failure==null) succeeded+=PatchKey(bundleUid,patchKey) }
        override suspend fun patchesRejected(rejection: PatchRejection) {
            assertEquals(HostEvent.PatchesRejected(rejection),HostEvent.decode(HostEvent.encode(HostEvent.PatchesRejected(rejection))))
            rejected+=rejection
        }
    }
    private suspend fun run(input: File,output: File,packs: Map<Int,List<Patch>>,times: Map<Int,Long?>,
        listener: Listener=Listener()) {
        val root=temp.newFolder()
        val request=PatchRequest("example.app",input.path,output.path,root.resolve("merged.apk").path,
            root.resolve("temp").path,root.resolve("workspace").path,
            packs.map { (uid,patches) -> PatchRequest.Bundle(uid,"fixture-$uid.jar",patches.mapTo(linkedSetOf()) { it.name!! },emptyMap(),times[uid]) },
            false,DeviceConfig(listOf("arm64-v8a"),listOf("en-US"),420))
        PatchPipeline(request,logger,listener) { packs.getValue(it.uid) }.run()
    }

    @Test fun newest_pack_precedes_alphabetical_names_with_dependencies_and_reverse_finalizers() = runBlocking {
        val trace=mutableListOf<String>()
        val dep=bytecodePatch("Dependency") { execute { trace+="dependency" };finalize { trace+="dependency finalizer" } }
        val recent=bytecodePatch("Z recent") { dependsOn(dep);execute { trace+="new" };finalize { trace+="new finalizer" } }
        val old=bytecodePatch("A old") { execute { trace+="old" };finalize { trace+="old finalizer" } }
        val output=temp.root.resolve("ordered.apk")
        run(original(),output,linkedMapOf(1 to listOf(old),9 to listOf(recent)),mapOf(1 to 10L,9 to 20L))
        assertEquals(listOf("dependency","new","old","old finalizer","new finalizer","dependency finalizer"),trace)
        assertEquals(0,literal(output))
    }

    @Test fun obb_archives_remain_original_and_are_extracted_fresh_for_real_ordered_retries() = runBlocking {
        val base = original()
        val key = "Android/obb/example.app/main.1.example.app.obb"
        val archive = temp.root.resolve("original.xapk")
        ZipOutputStream(archive.outputStream()).use { zip ->
            for ((name, bytes) in listOf("base.apk" to base.readBytes(), key to "owned expansion bytes".toByteArray())) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("../outside.obb")); zip.write(byteArrayOf(1)); zip.closeEntry()
        }
        val before = archive.readBytes()
        val filesRead = mutableListOf<File>()
        val output = temp.root.resolve("obb-result.apk")
        val result = retryFailedPatches(mapOf(1 to setOf("OBB reader"), 2 to setOf("Broken root"))) { selected, attempt ->
            val reader = patch("OBB reader") { execute {
                val files = packageMetadata.obbFiles
                assertEquals(setOf(key), files.keys)
                val file = files.getValue(key)
                assertEquals("owned expansion bytes", file.readText())
                filesRead += file
            } }
            val broken = patch("Broken root") { execute { error("structured execution failure") } }
            val all = mapOf(1 to listOf(reader), 2 to listOf(broken))
            val listener = Listener()
            val candidate = temp.root.resolve("obb-attempt-$attempt.apk")
            try {
                run(archive, candidate, selected.mapValues { (uid, _) -> all.getValue(uid) },
                    mapOf(1 to 20L, 2 to 10L), listener)
                candidate.copyTo(output)
                null
            } catch (failure: IllegalStateException) {
                listener.rejected.single()
            }
        }
        assertEquals(mapOf(1 to setOf("OBB reader")), result.applied)
        assertEquals(2, filesRead.size)
        assertNotEquals(filesRead[0], filesRead[1])
        assertTrue(filesRead.none { it.exists() })
        assertContentEquals(before, archive.readBytes())
        assertTrue(output.isFile)
        assertEquals(0, literal(output))
        assertFalse(temp.root.resolve("outside.obb").exists())
    }

    @Test fun actual_conflict_retries_only_failed_root_on_clean_original_and_keeps_good_older_patches() = runBlocking {
        val input=original(); val originalBytes=input.readBytes();val output=temp.root.resolve("survivors.apk")
        val trace=mutableListOf<String>();val failedOutputs=mutableListOf<File>();var executions=0
        val selection=linkedMapOf(1 to setOf("A conflicting","B extra"),9 to setOf("Z recent"))
        val result=retryFailedPatches(selection) { selected,attempt ->
            executions++
            val fp=fingerprint()
            val recent=bytecodePatch("Z recent") { execute {
                trace+="new $attempt";val method=fp.method
                val count=method.implementation!!.instructions.size
                method.replaceInstruction(0,"const/4 v0, 0x1");method.replaceInstruction(1,"return v0")
                for(index in 2 until count) method.replaceInstruction(index,"nop")
            } }
            val conflictFp=fingerprint()
            val bad=bytecodePatch("A conflicting") { execute { trace+="bad $attempt";conflictFp.method } }
            val extra=bytecodePatch("B extra") { execute { trace+="extra $attempt" } }
            val all=mapOf(1 to listOf(bad,extra),9 to listOf(recent))
            val packs=selected.mapValues { (uid,keys) -> all.getValue(uid).filter { it.name in keys } }
            val listener=Listener();val candidate=temp.root.resolve("candidate-$attempt.apk")
            try { run(input,candidate,packs,mapOf(1 to 10L,9 to 20L),listener)
                candidate.copyTo(output);null
            } catch(failure: Exception) {
                failedOutputs+=candidate
                assertTrue(failure.message.orEmpty().contains("fingerprint",true))
                assertEquals(failure.stackTraceToString(),listener.rejected.single().stackTrace)
                listener.rejected.single()
            }
        }
        assertEquals(2,executions);assertEquals(mapOf(1 to setOf("B extra"),9 to setOf("Z recent")),result.applied)
        assertEquals(listOf(PatchKey(1,"A conflicting")),result.omissions.map { it.patch })
        assertEquals(listOf("new 1","bad 1","new 2","extra 2"),trace)
        assertTrue(failedOutputs.none { it.exists() });assertTrue(output.isFile);assertContentEquals(originalBytes,input.readBytes())
    }

    @Test fun mutation_before_throw_is_discarded_instead_of_leaking_into_the_successful_apk() = runBlocking {
        val input=original();val output=temp.root.resolve("clean.apk");var count=0
        val result=retryFailedPatches(mapOf(1 to setOf("Z successful"),2 to setOf("A broken"))) { selected,attempt ->
            val goodFp=fingerprint();val badFp=fingerprint()
            val all=mapOf(1 to listOf(bytecodePatch("Z successful") { execute { count++;goodFp.method.replaceInstruction(2,"const/4 v0, 0x1") } }),
                2 to listOf(bytecodePatch("A broken") { execute { badFp.method.replaceInstruction(2,"const/4 v0, 0x0");error("broken after mutation") } }))
            val listener=Listener();val candidate=temp.root.resolve("dirty-$attempt.apk")
            try { run(input,candidate,selected.mapValues { (uid,_) -> all.getValue(uid) },mapOf(1 to 20L,2 to 10L),listener)
                candidate.copyTo(output);null
            } catch(failure: IllegalStateException) { assertFalse(candidate.exists());listener.rejected.single() }
        }
        assertEquals(2,count);assertEquals(setOf(1),result.applied.keys);assertEquals(1,literal(output))
    }

    @Test fun shared_failed_dependency_omits_exact_parents_and_not_an_unrelated_same_source_patch() = runBlocking {
        for(finalizeFailure in listOf(false,true)) {
            val dep=bytecodePatch("Shared dependency") {
                execute { if(!finalizeFailure) error("dependency failed") }
                finalize { if(finalizeFailure) error("finalizer failed") }
            }
            val parent1=bytecodePatch("Parent one") { dependsOn(dep);execute {} }
            val parent2=bytecodePatch("Parent two") { dependsOn(dep);execute {} }
            val other=bytecodePatch("Other") { execute {} }
            val listener=Listener();val output=temp.root.resolve("dependency-$finalizeFailure.apk")
            assertFailsWith<IllegalStateException> {
                run(original(),output,mapOf(1 to listOf(parent1,other),2 to listOf(parent2)),emptyMap(),listener)
            }
            assertFalse(output.exists())
            assertEquals(setOf(PatchKey(1,"Parent one"),PatchKey(2,"Parent two")),listener.rejected.single().affected)
        }
    }

    @Test fun duplicate_keys_preserve_source_ties_and_infrastructure_is_never_a_patch_rejection() = runBlocking {
        val trace=mutableListOf<Int>()
        val packs=listOf(2,1,3).associateWith { uid -> listOf(bytecodePatch("Same") { execute { trace+=uid } }) }
        val listener=Listener()
        run(original(),temp.root.resolve("ties.apk"),packs,mapOf(1 to 10L,2 to 10L,3 to null),listener)
        assertEquals(listOf(1,2,3),trace);assertEquals(listOf(1,2,3),listener.succeeded.map { it.sourceUid })
        for(failure in listOf(IOException("disk failed"),OutOfMemoryError("memory"),kotlinx.coroutines.CancellationException("cancel"))) {
            val bad=bytecodePatch("Failure") { execute { throw failure } };val events=Listener()
            val caught=runCatching { run(original(),temp.root.resolve("never-${failure.javaClass.simpleName}.apk"),mapOf(1 to listOf(bad)),emptyMap(),events) }.exceptionOrNull()
            assertSame(failure,caught);assertTrue(events.rejected.isEmpty())
        }
    }
}
