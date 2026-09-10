package xyz.mpv.rex.addon.ytdl.ipc

import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import xyz.mpv.rex.addon.ytdl.model.ExtractionOptions
import xyz.mpv.rex.addon.ytdl.model.PlaylistEntry
import xyz.mpv.rex.addon.ytdl.model.PlaylistResult
import xyz.mpv.rex.addon.ytdl.model.StreamResolutionResult
import xyz.mpv.rex.addon.ytdl.model.YtdlpInstallationInfo
import xyz.mpv.rex.addon.ytdl.model.YtdlpReleaseChannel

object IpcBundleConverter {
    const val INTERFACE_VERSION = 1

    // Status Keys
    const val KEY_IS_INSTALLED = "is_installed"
    const val KEY_VERSION = "version"
    const val KEY_CHANNEL = "channel"
    const val KEY_COMMIT_HASH = "commit_hash"
    const val KEY_ORIGIN = "origin"
    const val KEY_VARIANT = "variant"

    // Options Keys
    const val KEY_OPT_FORMAT = "opt_format"
    const val KEY_OPT_USER_AGENT = "opt_user_agent"
    const val KEY_OPT_REFERER = "opt_referer"
    const val KEY_OPT_PROXY = "opt_proxy"
    const val KEY_OPT_COOKIES_PATH = "opt_cookies_path"
    const val KEY_OPT_EXTRACTOR_ARGS = "opt_extractor_args"
    const val KEY_OPT_GEO_BYPASS = "opt_geo_bypass"

    // Stream Result Keys
    const val KEY_IS_SUCCESS = "is_success"
    const val KEY_VIDEO_URL = "video_url"
    const val KEY_AUDIO_URL = "audio_url"
    const val KEY_TITLE = "title"
    const val KEY_DURATION = "duration"
    const val KEY_THUMBNAIL = "thumbnail"
    const val KEY_UPLOADER = "uploader"
    const val KEY_HTTP_HEADERS = "http_headers" // Bundle of String -> String
    const val KEY_SUBTITLES = "subtitles"       // Bundle of String -> String
    const val KEY_ERROR_MESSAGE = "error_message"

    // Playlist Result Keys
    const val KEY_SOURCE_URL = "source_url"
    const val KEY_ENTRIES_JSON = "entries_json"

    fun toBundle(status: YtdlpInstallationInfo): Bundle = Bundle().apply {
        putBoolean(KEY_IS_INSTALLED, status.isInstalled)
        putString(KEY_VERSION, status.version)
        putString(KEY_CHANNEL, status.channel.name)
        putString(KEY_COMMIT_HASH, status.commitHash)
        putString(KEY_ORIGIN, status.origin)
        putString(KEY_VARIANT, status.variant)
    }

    fun toExtractionOptions(bundle: Bundle?): ExtractionOptions {
        if (bundle == null) return ExtractionOptions()
        return ExtractionOptions(
            format = bundle.getString(KEY_OPT_FORMAT),
            userAgent = bundle.getString(KEY_OPT_USER_AGENT),
            referer = bundle.getString(KEY_OPT_REFERER),
            proxy = bundle.getString(KEY_OPT_PROXY),
            cookiesFilePath = bundle.getString(KEY_OPT_COOKIES_PATH),
            extractorArgs = bundle.getString(KEY_OPT_EXTRACTOR_ARGS),
            geoBypass = bundle.getBoolean(KEY_OPT_GEO_BYPASS, true),
        )
    }

    fun toBundle(result: StreamResolutionResult): Bundle = Bundle().apply {
        putBoolean(KEY_IS_SUCCESS, result.isSuccess)
        putString(KEY_VIDEO_URL, result.videoUrl)
        putString(KEY_AUDIO_URL, result.audioUrl)
        putString(KEY_TITLE, result.title)
        putInt(KEY_DURATION, result.durationSeconds)
        putString(KEY_THUMBNAIL, result.thumbnailUrl)
        putString(KEY_UPLOADER, result.uploader)
        putString(KEY_ERROR_MESSAGE, result.errorMessage)

        val headersBundle = Bundle()
        result.httpHeaders.forEach { (k, v) -> headersBundle.putString(k, v) }
        putBundle(KEY_HTTP_HEADERS, headersBundle)

        val subsBundle = Bundle()
        result.subtitles.forEach { (k, v) -> subsBundle.putString(k, v) }
        putBundle(KEY_SUBTITLES, subsBundle)
    }

    fun toBundle(result: PlaylistResult): Bundle = Bundle().apply {
        putBoolean(KEY_IS_SUCCESS, result.isSuccess)
        putString(KEY_SOURCE_URL, result.sourceUrl)
        putString(KEY_TITLE, result.title)
        putString(KEY_ERROR_MESSAGE, result.errorMessage)

        val jsonArray = JSONArray()
        result.entries.forEach { entry ->
            val obj = JSONObject().apply {
                put("id", entry.id)
                put("url", entry.url)
                put("title", entry.title)
                put("artist", entry.artist)
                put("thumbnail", entry.thumbnailUrl)
                put("duration", entry.durationSeconds)
            }
            jsonArray.put(obj)
        }
        putString(KEY_ENTRIES_JSON, jsonArray.toString())
    }
}
