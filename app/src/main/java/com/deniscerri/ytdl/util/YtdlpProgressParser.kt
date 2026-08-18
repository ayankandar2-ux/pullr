package com.deniscerri.ytdl.util

/**
 * Parses yt-dlp's default `--newline` progress output, e.g.:
 *   [download]  45.2% of   10.00MiB at    1.20MiB/s ETA 00:08
 *   [download]  45.2% of ~  10.00MiB at    1.20MiB/s ETA 00:08 (frag 3/12)
 *   [download] 100% of   10.00MiB in 00:08
 * into structured pieces so the UI can show them individually instead of the raw line.
 */
object YtdlpProgressParser {

    data class ProgressInfo(
        val percent: Float?,
        val totalSize: String?,
        val speed: String?,
        val eta: String?
    )

    private val regex = Regex(
        """\[download]\s+(\d+\.?\d*)%\s+of\s+~?\s*([\d.]+\s?\w+)(?:\s+at\s+([\d.]+\s?\w+/s|Unknown speed))?(?:\s+(?:ETA|in)\s+([\d:]+))?"""
    )

    fun parse(line: String): ProgressInfo? {
        val match = regex.find(line) ?: return null
        val percent = match.groupValues.getOrNull(1)?.toFloatOrNull()
        val totalSize = match.groupValues.getOrNull(2)?.trim()?.ifBlank { null }
        val speedRaw = match.groupValues.getOrNull(3)?.trim()?.ifBlank { null }
        val speed = speedRaw?.takeUnless { it.equals("Unknown speed", ignoreCase = true) }
        val eta = match.groupValues.getOrNull(4)?.trim()?.ifBlank { null }
        return ProgressInfo(percent, totalSize, speed, eta)
    }

    /**
     * Builds a compact single-line summary, e.g. "34.10 MB • 1.2 MB/s • ETA 00:12".
     * Omits any piece that wasn't present in the source line.
     */
    fun buildSummary(info: ProgressInfo?): String {
        if (info == null) return ""
        val parts = mutableListOf<String>()
        info.totalSize?.let { parts.add(it) }
        info.speed?.let { parts.add(it) }
        info.eta?.let { parts.add("ETA $it") }
        return parts.joinToString(" • ")
    }
}
