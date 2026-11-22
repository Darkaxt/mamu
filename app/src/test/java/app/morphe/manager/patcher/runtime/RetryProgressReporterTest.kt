package app.morphe.manager.patcher.runtime

import android.app.Application
import app.morphe.manager.patcher.logger.*
import app.morphe.manager.patcher.patch.*
import app.morphe.manager.ui.model.State
import app.morphe.patcher.PatcherTask
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,manifest=Config.NONE)
class RetryProgressReporterTest {
    @Test fun real_task_transitions_and_patch_names_are_logged_without_counter_flooding() = runBlocking {
        val logs=mutableListOf<String>();val statuses=mutableListOf<State?>();val tasks=mutableListOf<Long>()
        val logger=object:Logger() { override fun log(level:LogLevel,message:String) { logs+=message } }
        var received:PatchRejection?=null
        val reporter=ProgressReporter(RuntimeEnvironment.getApplication(),logger,{ _,state,_ -> statuses+=state },{}, {},
            onTaskProgress={ _,done,_ -> tasks+=done },onRejected={ received=it })
        reporter.taskProgress(PatcherTask.DECODING_DEX,0,2);reporter.taskProgress(PatcherTask.DECODING_DEX,1,2)
        reporter.taskProgress(PatcherTask.DECODING_DEX,2,2);reporter.patchExecuting("Exact patch")
        assertEquals(listOf(0L,1L,2L),tasks);assertEquals(2,logs.size);assertTrue(logs.last().contains("Exact patch"))
        val rejection=PatchRejection(setOf(PatchKey(7,"Failed")),"specific cause","Failed","stack")
        reporter.patchesRejected(rejection);reporter.patchFailed("Failed","stack")
        assertEquals(rejection,received);assertFalse(State.FAILED in statuses);assertTrue(logs.last().contains("7:Failed"))
    }
    @Test fun unclassified_failure_keeps_the_existing_failed_state() = runBlocking {
        var state:State?=null;var named:String?=null
        val reporter=ProgressReporter(RuntimeEnvironment.getApplication(),object:Logger() { override fun log(level:LogLevel,message:String) {} },
            { _,value,_ -> state=value },{}, { named=it })
        reporter.patchFailed("Unknown","stack")
        assertEquals(State.FAILED,state);assertEquals("Unknown",named)
    }
}
