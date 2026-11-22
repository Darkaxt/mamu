package app.morphe.manager.domain.bundles

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommunityCatalogueTest {
    @Test fun `live catalogue snapshot maps every supported repository`() {
        val text = checkNotNull(javaClass.getResource("/community-catalogue-live-2026-10-04.json")).readText()
        val catalogue = Json.decodeFromString<CommunityCatalogue>(text)
        val endpoints = catalogue.endpoints()
        assertEquals(209, endpoints.size)
        assertEquals(3, endpoints.count { it.startsWith("https://gitlab.com/") })
        assertEquals(209, endpoints.map(::communitySourceKey).toSet().size)
    }

    @Test fun `catalogue validates repository identities and deduplicates casing`() {
        val document = Json { ignoreUnknownKeys = true }.decodeFromString<CommunityCatalogue>("""
            {"bundles":[
              {"source":"github","repo":"Owner/Patches","name":"First"},
              {"source":"github","repo":"owner/patches"},
              {"source":"gitlab","repo":"Group/Patches"},
              {"source":"github","repo":"../Patches"},
              {"source":"github","repo":"owner/patches?token=secret"},
              {"source":"github","repo":"owner/patches/extra"},
              {"source":"other","repo":"owner/patches"}
            ],"store":{}}
        """.trimIndent())
        assertEquals(listOf(
            "https://raw.githubusercontent.com/Owner/Patches/main/patches-bundle.json",
            "https://gitlab.com/Group/Patches/-/raw/main/patches-bundle.json"
        ), document.endpoints())
    }

    @Test fun `repository key survives branch and URL representation changes`() {
        assertEquals("github=owner/patches", communitySourceKey("https://github.com/Owner/Patches"))
        assertEquals("github=owner/patches", communitySourceKey("https://raw.githubusercontent.com/owner/patches/dev/patches-bundle.json"))
        assertEquals("gitlab=group/patches", communitySourceKey("https://gitlab.com/Group/Patches/-/raw/main/patches-bundle.json"))
        assertNull(communitySourceKey("https://github.com.attacker.example/owner/patches"))
    }

    @Test fun `discovery retains existing sources and exclusions without changing them`() {
        val incoming = CommunityCatalogue(listOf(
            CommunitySource("github", "owner/existing"),
            CommunitySource("github", "owner/deleted"),
            CommunitySource("github", "owner/blocked"),
            CommunitySource("github", "owner/new")
        )).endpoints()
        assertEquals(listOf("https://raw.githubusercontent.com/owner/new/main/patches-bundle.json"),
            missingCommunitySources(incoming, setOf("github=owner/existing"),
                setOf("github=owner/deleted"), setOf("github=owner/blocked")))
        assertEquals(emptyList(), missingCommunitySources(incoming,
            setOf("github=owner/existing", "github=owner/new"),
            setOf("github=owner/deleted"), setOf("github=owner/blocked")))
        assertEquals(emptyList(), missingCommunitySources(emptyList(),
            setOf("github=owner/existing"), emptySet(), emptySet()))
    }
}
