package xyz.mpv.rex.addon.ytdl.ipc;

interface IYtDlCallback {
    void onLog(String message);
    void onComplete(boolean success, String message);
}
