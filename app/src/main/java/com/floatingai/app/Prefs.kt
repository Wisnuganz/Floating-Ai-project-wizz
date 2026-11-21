package com.floatingai.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object Prefs {
    private const val ALIAS = "fai_key"
    private fun sp(c: Context) = c.getSharedPreferences("fai", Context.MODE_PRIVATE)

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return kg.generateKey()
    }

    fun setApiKey(c: Context, key: String) {
        val k = key.trim()
        if (k.isEmpty()) { sp(c).edit().remove("k").apply(); return }
        try {
            val ci = Cipher.getInstance("AES/GCM/NoPadding")
            ci.init(Cipher.ENCRYPT_MODE, secret())
            val ct = ci.doFinal(k.toByteArray(Charsets.UTF_8))
            sp(c).edit().putString("k", Base64.encodeToString(ci.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)).apply()
        } catch (e: Exception) { }
    }

    fun apiKey(c: Context): String = try {
        val p = sp(c).getString("k", null)?.split(":") ?: return ""
        val ci = Cipher.getInstance("AES/GCM/NoPadding")
        ci.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, Base64.decode(p[0], Base64.NO_WRAP)))
        String(ci.doFinal(Base64.decode(p[1], Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (e: Exception) { "" }

    fun model(c: Context): String = sp(c).getString("model", "gemini-2.5-flash")!!.ifBlank { "gemini-2.5-flash" }
    fun setModel(c: Context, m: String) = sp(c).edit().putString("model", m.trim()).apply()
    fun opacity(c: Context): Int = sp(c).getInt("opacity", 90)
    fun setOpacity(c: Context, v: Int) = sp(c).edit().putInt("opacity", v).apply()
    fun autoHide(c: Context): Boolean = sp(c).getBoolean("autohide", true)
    fun setAutoHide(c: Context, v: Boolean) = sp(c).edit().putBoolean("autohide", v).apply()

    fun loadHistory(c: Context): MutableList<GeminiClient.Msg> {
        val out = mutableListOf<GeminiClient.Msg>()
        try {
            val a = JSONArray(sp(c).getString("hist", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(GeminiClient.Msg(o.getString("r"), o.getString("t")))
            }
        } catch (e: Exception) { }
        return out
    }

    fun saveHistory(c: Context, h: List<GeminiClient.Msg>) {
        val a = JSONArray()
        h.takeLast(40).forEach { a.put(JSONObject().put("r", it.role).put("t", it.text.take(6000))) }
        sp(c).edit().putString("hist", a.toString()).apply()
    }

    fun clearHistory(c: Context) = sp(c).edit().remove("hist").apply()
}
