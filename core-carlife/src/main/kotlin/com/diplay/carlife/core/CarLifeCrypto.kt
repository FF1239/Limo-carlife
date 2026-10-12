package com.diplay.carlife.core

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * RSA/AES primitives for the real CarLife encryption handshake.
 *
 * Mirrors Baidu's Vehicle-Lib (Apache-2.0) which the protocol notes cite as the
 * authoritative source (docs/protocol-notes.md §9):
 *  - RSA key exchange: 2048-bit pair, public key carried as Base64(X.509
 *    SubjectPublicKeyInfo), payload encrypted with RSA/ECB/PKCS1Padding.
 *  - Session payloads: AES/ECB/PKCS5Padding (the default "AES" transformation),
 *    key = the raw 16-byte AES session key the phone RSA-encrypts to us.
 *
 * The AES key must be handled as RAW BYTES, never round-tripped through a UTF-8
 * String (a random 16-byte key is usually not valid UTF-8 and would corrupt the
 * key). [rsaDecryptToBytes] therefore returns the decrypted bytes directly.
 */
object CarLifeCrypto {
    @Volatile
    private var pair: KeyPair? = null

    /** Generate (or reuse) an RSA-2048 keypair; returns Base64(X.509) public key + private key. */
    fun ensureKeyPair(): Pair<String, PrivateKey> {
        val kp = pair ?: synchronized(this) {
            pair ?: run {
                val gen = KeyPairGenerator.getInstance("RSA")
                gen.initialize(2048, SecureRandom())
                gen.generateKeyPair().also { pair = it }
            }
        }
        val pub = java.util.Base64.getEncoder().encodeToString(kp.public.encoded)
        return pub to kp.private
    }

    /** RSA/ECB/PKCS1Padding decrypt of a Base64 string -> raw bytes (the AES session key). Null on failure. */
    fun rsaDecryptToBytes(privateKey: PrivateKey, base64: String): ByteArray? = try {
        val data = java.util.Base64.getDecoder().decode(base64)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        cipher.doFinal(data)
    } catch (e: Exception) {
        null
    }

    /** AES/ECB/PKCS5Padding decrypt; null on failure so the caller can keep the original bytes. */
    fun aesDecrypt(key: ByteArray, data: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        cipher.doFinal(data)
    } catch (e: Exception) {
        null
    }

    /** AES/ECB/PKCS5Padding encrypt; null on failure. */
    fun aesEncrypt(key: ByteArray, data: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        cipher.doFinal(data)
    } catch (e: Exception) {
        null
    }
}
