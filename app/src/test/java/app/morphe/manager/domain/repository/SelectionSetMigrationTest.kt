package app.morphe.manager.domain.repository

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.morphe.manager.data.room.AppDatabase
import app.morphe.manager.data.room.MIGRATION_18_19
import app.morphe.manager.patcher.patch.PatchInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SelectionSetMigrationTest {
    @Test fun `upgrade retains manual source policy selections and clone-specific named sets`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        // The test's long method directory plus a19-digit Robolectric suffix can put SQLite's
        // journal past Win32 MAX_PATH. Keep native database sidecar paths safely below it.
        val name = "migration"
        check(context.getDatabasePath(name).absolutePath.length + "-journal".length < 260)
        val schema = sequenceOf(File("schemas/app.morphe.manager.data.room.AppDatabase/18.json"),
            File("app/schemas/app.morphe.manager.data.room.AppDatabase/18.json"))
            .first { it.isFile }.readText().let { JSONObject(it).getJSONObject("database") }
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(18) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (index in 0 until entities.length()) {
                        val entity = entities.getJSONObject(index)
                        val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices") ?: continue
                        for (i in 0 until indices.length()) db.execSQL(indices.getJSONObject(i)
                            .getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    db.execSQL("INSERT INTO patch_bundles(uid,name,source,auto_update,enabled,sort_order) VALUES(1,'One','{}',0,1,0),(2,'Two','{}',1,1,1)")
                    db.execSQL("INSERT INTO patch_selections(uid,patch_bundle,package_name) VALUES(10,1,'original.clone')")
                    db.execSQL("INSERT INTO selected_patches(selection,patch_name) VALUES(10,'Same')")
                    db.execSQL("INSERT INTO app_source_mutes(patch_bundle,package_name) VALUES(2,'original.clone')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Fixture is exactly 18")
            }).build())
        helper.writableDatabase
        helper.close()
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(MIGRATION_18_19).allowMainThreadQueries().build()
        try {
            val sql = database.openHelper.writableDatabase // Room validates the complete migrated schema here.
            sql.query("SELECT auto_update FROM patch_bundles WHERE uid=1").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            sql.query("SELECT patch_name FROM selected_patches WHERE selection=10").use { assertTrue(it.moveToFirst()); assertEquals("Same", it.getString(0)) }
            sql.query("SELECT COUNT(*) FROM app_source_mutes").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            val repository = SelectionSetRepository(database)
            repository.save("original.clone", "Choice", mapOf(1 to setOf("Same"), 2 to setOf("Same")),
                mapOf(1 to mapOf("Same" to mapOf("value" to "first")), 2 to mapOf("Same" to mapOf("value" to "second"))))
            repository.save("original", "Choice", mapOf(2 to setOf("Other")), emptyMap())
            val clone = repository.summariesFor("original.clone").first().single()
            assertEquals(mapOf(1 to 1, 2 to 1), clone.patchCounts)
            val stored = requireNotNull(repository.stored(clone.uid))
            assertNotEquals(stored.options[1], stored.options[2])
            val patch = PatchInfo("Same", null, true, null, null)
            assertEquals(mapOf(1 to setOf("Same"), 2 to setOf("Same")), repository.load(clone.uid,
                mapOf(1 to mapOf("Same" to patch), 2 to mapOf("Same" to patch))).patches)
            repository.delete(clone.uid)
            assertTrue(repository.summariesFor("original.clone").first().isEmpty())
            assertEquals(1, repository.summariesFor("original").first().size)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
