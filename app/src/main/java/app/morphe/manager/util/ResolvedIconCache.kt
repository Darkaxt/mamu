package app.morphe.manager.util

import android.content.pm.PackageInfo
import android.graphics.drawable.Drawable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** Only already-resolved values: looking here must never decode a lazy Drawable. */
internal data class ResolvedIconSnapshot(val packageInfo: PackageInfo?, val drawable: Drawable?, val playPackage: String?)

internal class ResolvedIconCache {
    private val entries = ConcurrentHashMap<Pair<String, AppDataSource>, ResolvedIconSnapshot>()
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    fun peek(packageName: String, source: AppDataSource) = entries[packageName to source]

    @Synchronized fun publish(packageName: String, source: AppDataSource, value: ResolvedIconSnapshot, revision: Long) {
        if (_revision.value == revision) entries[packageName to source] = value
    }
    @Synchronized fun invalidate(packageName: String? = null, clearResolverCaches: () -> Unit = {}) {
        // Resolve readers/publications share this short cache-only monitor. Notify after clearing
        // all layers, so a new-generation lookup cannot observe old resolver entries.
        clearResolverCaches()
        if (packageName == null) entries.clear() else entries.keys.removeAll { it.first == packageName }
        _revision.value += 1
    }
}
