package xyz.mpv.rex.addon.ytdl.model

data class StreamResolutionResult(
    val isSuccess: Boolean,
    val videoUrl: String? = null,
    val audioUrl: String? = null,
    val title: String? = null,
    val durationSeconds: Int = 0,
    val thumbnailUrl: String? = null,
    val uploader: String? = null,
    val httpHeaders: Map<String, String> = emptyMap(),
    val subtitles: Map<String, String> = emptyMap(),
    val errorMessage: String? = null,
) {
    companion object {
        fun failure(message: String): StreamResolutionResult =
            StreamResolutionResult(
                isSuccess = false,
                errorMessage = message,
            )
    }
}

data class PlaylistEntry(
    val id: String?,
    val url: String,
    val title: String,
    val artist: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Int,
)

data class PlaylistResult(
    val isSuccess: Boolean,
    val sourceUrl: String,
    val title: String? = null,
    val entries: List<PlaylistEntry> = emptyList(),
    val errorMessage: String? = null,
) {
    companion object {
        fun failure(sourceUrl: String, message: String): PlaylistResult =
            PlaylistResult(
                isSuccess = false,
                sourceUrl = sourceUrl,
                errorMessage = message,
            )
    }
}
