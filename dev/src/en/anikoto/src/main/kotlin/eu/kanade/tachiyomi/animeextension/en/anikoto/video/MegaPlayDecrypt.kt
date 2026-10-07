package eu.kanade.tachiyomi.animeextension.en.anikoto.video

import android.util.Base64
import eu.kanade.tachiyomi.animeextension.en.anikoto.AnikotoLog
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ★ session 52: Decryptor for megaplay.buzz's encrypted getSources response.
 *
 * **Verified live 2026-09-09** (Sakamoto Days ep 10, data-id 2234):
 * `GET https://megaplay.buzz/stream/getSources?id=2234&type=sub` now returns:
 * ```json
 * {"tracks":[...],"t":1,"intro":{...},"outro":{...},"server":4,
 *  "enc":"wdeBruh3qqn_i5wUNnyaPQQl1wp7r0SrL6KQPNFd24_..."}
 * ```
 * — the `sources.file` field is GONE (this is what broke playback: the extension logged
 * `sources.file='null'` for every megaplay server).
 *
 * The player decrypts `enc` client-side. The algorithm was extracted from megaplay's own
 * `lib/newclient.min.js` (SegmentDecrypt + trust module, both use the same constants):
 * - **Cipher**: AES-256-CBC, PKCS#7 padding
 * - **Key**: the 16-char ASCII string "i?LMTAx0Q6,:}50U" **zero-padded to 32 bytes**
 *   (the JS does `new Uint8Array(32)` + set(keyBytes) → crypto.subtle picks AES-256)
 * - **IV**: the 16-char ASCII string "W0;27ToaUpl_P%'c" (16 bytes, used as-is)
 * - **Encoding**: base64url (chars `-`/`_`, unpadded)
 *
 * Decrypted output: `{"file":"https://megap.shiora.site/<hash>/<hash>/master.m3u8"}`
 * (the m3u8 host rotates per response — megap.shiora.site, megap.mikora.top, ...).
 *
 * Cross-checked by decrypting a live blob with openssl (aes-256-cbc, same key/iv) —
 * identical plaintext. See EXTENSIONS/anikoto/MEMORY/sites/getsources-migration-and-id-analysis.md §3.
 */
object MegaPlayDecrypt {

    /** 16-char AES key from newclient.min.js — zero-padded to 32 bytes at decrypt time. */
    private const val ENC_KEY = "i?LMTAx0Q6,:}50U"

    /** 16-byte AES IV from newclient.min.js. */
    private const val ENC_IV = "W0;27ToaUpl_P%'c"

    /**
     * Decrypt a getSources `enc` blob → the decrypted JSON string
     * (e.g. `{"file":"https://...master.m3u8"}`), or null on any failure.
     */
    fun decrypt(enc: String): String? = try {
        // Zero-padded 32-byte key (AES-256) — mirrors the player's Uint8Array(32) trick.
        val keyBytes = ByteArray(32)
        val rawKey = ENC_KEY.toByteArray(Charsets.UTF_8)
        System.arraycopy(rawKey, 0, keyBytes, 0, rawKey.size)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(ENC_IV.toByteArray(Charsets.UTF_8)),
        )
        String(cipher.doFinal(base64UrlDecode(enc)), Charsets.UTF_8)
    } catch (e: Exception) {
        AnikotoLog.e("MegaPlayDecrypt: AES decrypt FAILED (${e.javaClass.simpleName}: ${e.message?.take(80)})")
        null
    }

    /**
     * base64url decode (handles `-`/`_` chars and missing `=` padding —
     * Android's Base64 decoder is strict about padding, the site's blobs are unpadded).
     */
    private fun base64UrlDecode(s: String): ByteArray {
        val std = s.replace('-', '+').replace('_', '/')
        val padded = std + "=".repeat((4 - std.length % 4) % 4)
        return Base64.decode(padded, Base64.DEFAULT)
    }
}
