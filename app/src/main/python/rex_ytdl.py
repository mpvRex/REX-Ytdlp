import os
import sys
import json
import urllib.request

def _prepare_environment(ytdl_path, cert_file=None):
    if cert_file and os.path.isfile(cert_file):
        os.environ['SSL_CERT_FILE'] = cert_file
    if ytdl_path and os.path.isfile(ytdl_path):
        if ytdl_path in sys.path:
            sys.path.remove(ytdl_path)
        sys.path.insert(0, ytdl_path)

def get_version_info(ytdl_path):
    """
    Returns JSON metadata for the installed yt-dlp artifact, or None if unavailable.
    """
    if not ytdl_path or not os.path.isfile(ytdl_path):
        return None
    try:
        _prepare_environment(ytdl_path)
        # Import fresh version module from the downloaded yt-dlp zipapp
        if 'yt_dlp.version' in sys.modules:
            del sys.modules['yt_dlp.version']
        if 'yt_dlp' in sys.modules:
            del sys.modules['yt_dlp']
            
        from yt_dlp import version
        payload = {
            "version": getattr(version, "__version__", ""),
            "channel": getattr(version, "CHANNEL", ""),
            "commit": getattr(version, "RELEASE_GIT_HEAD", ""),
            "origin": getattr(version, "ORIGIN", ""),
            "variant": getattr(version, "VARIANT", ""),
        }
        return json.dumps(payload, separators=(",", ":"))
    except Exception as e:
        return None

def download_ytdlp(dest_path, nightly=False, log_callback=None):
    """
    Downloads the official standalone yt-dlp binary from GitHub releases.
    """
    url = (
        "https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/latest/download/yt-dlp"
        if nightly
        else "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"
    )
    temp_path = dest_path + ".download"

    def log(msg):
        if log_callback:
            try:
                log_callback(msg if msg.endswith("\n") else msg + "\n")
            except Exception:
                pass

    log(f"Connecting to {url}...")
    headers = {
        'User-Agent': (
            'Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
            'AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'
        )
    }

    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req) as response, open(temp_path, "wb") as out_file:
            total_bytes = int(response.headers.get("Content-Length", 0))
            downloaded = 0
            chunk_size = 64 * 1024
            last_reported_pct = -1

            while True:
                chunk = response.read(chunk_size)
                if not chunk:
                    break
                out_file.write(chunk)
                downloaded += len(chunk)

                if total_bytes > 0:
                    pct = int((downloaded / total_bytes) * 100)
                    if pct != last_reported_pct and pct % 10 == 0:
                        log(f"Downloading yt-dlp: {pct}% ({downloaded // 1024} KB / {total_bytes // 1024} KB)")
                        last_reported_pct = pct

        if not os.path.isfile(temp_path) or os.path.getsize(temp_path) == 0:
            raise IOError("Downloaded yt-dlp payload is empty")

        if os.path.exists(dest_path):
            try:
                os.remove(dest_path)
            except Exception:
                pass

        os.rename(temp_path, dest_path)
        try:
            os.chmod(dest_path, 0o600)
        except Exception:
            pass

        log("yt-dlp installation completed successfully.")
        return True
    except Exception as e:
        log(f"Download failed: {e}")
        if os.path.exists(temp_path):
            try:
                os.remove(temp_path)
            except Exception:
                pass
        raise e

def _get_ydl_opts(args):
    import yt_dlp
    ydl_opts = {}
    try:
        if hasattr(yt_dlp, 'parse_options'):
            res = yt_dlp.parse_options(args)
            if hasattr(res, 'ydl_opts'):
                ydl_opts = res.ydl_opts
            elif isinstance(res, (tuple, list)) and len(res) >= 4:
                ydl_opts = res[3]
    except Exception:
        pass

    if not ydl_opts:
        try:
            from yt_dlp.options import parseOpts
            _, opts, _ = parseOpts(args)
            ydl_opts = vars(opts) if hasattr(opts, '__dict__') else opts
        except Exception:
            ydl_opts = {}

    return ydl_opts if isinstance(ydl_opts, dict) else {}

def extract_media(ytdl_path, quickjs_path, url, cli_args_json, cert_file=None):
    """
    Extracts stream or playlist metadata using yt_dlp in-process via Chaquopy.
    """
    _prepare_environment(ytdl_path, cert_file)
    import yt_dlp

    args = json.loads(cli_args_json) if cli_args_json else []
    ydl_opts = _get_ydl_opts(args)

    # Configure QuickJS if available
    if quickjs_path and os.path.isfile(quickjs_path):
        runtimes = ydl_opts.get('js_runtimes') or {}
        if isinstance(runtimes, dict):
            runtimes['quickjs'] = {'path': quickjs_path}
            ydl_opts['js_runtimes'] = runtimes

    # Ensure quiet execution and no direct download
    ydl_opts['skip_download'] = True
    ydl_opts['quiet'] = True
    ydl_opts['no_warnings'] = True
    ydl_opts['ignoreerrors'] = True

    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(url, download=False)
        if not info:
            return None
        sanitized = ydl.sanitize_info(info)
        return json.dumps(sanitized)

