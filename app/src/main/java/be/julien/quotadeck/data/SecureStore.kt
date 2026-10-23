package be.julien.quotadeck.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import be.julien.quotadeck.core.Credential
import be.julien.quotadeck.core.ProviderId
import be.julien.quotadeck.core.ProviderSnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** All saved credentials and snapshots are AES-GCM encrypted with a non-exportable Android Keystore key. */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("encrypted_accounts", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val alias = "quotadeck.accounts.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized private fun write(name: String, value: String?) {
        if (value == null) { prefs.edit().remove(name).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        prefs.edit().putString(name, encoded).apply()
    }
    @Synchronized private fun read(name: String): String? {
        val encoded = prefs.getString(name, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }.getOrNull()
    }
    fun credential(id: ProviderId): Credential? = read("credential.${id.name}")?.let {
        runCatching { json.decodeFromString<Credential>(it) }.getOrNull()
    }
    fun save(id: ProviderId, credential: Credential) = write("credential.${id.name}", json.encodeToString(credential))
    fun snapshot(id: ProviderId): ProviderSnapshot? = read("snapshot.${id.name}")?.let {
        runCatching { json.decodeFromString<ProviderSnapshot>(it) }.getOrNull()
    }
    fun save(snapshot: ProviderSnapshot) = write("snapshot.${snapshot.provider.name}", json.encodeToString(snapshot))
    fun forget(id: ProviderId) { write("credential.${id.name}", null); write("snapshot.${id.name}", null) }
}
