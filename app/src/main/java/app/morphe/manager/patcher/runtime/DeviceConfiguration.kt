package app.morphe.manager.patcher.runtime

import android.content.res.Resources
import android.os.Build
import app.morphe.manager.patcher.DeviceConfig

/** System facts shared by interactive patching and queued original preparation. */
internal fun currentDeviceConfig(): DeviceConfig {
    val system = Resources.getSystem()
    val locales = system.configuration.locales
    return DeviceConfig(
        supportedAbis = Build.SUPPORTED_ABIS.filter { it.isNotBlank() },
        localeTags = (0 until locales.size()).map { locales[it].toLanguageTag() },
        densityDpi = system.displayMetrics.densityDpi
    )
}
