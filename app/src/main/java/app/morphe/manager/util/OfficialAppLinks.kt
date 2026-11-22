package app.morphe.manager.util

import java.net.URLEncoder

/** Call with the original identity recorded by patching, rather than a renamed installation. */
fun officialPlayStoreUrl(originalPackageName: String): String =
    "https://play.google.com/store/apps/details?id=${URLEncoder.encode(originalPackageName, "UTF-8")}"
