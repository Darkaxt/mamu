package app.morphe.manager.util

/** Only explicit terminal Android ABI names constrain a version; arbitrary suffixes stay opaque. */
private val VersionArchitecture = Regex("""(?:^|[-_])(arm64-v8a|armeabi-v7a|x86_64|x86)$""", RegexOption.IGNORE_CASE)

fun versionArchitecture(version: String?): String? = version?.let {
    VersionArchitecture.find(it)?.groupValues?.get(1)?.lowercase()
}

fun versionSupportsAbis(version: String?, abis: List<String>): Boolean =
    versionArchitecture(version)?.let { it in abis } ?: true
