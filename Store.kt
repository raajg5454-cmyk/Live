package com.livehelp

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object Store {
    private fun p(c: Context) = EncryptedSharedPreferences.create(
        c, "lh", MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    fun token(c: Context): String? = p(c).getString("t", null)
    fun save(c: Context, t: String) = p(c).edit().putString("t", t).apply()
    fun clear(c: Context) = p(c).edit().remove("t").apply()
    fun consented(c: Context) = p(c).getBoolean("consent", false)
    fun setConsented(c: Context) = p(c).edit().putBoolean("consent", true).apply()
}
