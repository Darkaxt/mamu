/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.domain.manager

import android.os.Build
import android.util.Log
import app.morphe.manager.downloaders.ApkMirrorSearch
import app.morphe.manager.domain.bundles.BundledAppTarget
import app.morphe.manager.network.api.ReleaseAPI
import app.morphe.manager.domain.bundles.PatchSourceHeaders
import app.morphe.manager.util.KnownApps
import app.morphe.manager.util.LOG_TAG
import app.morphe.manager.util.MORPHE_API_URL
import app.morphe.patcher.patch.FormFactor
import app.morphe.patcher.patch.SupportedAbi
import io.ktor.http.encodeURLPath
import java.net.URI

/**
 * Works out where a specific version of an app can be downloaded from.
 */
class DownloadUrlResolver(private val releaseAPI: ReleaseAPI) {

    /**
     * Resolves the download page for [packageName] at [version], falling back to native
     * APKMirror search when the API cannot point anywhere useful.
     *
     * Every redirect along the way is followed: the API sometimes points at itself once more,
     * and the download sites redirect for reasons of their own, so only the end of the chain
     * says what the user will actually get.
     *
     * @param patchSources The sources the app is patched from, see
     *   [app.morphe.manager.domain.repository.PatchBundleRepository.patchSourceHeaders]. The API
     *   counts requests by them.
     * @param target The source version [version] comes from, see [apiSearchUrl].
     */
    suspend fun resolve(packageName: String, version: String?, patchSources: PatchSourceHeaders? = null,
                        target: BundledAppTarget? = null): String {
        val url = follow(packageName, version, target) { releaseAPI.resolveRedirect(it, patchSources) }
        return ApkMirrorSearch.translate(url, packageName, version)
    }

    /**
     * Follows the API answer for [target]'s exact build. The API only knows some build codes: for
     * an unknown one it falls back to a web search, which on APKMirror lists every variant sharing
     * that code (often only split bundles). The version and ABI alone can still lead to a full
     * APK, so that query is asked before giving up on the API.
     */
    internal suspend fun follow(
        packageName: String,
        version: String?,
        target: BundledAppTarget?,
        redirect: suspend (String) -> String?
    ): String {
        val exact = apiSearchUrl(packageName, version, target)
        val plain = apiSearchUrl(packageName, version, target, includeBuildCode = false)
        val resolved = followApi(exact, redirect)
            ?: plain.takeIf { it != exact }?.let {
                Log.i(LOG_TAG, "The API does not know this build, asking for the version: $it")
                followApi(it, redirect)
            }
        return resolved?.let { finalUrl(it, packageName, version) } ?: webSearchUrl(packageName, version)
    }

    /** Where the API leads, or null when it only offers a web search or no answer at all. */
    private suspend fun followApi(searchUrl: String, redirect: suspend (String) -> String?): String? {
        Log.d(LOG_TAG, "Using search url: $searchUrl")

        var resolved = redirect(searchUrl) ?: run {
            Log.w(LOG_TAG, "No redirect location for: $searchUrl")
            return null
        }

        repeat(MAX_REDIRECTS) {
            // Recognize the API's fallback before issuing any request to Google.
            if (isGoogleSearch(resolved)) return null
            // A URL that redirects nowhere is the end of the chain
            val next = redirect(resolved) ?: return resolved
            Log.i(LOG_TAG, "Following redirect to: $next")
            resolved = next
        }

        Log.w(LOG_TAG, "Gave up after $MAX_REDIRECTS redirects")
        return null
    }

    /**
     * The unfollowed API URL, standing in for the destination until [resolve] has one.
     *
     * @param target The source version [version] comes from. Its build code for the ABI of this
     *   device and its form factor, other than a phone, are added to the query, so the API answers
     *   with that build. Ignored when it is for another version.
     * @param includeBuildCode False leaves the build code out, keeping the form factor.
     */
    fun apiSearchUrl(packageName: String, version: String?, target: BundledAppTarget? = null,
                     includeBuildCode: Boolean = true): String {
        val abi = Build.SUPPORTED_ABIS.first()
        val parts = mutableListOf(packageName, version ?: "any", abi)
        if (target != null && version != null && target.target.version == version) {
            if (includeBuildCode) supportedAbi(abi)?.let { target.abiBuildCodes?.get(it) }?.let { parts += it.toString() }
            target.formFactor?.takeIf { it != FormFactor.MOBILE }?.let { parts += it.name.lowercase() }
        }
        val query = parts.joinToString("~").encodeURLPath()
        return "$MORPHE_API_URL/v2/web-search/$query"
    }

    /** Used when the API is unreachable, so the user still lands on something useful. */
    fun webSearchUrl(packageName: String, version: String?): String {
        val constraints = setOf(if (packageName == KnownApps.YOUTUBE_MUSIC) Build.SUPPORTED_ABIS.first() else "nodpi")
        return ApkMirrorSearch.nativeSearch(packageName, version, constraints)
    }

    private fun isGoogleSearch(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.host in setOf("google.com", "www.google.com") && uri.path == "/search"
    }.getOrDefault(false)

    /** Hands back [url], or a web search when it cannot lead to the version that was asked for. */
    private fun finalUrl(url: String, packageName: String, version: String?): String {
        // Uptodown answers a retired download id with its "latest version" page, which is not
        // the build the patches need. Only its per-build pages end in "-x"
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val onUptodown = uri.host?.lowercase()?.endsWith("uptodown.com") == true
        if (onUptodown && !uri.path.orEmpty().trimEnd('/').endsWith("-x")) {
            Log.w(LOG_TAG, "Uptodown link lost its build, searching instead: $url")
            return webSearchUrl(packageName, version)
        }
        return url
    }

    private companion object {
        // Enough for the API pointing at itself and a site or two moving the page, while still
        // ending a redirect loop
        const val MAX_REDIRECTS = 5

        /** Match the patcher's ABI keys to the actual device ABI. */
        fun supportedAbi(abi: String): SupportedAbi? = when (abi) {
            "arm64-v8a" -> SupportedAbi.ARM64_V8A
            "armeabi-v7a", "armeabi" -> SupportedAbi.ARMEABI_V7A
            "x86_64" -> SupportedAbi.X86_64
            "x86" -> SupportedAbi.X86
            else -> null
        }
    }
}
