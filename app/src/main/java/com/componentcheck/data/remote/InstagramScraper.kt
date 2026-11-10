package com.componentcheck.data.remote

import android.content.Context
import android.util.Log
import com.componentcheck.data.auth.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class InstagramScraper(
    private val context: Context,
    private val sessionManager: SessionManager
) {

    private val headlessScraper = HeadlessWebViewScraper(context)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        private const val TAG = "ComponentCheck"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private const val IG_APP_ID = "936619743392459"

        fun parseFormattedCount(raw: String?): Long {
            if (raw.isNullOrBlank()) return 0L
            val clean = raw.trim().replace(",", "")
            return try {
                when {
                    clean.endsWith("k", ignoreCase = true) -> {
                        (clean.dropLast(1).toDouble() * 1_000).toLong()
                    }
                    clean.endsWith("m", ignoreCase = true) -> {
                        (clean.dropLast(1).toDouble() * 1_000_000).toLong()
                    }
                    clean.endsWith("b", ignoreCase = true) -> {
                        (clean.dropLast(1).toDouble() * 1_000_000_000).toLong()
                    }
                    else -> clean.toDouble().toLong()
                }
            } catch (e: Exception) {
                0L
            }
        }
    }

    suspend fun fetchProfile(handle: String): ProfileResult = withContext(Dispatchers.IO) {
        val cleanHandle = handle.trim().removePrefix("@").lowercase()
        if (cleanHandle.isBlank()) {
            return@withContext ProfileResult.Error("Username cannot be empty")
        }

        Log.i(TAG, "==================================================")
        Log.i(TAG, "==> fetchProfile started for @$cleanHandle (isLoggedIn=${sessionManager.isLoggedIn()})")

        // 1. If user is logged in, try authentic in-session headless WebView scraping first!
        // This runs inside the app's WebView context with all user session cookies automatically attached
        if (sessionManager.isLoggedIn()) {
            Log.i(TAG, "[Scraper] Trying Headless WebView scraping with authenticated session...")
            val webViewResult = headlessScraper.fetchProfileWithWebView(cleanHandle)
            Log.i(TAG, "[Scraper] Headless WebView result: $webViewResult")
            if (webViewResult is ProfileResult.Success) {
                Log.i(TAG, "Successfully scraped @$cleanHandle via Headless WebView: ${webViewResult.profile}")
                return@withContext webViewResult
            }
            if (webViewResult is ProfileResult.DeactivatedOrNotFound) {
                Log.i(TAG, "Headless WebView detected @$cleanHandle is Deactivated or Not Found")
                return@withContext webViewResult
            }
        }

        // 2. Try primary Web API endpoint
        val apiResult = fetchViaWebApi(cleanHandle)
        Log.i(TAG, "API Result: $apiResult")
        if (apiResult is ProfileResult.Success) {
            Log.i(TAG, "Successfully scraped @$cleanHandle via Web API: ${apiResult.profile}")
            return@withContext apiResult
        }

        // 3. Fallback to HTML OpenGraph / DOM Scraping
        val webResult = fetchViaWebHtml(cleanHandle)
        Log.i(TAG, "HTML Result: $webResult")
        if (webResult is ProfileResult.Success) {
            Log.i(TAG, "Successfully scraped @$cleanHandle via Web HTML: ${webResult.profile}")
            return@withContext webResult
        }

        // 4. If logged out, also try headless WebView as final attempt to let JS hydrate
        if (!sessionManager.isLoggedIn()) {
            Log.i(TAG, "[Scraper] Trying unauthenticated Headless WebView as fallback...")
            val fallbackWebViewResult = headlessScraper.fetchProfileWithWebView(cleanHandle)
            if (fallbackWebViewResult is ProfileResult.Success) {
                Log.i(TAG, "Successfully scraped @$cleanHandle via fallback WebView: ${fallbackWebViewResult.profile}")
                return@withContext fallbackWebViewResult
            }
        }

        Log.w(TAG, "Scraper could not fetch live stats for @$cleanHandle. API: $apiResult, HTML: $webResult")
        // Return the most informative result
        if (webResult !is ProfileResult.Error) webResult else apiResult
    }

    private fun fetchViaWebApi(handle: String): ProfileResult {
        val url = "https://www.instagram.com/api/v1/users/web_profile_info/?username=$handle"

        val cookie = sessionManager.getFullCookieHeader()
        val csrf = sessionManager.getCsrfToken()

        val cookieNames = cookie?.split(";")?.map { it.substringBefore("=").trim() }?.joinToString(", ")
        Log.i(TAG, "[WebApi] URL: $url | Cookie len: ${cookie?.length ?: 0} | Names: [$cookieNames] | CSRF: ${csrf ?: "null"}")

        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("x-ig-app-id", IG_APP_ID)
            .header("x-asbd-id", "129477")
            .header("x-requested-with", "XMLHttpRequest")
            .header("Accept", "*/*")
            .header("Referer", "https://www.instagram.com/$handle/")

        if (!csrf.isNullOrBlank()) {
            requestBuilder.header("x-csrftoken", csrf)
        }

        if (!cookie.isNullOrBlank()) {
            requestBuilder.header("Cookie", cookie)
        }

        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val code = response.code
                val body = response.body?.string() ?: ""
                Log.i(TAG, "[WebApi] HTTP code: $code | Body preview: ${body.take(250)}")

                when (code) {
                    200 -> {
                        if (body.isBlank()) return ProfileResult.Error("Empty response")
                        parseWebApiJson(handle, body)
                    }
                    404 -> ProfileResult.Error("Account web API returned 404")
                    429 -> ProfileResult.RateLimited("Instagram rate limit reached")
                    else -> ProfileResult.Error("HTTP $code: ${body.take(100)}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[WebApi] Exception: ${e.message}", e)
            ProfileResult.Error(e.message ?: "Connection error")
        }
    }

    private fun parseWebApiJson(handle: String, jsonString: String): ProfileResult {
        return try {
            val root = JSONObject(jsonString)
            val data = root.optJSONObject("data") ?: return ProfileResult.Error("Invalid API JSON")
            val user = data.optJSONObject("user") ?: return ProfileResult.Error("User object missing in API response")

            val fullName = user.optString("full_name", handle)
            val bio = user.optString("biography", "")
            val followerCount = user.optJSONObject("edge_followed_by")?.optLong("count", 0L) ?: 0L
            val followingCount = user.optJSONObject("edge_follow")?.optLong("count", 0L) ?: 0L
            val postCount = user.optJSONObject("edge_owner_to_timeline_media")?.optLong("count", 0L) ?: 0L
            val picUrl = user.optString("profile_pic_url_hd").takeIf { it.isNotBlank() }
                ?: user.optString("profile_pic_url", "")
            val isPrivate = user.optBoolean("is_private", false)
            val isVerified = user.optBoolean("is_verified", false)

            ProfileResult.Success(
                ScrapedProfile(
                    username = handle,
                    fullName = fullName,
                    bio = bio,
                    followerCount = followerCount,
                    followingCount = followingCount,
                    postCount = postCount,
                    profilePicUrl = picUrl,
                    isActive = true,
                    isPrivate = isPrivate,
                    isVerified = isVerified
                )
            )
        } catch (e: Exception) {
            ProfileResult.Error("Failed to parse profile JSON: ${e.message}")
        }
    }

    private fun fetchViaWebHtml(handle: String): ProfileResult {
        val url = "https://www.instagram.com/$handle/"
        Log.i(TAG, "[WebHtml] Connecting to: $url")
        return try {
            val connection = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(15000)
                .ignoreHttpErrors(true)
                .followRedirects(true)

            val cookie = sessionManager.getFullCookieHeader()
            if (!cookie.isNullOrBlank()) {
                connection.header("Cookie", cookie)
            }

            val doc = connection.get()
            val rawTitle = doc.title()
            Log.i(TAG, "[WebHtml] Page title: '$rawTitle'")

            val ogTitle = org.jsoup.parser.Parser.unescapeEntities(
                doc.select("meta[property=og:title]").attr("content").ifBlank { rawTitle },
                false
            )

            // Try OpenGraph description, then standard HTML meta description
            var desc = doc.select("meta[property=og:description]").attr("content")
            if (desc.isBlank()) {
                desc = doc.select("meta[name=description]").attr("content")
            }
            if (desc.isBlank()) {
                desc = doc.select("meta[property=twitter:description]").attr("content")
            }

            val unescapedDesc = org.jsoup.parser.Parser.unescapeEntities(desc, false)
            val ogImage = doc.select("meta[property=og:image]").attr("content").ifBlank {
                doc.select("meta[name=twitter:image]").attr("content")
            }

            Log.i(TAG, "[WebHtml] Title: '$ogTitle' | Description: '$unescapedDesc' | Image: '$ogImage'")

            if (rawTitle.contains("Page Not Found", ignoreCase = true) ||
                rawTitle.contains("isn't available", ignoreCase = true) ||
                rawTitle.contains("broken link", ignoreCase = true)
            ) {
                Log.w(TAG, "[WebHtml] Page Not Found / Unavailable detected in title")
                return ProfileResult.DeactivatedOrNotFound(handle)
            }

            var followers = 0L
            var following = 0L
            var posts = 0L

            if (unescapedDesc.isNotBlank()) {
                // Independent regex for followers (supports singular 'follower' and plural 'followers')
                val followerMatcher = Pattern.compile("([\\d,\\.KkMmBb]+)\\s+follower", Pattern.CASE_INSENSITIVE).matcher(unescapedDesc)
                if (followerMatcher.find()) {
                    followers = parseFormattedCount(followerMatcher.group(1))
                }

                // Independent regex for following
                val followingMatcher = Pattern.compile("([\\d,\\.KkMmBb]+)\\s+following", Pattern.CASE_INSENSITIVE).matcher(unescapedDesc)
                if (followingMatcher.find()) {
                    following = parseFormattedCount(followingMatcher.group(1))
                }

                // Independent regex for posts (supports 'post' and 'posts')
                val postMatcher = Pattern.compile("([\\d,\\.KkMmBb]+)\\s+post", Pattern.CASE_INSENSITIVE).matcher(unescapedDesc)
                if (postMatcher.find()) {
                    posts = parseFormattedCount(postMatcher.group(1))
                }
            }

            Log.i(TAG, "[WebHtml] Parsed counts: Followers=$followers, Following=$following, Posts=$posts")

            val name = if (ogTitle.isNotBlank() && (ogTitle.contains("(@") || ogTitle.contains("(&#064;"))) {
                ogTitle.substringBefore("(@").substringBefore("(&#064;").trim()
            } else {
                handle
            }

            // Note: og:description contains counter boilerplate (e.g. "X Followers, Y Following... - See Instagram photos...")
            // The real bio is never in HTML meta tags, so we set bio = "" here and let the DOM scraper / API extract the real bio.
            val cleanBio = ""

            if (followers > 0L || following > 0L || posts > 0L || unescapedDesc.isNotBlank() || ogTitle.contains(handle, ignoreCase = true)) {
                return ProfileResult.Success(
                    ScrapedProfile(
                        username = handle,
                        fullName = name.ifBlank { handle },
                        bio = cleanBio,
                        followerCount = followers,
                        followingCount = following,
                        postCount = posts,
                        profilePicUrl = ogImage,
                        isActive = true,
                        isPrivate = false,
                        isVerified = false
                    )
                )
            }

            Log.w(TAG, "[WebHtml] Could not extract OpenGraph metadata from page")
            ProfileResult.Error("Could not extract OpenGraph metadata")
        } catch (e: Exception) {
            Log.e(TAG, "[WebHtml] Exception: ${e.message}", e)
            ProfileResult.Error(e.message ?: "Web request failed")
        }
    }
}
