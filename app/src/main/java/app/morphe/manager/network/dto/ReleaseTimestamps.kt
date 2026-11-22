/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.network.dto

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Release timestamps from manifests and APIs represent UTC, with or without an explicit offset. */
internal fun parseTimestamp(timestamp: String): LocalDateTime {
    val normalized = when {
        timestamp.endsWith("Z", ignoreCase = true) -> timestamp
        timestamp.contains("+") -> timestamp
        timestamp.contains("T") && timestamp.lastIndexOf("-") > 10 -> timestamp
        else -> "${timestamp}Z"
    }
    return OffsetDateTime.parse(normalized).atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
}
