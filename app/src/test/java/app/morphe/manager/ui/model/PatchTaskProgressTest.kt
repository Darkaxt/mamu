package app.morphe.manager.ui.model

import android.app.Application
import app.morphe.patcher.PatcherTask
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class PatchTaskProgressTest {
    @Test fun `task counts advance actual progress and restart clears the prior attempt`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val progress = PatchRunProgress(RuntimeEnvironment.getApplication(), backgroundScope, 2)
            try {
                val initial = progress.progress
                progress.onProgress(null, State.COMPLETED, null)
                runCurrent()
                progress.onProgress(null, State.COMPLETED, null)
                runCurrent()
                val before = progress.progress
                progress.onTaskProgress(PatcherTask.DECODING_DEX, 1, 2)
                runCurrent()
                assertEquals(PatcherTask.DECODING_DEX, progress.runningTask?.task)
                assertEquals(1L, progress.runningTask?.done)
                assertTrue(progress.progress > before)
                val midpoint = progress.progress
                progress.onTaskProgress(PatcherTask.DECODING_DEX, 0, 2)
                runCurrent()
                assertEquals(midpoint, progress.progress)
                progress.onTaskProgress(PatcherTask.DECODING_DEX, 2, 2)
                runCurrent()
                assertNull(progress.runningTask)
                progress.onPatchCompleted()
                runCurrent()
                progress.onRestart(1)
                runCurrent()
                assertEquals(0, progress.completedPatches)
                assertEquals(1, progress.totalPatches)
                assertNull(progress.runningTask)
                assertEquals(initial, progress.progress)
            } finally { progress.close() }
        } finally { Dispatchers.resetMain() }
    }
}
