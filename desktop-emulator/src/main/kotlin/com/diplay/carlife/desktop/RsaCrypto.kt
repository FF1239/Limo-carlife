package com.diplay.carlife.desktop

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher

/**
 * RSA key exchange matching Baidu's RSAManager: 2048-bit pair, keys carried as
 * Base64(X.509 subjectPublicKeyInfo) / Base64(PKCS#8), payloads encrypted with
 * RSA/ECB/PKCS1Padding.
 */
object RsaCrypto {
    @Volatile
    private var pair: KeyPair? = null

    fun ensureKeyPair(): Pair<String, PrivateKey> {
        pair ?: synchronized(this) {
            pair ?: run {
                val gen = KeyPairGenerator.getInstance("RSA")
                gen.initialize(2048, SecureRandom())
                pair = gen.generateKeyPair()
            }
        }
        val kp = pair!!
        val pub = java.util.Base64.getEncoder().encodeToString(kp.public.encoded)
        return pub to kp.private
    }

    fun decrypt(privateKey: PrivateKey, base64Data: String): String? = try {
        val data = java.util.Base64.getDecoder().decode(base64Data)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        String(cipher.doFinal(data), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}
