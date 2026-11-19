package io.buzincast.media

import java.io.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Framed, authenticated encryption for Buzin-to-Buzin relay. Not Google Cast. */
class SecureMirrorChannel(input: InputStream, output: OutputStream, private val key: SecretKeySpec,
                          private val crypto: MirrorCipher = JvmMirrorCipher) {
    private val input = DataInputStream(input)
    private val output = DataOutputStream(output)
    private val random = SecureRandom()
    private var tx = 0L
    private var rx = 0L

    @Synchronized fun send(bytes: ByteArray) {
        require(bytes.size <= MirrorPacketCodec.MAX_BYTES + 32 && tx >= 0)
        val sequence = tx++
        val nonce = ByteArray(12).also(random::nextBytes)
        val encrypted = crypto.crypt(true, key, nonce, ByteBuffer.allocate(8).putLong(sequence).array(), bytes)
        output.writeInt(encrypted.size); output.writeLong(sequence); output.write(nonce); output.write(encrypted)
        output.flush()
    }

    fun receive(): ByteArray {
        val size = input.readInt()
        require(size in 16..MirrorPacketCodec.MAX_BYTES + 64)
        val sequence = input.readLong(); require(sequence == rx && sequence >= 0) { "Out-of-order relay frame" }
        val nonce = ByteArray(12).also(input::readFully)
        val encrypted = ByteArray(size).also(input::readFully)
        return crypto.crypt(false, key, nonce, ByteBuffer.allocate(8).putLong(sequence).array(), encrypted).also { rx++ }
    }

    companion object {
        val MAGIC = "BZCAST02".toByteArray(Charsets.US_ASCII)
        fun normalizeCode(code: String) = code.uppercase(java.util.Locale.US).filter { it.isLetterOrDigit() }
        fun deriveKey(code: String, salt: ByteArray): SecretKeySpec {
            val normalized = normalizeCode(code)
            require(normalized.length == 12 && salt.size == 16)
            val spec = PBEKeySpec(normalized.toCharArray(), salt, 100_000, 256)
            return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded, "AES") }
            finally { spec.clearPassword() }
        }
    }
}

/** Platform binding only. Framing, sequence/AAD and key derivation stay in the shared channel. */
fun interface MirrorCipher {
    fun crypt(encrypt: Boolean, key: SecretKeySpec, nonce: ByteArray, aad: ByteArray, data: ByteArray): ByteArray
}

// Keep newer platform types in their own class: API17 supplies its native engine
// explicitly and must never load GCMParameterSpec or instantiate this default.
private object JvmMirrorCipher : MirrorCipher {
    override fun crypt(encrypt: Boolean, key: SecretKeySpec, nonce: ByteArray, aad: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(data)
    }
}
