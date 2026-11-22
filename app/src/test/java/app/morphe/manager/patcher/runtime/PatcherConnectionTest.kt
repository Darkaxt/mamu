package app.morphe.manager.patcher.runtime

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import app.morphe.manager.patcher.PatchInputUse
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, manifest = Config.NONE)
class PatcherConnectionTest {
    private class Binding(private val accepted: Boolean = true) : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val unbound = CompletableDeferred<Unit>()
        var death: IBinder.DeathRecipient? = null
        var unbindCount = 0
        private val binder = Proxy.newProxyInstance(IBinder::class.java.classLoader, arrayOf(IBinder::class.java)) { _, method, args ->
            when (method.name) {
                "linkToDeath" -> { death = args!![0] as IBinder.DeathRecipient; null }
                "isBinderAlive", "pingBinder" -> true
                "queryLocalInterface" -> null
                "getInterfaceDescriptor" -> "app.morphe.manager.patcher.runtime.IPatcherService"
                else -> null
            }
        } as IBinder

        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            if (accepted) connection.onServiceConnected(ComponentName(this, PatcherService::class.java), binder)
            return accepted
        }
        override fun unbindService(connection: ServiceConnection) {
            unbindCount++
            unbound.complete(Unit)
        }
    }

    @Test fun input_cannot_be_released_when_unbound_but_process_still_owns_it() = runBlocking {
        val binding = Binding()
        val connection = PatcherProcesses.PatcherConnection.connect(binding)
        val use = PatchInputUse()
        assertTrue(use.begin())
        val release = async(Dispatchers.IO) { connection.close(); use.finish() }
        binding.unbound.await()
        val cleanup = async(start = CoroutineStart.UNDISPATCHED) { use.awaitReleased() }
        assertFalse("Unbind is not process death", cleanup.isCompleted)
        binding.death!!.binderDied()
        release.await()
        cleanup.await()
        connection.close()
        assertEquals(1, binding.unbindCount)
    }

    @Test fun rejected_binding_preserves_failure_without_unbinding_an_unowned_service() = runBlocking {
        val binding = Binding(accepted = false)
        try {
            PatcherProcesses.PatcherConnection.connect(binding)
            fail("Expected binding failure")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("could not be bound"))
        }
        assertEquals(0, binding.unbindCount)
    }
}
