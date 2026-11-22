package app.morphe.manager.patcher.patch

import org.json.JSONObject
import java.util.Locale

/** Shared data file is also audited against the real catalogue by dump_patch_catalogue.py. */
object UniversalPatchCategories {
    private val data by lazy { JSONObject(checkNotNull(javaClass.classLoader?.getResourceAsStream(
        "universal-categories.json")).bufferedReader().use { it.readText() }) }
    private data class Rule(val name: String, val aliases: List<String>, val pattern: Regex)
    private val rules by lazy {
        val array = data.getJSONArray("categories")
        (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            val aliases = row.getJSONArray("aliases")
            Rule(row.getString("name"), (0 until aliases.length()).map(aliases::getString),
                Regex(row.getString("pattern"), RegexOption.IGNORE_CASE))
        }
    }
    fun category(name: String, description: String?, declared: String?): String {
        val normalized = declared.orEmpty().trim().replace(Regex("^[^\\p{L}\\p{N}_]+"), "").lowercase(Locale.ROOT)
        rules.firstOrNull { normalized == it.name.lowercase(Locale.ROOT) || normalized in it.aliases }
            ?.let { return it.name }
        listOf(name, description.orEmpty()).forEach { text ->
            rules.firstOrNull { it.pattern.containsMatchIn(text) }?.let { return it.name }
        }
        if (normalized.isNotEmpty() && normalized !in setOf("misc", "general", "featured", "disable",
                "enable", "force", "hide", "ignore", "allow", "bypass", "fake", "spoof", "prompts"))
            return checkNotNull(declared).trim()
        return data.getString("fallback")
    }
    fun category(patch: PatchInfo) = category(patch.displayName, patch.description, patch.category)
}
