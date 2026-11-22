package app.morphe.manager.ui.viewmodel

import app.morphe.manager.network.PlayStoreListing
import app.morphe.manager.ui.model.HomeAppItem
import app.morphe.manager.ui.screen.home.HomeAppFilterMode
import kotlin.test.*

class HomeLibraryTest {
    @Test fun `superseded catalogue projection cooperatively stops before publishing`() {
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            projectHomeLibrary(rows, emptyList(), listings, HomeLibrarySelection(), HomeAppFilterMode.ALL,
                checkActive = { throw kotlinx.coroutines.CancellationException("new selection") })
        }
    }
    private fun row(pkg: String, name: String, id: String = pkg) = HomeAppItem(id, pkg, name,
        emptyList(), null, null, "1", false, false, false, false, false, false,
        null, false, null, 1, id != pkg)
    private val shopping = row("com.example.shopping", "Shopping")
    private val game = row("com.example.game", "Game")
    private val clone = row(game.packageName, "Game clone", "com.example.clone")
    private val unknown = row("com.example.unknown", "Unknown")
    private val rows = listOf(shopping, game, clone, unknown)
    private val listings = mapOf(shopping.packageName to PlayStoreListing(shopping.packageName, null, "SHOPPING"),
        game.packageName to PlayStoreListing(game.packageName, null, "GAME_ACTION"))

    @Test fun `populated facets count row identities and keep incoming recommendation order`() {
        val result = projectHomeLibrary(rows, emptyList(), listings, HomeLibrarySelection(), HomeAppFilterMode.ALL)
        assertEquals(rows, result.visible)
        assertEquals(mapOf(StoreAppType.ALL to 4, StoreAppType.APPS to 1, StoreAppType.GAMES to 2, StoreAppType.OTHERS to 1), result.typeCounts)
        assertEquals(2, result.categoryCounts[StoreCategory.ACTION])
        assertFalse(StoreCategory.TOOLS in result.categoryCounts)
        val selected = projectHomeLibrary(rows, emptyList(), listings, HomeLibrarySelection(type = StoreAppType.GAMES), HomeAppFilterMode.ALL)
        assertEquals(listOf(game, clone), selected.visible)
        assertEquals(mapOf(null to 2, StoreCategory.ACTION to 2), selected.categoryCounts)
    }

    @Test fun `search and state filters remove empty choices without trapping selection`() {
        val result = projectHomeLibrary(rows, emptyList(), listings,
            HomeLibrarySelection("Shopping", StoreAppType.GAMES, StoreCategory.ACTION), HomeAppFilterMode.ALL)
        assertEquals(HomeLibrarySelection("Shopping"), result.selection)
        assertEquals(listOf(shopping), result.visible)
        assertEquals(mapOf(StoreAppType.ALL to 1, StoreAppType.APPS to 1), result.typeCounts)
        val empty = projectHomeLibrary(rows, emptyList(), listings, HomeLibrarySelection(), HomeAppFilterMode.INSTALLED)
        assertEquals(mapOf(StoreAppType.ALL to 0), empty.typeCounts)
        assertEquals(mapOf<StoreCategory?, Int>(null to 0), empty.categoryCounts)
    }

    @Test fun `unknown metadata remains discoverable and hidden apps appear only during search`() {
        val result = projectHomeLibrary(rows, listOf(shopping.copy(id = "hidden")), listings,
            HomeLibrarySelection(type = StoreAppType.OTHERS), HomeAppFilterMode.ALL)
        assertEquals(listOf(unknown), result.visible)
        assertTrue(result.hidden.isEmpty())
        val hidden = projectHomeLibrary(rows, listOf(shopping.copy(id = "hidden")), listings,
            HomeLibrarySelection("Shopping"), HomeAppFilterMode.ALL)
        assertEquals("hidden", hidden.hidden.single().id)
        assertEquals(1, hidden.typeCounts[StoreAppType.ALL])
    }

    @Test fun `unlisted game subcategory retains Games type without inventing a category`() {
        val result = projectHomeLibrary(listOf(game), emptyList(),
            mapOf(game.packageName to PlayStoreListing(game.packageName, null, "GAME_NEW_CATEGORY")),
            HomeLibrarySelection(type = StoreAppType.GAMES), HomeAppFilterMode.ALL)
        assertEquals(listOf(game), result.visible)
        assertEquals(1, result.typeCounts[StoreAppType.GAMES])
        assertFalse(StoreAppType.OTHERS in result.typeCounts)
        assertEquals(mapOf<StoreCategory?, Int>(null to 1), result.categoryCounts)
    }
}
