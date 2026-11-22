package app.morphe.manager.util

import kotlin.test.*

class ResolvedIconCacheTest {
    @Test fun `resolver layers clear before notifying a new snapshot generation`() {
        val cache = ResolvedIconCache()
        val stale = ResolvedIconSnapshot(null, null, "com.example.old")
        val previous = cache.revision.value
        val resolverLayer = mutableMapOf("com.example.app" to stale)
        cache.publish("com.example.app", AppDataSource.INSTALLED, stale, previous)
        cache.invalidate("com.example.app") {
            assertEquals(previous, cache.revision.value)
            resolverLayer.clear()
        }
        assertTrue(resolverLayer.isEmpty())
        assertEquals(previous + 1, cache.revision.value)
        cache.publish("com.example.app", AppDataSource.INSTALLED, stale, previous)
        assertNull(cache.peek("com.example.app", AppDataSource.INSTALLED))
    }

    @Test fun `warm snapshot is immediately available and invalidated results cannot resurrect it`() {
        val cache = ResolvedIconCache()
        val value = ResolvedIconSnapshot(null, null, "com.example.original")
        val revision = cache.revision.value
        cache.publish("com.example.clone", AppDataSource.PATCHED_APK, value, revision)
        assertSame(value, cache.peek("com.example.clone", AppDataSource.PATCHED_APK))
        assertNull(cache.peek("com.example.clone", AppDataSource.INSTALLED))
        cache.invalidate("com.example.clone")
        cache.publish("com.example.clone", AppDataSource.PATCHED_APK, value, revision)
        assertNull(cache.peek("com.example.clone", AppDataSource.PATCHED_APK))
        cache.publish("com.example.clone", AppDataSource.PATCHED_APK, value, cache.revision.value)
        assertSame(value, cache.peek("com.example.clone", AppDataSource.PATCHED_APK))
        cache.invalidate()
        assertNull(cache.peek("com.example.clone", AppDataSource.PATCHED_APK))
    }
}
