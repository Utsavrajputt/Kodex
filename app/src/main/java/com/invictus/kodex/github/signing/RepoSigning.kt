package com.invictus.kodex.github.signing

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/** Generates an app-signing keystore and the four GitHub Actions secrets that carry it. */
object RepoSigning {
    const val SECRET_KEYSTORE = "KEYSTORE_BASE64"
    const val SECRET_STORE_PASSWORD = "KEYSTORE_PASSWORD"
    const val SECRET_ALIAS = "KEY_ALIAS"
    const val SECRET_KEY_PASSWORD = "KEY_PASSWORD"

    val SECRET_NAMES = listOf(SECRET_KEYSTORE, SECRET_STORE_PASSWORD, SECRET_ALIAS, SECRET_KEY_PASSWORD)
    const val VALIDITY_YEARS = 25

    data class Identity(val alias: String, val commonName: String, val orgUnit: String, val org: String, val country: String)

    private const val CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

    /** 24 letters/digits (no look-alikes, no shell-special characters, so it is safe in any CI script). */
    fun newPassword(): String {
        val r = SecureRandom()
        return buildString { repeat(24) { append(CHARS[r.nextInt(CHARS.length)]) } }
    }

    /** RSA key generation takes a moment on a phone, so this runs off the main thread. */
    suspend fun secrets(identity: Identity, password: String): Map<String, String> = withContext(Dispatchers.Default) {
        val store = KeystoreFactory.generate(
            identity.alias, password.toCharArray(), identity.commonName, identity.orgUnit, identity.org, identity.country, VALIDITY_YEARS,
        )
        linkedMapOf(
            SECRET_KEYSTORE to Base64.encodeToString(store, Base64.NO_WRAP),
            SECRET_STORE_PASSWORD to password,
            SECRET_ALIAS to identity.alias,
            // PKCS12: the store and key password are the same value.
            SECRET_KEY_PASSWORD to password,
        )
    }
}
