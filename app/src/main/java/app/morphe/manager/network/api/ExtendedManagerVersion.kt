package app.morphe.manager.network.api

/** Numeric fork revisions, with legacy labels retained only to migrate installed releases. */
internal fun compareExtendedManagerVersions(first: String, second: String): Int? {
    val pattern = Regex("""^(\d+)\.(\d+)\.(\d+)(?:\.(\d+)|-extended\.(\d+)|-(dev|alpha|beta|rc)\.(\d+))?$""")
    fun parse(value: String): Pair<List<java.math.BigInteger>, List<java.math.BigInteger>>? {
        val match = pattern.matchEntire(value.trim().removePrefix("v")) ?: return null
        val groups = match.groupValues
        val core = groups.subList(1, 4).map(String::toBigInteger)
        val revision = (groups[4].ifEmpty { groups[5] }.ifEmpty { "0" }).toBigInteger()
        val qualifier = when (groups[6]) { "dev" -> 0; "alpha" -> 1; "beta" -> 2; "rc" -> 3; else -> 4 }
        return (core + revision) to listOf(qualifier.toBigInteger(), groups[7].ifEmpty { "0" }.toBigInteger())
    }
    val a = parse(first) ?: return null
    val b = parse(second) ?: return null
    for ((left, right) in (a.first + a.second).zip(b.first + b.second)) {
        val comparison = left.compareTo(right)
        if (comparison != 0) return comparison
    }
    return 0
}
