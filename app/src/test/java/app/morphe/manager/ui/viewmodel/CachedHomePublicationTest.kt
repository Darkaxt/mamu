package app.morphe.manager.ui.viewmodel

import app.morphe.manager.domain.manager.HomeAppSortMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Test
import kotlin.test.*

class CachedHomePublicationTest {
    private fun cards(name: String) = HomeCards(emptyList(), emptyList(), HomeAppSortMode.entries.first(),
        listOf(HomeAppSourceGroup(0, name, emptySet(), emptyList(), false, null, null)))

    @Test fun cacheAndLocalChangesPublishWhileReadyOnlyCatalogueIsHeld() = runBlocking {
        val cache = MutableStateFlow<HomeCards?>(cards("cached"))
        val fresh = MutableSharedFlow<HomeCards?>(extraBufferCapacity = 1)
        val first = CompletableDeferred<Unit>(); val changed = CompletableDeferred<Unit>(); val settled = CompletableDeferred<Unit>()
        val observed = mutableListOf<HomeCards?>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            cachedHomeCardsUntilLive(cache, fresh).collect {
                observed += it
                when (it?.sourceGroups?.single()?.name) {
                    "cached" -> first.complete(Unit)
                    "current preferences" -> changed.complete(Unit)
                    "live" -> settled.complete(Unit)
                }
            }
        }
        try {
            first.await()
            assertEquals<List<HomeCards?>>(listOf(cards("cached")), observed)
            cache.value = cards("current preferences")
            changed.await()
            fresh.emit(cards("live"))
            settled.await()
            cache.value = cards("obsolete cache")
            fresh.emit(null) // Temporary source loading must not blank an established list.
            yield()
            assertEquals<List<HomeCards?>>(listOf(cards("cached"), cards("current preferences"), cards("live")), observed)
        } finally { job.cancelAndJoin() }
    }

    @Test fun slowCacheCannotReplaceLiveEvenIfItsReaderFinishesAfterCancellation() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val shown = CompletableDeferred<Unit>()
        val cache = flow<HomeCards?> {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            emit(cards("late cache"))
        }
        val fresh = flow<HomeCards?> { entered.await(); emit(cards("live")); awaitCancellation() }
        val observed = mutableListOf<HomeCards?>()
        val job = launch { cachedHomeCardsUntilLive(cache, fresh).collect { observed += it; shown.complete(Unit) } }
        try {
            shown.await(); release.complete(Unit); yield()
            assertEquals<List<HomeCards?>>(listOf(cards("live")), observed)
        } finally { release.complete(Unit); job.cancelAndJoin() }
    }

    @Test fun missingCacheStillAllowsAnAuthoritativeEmptyCatalogue() = runBlocking {
        val empty = HomeCards(emptyList(), emptyList(), HomeAppSortMode.entries.first(), emptyList())
        assertEquals(listOf(empty), cachedHomeCardsUntilLive(flowOf(null), flowOf(empty)).toList())
    }
}
