package com.Blockades

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {

    fun pbkdf2AesCbcDecrypt(ciphertext: ByteArray, passphrase: String, saltB64: String, ivB64: String): String? {
        return try {
            val salt = Base64.decode(saltB64, Base64.DEFAULT)
            val iv = Base64.decode(ivB64, Base64.DEFAULT)
            val spec = PBEKeySpec(passphrase.toCharArray(), salt, 999, 256)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            val key = factory.generateSecret(spec).encoded
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Oynatıcı sayfasındaki gizli imza değerini URL'ye ekler.
     * Not: Bu, gerçek site algoritmasının basitleştirilmiş bir versiyonudur;
     * site tarafında farklı parametre adı kullanılıyorsa regex güncellenmelidir.
     */
    fun pichiveSign(playerHtml: String, url: String): String {
        val sign = Regex("""(?:sign|token|hash)\s*[:=]\s*['"]([a-fA-F0-9]{16,})['"]""")
            .find(playerHtml)?.groupValues?.get(1) ?: return url
        val sep = if ('?' in url) '&' else '?'
        return "$url${sep}sign=$sign"
    }
}