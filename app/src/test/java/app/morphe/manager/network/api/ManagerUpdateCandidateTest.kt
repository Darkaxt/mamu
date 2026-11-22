package app.morphe.manager.network.api

import app.morphe.manager.network.dto.ReleaseAsset
import app.morphe.manager.network.utils.*
import io.ktor.http.HttpStatusCode
import java.time.LocalDateTime
import java.io.IOException
import kotlin.test.*

class ManagerUpdateCandidateTest {
    private fun asset(version: String) = ReleaseAsset(
        "https://github.com/Darkaxt/mamu/releases/download/v$version/Mamu-$version.apk",
        LocalDateTime.of(2026, 10, 13, 0, 0), description = "Notes", version = version)

    @Test fun `current and older versions are current while a newer numeric revision is offered`() {
        assertNull(managerUpdateCandidate(APIResponse.Success(asset("1.35.0.4")), "1.35.0.4", false))
        assertNull(managerUpdateCandidate(APIResponse.Success(asset("1.35.0.3")), "1.35.0.4", false))
        assertEquals(asset("1.35.0.10"), managerUpdateCandidate(APIResponse.Success(asset("1.35.0.10")), "1.35.0.9", false))
        val newer = asset("1.35.0.5")
        assertEquals(newer, managerUpdateCandidate(APIResponse.Success(newer), "1.35.0.4", false))
        assertEquals(newer, managerUpdateCandidate(APIResponse.Success(newer), "1.35.0-extended.4", false))
    }

    @Test fun `authentication and network failures cannot be mistaken for up to date`() {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.NotFound)) {
            val error = APIError(status, "Private repository unavailable")
            assertSame(error, assertFailsWith<APIError> {
                managerUpdateCandidate(APIResponse.Error(error), "1.35.0.4", false)
            })
        }
        val error = APIFailure(IOException("Offline"), null)
        assertSame(error, assertFailsWith<APIFailure> {
            managerUpdateCandidate(APIResponse.Failure(error), "1.35.0.4", false)
        })
    }

    @Test fun `invalid version or foreign update asset cannot report a successful check`() {
        assertFailsWith<IOException> {
            managerUpdateCandidate(APIResponse.Success(asset("invalid")), "1.35.0.4", false)
        }
        assertFailsWith<IOException> {
            managerUpdateCandidate(APIResponse.Success(asset("1.35.0.5").copy(downloadUrl = "https://example.com/app.apk")), "1.35.0.4", false)
        }
    }
}
