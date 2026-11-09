package com.invictus.kodex.core.crypto

import android.util.Base64
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec

/** A public key in the shapes people paste into config files. [hex] and [openssh] are null where they do not apply. */
data class DerivedKey(
    val kind: String,
    val base64: String,
    val hex: String?,
    val pem: String,
    val openssh: String?,
)

class KeyParseException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { Empty, Encrypted, Unsupported, Unrecognized }
}

/** Finds the public key that belongs to a private key (RSA or Ed25519). Nothing is stored or sent anywhere. */
object PublicKeyDeriver {
    private val ED_PKCS8_PREFIX = byteArrayOf(0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20)
    private val ED_SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
    private val HEX = Regex("^[0-9a-fA-F]+$")

    fun derive(input: String): DerivedKey {
        val text = input.trim()
        if (text.isEmpty()) throw KeyParseException(KeyParseException.Reason.Empty)
        return when {
            "ENCRYPTED" in text -> throw KeyParseException(KeyParseException.Reason.Encrypted)
            "BEGIN OPENSSH PRIVATE KEY" in text -> fromOpenSsh(pemBody(text))
            "BEGIN RSA PRIVATE KEY" in text -> fromPkcs8(pkcs1ToPkcs8(pemBody(text)))
            "BEGIN PRIVATE KEY" in text -> fromPkcs8(pemBody(text))
            "-----BEGIN" in text -> throw KeyParseException(KeyParseException.Reason.Unsupported)
            else -> fromRaw(text)
        }
    }

    // ---- inputs ---------------------------------------------------------------------------------

    private fun pemBody(text: String): ByteArray {
        val body = text.lines().filterNot { it.startsWith("-----") }.joinToString("").filterNot { it.isWhitespace() }
        return decodeBase64(body) ?: throw KeyParseException(KeyParseException.Reason.Unrecognized)
    }

    private fun decodeBase64(s: String): ByteArray? =
        runCatching { Base64.decode(s, Base64.DEFAULT) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: runCatching { Base64.decode(s, Base64.URL_SAFE) }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun fromRaw(text: String): DerivedKey {
        val clean = text.filterNot { it.isWhitespace() }
        val bytes = if (HEX.matches(clean) && clean.length % 2 == 0) {
            ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } else {
            decodeBase64(clean)
        } ?: throw KeyParseException(KeyParseException.Reason.Unrecognized)
        return when (bytes.size) {
            32, 64 -> ed25519(bytes.copyOfRange(0, 32)) // 64 bytes = seed followed by the public key
            48 -> if (bytes.take(ED_PKCS8_PREFIX.size).toByteArray().contentEquals(ED_PKCS8_PREFIX)) fromPkcs8(bytes) else throw KeyParseException(KeyParseException.Reason.Unrecognized)
            else -> if (bytes.size > 100) fromPkcs8(bytes) else throw KeyParseException(KeyParseException.Reason.Unrecognized)
        }
    }

    private fun fromPkcs8(der: ByteArray): DerivedKey {
        if (der.size == 48 && der.copyOfRange(0, ED_PKCS8_PREFIX.size).contentEquals(ED_PKCS8_PREFIX)) {
            return ed25519(der.copyOfRange(16, 48))
        }
        val key = runCatching { KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der)) }.getOrNull()
        val crt = key as? RSAPrivateCrtKey ?: throw KeyParseException(KeyParseException.Reason.Unsupported)
        return rsa(crt.modulus, crt.publicExponent)
    }

    /** Wraps an old-style "RSA PRIVATE KEY" (PKCS#1) into the PKCS#8 envelope Java reads. */
    private fun pkcs1ToPkcs8(pkcs1: ByteArray): ByteArray {
        val algorithm = byteArrayOf(0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00)
        val inner = byteArrayOf(0x02, 0x01, 0x00) + algorithm + derWrap(0x04, pkcs1)
        return derWrap(0x30, inner)
    }

    private fun derWrap(tag: Int, content: ByteArray): ByteArray {
        val n = content.size
        val len = when {
            n < 128 -> byteArrayOf(n.toByte())
            n < 256 -> byteArrayOf(0x81.toByte(), n.toByte())
            else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        }
        return byteArrayOf(tag.toByte()) + len + content
    }

    /** Unencrypted `openssh-key-v1` file: the public half is stored in the clear right after the header. */
    private fun fromOpenSsh(bin: ByteArray): DerivedKey {
        val magic = "openssh-key-v1\u0000".toByteArray(Charsets.ISO_8859_1)
        if (bin.size < magic.size || !bin.copyOfRange(0, magic.size).contentEquals(magic)) throw KeyParseException(KeyParseException.Reason.Unrecognized)
        val r = Reader(bin, magic.size)
        val cipher = String(r.string(), Charsets.US_ASCII)
        r.string() // kdf name
        r.string() // kdf options
        if (cipher != "none") throw KeyParseException(KeyParseException.Reason.Encrypted)
        r.u32() // number of keys
        val pub = Reader(r.string(), 0)
        return when (String(pub.string(), Charsets.US_ASCII)) {
            "ssh-ed25519" -> edFromPublic(pub.string())
            "ssh-rsa" -> {
                val e = BigInteger(pub.string())
                val n = BigInteger(pub.string())
                rsa(n, e)
            }
            else -> throw KeyParseException(KeyParseException.Reason.Unsupported)
        }
    }

    private class Reader(private val b: ByteArray, private var pos: Int) {
        fun u32(): Int {
            if (pos + 4 > b.size) throw KeyParseException(KeyParseException.Reason.Unrecognized)
            val v = ((b[pos].toInt() and 0xff) shl 24) or ((b[pos + 1].toInt() and 0xff) shl 16) or ((b[pos + 2].toInt() and 0xff) shl 8) or (b[pos + 3].toInt() and 0xff)
            pos += 4
            return v
        }

        fun string(): ByteArray {
            val n = u32()
            if (n < 0 || pos + n > b.size) throw KeyParseException(KeyParseException.Reason.Unrecognized)
            return b.copyOfRange(pos, pos + n).also { pos += n }
        }
    }

    // ---- outputs --------------------------------------------------------------------------------

    private fun ed25519(seed: ByteArray): DerivedKey = edFromPublic(Ed25519.publicFromSeed(seed))

    private fun edFromPublic(pub: ByteArray): DerivedKey {
        val blob = sshString("ssh-ed25519".toByteArray()) + sshString(pub)
        return DerivedKey(
            kind = "Ed25519",
            base64 = b64(pub),
            hex = pub.joinToString("") { "%02x".format(it) },
            pem = pem(ED_SPKI_PREFIX + pub),
            openssh = "ssh-ed25519 " + b64(blob),
        )
    }

    private fun rsa(modulus: BigInteger, exponent: BigInteger): DerivedKey {
        val spki = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent)).encoded
        val blob = sshString("ssh-rsa".toByteArray()) + sshString(exponent.toByteArray()) + sshString(modulus.toByteArray())
        return DerivedKey(
            kind = "RSA ${modulus.bitLength()}",
            base64 = b64(spki),
            hex = null,
            pem = pem(spki),
            openssh = "ssh-rsa " + b64(blob),
        )
    }

    private fun sshString(b: ByteArray): ByteArray {
        val n = b.size
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + b
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    /** X.509 SubjectPublicKeyInfo as a PEM block. */
    fun pem(spki: ByteArray): String =
        "-----BEGIN PUBLIC KEY-----\n" + b64(spki).chunked(64).joinToString("\n") + "\n-----END PUBLIC KEY-----"
}
