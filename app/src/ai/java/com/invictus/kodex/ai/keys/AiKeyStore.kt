package com.invictus.kodex.ai.keys

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * BYOK vault (plan 9.2): same Keystore-AES/GCM pattern as core/security/GitCredentialStore
 * but a separate master key + file, since AI keys are not git host credentials.
 * Keys never appear in prefs, logs or crash traces; a corrupt file degrades to "no keys".
 */
class AiKeyStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)

    @Synchronized
    fun get(providerId: String): String? = readAll()[providerId]

    @Synchronized
    fun put(providerId: String, key: String) {
        val all = readAll().toMutableMap()
        all[providerId] = key
        writeAll(all)
    }

    @Synchronized
    fun remove(providerId: String) {
        val all = readAll().toMutableMap()
        if (all.remove(providerId) != null) writeAll(all)
    }

    @Synchronized
    fun providers(): List<String> = readAll().keys.sorted()

    private fun readAll(): Map<String, String> = runCatching { readAllUnsafe() }.getOrDefault(emptyMap())

    private fun readAllUnsafe(): Map<String, String> {
        if (!file.exists() || file.length() == 0L) return emptyMap()
        val blob = file.readBytes()
        val iv = blob.copyOfRange(0, GCM_IV_BYTES)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val plain = String(cipher.doFinal(blob.copyOfRange(GCM_IV_BYTES, blob.size)), Charsets.UTF_8)
        val json = JSONObject(plain)
        val out = LinkedHashMap<String, String>()
        for (key in json.keys()) out[key] = json.getString(key)
        return out
    }

    private fun writeAll(all: Map<String, String>) {
        val json = JSONObject()
        all.forEach { (id, key) -> json.put(id, key) }
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(cipher.iv + encrypted)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "app_ai_key_master_key"
        private const val FILE_NAME = "ai_keys.enc"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
