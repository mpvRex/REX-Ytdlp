package xyz.mpv.rex.addon.ytdl.model

enum class YtdlpReleaseChannel {
    STABLE,
    NIGHTLY,
    MASTER,
    CUSTOM,
    UNKNOWN;

    companion object {
        fun resolve(
            channel: String?,
            origin: String?,
            version: String?,
        ): YtdlpReleaseChannel {
            val normalizedChannel = channel.orEmpty().trim().lowercase()
            val normalizedOrigin = origin.orEmpty().trim().lowercase()
            return when {
                normalizedChannel == "stable" || normalizedOrigin == "yt-dlp/yt-dlp" -> STABLE
                normalizedChannel == "nightly" || normalizedOrigin == "yt-dlp/yt-dlp-nightly-builds" -> NIGHTLY
                normalizedChannel == "master" || normalizedOrigin == "yt-dlp/yt-dlp-master-builds" -> MASTER
                normalizedChannel.isNotEmpty() || normalizedOrigin.isNotEmpty() -> CUSTOM
                version.orEmpty().count { it == '.' } >= 3 -> NIGHTLY
                !version.isNullOrBlank() -> STABLE
                else -> UNKNOWN
            }
        }
    }
}

data class YtdlpInstallationInfo(
    val isInstalled: Boolean,
    val version: String? = null,
    val channel: YtdlpReleaseChannel = YtdlpReleaseChannel.UNKNOWN,
    val commitHash: String? = null,
    val origin: String? = null,
    val variant: String? = null,
) {
    val shortCommitHash: String?
        get() = commitHash?.take(8)

    companion object {
        val NotInstalled = YtdlpInstallationInfo(isInstalled = false)
    }
}
