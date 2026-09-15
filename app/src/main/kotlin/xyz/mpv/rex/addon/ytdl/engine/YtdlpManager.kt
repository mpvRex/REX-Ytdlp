package xyz.mpv.rex.addon.ytdl.engine

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import xyz.mpv.rex.addon.ytdl.model.ExtractionOptions
import xyz.mpv.rex.addon.ytdl.model.PlaylistEntry
import xyz.mpv.rex.addon.ytdl.model.PlaylistResult
import xyz.mpv.rex.addon.ytdl.model.StreamResolutionResult
import xyz.mpv.rex.addon.ytdl.model.VideoQuality
import xyz.mpv.rex.addon.ytdl.model.YtdlpInstallationInfo
import xyz.mpv.rex.addon.ytdl.model.YtdlpReleaseChannel
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object YtdlpManager {
    private const val TAG = "YtdlpManager"
    private const val YTDL_DIR = "ytdl"
    private const val PLAYBACK_RUNTIME_VERSION = "2"
    private const val PLAYBACK_RUNTIME_VERSION_FILE = "playback-runtime-version"
    private const val MAX_IMPORTED_PLAYLIST_ENTRIES = 5_000

    private val installMutex = Mutex()
    private val _installationInfo = MutableStateFlow<YtdlpInstallationInfo?>(null)
    val installationInfo: StateFlow<YtdlpInstallationInfo?> = _installationInfo.asStateFlow()

    @Volatile
    private var runtimeAssetsPrepared = false

    fun getYtdlDir(context: Context): File =
        File(context.filesDir, YTDL_DIR).apply { if (!exists()) mkdirs() }

    fun getQuickJsPath(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libqjs.so").absolutePath

    fun isInstalled(context: Context): Boolean {
        val ytDlp = File(getYtdlDir(context), "yt-dlp")
        return ytDlp.isFile && ytDlp.length() > 0L
    }

    private fun ensurePython(context: Context): Python {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        return Python.getInstance()
    }

    suspend fun refreshInstallationInfo(context: Context): YtdlpInstallationInfo =
        withContext(Dispatchers.IO) {
            installMutex.withLock {
                readInstallationInfo(context).also { info -> _installationInfo.value = info }
            }
        }

    suspend fun ensureReady(context: Context, onLog: (String) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            installMutex.withLock {
                if (!prepareRuntimeAssets(context, onLog)) return@withLock false
                if (isInstalled(context)) return@withLock true
                installYtdlp(context, nightly = false, onLog = onLog)
            }
        }

    suspend fun runInstall(context: Context, onLog: (String) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            installMutex.withLock {
                if (!prepareRuntimeAssets(context, onLog)) return@withLock false
                installYtdlp(context, nightly = false, onLog = onLog)
            }
        }

    suspend fun runUpdate(
        context: Context,
        nightly: Boolean = false,
        onLog: (String) -> Unit = {},
    ): Boolean =
        withContext(Dispatchers.IO) {
            installMutex.withLock {
                onLog(if (nightly) "Updating yt-dlp to nightly channel...\n" else "Updating yt-dlp...\n")
                installYtdlp(context, nightly = nightly, onLog = onLog)
            }
        }

    suspend fun resolveStream(
        context: Context,
        url: String,
        options: ExtractionOptions = ExtractionOptions(),
        onLog: (String) -> Unit = {},
    ): StreamResolutionResult =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureReady(context, onLog)) {
                    return@withContext StreamResolutionResult.failure("yt-dlp engine could not be initialized")
                }

                val ytdlFile = File(getYtdlDir(context), "yt-dlp")
                val quickJs = File(getQuickJsPath(context))
                val certFile = File(context.filesDir, "cacert.pem")

                val cliArgs = buildList {
                    add("--ignore-config")
                    add("--no-playlist")
                    add("--dump-single-json")
                    add("--skip-download")
                    add("--no-warnings")
                    add("--no-progress")
                    add("--ignore-errors")

                    if (quickJs.isFile) {
                        add("--js-runtimes")
                        add("quickjs:${quickJs.absolutePath}")
                    }

                    val formatStr = options.format?.takeIf(String::isNotBlank) ?: "bestvideo+bestaudio/best"
                    add("--format")
                    add(formatStr)

                    options.userAgent?.takeIf(String::isNotBlank)?.let {
                        add("--user-agent")
                        add(it)
                    }
                    options.referer?.takeIf(String::isNotBlank)?.let {
                        add("--referer")
                        add(it)
                    }
                    options.proxy?.takeIf(String::isNotBlank)?.let {
                        add("--proxy")
                        add(it)
                    }
                    options.extractorArgs?.takeIf(String::isNotBlank)?.let {
                        add("--extractor-args")
                        add(it)
                    }
                    if (options.geoBypass) {
                        add("--geo-bypass")
                    }
                    options.cookiesFilePath?.takeIf(String::isNotBlank)?.let {
                        add("--cookies")
                        add(it)
                    }
                }

                val py = ensurePython(context)
                val rexYtdl = py.getModule("rex_ytdl")
                val cliArgsJson = JSONArray(cliArgs).toString()

                val jsonOutput = rexYtdl.callAttr(
                    "extract_media",
                    ytdlFile.absolutePath,
                    if (quickJs.isFile) quickJs.absolutePath else null,
                    url,
                    cliArgsJson,
                    if (certFile.isFile) certFile.absolutePath else null
                )?.toString()

                if (jsonOutput.isNullOrBlank()) {
                    return@withContext StreamResolutionResult.failure("yt-dlp returned empty metadata")
                }

                parseStreamMetadata(jsonOutput)
                    ?: StreamResolutionResult.failure("Unable to parse stream metadata from yt-dlp output")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve stream", e)
                StreamResolutionResult.failure("Extraction error: ${e.message}")
            }
        }

    suspend fun extractPlaylist(
        context: Context,
        url: String,
        options: ExtractionOptions = ExtractionOptions(),
        onLog: (String) -> Unit = {},
    ): PlaylistResult =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureReady(context, onLog)) {
                    return@withContext PlaylistResult.failure(url, "yt-dlp engine is not ready")
                }

                val ytdlFile = File(getYtdlDir(context), "yt-dlp")
                val quickJs = File(getQuickJsPath(context))
                val certFile = File(context.filesDir, "cacert.pem")

                val cliArgs = buildList {
                    add("--ignore-config")
                    add("--flat-playlist")
                    add("--dump-single-json")
                    add("--yes-playlist")
                    add("--skip-download")
                    add("--no-warnings")
                    add("--no-progress")
                    add("--ignore-errors")
                    add("--playlist-end")
                    add(MAX_IMPORTED_PLAYLIST_ENTRIES.toString())

                    if (quickJs.isFile) {
                        add("--js-runtimes")
                        add("quickjs:${quickJs.absolutePath}")
                    }

                    options.userAgent?.takeIf(String::isNotBlank)?.let {
                        add("--user-agent")
                        add(it)
                    }
                    options.referer?.takeIf(String::isNotBlank)?.let {
                        add("--referer")
                        add(it)
                    }
                    options.proxy?.takeIf(String::isNotBlank)?.let {
                        add("--proxy")
                        add(it)
                    }
                    options.cookiesFilePath?.takeIf(String::isNotBlank)?.let {
                        add("--cookies")
                        add(it)
                    }
                    if (options.geoBypass) {
                        add("--geo-bypass")
                    }
                }

                val py = ensurePython(context)
                val rexYtdl = py.getModule("rex_ytdl")
                val cliArgsJson = JSONArray(cliArgs).toString()

                val jsonOutput = rexYtdl.callAttr(
                    "extract_media",
                    ytdlFile.absolutePath,
                    if (quickJs.isFile) quickJs.absolutePath else null,
                    url,
                    cliArgsJson,
                    if (certFile.isFile) certFile.absolutePath else null
                )?.toString()

                if (jsonOutput.isNullOrBlank()) {
                    return@withContext PlaylistResult.failure(url, "yt-dlp returned empty playlist output")
                }

                parsePlaylistMetadata(url, jsonOutput)
                    ?: PlaylistResult.failure(url, "No playlist items found")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract playlist", e)
                PlaylistResult.failure(url, "Playlist extraction error: ${e.message}")
            }
        }

    private fun parseStreamMetadata(payload: String): StreamResolutionResult? {
        return runCatching {
            val root = JSONObject(payload)
            val title = root.optionalString("title")
            val duration = root.optDouble("duration", 0.0).toInt()
            val thumbnail = root.optionalString("thumbnail")
            val uploader = root.optionalString("uploader") ?: root.optionalString("channel")

            val headersMap = mutableMapOf<String, String>()
            val safeHeaderNames = setOf("User-Agent", "Referer", "Cookie", "Origin")
            root.optJSONObject("http_headers")?.let { headersJson ->
                val keys = headersJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (safeHeaderNames.any { it.equals(key, ignoreCase = true) }) {
                        headersJson.optString(key).takeIf(String::isNotBlank)?.let { value ->
                            headersMap[key] = value
                        }
                    }
                }
            }

            val subtitlesMap = mutableMapOf<String, String>()
            root.optJSONObject("subtitles")?.let { subsJson ->
                val langs = subsJson.keys()
                while (langs.hasNext()) {
                    val lang = langs.next()
                    subsJson.optJSONArray(lang)?.let { tracks ->
                        for (i in 0 until tracks.length()) {
                            val trackObj = tracks.optJSONObject(i) ?: continue
                            val ext = trackObj.optString("ext")
                            val subUrl = trackObj.optionalString("url")
                            if (subUrl != null && (ext == "vtt" || ext == "srt")) {
                                subtitlesMap[lang] = subUrl
                                break
                            }
                        }
                    }
                }
            }

            var videoUrl: String? = null
            var audioUrl: String? = null

            val requestedFormats = root.optJSONArray("requested_formats")
            if (requestedFormats != null && requestedFormats.length() >= 2) {
                val f0 = requestedFormats.getJSONObject(0)
                val f1 = requestedFormats.getJSONObject(1)

                val v0 = f0.optionalString("vcodec") != null && f0.optionalString("vcodec") != "none"
                val v1 = f1.optionalString("vcodec") != null && f1.optionalString("vcodec") != "none"

                if (v0 && !v1) {
                    videoUrl = f0.optionalString("url")
                    audioUrl = f1.optionalString("url")
                } else if (v1 && !v0) {
                    videoUrl = f1.optionalString("url")
                    audioUrl = f0.optionalString("url")
                } else {
                    videoUrl = f0.optionalString("url")
                    audioUrl = f1.optionalString("url")
                }

                if (headersMap.isEmpty()) {
                    f0.optJSONObject("http_headers")?.let { hJson ->
                        val keys = hJson.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            if (safeHeaderNames.any { it.equals(k, ignoreCase = true) }) {
                                hJson.optString(k).takeIf(String::isNotBlank)?.let { v -> headersMap[k] = v }
                            }
                        }
                    }
                }
            } else {
                videoUrl = root.optionalString("url")
            }

            if (videoUrl.isNullOrBlank()) {
                return null
            }

            val availableQualities = extractAvailableQualities(root, videoUrl, audioUrl)

            StreamResolutionResult(
                isSuccess = true,
                videoUrl = videoUrl,
                audioUrl = audioUrl,
                title = title,
                durationSeconds = duration,
                thumbnailUrl = thumbnail,
                uploader = uploader,
                httpHeaders = headersMap,
                subtitles = subtitlesMap,
                availableQualities = availableQualities,
            )
        }.getOrNull()
    }

    private fun parsePlaylistMetadata(source: String, payload: String): PlaylistResult? {
        return runCatching {
            val root = JSONObject(payload)
            val entriesJson = root.optJSONArray("entries") ?: return null
            val entries = ArrayList<PlaylistEntry>(entriesJson.length().coerceAtMost(MAX_IMPORTED_PLAYLIST_ENTRIES))
            val seenUrls = HashSet<String>()

            for (index in 0 until minOf(entriesJson.length(), MAX_IMPORTED_PLAYLIST_ENTRIES)) {
                val item = entriesJson.optJSONObject(index) ?: continue
                val id = item.optionalString("id")
                val url = sequenceOf("webpage_url", "original_url", "url")
                    .mapNotNull { item.optionalString(it) }
                    .firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
                    ?: (if (id != null) "https://www.youtube.com/watch?v=$id" else null)
                    ?: continue

                if (!seenUrls.add(url)) continue

                val title = sequenceOf("title", "fulltitle", "id")
                    .mapNotNull { item.optionalString(it) }
                    .firstOrNull() ?: "Video ${entries.size + 1}"

                val artist = sequenceOf("artist", "creator", "uploader", "channel")
                    .mapNotNull { item.optionalString(it) }
                    .firstOrNull()

                val thumbnail = item.optionalString("thumbnail")
                    ?: (if (id != null) "https://i.ytimg.com/vi/$id/hqdefault.jpg" else null)

                val duration = item.optDouble("duration", 0.0).toInt()

                entries += PlaylistEntry(
                    id = id,
                    url = url,
                    title = title,
                    artist = artist,
                    thumbnailUrl = thumbnail,
                    durationSeconds = duration,
                )
            }

            PlaylistResult(
                isSuccess = true,
                sourceUrl = source,
                title = root.optionalString("title") ?: "Playlist",
                entries = entries,
            )
        }.getOrNull()
    }

    private fun readInstallationInfo(context: Context): YtdlpInstallationInfo {
        val ytdlFile = File(getYtdlDir(context), "yt-dlp")
        if (!ytdlFile.isFile || ytdlFile.length() == 0L) {
            return YtdlpInstallationInfo(isInstalled = false)
        }
        return try {
            val py = ensurePython(context)
            val rexYtdl = py.getModule("rex_ytdl")
            val payload = rexYtdl.callAttr("get_version_info", ytdlFile.absolutePath)?.toString()
            if (!payload.isNullOrBlank()) {
                parseInstallationInfo(payload) ?: YtdlpInstallationInfo(isInstalled = true)
            } else {
                YtdlpInstallationInfo(isInstalled = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query yt-dlp version info: ${e.message}", e)
            YtdlpInstallationInfo(isInstalled = true)
        }
    }

    private fun parseInstallationInfo(payload: String): YtdlpInstallationInfo? =
        runCatching {
            val json = JSONObject(payload)
            val version = json.optionalString("version")
            val channel = json.optionalString("channel")
            val origin = json.optionalString("origin")
            YtdlpInstallationInfo(
                isInstalled = true,
                version = version,
                channel = YtdlpReleaseChannel.resolve(channel, origin, version),
                commitHash = json.optionalString("commit"),
                origin = origin,
                variant = json.optionalString("variant"),
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to parse installed yt-dlp metadata", error)
        }.getOrNull()

    private fun installYtdlp(
        context: Context,
        nightly: Boolean = false,
        onLog: (String) -> Unit,
    ): Boolean {
        prepareRuntimeAssets(context, onLog)
        val ytdlFile = File(getYtdlDir(context), "yt-dlp")
        return try {
            val py = ensurePython(context)
            val rexYtdl = py.getModule("rex_ytdl")
            val logCallback = { msg: String -> onLog(msg) }

            rexYtdl.callAttr("download_ytdlp", ytdlFile.absolutePath, nightly, logCallback)
            val installed = isInstalled(context)
            if (installed) {
                markPlaybackRuntimeReady(context)
            }
            _installationInfo.value = readInstallationInfo(context)
            installed
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download/install yt-dlp", e)
            onLog("Installation failed: ${e.message}\n")
            false
        }
    }

    private fun prepareRuntimeAssets(context: Context, onLog: (String) -> Unit = {}): Boolean {
        if (!runtimeAssetsPrepared) {
            val certFile = File(context.filesDir, "cacert.pem")
            copyAssetFile(context, "cacert.pem", certFile)
            runtimeAssetsPrepared = certFile.isFile && certFile.length() > 0L
        }
        if (!runtimeAssetsPrepared) onLog("Failed to prepare bundled certificate assets.\n")
        return runtimeAssetsPrepared
    }

    private fun copyAssetFile(context: Context, assetPath: String, outFile: File): Boolean {
        return try {
            context.assets.open(assetPath).use { input ->
                val size = input.available().toLong()
                if (outFile.exists() && outFile.length() == size) {
                    return true
                }
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
                true
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to copy asset: $assetPath", e)
            false
        }
    }

    private fun markPlaybackRuntimeReady(context: Context) {
        runCatching {
            File(getYtdlDir(context), PLAYBACK_RUNTIME_VERSION_FILE).writeText(PLAYBACK_RUNTIME_VERSION)
        }.onFailure { error ->
            Log.w(TAG, "Failed to persist runtime version", error)
        }
    }

    private fun extractAvailableQualities(
        root: JSONObject,
        selectedVideoUrl: String?,
        selectedAudioUrl: String?,
    ): List<VideoQuality> {
        val formatsJson = root.optJSONArray("formats") ?: return emptyList()

        // 1. Identify best audio stream URL
        var bestAudioUrl = selectedAudioUrl
        if (bestAudioUrl.isNullOrBlank()) {
            var highestAudioBitrate = -1.0
            for (i in 0 until formatsJson.length()) {
                val f = formatsJson.optJSONObject(i) ?: continue
                val vcodec = f.optionalString("vcodec")
                val acodec = f.optionalString("acodec")
                val url = f.optionalString("url") ?: continue
                if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) continue
                if ((vcodec == null || vcodec == "none") && (acodec != null && acodec != "none")) {
                    val abr = f.optDouble("abr", f.optDouble("tbr", 0.0))
                    if (abr > highestAudioBitrate) {
                        highestAudioBitrate = abr
                        bestAudioUrl = url
                    }
                }
            }
        }

        data class CandidateFormat(
            val formatId: String,
            val height: Int,
            val width: Int,
            val fps: Int,
            val vcodec: String,
            val acodec: String?,
            val ext: String,
            val note: String,
            val bitrate: Long,
            val url: String,
            val isVideoOnly: Boolean,
        )

        val candidates = mutableListOf<CandidateFormat>()
        for (i in 0 until formatsJson.length()) {
            val f = formatsJson.optJSONObject(i) ?: continue
            val vcodec = f.optionalString("vcodec") ?: continue
            if (vcodec == "none") continue // Skip pure audio

            val ext = f.optionalString("ext") ?: ""
            val note = f.optionalString("format_note") ?: ""
            val protocol = f.optionalString("protocol") ?: ""
            // Exclude storyboards and mhtml
            if (ext.equals("mhtml", ignoreCase = true) ||
                note.contains("storyboard", ignoreCase = true) ||
                protocol.contains("mhtml", ignoreCase = true)) {
                continue
            }

            val url = f.optionalString("url") ?: continue
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                continue
            }

            var height = f.optInt("height", 0)
            if (height <= 0) {
                height = parseHeight(note) ?: parseHeight(f.optionalString("resolution")) ?: 0
            }
            if (height <= 0) continue

            val width = f.optInt("width", 0)
            val fps = f.optDouble("fps", 0.0).toInt()
            val acodec = f.optionalString("acodec")
            val isVideoOnly = acodec == null || acodec == "none"
            val bitrate = (f.optDouble("tbr", f.optDouble("vbr", 0.0)) * 1000).toLong()
            val formatId = f.optionalString("format_id") ?: "$i"

            candidates.add(
                CandidateFormat(
                    formatId = formatId,
                    height = height,
                    width = width,
                    fps = fps,
                    vcodec = vcodec,
                    acodec = acodec,
                    ext = ext,
                    note = note,
                    bitrate = bitrate,
                    url = url,
                    isVideoOnly = isVideoOnly,
                )
            )
        }

        if (candidates.isEmpty()) return emptyList()

        // Group by (height, isHighFps)
        val grouped = candidates.groupBy { candidate ->
            val is60 = candidate.fps >= 50
            Pair(candidate.height, is60)
        }

        val resultQualities = mutableListOf<VideoQuality>()
        for ((groupKey, groupCandidates) in grouped) {
            val (height, is60) = groupKey

            // Pick the best format for this resolution (prioritizing the selected video URL if in this group)
            val bestCandidate = groupCandidates.firstOrNull { it.url == selectedVideoUrl }
                ?: groupCandidates.firstOrNull { it.url.substringBefore("?") == selectedVideoUrl?.substringBefore("?") }
                ?: groupCandidates.maxWithOrNull(
                    compareBy<CandidateFormat> { candidate ->
                        val vc = candidate.vcodec.lowercase()
                        when {
                            height <= 1080 && (vc.startsWith("avc") || vc.startsWith("h264")) -> 3
                            height > 1080 && (vc.startsWith("vp9") || vc.startsWith("vp09")) -> 3
                            vc.startsWith("vp9") || vc.startsWith("vp09") -> 2
                            vc.startsWith("av01") || vc.startsWith("av1") -> 1
                            else -> 0
                        }
                    }.thenBy { it.bitrate }
                ) ?: continue

            val label = buildString {
                append("${height}p")
                if (is60) append("60")
                if (height >= 2160) append(" (4K)")
                else if (height >= 1440) append(" (2K)")
            }

            val friendlyCodec = when {
                bestCandidate.vcodec.startsWith("avc", ignoreCase = true) -> "AVC"
                bestCandidate.vcodec.startsWith("vp9", ignoreCase = true) ||
                bestCandidate.vcodec.startsWith("vp09", ignoreCase = true) -> "VP9"
                bestCandidate.vcodec.startsWith("av01", ignoreCase = true) ||
                bestCandidate.vcodec.startsWith("av1", ignoreCase = true) -> "AV1"
                else -> bestCandidate.vcodec
            }

            resultQualities.add(
                VideoQuality(
                    id = bestCandidate.formatId,
                    label = label,
                    height = height,
                    width = bestCandidate.width,
                    fps = bestCandidate.fps,
                    codec = friendlyCodec,
                    bitrate = bestCandidate.bitrate,
                    videoUrl = bestCandidate.url,
                    audioUrl = if (bestCandidate.isVideoOnly) bestAudioUrl else null,
                    isDASH = bestCandidate.isVideoOnly && !bestAudioUrl.isNullOrBlank(),
                    isAudioOnly = false,
                )
            )
        }

        // Sort descending by height, then fps
        resultQualities.sortWith(compareByDescending<VideoQuality> { it.height }.thenByDescending { it.fps })

        // If audio stream is available, append "Audio Only"
        if (!bestAudioUrl.isNullOrBlank()) {
            resultQualities.add(
                VideoQuality(
                    id = "audio_only",
                    label = "Audio Only",
                    videoUrl = bestAudioUrl,
                    audioUrl = null,
                    isDASH = false,
                    isAudioOnly = true,
                )
            )
        }

        return resultQualities
    }

    private fun parseHeight(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val match = Regex("(\\d{3,4})p?").find(text) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun JSONObject.optionalString(key: String): String? =
        optString(key)
            .trim()
            .takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
}
