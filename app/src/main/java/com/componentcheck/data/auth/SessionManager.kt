package com.componentcheck.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class SessionManager(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "instagram_secure_prefs",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("instagram_fallback_prefs", Context.MODE_PRIVATE)
    }

    companion object {
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_CSRF_TOKEN = "csrf_token"
        private const val KEY_DS_USER_ID = "ds_user_id"
        private const val KEY_COOKIES_FULL = "cookies_full"
        private const val KEY_LOGGED_IN_USER = "logged_in_username"
    }

    fun saveCookiesFromUrl(url: String = "https://www.instagram.com"): Boolean {
        val cookieManager = CookieManager.getInstance()
        val cookieHeader = cookieManager.getCookie(url) ?: return false

        var sessionId: String? = null
        var csrfToken: String? = null
        var dsUserId: String? = null

        val pairs = cookieHeader.split(";")
        for (rawPair in pairs) {
            val pair = rawPair.trim()
            val equalsIndex = pair.indexOf('=')
            if (equalsIndex > 0) {
                val key = pair.substring(0, equalsIndex).trim()
                val value = pair.substring(equalsIndex + 1).trim()
                when (key) {
                    "sessionid" -> sessionId = value
                    "csrftoken" -> csrfToken = value
                    "ds_user_id" -> dsUserId = value
                }
            }
        }

        if (!sessionId.isNullOrBlank()) {
            prefs.edit()
                .putString(KEY_SESSION_ID, sessionId)
                .putString(KEY_CSRF_TOKEN, csrfToken)
                .putString(KEY_DS_USER_ID, dsUserId)
                .putString(KEY_COOKIES_FULL, cookieHeader)
                .apply()
            return true
        }

        return false
    }

    fun getSessionId(): String? {
        val saved = prefs.getString(KEY_SESSION_ID, null)
        if (!saved.isNullOrBlank()) return saved
        val live = getFullCookieHeader() ?: return null
        return live.split(";").find { it.trim().startsWith("sessionid=") }?.substringAfter("=")?.trim()
    }

    fun getCsrfToken(): String? {
        val live = getFullCookieHeader()
        if (!live.isNullOrBlank()) {
            val token = live.split(";").find { it.trim().startsWith("csrftoken=") }?.substringAfter("=")?.trim()
            if (!token.isNullOrBlank()) return token
        }
        return prefs.getString(KEY_CSRF_TOKEN, null)
    }

    fun getFullCookieHeader(): String? {
        val cm = try { CookieManager.getInstance() } catch (e: Exception) { null }
        val c1 = cm?.getCookie("https://www.instagram.com")
        val c2 = cm?.getCookie("https://instagram.com")
        val c3 = cm?.getCookie(".instagram.com")
        android.util.Log.i("ComponentCheck", "[SessionManager] Cookies: c1(www)=${c1?.length ?: 0}, c2(ig)=${c2?.length ?: 0}, c3(.ig)=${c3?.length ?: 0}")

        val bestLive = listOfNotNull(c1, c2, c3).maxByOrNull { it.length }
        if (!bestLive.isNullOrBlank() && bestLive.contains("sessionid=")) {
            return bestLive
        }
        return prefs.getString(KEY_COOKIES_FULL, null)
    }

    fun isLoggedIn(): Boolean = !getSessionId().isNullOrBlank()

    fun setLoggedInUsername(username: String) {
        prefs.edit().putString(KEY_LOGGED_IN_USER, username).apply()
    }

    fun getLoggedInUsername(): String? = prefs.getString(KEY_LOGGED_IN_USER, null)

    fun clearSession() {
        prefs.edit().clear().apply()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }
}
