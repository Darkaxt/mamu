package app.morphe.manager.ui.viewmodel

import android.app.Application
import androidx.room.Room
import app.morphe.manager.data.room.AppDatabase
import app.morphe.manager.domain.repository.SelectionSetRepository
import app.morphe.manager.patcher.patch.PatchInfo
import app.morphe.manager.ui.screen.shared.CopySelectionCandidate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

/** Real Room set data through the controller used by both Extended expert callers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class NamedCopyIntegrationTest {
    @Test fun sectionCopy() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val sets = SelectionSetRepository(db)
        db.openHelper.writableDatabase.execSQL("INSERT INTO patch_bundles(uid,name,source,auto_update,enabled,sort_order) VALUES(7,'Source','{}',1,1,0),(8,'Foreign','{}',1,1,1)")
        startKoin { modules(module { single { sets }; single<Application> { RuntimeEnvironment.getApplication() } }) }
        try {
            sets.save("donor.clone", "First", mapOf(7 to setOf("Keep", "Other section"), 8 to setOf("Foreign")), emptyMap())
            sets.save("donor.clone", "Second", mapOf(7 to setOf("Other section")), emptyMap())
            val summaries = sets.summariesFor("donor.clone").first().associateBy { it.name }
            fun candidate(name: String) = CopySelectionCandidate(7, "Source", "donor.clone", "Donor", 2, 1,
                true, false, summaries.getValue(name).uid, name)
            val patches = listOf("Keep", "Other section").associateWith { PatchInfo(it, null, true, null, null) }
            val controller = CopySelectionController()
            // Establish the actual picker owner/scope without starting unrelated catalogue IO.
            controller.open(CoroutineScope(Job().apply { cancel() }), "target", 9, patches.keys,
                patchScope = setOf("Keep"))
            val copied = assertNotNull(controller.resolve(candidate("First"), patches, setOf("Keep")))
            assertEquals(setOf("Keep"), copied.patches)
            assertTrue(copied.options.isEmpty())
            // The other named set must not fall back to the donor's saved/default selection.
            assertNull(controller.resolve(candidate("Second"), patches, setOf("Keep")))
        } finally {
            stopKoin()
            db.close()
        }
    }
}
