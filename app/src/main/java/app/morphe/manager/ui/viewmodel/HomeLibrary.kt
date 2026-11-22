package app.morphe.manager.ui.viewmodel

import androidx.annotation.StringRes
import app.morphe.manager.R
import app.morphe.manager.network.PlayStoreListing
import app.morphe.manager.ui.model.HomeAppItem
import app.morphe.manager.ui.screen.home.HomeAppFilterMode

enum class StoreAppType(@param:StringRes val label: Int) {
    ALL(R.string.library_all), APPS(R.string.library_apps), GAMES(R.string.library_games), OTHERS(R.string.library_others)
}

/** Stable identifiers from the original package's Play listing; labels use app resources. */
enum class StoreCategory(val storeId: String, val type: StoreAppType, @param:StringRes val label: Int) {
    ART("ART_AND_DESIGN", StoreAppType.APPS, R.string.store_art),
    AUTO("AUTO_AND_VEHICLES", StoreAppType.APPS, R.string.store_auto),
    BEAUTY("BEAUTY", StoreAppType.APPS, R.string.store_beauty),
    BOOKS("BOOKS_AND_REFERENCE", StoreAppType.APPS, R.string.store_books),
    BUSINESS("BUSINESS", StoreAppType.APPS, R.string.store_business),
    COMICS("COMICS", StoreAppType.APPS, R.string.store_comics),
    COMMUNICATION("COMMUNICATION", StoreAppType.APPS, R.string.store_communication),
    DATING("DATING", StoreAppType.APPS, R.string.store_dating),
    EDUCATION("EDUCATION", StoreAppType.APPS, R.string.store_education),
    ENTERTAINMENT("ENTERTAINMENT", StoreAppType.APPS, R.string.store_entertainment),
    EVENTS("EVENTS", StoreAppType.APPS, R.string.store_events),
    FINANCE("FINANCE", StoreAppType.APPS, R.string.store_finance),
    FOOD("FOOD_AND_DRINK", StoreAppType.APPS, R.string.store_food),
    HEALTH("HEALTH_AND_FITNESS", StoreAppType.APPS, R.string.store_health),
    HOUSE("HOUSE_AND_HOME", StoreAppType.APPS, R.string.store_house),
    LIBRARIES("LIBRARIES_AND_DEMO", StoreAppType.APPS, R.string.store_libraries),
    LIFESTYLE("LIFESTYLE", StoreAppType.APPS, R.string.store_lifestyle),
    MAPS("MAPS_AND_NAVIGATION", StoreAppType.APPS, R.string.store_maps),
    MEDICAL("MEDICAL", StoreAppType.APPS, R.string.store_medical),
    MUSIC("MUSIC_AND_AUDIO", StoreAppType.APPS, R.string.store_music),
    NEWS("NEWS_AND_MAGAZINES", StoreAppType.APPS, R.string.store_news),
    PARENTING("PARENTING", StoreAppType.APPS, R.string.store_parenting),
    PERSONALIZATION("PERSONALIZATION", StoreAppType.APPS, R.string.store_personalization),
    PHOTOGRAPHY("PHOTOGRAPHY", StoreAppType.APPS, R.string.store_photography),
    PRODUCTIVITY("PRODUCTIVITY", StoreAppType.APPS, R.string.store_productivity),
    SHOPPING("SHOPPING", StoreAppType.APPS, R.string.store_shopping),
    SOCIAL("SOCIAL", StoreAppType.APPS, R.string.store_social),
    SPORTS("SPORTS", StoreAppType.APPS, R.string.store_sports),
    TOOLS("TOOLS", StoreAppType.APPS, R.string.store_tools),
    TRAVEL("TRAVEL_AND_LOCAL", StoreAppType.APPS, R.string.store_travel),
    VIDEO("VIDEO_PLAYERS", StoreAppType.APPS, R.string.store_video),
    WEATHER("WEATHER", StoreAppType.APPS, R.string.store_weather),
    ACTION("GAME_ACTION", StoreAppType.GAMES, R.string.store_action),
    ADVENTURE("GAME_ADVENTURE", StoreAppType.GAMES, R.string.store_adventure),
    ARCADE("GAME_ARCADE", StoreAppType.GAMES, R.string.store_arcade),
    BOARD("GAME_BOARD", StoreAppType.GAMES, R.string.store_board),
    CARD("GAME_CARD", StoreAppType.GAMES, R.string.store_card),
    CASINO("GAME_CASINO", StoreAppType.GAMES, R.string.store_casino),
    CASUAL("GAME_CASUAL", StoreAppType.GAMES, R.string.store_casual),
    EDUCATIONAL("GAME_EDUCATIONAL", StoreAppType.GAMES, R.string.store_educational),
    GAME_MUSIC("GAME_MUSIC", StoreAppType.GAMES, R.string.store_music_games),
    PUZZLE("GAME_PUZZLE", StoreAppType.GAMES, R.string.store_puzzle),
    RACING("GAME_RACING", StoreAppType.GAMES, R.string.store_racing),
    ROLE_PLAYING("GAME_ROLE_PLAYING", StoreAppType.GAMES, R.string.store_role_playing),
    SIMULATION("GAME_SIMULATION", StoreAppType.GAMES, R.string.store_simulation),
    GAME_SPORTS("GAME_SPORTS", StoreAppType.GAMES, R.string.store_sports_games),
    STRATEGY("GAME_STRATEGY", StoreAppType.GAMES, R.string.store_strategy),
    TRIVIA("GAME_TRIVIA", StoreAppType.GAMES, R.string.store_trivia),
    WORD("GAME_WORD", StoreAppType.GAMES, R.string.store_word);

    companion object {
        private val byId = entries.associateBy { it.storeId }
        fun of(listing: PlayStoreListing?) = byId[listing?.categoryId]
    }
}

data class HomeLibrarySelection(val query: String = "", val type: StoreAppType = StoreAppType.ALL,
    val category: StoreCategory? = null)
data class HomeLibraryProjection(val visible: List<HomeAppItem>, val hidden: List<HomeAppItem>,
    val typeCounts: Map<StoreAppType, Int>, val categoryCounts: Map<StoreCategory?, Int>,
    val selection: HomeLibrarySelection)

/** Run on Default. Iteration preserves the existing recommendation/manual order and clone row IDs. */
internal fun projectHomeLibrary(visible: List<HomeAppItem>, hidden: List<HomeAppItem>,
    listings: Map<String, PlayStoreListing>, selection: HomeLibrarySelection, filter: HomeAppFilterMode,
    checkActive: () -> Unit = {}): HomeLibraryProjection {
    val query = selection.query.trim()
    fun matches(item: HomeAppItem): Boolean {
        checkActive()
        return filter.matches(item) && (query.isEmpty() || item.displayName.contains(query, true) ||
            item.packageName.contains(query, true) || item.id.contains(query, true))
    }
    fun category(item: HomeAppItem) = StoreCategory.of(listings[item.packageName])
    fun type(item: HomeAppItem) = when {
        listings[item.packageName]?.categoryId?.startsWith("GAME_") == true -> StoreAppType.GAMES
        else -> category(item)?.type ?: StoreAppType.OTHERS
    }
    val candidates = visible.filter(::matches)
    val counts = linkedMapOf(StoreAppType.ALL to candidates.size)
    candidates.forEach { checkActive(); counts.merge(type(it), 1, Int::plus) }
    val selectedType = selection.type.takeIf { it == StoreAppType.ALL || (counts[it] ?: 0) > 0 } ?: StoreAppType.ALL
    val base = candidates.filter { checkActive(); selectedType == StoreAppType.ALL || type(it) == selectedType }
    // null denotes the All categories escape, including rows with unknown category metadata.
    val categories = linkedMapOf<StoreCategory?, Int>(null to base.size)
    base.forEach { item -> checkActive(); category(item)?.let { categories.merge(it, 1, Int::plus) } }
    val selectedCategory = selection.category?.takeIf { it in categories }
    fun inFacet(item: HomeAppItem): Boolean {
        checkActive()
        return (selectedType == StoreAppType.ALL || type(item) == selectedType) &&
            (selectedCategory == null || category(item) == selectedCategory)
    }
    return HomeLibraryProjection(base.filter(::inFacet),
        if (query.isEmpty()) emptyList() else hidden.filter { matches(it) && inFacet(it) },
        counts, categories, selection.copy(type = selectedType, category = selectedCategory))
}
