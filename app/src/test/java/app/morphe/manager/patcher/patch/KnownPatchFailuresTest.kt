package app.morphe.manager.patcher.patch

import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, manifest = Config.NONE)
class KnownPatchFailuresTest {
    @get:Rule val temp = TemporaryFolder()
    private val context = PatchFailureContext("app|7.14.0|714|ARM64|sha", mapOf(1 to "source-a", 2 to "source-b"))
    private fun failure() = KnownPatchFailure(context.apk, 1, "source-a", "Free store", "dependency: native pattern not found")

    @Test fun aborted_attempt_success_cannot_clear_a_later_dependency_finalizer_failure() {
        val outcomes=listOf(PatchExecutionOutcome(1,"Free store","dependency finalizer failed"),
            PatchExecutionOutcome(1,"Free store",null))
        val aborted=completedPatchOutcomes(outcomes,false)
        assertEquals(1,aborted.size)
        assertEquals(setOf("1:Free store"),knownFailures(context,
            updatedFailureRecords(emptyList(),context,aborted,context.bundles)).keys)
        val finished=completedPatchOutcomes(outcomes,true)
        assertTrue(knownFailures(context,updatedFailureRecords(listOf(failure()),context,finished,context.bundles)).isEmpty())
    }

    @Test fun `failure persists across restart and binds actual source patch build ABI and content`() {
        val file = AtomicFile(temp.root.resolve("failures.json"))
        writeFailureRecords(file, listOf(failure()))
        val records = readFailureRecords(AtomicFile(file.baseFile))
        assertEquals(mapOf("1:Free store" to failure().reason), knownFailures(context, records))
        for (apk in listOf("app|7.13.0|714|ARM64|sha", "app|7.14.0|715|ARM64|sha",
            "app|7.14.0|714|ARM32|sha", "app|7.14.0|714|ARM64|different", "other|7.14.0|714|ARM64|sha")) {
            assertTrue(knownFailures(context.copy(apk = apk), records).isEmpty())
        }
        assertTrue(knownFailures(context.copy(bundles = mapOf(1 to "updated")), records).isEmpty())
        val selected = mapOf(1 to setOf("Free store", "Other"), 2 to setOf("Free store"))
        assertEquals(mapOf(1 to setOf("Other"), 2 to setOf("Free store")), selected.withoutKnownFailures(context, records))
    }

    @Test fun `successful explicit retry clears only exact restriction and preserves source caution`() {
        val other = failure().copy(uid = 2, release = "source-b")
        val records = updatedFailureRecords(listOf(failure(), other), context,
            listOf(PatchExecutionOutcome(1, "Free store", null)), context.bundles)
        assertEquals(setOf("2:Free store"), knownFailures(context, records).keys)
        assertEquals(mapOf(1 to 1), sourceFailureCounts(records, mapOf(1 to setOf("Other patch"))))
        assertTrue(sourceFailureCounts(records, mapOf(1 to emptySet(), 9 to setOf("Other"))).isEmpty())
        val updated = context.copy(bundles = mapOf(1 to "new-release"))
        assertTrue(knownFailures(updated, records).isEmpty())
        assertEquals(mapOf(1 to 1), sourceFailureCounts(records, mapOf(1 to setOf("Free store"))))
    }

    @Test fun `source replaced mid execution cannot inherit stale outcome and repeated failure is not double counted`() {
        assertTrue(updatedFailureRecords(emptyList(), context, listOf(PatchExecutionOutcome(1, "Free store", "bad")),
            mapOf(1 to "new-source")).isEmpty())
        val records = updatedFailureRecords(listOf(failure()), context,
            listOf(PatchExecutionOutcome(1, "Free store", "again")), context.bundles)
        assertEquals(1, records.size)
        assertEquals("again", records.single().reason)
    }

    @Test fun `only attributable execution errors count including named dependency cause`() {
        val reason = attributablePatchFailure(IllegalStateException("parent failed",
            IllegalArgumentException("character branch: ARM64 pattern absent")))
        assertEquals("parent failed: character branch: ARM64 pattern absent", reason)
        for (error in listOf(CancellationException(), IOException("disk full"), OutOfMemoryError(), InterruptedException())) {
            assertNull(attributablePatchFailure(error))
            assertNull(attributablePatchFailure(IllegalStateException("dependency", error)))
        }
    }

    @Test fun `availability never silently readds failed required patch but explicit retry is retained`() {
        val failed = setOf("1:Free store")
        val autoEnabled = mapOf(1 to setOf("Free store", "Other"))
        assertEquals(mapOf(1 to setOf("Other")), autoEnabled.preserveKnownFailureChoices(mapOf(1 to setOf("Other")), failed))
        assertEquals(autoEnabled, autoEnabled.preserveKnownFailureChoices(autoEnabled, failed))
    }
    @Test fun `explicit retry permission cannot approve a different source release APK digest or ABI`() {
        val key = knownFailureRetryKey(context, "1:Free store")
        assertNotEquals(key, knownFailureRetryKey(context.copy(apk = "different APK"), "1:Free store"))
        assertNotEquals(key, knownFailureRetryKey(context.copy(bundles = mapOf(1 to "updated")), "1:Free store"))
        assertNotEquals(key, knownFailureRetryKey(context, "2:Free store"))
        assertNotEquals(key, knownFailureRetryKey(context, "1:Other patch"))
    }

    @Test fun `archive and installed split content match regardless of archive metadata and changes invalidate identity`() {
        val base = temp.newFile("base.apk").also { it.writeText("base bytes") }
        val split = temp.newFile("split.apk").also { it.writeText("native arm64 bytes") }
        val archive = temp.newFile("download.apkm")
        java.util.zip.ZipOutputStream(archive.outputStream()).use { zip ->
            for ((name, bytes) in listOf("metadata.json" to "ignored".toByteArray(), "base.apk" to base.readBytes(), "splits/arm64.apk" to split.readBytes())) {
                zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        val installed = (originalApkDigests(base) + originalApkDigests(split)).sorted()
        assertEquals(installed, originalApkDigests(archive).sorted())
        split.writeText("changed native arm64 bytes")
        assertNotEquals(installed, (originalApkDigests(base) + originalApkDigests(split)).sorted())
    }

}
