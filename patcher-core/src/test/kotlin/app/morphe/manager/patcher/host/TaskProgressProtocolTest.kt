package app.morphe.manager.patcher.host

import app.morphe.patcher.PatcherTask
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskProgressProtocolTest {
    @Test fun `host task progress round trip keeps exact task and large byte counts`() {
        val sent = HostEvent.TaskProgress(PatcherTask.WRITING_APK, 4_294_967_296L, 8_589_934_592L)
        val received = Json.decodeFromString(HostEvent.serializer(), Json.encodeToString(HostEvent.serializer(), sent))
        assertEquals(sent, received)
    }
}
