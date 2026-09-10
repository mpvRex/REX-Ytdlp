package xyz.mpv.rex.addon.ytdl.service

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import xyz.mpv.rex.addon.ytdl.engine.YtdlpManager
import xyz.mpv.rex.addon.ytdl.ipc.IYtDlCallback
import xyz.mpv.rex.addon.ytdl.ipc.IYtDlService
import xyz.mpv.rex.addon.ytdl.ipc.IpcBundleConverter

class YtDlRemoteService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val binder = object : IYtDlService.Stub() {
        override fun getAddonVersion(): Int = IpcBundleConverter.INTERFACE_VERSION

        override fun isReady(): Boolean = runBlocking(Dispatchers.IO) {
            YtdlpManager.ensureReady(this@YtDlRemoteService)
        }

        override fun getStatus(): Bundle = runBlocking(Dispatchers.IO) {
            val info = YtdlpManager.refreshInstallationInfo(this@YtDlRemoteService)
            IpcBundleConverter.toBundle(info)
        }

        override fun runInstall(callback: IYtDlCallback?) {
            scope.launch {
                try {
                    val success = YtdlpManager.runInstall(this@YtDlRemoteService) { logMsg ->
                        runCatching { callback?.onLog(logMsg) }
                    }
                    val msg = if (success) "Installation completed successfully." else "Installation failed."
                    runCatching { callback?.onComplete(success, msg) }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in runInstall", e)
                    runCatching { callback?.onComplete(false, e.message ?: "Unknown error") }
                }
            }
        }

        override fun runUpdate(nightly: Boolean, callback: IYtDlCallback?) {
            scope.launch {
                try {
                    val success = YtdlpManager.runUpdate(this@YtDlRemoteService, nightly) { logMsg ->
                        runCatching { callback?.onLog(logMsg) }
                    }
                    val msg = if (success) "Update completed successfully." else "Update failed."
                    runCatching { callback?.onComplete(success, msg) }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in runUpdate", e)
                    runCatching { callback?.onComplete(false, e.message ?: "Unknown error") }
                }
            }
        }

        override fun resolveStream(url: String?, options: Bundle?): Bundle = runBlocking(Dispatchers.IO) {
            if (url.isNullOrBlank()) {
                val failure = xyz.mpv.rex.addon.ytdl.model.StreamResolutionResult.failure("Invalid or empty URL")
                return@runBlocking IpcBundleConverter.toBundle(failure)
            }
            val extractionOptions = IpcBundleConverter.toExtractionOptions(options)
            val result = YtdlpManager.resolveStream(this@YtDlRemoteService, url, extractionOptions)
            IpcBundleConverter.toBundle(result)
        }

        override fun extractPlaylist(url: String?, options: Bundle?): Bundle = runBlocking(Dispatchers.IO) {
            if (url.isNullOrBlank()) {
                val failure = xyz.mpv.rex.addon.ytdl.model.PlaylistResult.failure("", "Invalid or empty URL")
                return@runBlocking IpcBundleConverter.toBundle(failure)
            }
            val extractionOptions = IpcBundleConverter.toExtractionOptions(options)
            val result = YtdlpManager.extractPlaylist(this@YtDlRemoteService, url, extractionOptions)
            IpcBundleConverter.toBundle(result)
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Client bound to YtDlRemoteService: action=${intent?.action}")
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        private const val TAG = "YtDlRemoteService"
        const val ACTION_BIND = "xyz.mpv.rex.addon.ytdl.BIND_SERVICE"
    }
}
