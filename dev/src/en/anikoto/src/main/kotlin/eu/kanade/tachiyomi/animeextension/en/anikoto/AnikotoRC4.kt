package eu.kanade.tachiyomi.animeextension.en.anikoto

import android.util.Base64

/**
 * RC4 encryption for the `vrf` query parameter used by /ajax/episode/list/{animeId}.
 *
 * Per MEMORY/research/apk-reference/02-video-pipeline-and-proxy.md §3:
 * - Key: "simple-hash" (hardcoded)
 * - Algorithm: textbook standard RC4 (KSA + PRGA)
 * - Output: Base64.NO_WRAP( rc4(key, animeId).getBytes(ISO_8859_1) )
 * - The server does NOT currently validate this (session 11 verification), but we implement it
 *   to match the reference and be safe against future validation.
 */
object AnikotoRC4 {

    private const val KEY = "simple-hash"

    /**
     * Encode the vrf parameter for the episode-list AJAX call.
     * @param animeId the data-id from #watch-main
     * @return Base64-encoded RC4-encrypted string (NO_WRAP, then URL-encoded by caller)
     */
    fun encodeVrf(animeId: String): String {
        val encrypted = rc4(KEY, animeId)
        val bytes = encrypted.toByteArray(Charsets.ISO_8859_1)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Textbook RC4 (KSA + PRGA). Operates on chars (wide) but output stays in 0-255.
     * Symmetric: rc4(key, rc4(key, data)) == data.
     */
    fun rc4(key: String, input: String): String {
        val s = IntArray(256) { it }
        var j = 0
        val keyLen = key.length
        for (i in 0 until 256) {
            j = (s[i] + j + key[i % keyLen].code) % 256
            s[i] = s[j].also { s[j] = s[i] }
        }

        val out = StringBuilder(input.length)
        var i = 0
        j = 0
        for (c in input) {
            i = (i + 1) % 256
            j = (s[i] + j) % 256
            s[i] = s[j].also { s[j] = s[i] }
            val k = s[(s[i] + s[j]) % 256]
            out.append((c.code xor k).toChar())
        }
        return out.toString()
    }
}
