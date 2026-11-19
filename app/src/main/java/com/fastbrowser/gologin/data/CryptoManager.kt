package com.fastbrowser.gologin.data

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

class CryptoManager(context: Context) {

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

    fun encrypt(plaintext: String, associatedData: ByteArray = DEFAULT_AAD): String {
        if (plaintext.isEmpty()) return ""
        val ciphertext = aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), associatedData)
        return ENCRYPTED_PREFIX + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    fun decrypt(storedValue: String, associatedData: ByteArray = DEFAULT_AAD): String {
        if (storedValue.isEmpty()) return ""
        // Graceful migration for legacy plaintext entries
        if (!storedValue.startsWith(ENCRYPTED_PREFIX)) return storedValue
        val raw = Base64.decode(storedValue.removePrefix(ENCRYPTED_PREFIX), Base64.NO_WRAP)
        return String(aead.decrypt(raw, associatedData), Charsets.UTF_8)
    }

    private companion object {
        const val KEYSET_NAME = "fastbrowser_master_keyset"
        const val PREF_FILE_NAME = "fastbrowser_tink_prefs"
        const val MASTER_KEY_URI = "android-keystore://fastbrowser_master_key"
        const val ENCRYPTED_PREFIX = "enc:v1:"
        val DEFAULT_AAD = "fastbrowser-datastore-v1".toByteArray(Charsets.UTF_8)
    }
}
