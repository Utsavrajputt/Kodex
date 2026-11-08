package com.invictus.kodex.github.signing

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale

/** Names of the four Actions secrets that carry a keystore; the defaults match the usual Android signing workflow. */
data class SecretNames(
    val keystore: String = RepoSigning.SECRET_KEYSTORE,
    val storePassword: String = RepoSigning.SECRET_STORE_PASSWORD,
    val alias: String = RepoSigning.SECRET_ALIAS,
    val keyPassword: String = RepoSigning.SECRET_KEY_PASSWORD,
) {
    fun all(): List<String> = listOf(keystore, storePassword, alias, keyPassword)
}

/** Everything the keystore generator tool can set. [password] is used for both the store and the key (PKCS12). */
data class KeystoreSpec(
    val alias: String,
    val password: String,
    val keySize: Int = 2048,
    val validityYears: Int = 25,
    val commonName: String,
    val orgUnit: String = "",
    val org: String = "",
    val locality: String = "",
    val state: String = "",
    val country: String = "",
)

/** A generated keystore plus what to show about it. */
class GeneratedKeystore(
    val bytes: ByteArray,
    val alias: String,
    val password: String,
    val subject: String,
    val validUntil: String,
    /** SHA-256 certificate fingerprint, `AA:BB:..`. */
    val sha256: String,
) {
    fun secrets(names: SecretNames): Map<String, String> = linkedMapOf(
        names.keystore to Base64.encodeToString(bytes, Base64.NO_WRAP),
        names.storePassword to password,
        names.alias to alias,
        names.keyPassword to password,
    )
}

object KeystoreTool {
    /** RSA key generation takes a moment (longer for 4096), so it runs off the main thread. */
    suspend fun generate(spec: KeystoreSpec): GeneratedKeystore = withContext(Dispatchers.Default) {
        val bytes = KeystoreFactory.generate(
            spec.alias, spec.password.toCharArray(), spec.commonName, spec.orgUnit, spec.org,
            spec.locality, spec.state, spec.country, spec.validityYears, spec.keySize,
        )
        // Read it back like Gradle will: this proves the file opens with the password and gives the real details.
        val ks = KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(bytes), spec.password.toCharArray()) }
        val cert = ks.getCertificate(spec.alias) as X509Certificate
        val fp = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
        GeneratedKeystore(
            bytes = bytes, alias = spec.alias, password = spec.password,
            subject = cert.subjectX500Principal.name,
            validUntil = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(cert.notAfter),
            sha256 = fp,
        )
    }

    private val SECRET_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    /** GitHub rule: letters, digits, underscore, not starting with a digit or the reserved GITHUB_ prefix. */
    fun validSecretName(name: String): Boolean = SECRET_NAME.matches(name) && !name.startsWith("GITHUB_", ignoreCase = true)
}
