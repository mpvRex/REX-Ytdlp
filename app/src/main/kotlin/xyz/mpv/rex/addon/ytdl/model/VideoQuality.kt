package xyz.mpv.rex.addon.ytdl.model

/**
 * Represents a video or audio stream quality option extracted from yt-dlp.
 */
data class VideoQuality(
    val id: String,
    val label: String,
    val height: Int = 0,
    val width: Int = 0,
    val fps: Int = 0,
    val codec: String? = null,
    val bitrate: Long = 0L,
    val videoUrl: String? = null,
    val audioUrl: String? = null,
    val isDASH: Boolean = false,
    val isAudioOnly: Boolean = false,
)
