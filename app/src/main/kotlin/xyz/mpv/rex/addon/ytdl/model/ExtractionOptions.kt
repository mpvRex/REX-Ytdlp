package xyz.mpv.rex.addon.ytdl.model

data class ExtractionOptions(
    val format: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val proxy: String? = null,
    val cookiesFilePath: String? = null,
    val extractorArgs: String? = null,
    val geoBypass: Boolean = true,
)
