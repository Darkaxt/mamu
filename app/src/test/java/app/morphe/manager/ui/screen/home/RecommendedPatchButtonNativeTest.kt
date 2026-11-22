package app.morphe.manager.ui.screen.home

import android.app.Application
import androidx.compose.material3.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.morphe.manager.data.room.AppDatabase
import app.morphe.manager.di.*
import app.morphe.manager.domain.repository.PatchBundleRepository
import app.morphe.manager.patcher.patch.*
import app.morphe.manager.ui.model.SelectedApp
import app.morphe.manager.ui.viewmodel.ExpertModeSelection
import app.morphe.patcher.patch.*
import io.ktor.client.HttpClient
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.*
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w1280dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecommendedPatchButtonNativeTest {
    @get:Rule val rule=createComposeRule()
    private lateinit var koin:org.koin.core.Koin
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun setup() {
        app.applicationInfo.nativeLibraryDir=File(app.cacheDir,"lib/arm64").path
        koin=startKoin { androidContext(app);modules(httpModule,preferencesModule,repositoryModule,serviceModule,managerModule,databaseModule) }.koin
    }
    @After fun close() {
        scope.cancel();val repository=koin.get<PatchBundleRepository>()
        val field=PatchBundleRepository::class.java.getDeclaredField("scope").apply { isAccessible=true }
        runBlocking { (field.get(repository) as CoroutineScope).coroutineContext[Job]?.cancelAndJoin() }
        koin.get<HttpClient>().close();koin.get<AppDatabase>().close();stopKoin()
    }
    @Test fun recommended_button_immediately_applies_defaults_without_reading_an_apk() {
        val bundles=(1..5).map { uid -> PatchBundleInfo.Global("Source $uid","1",uid,true,listOf(
            PatchInfo("Default $uid",null,true,persistentListOf(CompatiblePackage("example.app",persistentSetOf("1.0"))),null)
        )).forPackage("example.app","1.0",7) }
        val original=File(app.cacheDir,"absent-original.apk");assertFalse(original.exists())
        val owner=ExpertModeSelection(scope,{ _,_,_,_ -> },MutableStateFlow(emptyList()))
        owner.open(SelectedApp.Local("example.app","1.0",7,original,false),bundles,emptySet(),emptySet(),
            emptyMap(),emptyMap(),emptyMap(),InstallerType.STANDARD,ApkArchitecture.UNIVERSAL,false,null)
        val mutations=mutableListOf<Int>()
        rule.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
            ExpertModeDialog("example.app",appName="Example",apkVersion="1.0",apkVersionCode=7,
                selection=ExpertPatchSelection(owner.allPatchesInfo,owner.options,owner.totalSelectedCount),hasMultipleBundles=true,
                patchActions=ExpertPatchActions(owner::toggle,owner::selectAll,owner::deselectAll,
                    onResetToDefault={ uid,names -> mutations+=uid;owner.resetToDefault(uid,names) },
                    onRestoreSaved=owner::restoreSaved,onOptionChange=owner::updateOption,onResetOptions=owner::resetOptions),
                onSourceVisibilityChange={ _,_,_ -> },onAllowUnsupported=owner::allowUnsupported,onDismiss={},onProceed={})
        } }
        rule.onNodeWithContentDescription("Enable recommended patches").performClick()
        rule.onNodeWithText("OK").performClick()
        rule.runOnIdle {
            assertEquals(setOf(1,2,3,4,5),mutations.toSet())
            assertEquals(5,owner.patches.values.sumOf { it.size })
        }
        rule.onNodeWithText("Selecting recommended patches").assertDoesNotExist()
        rule.onNodeWithText("Find compatible patches").assertDoesNotExist()
        assertFalse(original.exists())
    }
}
