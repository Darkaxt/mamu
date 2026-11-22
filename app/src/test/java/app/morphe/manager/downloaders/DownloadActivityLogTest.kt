package app.morphe.manager.downloaders

import app.morphe.manager.patcher.logger.LogLevel
import org.junit.Test
import kotlin.test.*

class DownloadActivityLogTest {
    @Test fun queueFeedbackUpdatesOnlyItsActiveStepAndPreservesAttemptReasons() {
        val log = DownloadActivityLog()
        log.reset(4)
        val play = "https://play.google.com/store/apps/details?id=com.miniclip.plagueinc"
        log.record(4, "play-miss", "Google Play", "Requested version unavailable", LogLevel.WARN, pageUrl = play)
        log.record(4, "pure-lookup", "APKPure", "Resolving package")
        assertTrue(log.update(4, "pure-lookup", "Byparr queue: 2 of 3"))
        assertEquals(2, log.entries.value.size)
        assertNull(log.entries.value.first().detail)
        assertEquals(play, log.entries.value.first().pageUrl)
        log.record(4, "pure-version", "APKPure", "Matching version")
        assertFalse(log.update(4, "pure-lookup", "Old queue callback"))
        assertEquals("Matching version", log.entries.value.last().message)
        assertNull(log.entries.value.last().detail)
    }

    @Test fun transferSamplesAreCoalescedAndResetRejectsOldAcquisition() {
        val log = DownloadActivityLog()
        log.reset(8)
        repeat(500) { log.record(8, "transfer", "APKPure", "Downloading APK") }
        assertEquals(1, log.entries.value.size)
        log.reset(9)
        assertFalse(log.record(8, "late", "APKMirror", "Late result"))
        assertFalse(log.update(8, "transfer", "Late queue result"))
        assertTrue(log.entries.value.isEmpty())
        log.record(9, "validate", "APKPure", "Checking APK")
        assertEquals("Checking APK", log.entries.value.single().message)
    }

    @Test fun historyIsBoundedAndWarningsRemainWarnings() {
        val log = DownloadActivityLog()
        log.reset(1)
        repeat(150) { log.record(1, "$it", "APKPure", "Step $it", LogLevel.WARN) }
        assertEquals(80, log.entries.value.size)
        assertEquals("Step 149", log.entries.value.last().message)
        assertTrue(log.entries.value.all { it.level == LogLevel.WARN })
    }

    @Test fun resolvedPageSurvivesQueueAndTransferUpdatesButNotRequestReplacement() {
        val log = DownloadActivityLog(); log.reset(12)
        val page = "https://apkpure.net/plague-inc/com.miniclip.plagueinc/download/1.27.5?from=version"
        log.record(12, "matching", "APKPure", "Matching version", pageUrl = page)
        log.update(12, "matching", "Byparr queue: 2 of 3")
        assertEquals(page, log.entries.value.last().pageUrl)
        log.record(12, "transfer", "APKPure", "Downloading APK")
        assertEquals(page, log.entries.value.last().pageUrl)
        log.record(12, "other", "APKMirror", "Resolving package")
        assertNull(log.entries.value.last().pageUrl)
        log.reset(13)
        assertFalse(log.record(12, "stale", "APKPure", "Old result", pageUrl = page))
        log.record(13, "fresh", "APKPure", "Resolving package")
        assertNull(log.entries.value.single().pageUrl)
    }

    @Test fun onlyPublicRelatedPagesAreAdmittedForBrowserActions() {
        for (page in listOf("https://www.apkmirror.com/?s=com.example.app", "https://apkpure.net/p/com.example.app",
            "https://apkcombo.com/example/com.example.app/", "https://example.en.uptodown.com/android",
            "https://play.google.com/store/apps/details?id=com.example.app")) assertEquals(page, downloadActivityPage(page))
        for (page in listOf(null, "http://apkpure.net/p/com.example.app", "https://user:secret@apkpure.net/p/com.example.app",
            "https://apkpure.net:1234/p/com.example.app", "https://apkpure.net.evil.test/p/com.example.app",
            "https://d.apkpure.net/b/APK/com.example.app", "https://www.apkmirror.com/wp-content/uploads/file.apk",
            "https://apkcombo.com/d", "https://play.googleapis.com/download?token=private", "intent://example",
            "https://play.google.com/store/apps/details?id=com.example.app&token=secret")) assertNull(downloadActivityPage(page), page)
    }
}
