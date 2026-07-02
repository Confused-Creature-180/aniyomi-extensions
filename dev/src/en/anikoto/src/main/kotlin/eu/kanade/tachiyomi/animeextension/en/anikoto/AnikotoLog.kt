package eu.kanade.tachiyomi.animeextension.en.anikoto

import android.util.Log

/**
 * Extension logger — writes to Android logcat only (tag "Anikoto").
 *
 * ★ session 46 (v16.30→v16.6 release): simplified to logcat-only.
 * Previous versions wrote to `Download/1118000/anikoto-*.log` files, but that required
 * `WRITE_EXTERNAL_STORAGE` (cluttering the user's device + needing a permission).
 * The extension is mature enough that logcat is sufficient for debugging.
 *
 * Usage:
 *   AnikotoLog.i("getHosterList START")
 *   AnikotoLog.d("GET $url")
 *   AnikotoLog.e("resolveStreamForTask FAILED", e)
 *
 * To capture logs on-device: `adb logcat -s Anikoto:*`
 */
object AnikotoLog {

    private const val TAG = "Anikoto"
    private const val EXTENSION_VERSION = "v16.9 (ext-lib 16, versionId=11 STABLE)"

    fun i(msg: String) {
        Log.i(TAG, msg)
    }

    fun d(msg: String) {
        Log.d(TAG, msg)
    }

    fun w(msg: String) {
        Log.w(TAG, msg)
    }

    fun e(msg: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, msg, throwable)
        } else {
            Log.e(TAG, msg)
        }
    }

    /**
     * Truncate a string for logging (avoids logcat 4KB line limit + keeps logs readable).
     */
    fun trunc(s: String, maxLen: Int = 60): String {
        return if (s.length <= maxLen) s else s.substring(0, maxLen) + "…(${s.length})"
    }
}
