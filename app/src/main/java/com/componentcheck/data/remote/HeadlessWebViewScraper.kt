package com.componentcheck.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.regex.Pattern
import kotlin.coroutines.resume

class HeadlessWebViewScraper(private val context: Context) {

    companion object {
        private const val TAG = "ComponentCheck"
        private const val TIMEOUT_MS = 20000L
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }

    class ScraperBridge(private val onResultReceived: (String) -> Unit) {
        @JavascriptInterface
        fun postResult(data: String) {
            Log.i(TAG, "[WebViewBridge] Data received: ${data.take(200)}")
            onResultReceived(data)
        }
    }

    suspend fun fetchProfileWithWebView(handle: String): ProfileResult = withContext(Dispatchers.Main) {
        val cleanHandle = handle.trim().removePrefix("@").lowercase()
        Log.i(TAG, "[WebViewScraper] Starting authenticated scrape for @$cleanHandle")

        val result = withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine<ProfileResult> { cont ->
                var webView: WebView? = null
                var isFinished = false

                fun finishWith(res: ProfileResult) {
                    if (!isFinished) {
                        isFinished = true
                        try {
                            webView?.stopLoading()
                            webView?.destroy()
                        } catch (e: Exception) {
                            Log.w(TAG, "[WebViewScraper] Cleanup error: ${e.message}")
                        }
                        webView = null
                        if (cont.isActive) {
                            cont.resume(res)
                        }
                    }
                }

                try {
                    webView = WebView(context.applicationContext)
                    setupWebView(webView!!)
                    webView!!.resumeTimers()

                    val bridge = ScraperBridge { rawJson ->
                        Handler(Looper.getMainLooper()).post {
                            if (isFinished) return@post
                            val parsed = parseBridgeResult(cleanHandle, rawJson)
                            finishWith(parsed)
                        }
                    }
                    webView!!.addJavascriptInterface(bridge, "ScraperBridge")

                    val targetUrl = "https://www.instagram.com/$cleanHandle/"
                    Log.i(TAG, "[WebViewScraper] Loading URL: $targetUrl")

                    webView!!.webViewClient = object : WebViewClient() {
                        private var hasFired = false

                        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                            Log.w(TAG, "[WebViewScraper] onRenderProcessGone handled safely")
                            try {
                                view?.destroy()
                            } catch (e: Exception) {}
                            return true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            Log.i(TAG, "[WebViewScraper] onPageFinished: $url")
                            if (hasFired || isFinished || view == null) return
                            hasFired = true

                            // Wait 2 seconds for client-side rendering
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (isFinished) return@postDelayed
                                injectScraperScript(view, cleanHandle)
                            }, 2000)
                        }
                    }

                    cont.invokeOnCancellation {
                        Handler(Looper.getMainLooper()).post {
                            try {
                                webView?.stopLoading()
                                webView?.destroy()
                            } catch (e: Exception) {}
                        }
                    }

                    webView!!.loadUrl(targetUrl)

                } catch (e: Exception) {
                    Log.e(TAG, "[WebViewScraper] Error during scrape setup: ${e.message}", e)
                    finishWith(ProfileResult.Error(e.message ?: "Failed to start WebView"))
                }
            }
        }

        result ?: ProfileResult.Error("WebView scrape timed out after ${TIMEOUT_MS / 1000}s")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView) {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = USER_AGENT
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
    }

    private fun injectScraperScript(webView: WebView, handle: String) {
        val script = """
            (function() {
                try {
                    let desc = document.querySelector('meta[property="og:description"]')?.content || 
                               document.querySelector('meta[name="description"]')?.content || '';
                    let title = document.querySelector('meta[property="og:title"]')?.content || document.title || '';
                    let img = document.querySelector('meta[property="og:image"]')?.content || '';
                    
                    let header = document.querySelector('header') || document.querySelector('main section') || document.querySelector('main');
                    let headerText = header ? header.innerText : '';
                    let sectionText = document.querySelector('main')?.innerText || '';
                    let fullText = (headerText + '\n' + sectionText).substring(0, 4000);
                    let bodyText = document.body ? document.body.innerText.substring(0, 2000) : '';

                    // Check for active profile signals:
                    let corpus = desc + '\n' + fullText;
                    let hasStats = /[\d,\.]+\s+(followers?|following|posts?)/i.test(corpus);
                    let hasHandleInTitle = title.toLowerCase().includes('(@' + '$handle'.toLowerCase() + ')');
                    let hasHandleInDesc = desc.toLowerCase().includes('(@' + '$handle'.toLowerCase() + ')');

                    // True deactivation / missing check:
                    let isNotFound = !hasStats && !hasHandleInTitle && !hasHandleInDesc && (
                        title.includes("Page Not Found") ||
                        title.includes("isn't available") ||
                        bodyText.includes("Sorry, this page isn't available") ||
                        bodyText.includes("The link you followed may be broken") ||
                        bodyText.includes("page may have been removed")
                    );

                    if (isNotFound) {
                        window.ScraperBridge.postResult(JSON.stringify({
                            success: true,
                            source: 'DEACTIVATED',
                            isDeactivated: true
                        }));
                        return;
                    }

                    // 1. Live Counters directly from DOM profile card elements (more up-to-date than CDN meta tags)
                    let domFollowers = '';
                    let domFollowing = '';
                    let domPosts = '';

                    let followersEl = document.querySelector('header a[href*="/followers"], a[href*="/followers"]');
                    if (followersEl) {
                        let spanWithTitle = followersEl.querySelector('span[title]');
                        domFollowers = (spanWithTitle ? spanWithTitle.getAttribute('title') : '') || followersEl.innerText || '';
                    }

                    let followingEl = document.querySelector('header a[href*="/following"], a[href*="/following"]');
                    if (followingEl) {
                        let spanWithTitle = followingEl.querySelector('span[title]');
                        domFollowing = (spanWithTitle ? spanWithTitle.getAttribute('title') : '') || followingEl.innerText || '';
                    }

                    let postsEl = Array.from(document.querySelectorAll('header ul li, header li, ul li'))
                        .find(el => el.innerText && /posts?/i.test(el.innerText) && !/followers?|following/i.test(el.innerText));
                    if (postsEl) {
                        let spanWithTitle = postsEl.querySelector('span[title]');
                        domPosts = (spanWithTitle ? spanWithTitle.getAttribute('title') : '') || postsEl.innerText || '';
                    }

                    // 2. Live Display Name from DOM profile card
                    let domFullName = '';
                    if (header) {
                        let nameEl = header.querySelector('section > div:last-child h1, section > div:last-child span, h1');
                        if (nameEl && nameEl.innerText && !nameEl.innerText.toLowerCase().includes('$handle'.toLowerCase())) {
                            domFullName = nameEl.innerText.trim();
                        }
                    }

                    // 3. Deeper DOM extraction for user Bio (never present in meta tags)
                    let domBio = '';
                    if (header) {
                        let bioCandidates = Array.from(header.querySelectorAll('div, span, p, h1')).filter(el => {
                            if (el.closest('ul') || el.closest('button') || el.closest('a') || el.closest('nav')) return false;
                            let text = el.innerText ? el.innerText.trim() : '';
                            if (!text) return false;
                            if (text.toLowerCase() === '$handle'.toLowerCase() || text.toLowerCase() === ('@' + '$handle').toLowerCase()) return false;
                            if (/^(follow|following|edit profile|share profile|message|contact|options)$/i.test(text)) return false;
                            if (/^[\d,\.]+\s+(followers?|following|posts?)$/i.test(text)) return false;
                            if (text.includes('followers') && text.includes('following')) return false;
                            if (domFullName && text === domFullName) return false;

                            let style = window.getComputedStyle(el);
                            let ws = style ? (style.whiteSpace || '') : '';
                            let isPre = ws.includes('pre') || ws.includes('break');
                            let isDirAuto = el.getAttribute('dir') === 'auto';
                            let isBioClass = el.className && typeof el.className === 'string' && (el.className.includes('_ap3a') || el.className.includes('_aa_c'));

                            return isPre || isDirAuto || isBioClass || text.includes('\n');
                        });

                        for (let el of bioCandidates) {
                            let t = el.innerText ? el.innerText.trim() : '';
                            if (t.length > domBio.length && (!domFullName || t !== domFullName)) {
                                domBio = t;
                            }
                        }
                    }

                    // 4. Official user biography and full_name from embedded JSON script tags
                    let scriptBio = '';
                    let scriptFullName = '';
                    try {
                        let scriptTags = document.querySelectorAll('script[type="application/json"]');
                        for (let s of scriptTags) {
                            let text = s.innerText || s.textContent || '';
                            if (!text.includes('biography')) continue;
                            try {
                                let parsed = JSON.parse(text);
                                function findUserInObj(obj) {
                                    if (!obj || typeof obj !== 'object') return;
                                    if ('biography' in obj && 'username' in obj) {
                                        if (String(obj.username).toLowerCase() === '$handle'.toLowerCase()) {
                                            scriptBio = obj.biography || '';
                                            scriptFullName = obj.full_name || '';
                                            return;
                                        }
                                    }
                                    for (let k of Object.keys(obj)) {
                                        if (!scriptBio && !scriptFullName) {
                                            findUserInObj(obj[k]);
                                        }
                                    }
                                }
                                findUserInObj(parsed);
                                if (scriptBio || scriptFullName) break;
                            } catch (e) {}
                        }
                    } catch (e) {}

                    window.ScraperBridge.postResult(JSON.stringify({
                        success: true,
                        source: 'DOM',
                        desc: desc,
                        title: title,
                        img: img,
                        domFollowers: domFollowers,
                        domFollowing: domFollowing,
                        domPosts: domPosts,
                        domFullName: domFullName,
                        domBio: domBio,
                        scriptBio: scriptBio,
                        scriptFullName: scriptFullName,
                        headerText: headerText,
                        fullText: fullText
                    }));
                } catch (err) {
                    window.ScraperBridge.postResult(JSON.stringify({
                        success: false,
                        error: err.toString()
                    }));
                }
            })();
        """.trimIndent()

        Log.i(TAG, "[WebViewScraper] Injecting scraper script into page...")
        webView.evaluateJavascript(script, null)
    }

    private fun parseBridgeResult(handle: String, rawJson: String): ProfileResult {
        return try {
            val root = JSONObject(rawJson)
            if (!root.optBoolean("success", true)) {
                return ProfileResult.Error(root.optString("error", "Scraper script failed"))
            }

            val source = root.optString("source")
            if (root.optBoolean("isDeactivated", false) || source == "DEACTIVATED" || source == "API_404") {
                Log.i(TAG, "[WebViewScraper] Account @$handle identified as DEACTIVATED / Not Found")
                return ProfileResult.DeactivatedOrNotFound(handle)
            }

            if (source == "API") {
                val user = root.getJSONObject("user")
                val fullName = user.optString("full_name", handle)
                val bio = user.optString("biography", "")
                val followerCount = user.optJSONObject("edge_followed_by")?.optLong("count", 0L) ?: 0L
                val followingCount = user.optJSONObject("edge_follow")?.optLong("count", 0L) ?: 0L
                val postCount = user.optJSONObject("edge_owner_to_timeline_media")?.optLong("count", 0L) ?: 0L
                val picUrl = user.optString("profile_pic_url_hd").takeIf { it.isNotBlank() }
                    ?: user.optString("profile_pic_url", "")
                val isPrivate = user.optBoolean("is_private", false)
                val isVerified = user.optBoolean("is_verified", false)

                Log.i(TAG, "[WebViewScraper] Scraped via in-page API: $followerCount followers, $followingCount following, $postCount posts")
                return ProfileResult.Success(
                    ScrapedProfile(
                        username = handle,
                        fullName = fullName.ifBlank { handle },
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
            }

            // Fallback to DOM/Text parsing
            val desc = root.optString("desc")
            val title = root.optString("title")
            val img = root.optString("img")
            val domFollowersRaw = root.optString("domFollowers").trim()
            val domFollowingRaw = root.optString("domFollowing").trim()
            val domPostsRaw = root.optString("domPosts").trim()
            val domFullName = root.optString("domFullName").trim()
            val domBio = root.optString("domBio").trim()
            val scriptBio = root.optString("scriptBio").trim()
            val scriptFullName = root.optString("scriptFullName").trim()
            val headerText = root.optString("headerText")
            val fullText = root.optString("fullText")

            Log.i(TAG, "[WebViewScraper] Parsing DOM Profile Card: domF='$domFollowersRaw', domIng='$domFollowingRaw', domP='$domPostsRaw', domName='$domFullName', domBio='$domBio'")

            var followers = 0L
            var following = 0L
            var posts = 0L

            // 1. Live Counters: Priority 1 = Direct DOM card elements (most up-to-date)
            if (domFollowersRaw.isNotBlank()) {
                val m = Pattern.compile("([\\d,\\.KkMmBb]+)").matcher(domFollowersRaw)
                if (m.find()) followers = InstagramScraper.parseFormattedCount(m.group(1))
            }
            if (domFollowingRaw.isNotBlank()) {
                val m = Pattern.compile("([\\d,\\.KkMmBb]+)").matcher(domFollowingRaw)
                if (m.find()) following = InstagramScraper.parseFormattedCount(m.group(1))
            }
            if (domPostsRaw.isNotBlank()) {
                val m = Pattern.compile("([\\d,\\.KkMmBb]+)").matcher(domPostsRaw)
                if (m.find()) posts = InstagramScraper.parseFormattedCount(m.group(1))
            }

            // Priority 2 = Parsed from rendered DOM headerText
            if (followers == 0L && headerText.isNotBlank()) {
                val m = Pattern.compile("([\\d,\\.KkMmBb]+)\\s+follower", Pattern.CASE_INSENSITIVE).matcher(headerText)
                if (m.find()) followers = InstagramScraper.parseFormattedCount(m.group(1))
            }
            if (following == 0L && headerText.isNotBlank()) {
                val m = Pattern.compile("([\\d,\\.KkMmBb]+)\\s+following", Pattern.CASE_INSENSITIVE).matcher(headerText)
                if (m.find()) following = InstagramScraper.parseFormattedCount(m.group(1))
            }
            if (posts == 0L && headerText.isNotBlank()) {
                val m = Pattern.compile("([\\d,\\.KkMmBb]+)\\s+post", Pattern.CASE_INSENSITIVE).matcher(headerText)
                if (m.find()) posts = InstagramScraper.parseFormattedCount(m.group(1))
            }

            // Priority 3 = Fallback to meta description ONLY if live DOM exposed no counters
            if (followers == 0L && desc.isNotBlank()) {
                val m = Pattern.compile("([\\d,\\.KkMmBb]+)\\s+follower", Pattern.CASE_INSENSITIVE).matcher(desc)
                if (m.find()) followers = InstagramScraper.parseFormattedCount(m.group(1))
            }
            if (following == 0L && desc.isNotBlank()) {
                val m = Pattern.compile("([\\d,\\.KkMmBb]+)\\s+following", Pattern.CASE_INSENSITIVE).matcher(desc)
                if (m.find()) following = InstagramScraper.parseFormattedCount(m.group(1))
            }
            if (posts == 0L && desc.isNotBlank()) {
                val m = Pattern.compile("([\\d,\\.KkMmBb]+)\\s+post", Pattern.CASE_INSENSITIVE).matcher(desc)
                if (m.find()) posts = InstagramScraper.parseFormattedCount(m.group(1))
            }

            Log.i(TAG, "[WebViewScraper] Resolved Live Counts: Followers=$followers, Following=$following, Posts=$posts")

            val hasHandleInTitle = title.contains("(@$handle", ignoreCase = true)
            val hasHandleInDesc = desc.contains("(@$handle", ignoreCase = true)
            val hasValidStats = followers > 0L || following > 0L || posts > 0L

            // Only consider account deactivated if there are NO valid stats and NO handle confirmation in title/desc
            if (!hasValidStats && !hasHandleInTitle && !hasHandleInDesc) {
                if (title.contains("Page Not Found", ignoreCase = true) ||
                    title.contains("isn't available", ignoreCase = true) ||
                    fullText.contains("Sorry, this page isn't available", ignoreCase = true) ||
                    fullText.contains("link you followed may be broken", ignoreCase = true) ||
                    fullText.contains("page may have been removed", ignoreCase = true) ||
                    (headerText.isBlank() && desc.isBlank())
                ) {
                    Log.w(TAG, "[WebViewScraper] Confirmed unavailable / deactivated page for @$handle")
                    return ProfileResult.DeactivatedOrNotFound(handle)
                }
            }

            // 2. Extract Full Name: Prioritize live DOM card -> script state -> title
            var fullName = if (domFullName.isNotBlank() && !domFullName.equals(handle, ignoreCase = true)) {
                domFullName
            } else if (scriptFullName.isNotBlank() && !scriptFullName.equals(handle, ignoreCase = true)) {
                scriptFullName
            } else {
                ""
            }

            if (fullName.isBlank() && title.contains("(@")) {
                val candidate = title.substringBefore("(@").trim()
                if (candidate.isNotBlank() && !candidate.equals(handle, ignoreCase = true) && !candidate.equals("Instagram", ignoreCase = true)) {
                    fullName = candidate
                }
            }
            if (fullName.isBlank() && desc.contains("from ")) {
                val afterFrom = desc.substringAfter("from ")
                if (afterFrom.contains("(@")) {
                    val candidate = afterFrom.substringBefore("(@").trim()
                    if (candidate.isNotBlank() && !candidate.equals(handle, ignoreCase = true)) {
                        fullName = candidate
                    }
                }
            }

            // 3. Extract Bio: Read deeper DOM elements from profile card (never from meta tags)
            var bio = if (domBio.isNotBlank()) domBio else scriptBio
            if (bio.isBlank()) {
                val domText = if (headerText.isNotBlank()) headerText else fullText
                if (domText.isNotBlank()) {
                    val lines = domText.split("\n").map { it.trim() }.filter { it.isNotBlank() }
                    // Match followers/following specifically to avoid matching the POSTS tab bar
                    var lastStatIndex = -1
                    for (i in lines.indices) {
                        val l = lines[i].lowercase()
                        if (l == "followers" || l == "follower" || l == "following") {
                            lastStatIndex = i
                        }
                    }

                    if (lastStatIndex != -1 && lastStatIndex + 1 < lines.size) {
                        val afterStats = lines.subList(lastStatIndex + 1, lines.size)
                        val actionButtons = setOf(
                            "follow", "following", "requested", "message", "edit profile",
                            "contact", "options", "share profile", "threads", "similar accounts"
                        )
                        val stopKeywords = setOf(
                            "posts", "reels", "tagged", "see instagram", "more posts from",
                            "about", "blog", "help", "privacy", "terms"
                        )
                        val bioLines = mutableListOf<String>()

                        for (line in afterStats) {
                            val lower = line.lowercase()
                            // Stop immediately upon hitting the photo grid tab bar or footer
                            if (stopKeywords.contains(lower) ||
                                lower.startsWith("see instagram") ||
                                lower.startsWith("more posts from") ||
                                lower.startsWith("http://") ||
                                lower.startsWith("https://")
                            ) {
                                break
                            }
                            // Skip action buttons
                            if (actionButtons.contains(lower)) {
                                continue
                            }
                            // Skip handle if repeated
                            if (lower == handle.lowercase() || lower == "@${handle.lowercase()}") {
                                continue
                            }
                            // Skip display name if it matches fullName
                            if (fullName.isNotBlank() && line == fullName) {
                                continue
                            }
                            // If fullName is still blank, first candidate line is display name
                            if (fullName.isBlank() && bioLines.isEmpty()) {
                                fullName = line
                                continue
                            }

                            bioLines.add(line)
                        }

                        if (bioLines.isNotEmpty()) {
                            bio = bioLines.joinToString("\n")
                        }
                    }
                }
            }

            // Fallback for fullName if none discovered
            if (fullName.isBlank()) {
                fullName = handle
            }

            // Remove leading display name from bio if duplicated
            if (fullName.isNotBlank() && bio.startsWith(fullName)) {
                bio = bio.removePrefix(fullName).trim()
            }

            // Strict cleanup: Disallow standard Instagram boilerplate from being treated as a bio
            if (bio.contains("See Instagram photos and videos", ignoreCase = true) ||
                bio.matches(Regex("(?i)^[\\d,\\.KkMmBb]+\\s+Followers?,\\s+[\\d,\\.KkMmBb]+\\s+Following.*")) ||
                bio.startsWith("Monitoring active for")
            ) {
                bio = ""
            }

            Log.i(TAG, "[WebViewScraper] Final parsed profile: name='$fullName', bio='$bio'")

            // If account has zero counts, no header, and no profile info -> likely deactivated
            if (followers == 0L && following == 0L && posts == 0L && headerText.isBlank() && !desc.contains("from ")) {
                Log.w(TAG, "[WebViewScraper] Zero stats and missing header -> @$handle is Deactivated")
                return ProfileResult.DeactivatedOrNotFound(handle)
            }

            if (followers > 0L || following > 0L || posts > 0L || desc.isNotBlank() || headerText.isNotBlank()) {
                ProfileResult.Success(
                    ScrapedProfile(
                        username = handle,
                        fullName = fullName,
                        bio = bio,
                        followerCount = followers,
                        followingCount = following,
                        postCount = posts,
                        profilePicUrl = img,
                        isActive = true,
                        isPrivate = false,
                        isVerified = false
                    )
                )
            } else {
                ProfileResult.DeactivatedOrNotFound(handle)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[WebViewScraper] Error parsing bridge result: ${e.message}", e)
            ProfileResult.Error("Bridge parse exception: ${e.message}")
        }
    }
}
