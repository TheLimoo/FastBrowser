package com.fastbrowser.gologin.data

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/**
 * The v1.0.7 Kotlin app's storage, kept verbatim for the one-time upgrade
 * migration only. DataStore `fastbrowser_settings` + the Tink AES256-GCM
 * keyset (SharedPreferences `fastbrowser_tink_prefs`, master key
 * `android-keystore://fastbrowser_master_key`). Passwords were encrypted
 * with the server id as associated data and the `enc:v1:` prefix.
 * Nothing in v2 writes here — the migration only reads, decrypts, and
 * hands plaintext to Dart, which re-encrypts with its own key.
 */
val Context.legacyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fastbrowser_settings",
)

class LegacyCryptoManager(context: Context) {

    private val aead: Aead by lazy {
        AeadConfig.register()
        val keysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(context.applicationContext, KEYSET_NAME, PREF_FILE_NAME)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    fun decrypt(storedValue: String, associatedData: ByteArray): String {
        if (storedValue.isEmpty()) return ""
        if (!storedValue.startsWith(ENCRYPTED_PREFIX)) return storedValue
        val raw = Base64.decode(storedValue.removePrefix(ENCRYPTED_PREFIX), Base64.NO_WRAP)
        return String(aead.decrypt(raw, associatedData), Charsets.UTF_8)
    }

    private companion object {
        const val KEYSET_NAME = "fastbrowser_master_keyset"
        const val PREF_FILE_NAME = "fastbrowser_tink_prefs"
        const val MASTER_KEY_URI = "android-keystore://fastbrowser_master_key"
        const val ENCRYPTED_PREFIX = "enc:v1:"
    }
}
