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
        private const val TIMEOUT_MS = 25000L
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }

    class ScraperBridge(private val onResultReceived: (String) -> Unit) {
        @JavascriptInterface
        fun postResult(data: String) {
            Log.i(TAG, "[WebViewBridge] Data received: ${data.take(300)}")
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

                            // Inject active polling scraper into the live page
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (isFinished) return@postDelayed
                                injectScraperScript(view, cleanHandle)
                            }, 500)
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
                let cleanHandle = '$handle'.toLowerCase();
                let startTime = Date.now();
                let maxWaitMs = 14000;
                let pollIntervalMs = 250;
                let hasPosted = false;

                function postResult(payload) {
                    if (hasPosted) return;
                    hasPosted = true;
                    if (window._scraperPollTimer) {
                        clearInterval(window._scraperPollTimer);
                        window._scraperPollTimer = null;
                    }
                    window.ScraperBridge.postResult(JSON.stringify(payload));
                }

                function extractScriptData(handle) {
                    let scriptBio = '';
                    let scriptFullName = '';
                    let scriptFollowers = '';
                    let scriptFollowing = '';
                    let scriptPosts = '';
                    let scriptPic = '';

                    try {
                        let scripts = document.querySelectorAll('script[type="application/json"], script:not([src])');
                        for (let s of scripts) {
                            let text = s.innerText || s.textContent || '';
                            if (!text || (!text.includes('biography') && !text.includes('edge_follow') && !text.includes('following_count'))) continue;

                            // 1. Try deep JSON search if valid JSON
                            if (text.trim().startsWith('{') || text.trim().startsWith('[')) {
                                try {
                                    let parsed = JSON.parse(text);
                                    function findUser(obj) {
                                        if (!obj || typeof obj !== 'object') return;
                                        if ('biography' in obj && ('username' in obj || 'full_name' in obj)) {
                                            let uname = String(obj.username || '').toLowerCase();
                                            if (!uname || uname === handle) {
                                                if (!scriptBio && obj.biography) scriptBio = String(obj.biography);
                                                if (!scriptFullName && obj.full_name) scriptFullName = String(obj.full_name);
                                                if (!scriptFollowers && obj.edge_followed_by && obj.edge_followed_by.count !== undefined) {
                                                    scriptFollowers = String(obj.edge_followed_by.count);
                                                }
                                                if (!scriptFollowing && obj.edge_follow && obj.edge_follow.count !== undefined) {
                                                    scriptFollowing = String(obj.edge_follow.count);
                                                }
                                                if (!scriptPosts && obj.edge_owner_to_timeline_media && obj.edge_owner_to_timeline_media.count !== undefined) {
                                                    scriptPosts = String(obj.edge_owner_to_timeline_media.count);
                                                }
                                                if (!scriptPic && (obj.profile_pic_url_hd || obj.profile_pic_url)) {
                                                    scriptPic = String(obj.profile_pic_url_hd || obj.profile_pic_url);
                                                }
                                                return;
                                            }
                                        }
                                        if (Array.isArray(obj)) {
                                            for (let item of obj) findUser(item);
                                        } else {
                                            for (let k of Object.keys(obj)) findUser(obj[k]);
                                        }
                                    }
                                    findUser(parsed);
                                } catch (e) {}
                            }

                            // 2. Regex fallback on script text
                            if (!scriptBio) {
                                let m = text.match(/"biography"\s*:\s*"((?:[^"\\]|\\.)*)"/);
                                if (m && m[1]) {
                                    try { scriptBio = JSON.parse('"' + m[1] + '"'); } catch (e) { scriptBio = m[1].replace(/\\n/g, '\n').replace(/\\"/g, '"'); }
                                }
                            }
                            if (!scriptFullName) {
                                let m = text.match(/"full_name"\s*:\s*"((?:[^"\\]|\\.)*)"/);
                                if (m && m[1]) {
                                    try { scriptFullName = JSON.parse('"' + m[1] + '"'); } catch (e) { scriptFullName = m[1]; }
                                }
                            }
                            if (!scriptFollowing) {
                                let m = text.match(/"edge_follow"\s*:\s*\{\s*"count"\s*:\s*(\d+)/) || text.match(/"following_count"\s*:\s*(\d+)/);
                                if (m && m[1]) scriptFollowing = m[1];
                            }
                            if (!scriptFollowers) {
                                let m = text.match(/"edge_followed_by"\s*:\s*\{\s*"count"\s*:\s*(\d+)/) || text.match(/"follower_count"\s*:\s*(\d+)/);
                                if (m && m[1]) scriptFollowers = m[1];
                            }
                            if (!scriptPosts) {
                                let m = text.match(/"edge_owner_to_timeline_media"\s*:\s*\{\s*"count"\s*:\s*(\d+)/) || text.match(/"media_count"\s*:\s*(\d+)/);
                                if (m && m[1]) scriptPosts = m[1];
                            }
                            if (!scriptPic) {
                                let m = text.match(/"profile_pic_url_hd"\s*:\s*"((?:[^"\\]|\\.)*)"/) || text.match(/"profile_pic_url"\s*:\s*"((?:[^"\\]|\\.)*)"/);
                                if (m && m[1]) {
                                    try { scriptPic = JSON.parse('"' + m[1] + '"'); } catch (e) { scriptPic = m[1]; }
                                }
                            }

                            if (scriptBio && scriptFollowing) break;
                        }
                    } catch (e) {}

                    return { scriptBio, scriptFullName, scriptFollowers, scriptFollowing, scriptPosts, scriptPic };
                }

                function extractStatCount(keyword) {
                    let root = document.querySelector('header') || document.querySelector('main section') || document.querySelector('main');
                    if (!root) return '';

                    // 1. Check anchor or list item matching href
                    let el = root.querySelector('a[href*="/' + keyword + '"], li:has(a[href*="/' + keyword + '"])') ||
                             document.querySelector('a[href*="/' + keyword + '"]');
                    if (el) {
                        let span = el.querySelector('span[title]');
                        if (span && span.getAttribute('title')) {
                            let t = span.getAttribute('title').trim();
                            if (/^[\d,\.]+[KkMmBb]?${'$'}/.test(t)) return t;
                        }
                        let txt = el.innerText || '';
                        let m = txt.match(/([\d,\.]+[KkMmBb]?)\s*(?:followers?|following|posts?)/i) || 
                                txt.match(/(?:followers?|following|posts?)\s*([\d,\.]+[KkMmBb]?)/i) ||
                                txt.match(/^([\d,\.]+[KkMmBb]?)${'$'}/);
                        if (m) return m[1];
                    }

                    // 2. Search all elements containing keyword as a word
                    let regex = new RegExp('\\b' + keyword + '\\b', 'i');
                    let elements = Array.from(root.querySelectorAll('li, a, div, span, button'))
                        .filter(e => e.children.length <= 3 && e.innerText && regex.test(e.innerText) && !/edit profile|share profile|message|contact/i.test(e.innerText));
                    
                    for (let e of elements) {
                        let span = e.querySelector('span[title]');
                        if (span && span.getAttribute('title')) {
                            let t = span.getAttribute('title').trim();
                            if (/^[\d,\.]+[KkMmBb]?${'$'}/.test(t)) return t;
                        }
                        let m = e.innerText.match(/([\d,\.]+[KkMmBb]?)\s*(?:followers?|following|posts?)/i) ||
                                e.innerText.match(/(?:followers?|following|posts?)\s*([\d,\.]+[KkMmBb]?)/i);
                        if (m) return m[1];
                        if (e.previousElementSibling) {
                            let prevText = (e.previousElementSibling.innerText || '').trim();
                            if (/^[\d,\.]+[KkMmBb]?${'$'}/.test(prevText)) return prevText;
                        }
                        if (e.nextElementSibling) {
                            let nextText = (e.nextElementSibling.innerText || '').trim();
                            if (/^[\d,\.]+[KkMmBb]?${'$'}/.test(nextText)) return nextText;
                        }
                    }

                    // 3. Line by line search in root.innerText
                    let lines = (root.innerText || '').split('\n').map(l => l.trim()).filter(Boolean);
                    for (let i = 0; i < lines.length; i++) {
                        let l = lines[i].toLowerCase();
                        if (l === keyword || l === keyword + 's' || (keyword === 'followers' && l === 'follower')) {
                            if (i > 0 && /^[\d,\.]+[KkMmBb]?${'$'}/.test(lines[i - 1])) {
                                return lines[i - 1];
                            }
                            if (i + 1 < lines.length && /^[\d,\.]+[KkMmBb]?${'$'}/.test(lines[i + 1])) {
                                return lines[i + 1];
                            }
                        }
                        let m = lines[i].match(new RegExp('([\\d,\\.]+[KkMmBb]?)\\s+' + keyword, 'i')) ||
                                lines[i].match(new RegExp(keyword + '\\s+([\\d,\\.]+[KkMmBb]?)', 'i'));
                        if (m) return m[1];
                    }

                    return '';
                }

                function extractDomFullName(handle) {
                    let root = document.querySelector('header') || document.querySelector('main section') || document.querySelector('main');
                    if (!root) return '';

                    let candidates = Array.from(root.querySelectorAll('h1, h2, span, div'))
                        .filter(el => {
                            if (el.closest('ul') || el.closest('button') || el.closest('a') || el.closest('nav')) return false;
                            let t = (el.innerText || '').trim();
                            if (!t || t.length > 50 || t.includes('\n')) return false;
                            let lower = t.toLowerCase();
                            if (lower === handle.toLowerCase() || lower === '@' + handle.toLowerCase()) return false;
                            if (/^(follow|following|edit profile|share profile|message|contact|options|posts|followers)$/i.test(lower)) return false;
                            
                            let style = window.getComputedStyle(el);
                            let fw = style ? (style.fontWeight || '') : '';
                            let isBold = fw === 'bold' || fw === '600' || fw === '700' || fw === '800';
                            return isBold || el.tagName === 'H1';
                        });

                    for (let c of candidates) {
                        let t = (c.innerText || '').trim();
                        if (t && !t.toLowerCase().includes(handle.toLowerCase())) {
                            return t;
                        }
                    }
                    return '';
                }

                function extractDomBio(handle, fullName) {
                    let root = document.querySelector('header') || document.querySelector('main section') || document.querySelector('main');
                    if (!root) return '';

                    let bioCandidates = [];
                    let allElements = Array.from(root.querySelectorAll('div, span, p, h1, section'));

                    for (let el of allElements) {
                        if (el.children.length > 5) continue;
                        if (el.closest('ul') || el.closest('button') || el.closest('a') || el.closest('nav') || el.closest('footer')) continue;

                        let text = (el.innerText || '').trim();
                        if (!text || text.length < 2) continue;

                        if (text.toLowerCase() === handle.toLowerCase() || text.toLowerCase() === ('@' + handle).toLowerCase()) continue;
                        if (fullName && text.toLowerCase() === fullName.toLowerCase()) continue;
                        if (/^(follow|following|edit profile|share profile|message|contact|options|threads|similar accounts)$/i.test(text)) continue;
                        if (/^[\d,\.]+\s+(followers?|following|posts?)${'$'}/i.test(text)) continue;
                        if (/^(followers?|following|posts?)${'$'}/i.test(text)) continue;
                        if (text.includes('followers') && text.includes('following')) continue;
                        if (text.includes('See Instagram photos and videos') || text.includes('More posts from') || text.includes('About Help Press API Jobs')) continue;

                        let style = window.getComputedStyle(el);
                        let ws = style ? (style.whiteSpace || '') : '';
                        let isPre = ws.includes('pre') || ws.includes('break');
                        let isDirAuto = el.getAttribute('dir') === 'auto';
                        let isBioClass = el.className && typeof el.className === 'string' && 
                                         (el.className.includes('_ap3a') || el.className.includes('_aa_c') || el.className.includes('_aaco') || el.className.includes('_aacl'));

                        if (isPre || isDirAuto || isBioClass || text.includes('\n')) {
                            bioCandidates.push({
                                text: text,
                                score: (isPre ? 3 : 0) + (isDirAuto ? 2 : 0) + (isBioClass ? 3 : 0) + (text.includes('\n') ? 2 : 0) + Math.min(text.length / 10, 5)
                            });
                        }
                    }

                    if (bioCandidates.length > 0) {
                        bioCandidates.sort((a, b) => b.score - a.score);
                        return bioCandidates[0].text;
                    }

                    // Method B: Parse text lines after stats
                    let headerText = root.innerText || '';
                    if (headerText) {
                        let lines = headerText.split('\n').map(l => l.trim()).filter(Boolean);
                        let lastStatIdx = -1;
                        for (let i = 0; i < lines.length; i++) {
                            let l = lines[i].toLowerCase();
                            if (l === 'followers' || l === 'follower' || l === 'following' || l.endsWith(' followers') || l.endsWith(' following')) {
                                lastStatIdx = i;
                            }
                        }

                        if (lastStatIdx !== -1 && lastStatIdx + 1 < lines.length) {
                            let bioLines = [];
                            let stopKeywords = ['posts', 'reels', 'tagged', 'see instagram', 'more posts from', 'about', 'help', 'threads.net', 'threads'];
                            let actionButtons = ['follow', 'following', 'message', 'edit profile', 'share profile', 'contact', 'options'];

                            for (let i = lastStatIdx + 1; i < lines.length; i++) {
                                let line = lines[i];
                                let lower = line.toLowerCase();

                                if (stopKeywords.some(sk => lower === sk || lower.startsWith(sk))) break;
                                if (actionButtons.includes(lower)) continue;
                                if (lower === handle.toLowerCase() || lower === '@' + handle.toLowerCase()) continue;
                                if (fullName && lower === fullName.toLowerCase()) continue;
                                if (/^[\d,\.]+\s+(followers?|following|posts?)${'$'}/i.test(lower)) continue;

                                bioLines.push(line);
                            }
                            if (bioLines.length > 0) {
                                return bioLines.join('\n');
                            }
                        }
                    }

                    return '';
                }

                function checkPage() {
                    try {
                        let elapsed = Date.now() - startTime;
                        let title = document.querySelector('meta[property="og:title"]')?.content || document.title || '';
                        let desc = document.querySelector('meta[property="og:description"]')?.content || 
                                   document.querySelector('meta[name="description"]')?.content || '';
                        let img = document.querySelector('meta[property="og:image"]')?.content || '';
                        let header = document.querySelector('header') || document.querySelector('main section') || document.querySelector('main');
                        let headerText = header ? (header.innerText || '') : '';
                        let mainText = document.querySelector('main')?.innerText || '';
                        let fullText = (headerText + '\n' + mainText).substring(0, 5000);
                        let bodyText = document.body ? document.body.innerText.substring(0, 2000) : '';

                        // 1. True deactivation / missing check:
                        let isNotFoundMarker = title.includes("Page Not Found") ||
                                               title.includes("isn't available") ||
                                               bodyText.includes("Sorry, this page isn't available") ||
                                               bodyText.includes("The link you followed may be broken") ||
                                               bodyText.includes("page may have been removed");

                        let hasLiveStatsInHeader = /followers?/i.test(headerText) && /following/i.test(headerText);

                        if (isNotFoundMarker && !hasLiveStatsInHeader && elapsed >= 2000) {
                            postResult({
                                success: true,
                                source: 'DEACTIVATED',
                                isDeactivated: true
                            });
                            return;
                        }

                        // 2. Extract JSON script payload
                        let scriptData = extractScriptData(cleanHandle);

                        // 3. Extract DOM metrics & text
                        let domFollowing = extractStatCount('following');
                        let domFollowers = extractStatCount('followers') || extractStatCount('follower');
                        let domPosts = extractStatCount('posts') || extractStatCount('post');
                        let domFullName = extractDomFullName(cleanHandle);
                        let domBio = extractDomBio(cleanHandle, domFullName);

                        // 4. Check if React has hydrated the profile
                        let hasHydrated = (domFollowing && domFollowers) || 
                                          (scriptData.scriptFollowing && scriptData.scriptFollowers) ||
                                          (hasLiveStatsInHeader && (domFollowing || scriptData.scriptFollowing));

                        if (hasHydrated || elapsed >= maxWaitMs) {
                            postResult({
                                success: true,
                                source: 'DOM',
                                isLiveHydrated: !!hasHydrated,
                                desc: desc,
                                title: title,
                                img: (scriptData.scriptPic || img),
                                domFollowers: domFollowers,
                                domFollowing: domFollowing,
                                domPosts: domPosts,
                                domFullName: domFullName,
                                domBio: domBio,
                                scriptBio: scriptData.scriptBio,
                                scriptFullName: scriptData.scriptFullName,
                                scriptFollowers: scriptData.scriptFollowers,
                                scriptFollowing: scriptData.scriptFollowing,
                                scriptPosts: scriptData.scriptPosts,
                                headerText: headerText,
                                fullText: fullText
                            });
                        }
                    } catch (err) {
                        if (Date.now() - startTime >= maxWaitMs) {
                            postResult({
                                success: false,
                                error: err.toString()
                            });
                        }
                    }
                }

                if (window._scraperPollTimer) {
                    clearInterval(window._scraperPollTimer);
                }
                checkPage();
                if (!hasPosted) {
                    window._scraperPollTimer = setInterval(checkPage, pollIntervalMs);
                }
            })();
        """.trimIndent()

        Log.i(TAG, "[WebViewScraper] Injecting active polling scraper script into page...")
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

            // DOM & JSON State Parsing
            val desc = root.optString("desc")
            val title = root.optString("title")
            val img = root.optString("img")
            val isLiveHydrated = root.optBoolean("isLiveHydrated", false)

            val scriptFollowing = root.optString("scriptFollowing").trim()
            val scriptFollowers = root.optString("scriptFollowers").trim()
            val scriptPosts = root.optString("scriptPosts").trim()
            val scriptBio = root.optString("scriptBio").trim()
            val scriptFullName = root.optString("scriptFullName").trim()

            val domFollowersRaw = root.optString("domFollowers").trim()
            val domFollowingRaw = root.optString("domFollowing").trim()
            val domPostsRaw = root.optString("domPosts").trim()
            val domFullName = root.optString("domFullName").trim()
            val domBio = root.optString("domBio").trim()
            val headerText = root.optString("headerText")
            val fullText = root.optString("fullText")

            Log.i(TAG, "[WebViewScraper] Parsing DOM Profile Card (hydrated=$isLiveHydrated): domF='$domFollowersRaw', domIng='$domFollowingRaw', scriptF='$scriptFollowers', scriptIng='$scriptFollowing', domBio='$domBio', scriptBio='$scriptBio'")

            var followers = 0L
            var following = 0L
            var posts = 0L

            // 1. Raw numbers from embedded JSON script state (highest fidelity live data)
            if (scriptFollowers.isNotBlank()) {
                followers = scriptFollowers.toLongOrNull() ?: InstagramScraper.parseFormattedCount(scriptFollowers)
            }
            if (scriptFollowing.isNotBlank()) {
                following = scriptFollowing.toLongOrNull() ?: InstagramScraper.parseFormattedCount(scriptFollowing)
            }
            if (scriptPosts.isNotBlank()) {
                posts = scriptPosts.toLongOrNull() ?: InstagramScraper.parseFormattedCount(scriptPosts)
            }

            // 2. Live Counters directly from DOM profile card elements
            if (followers == 0L && domFollowersRaw.isNotBlank()) {
                val m = Pattern.compile("([\\d,\\.KkMmBb]+)").matcher(domFollowersRaw)
                if (m.find()) followers = InstagramScraper.parseFormattedCount(m.group(1))
            }
            if (following == 0L && domFollowingRaw.isNotBlank()) {
                val m = Pattern.compile("([\\d,\\.KkMmBb]+)").matcher(domFollowingRaw)
                if (m.find()) following = InstagramScraper.parseFormattedCount(m.group(1))
            }
            if (posts == 0L && domPostsRaw.isNotBlank()) {
                val m = Pattern.compile("([\\d,\\.KkMmBb]+)").matcher(domPostsRaw)
                if (m.find()) posts = InstagramScraper.parseFormattedCount(m.group(1))
            }

            // 3. Parsed from rendered DOM headerText
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

            // 4. Fallback to CDN meta description ONLY if live DOM was NOT hydrated
            if (!isLiveHydrated) {
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
            }

            Log.i(TAG, "[WebViewScraper] Resolved Live Counts: Followers=$followers, Following=$following, Posts=$posts")

            val hasHandleInTitle = title.contains("(@$handle", ignoreCase = true)
            val hasHandleInDesc = desc.contains("(@$handle", ignoreCase = true)
            val hasValidStats = followers > 0L || following > 0L || posts > 0L

            // Deactivation check: only if no stats and error page markers match
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

            // Extract Full Name: Prioritize JSON script state -> live DOM card -> title/desc
            var fullName = if (scriptFullName.isNotBlank() && !scriptFullName.equals(handle, ignoreCase = true)) {
                scriptFullName
            } else if (domFullName.isNotBlank() && !domFullName.equals(handle, ignoreCase = true)) {
                domFullName
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
            if (fullName.isBlank()) {
                fullName = handle
            }

            // Extract Bio: Prioritize script JSON biography -> live DOM card element
            var bio = if (scriptBio.isNotBlank()) scriptBio else domBio

            // Fallback for bio from text lines if DOM element search didn't pick it up
            if (bio.isBlank()) {
                val domText = if (headerText.isNotBlank()) headerText else fullText
                if (domText.isNotBlank()) {
                    val lines = domText.split("\n").map { it.trim() }.filter { it.isNotBlank() }
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
                            if (stopKeywords.contains(lower) ||
                                lower.startsWith("see instagram") ||
                                lower.startsWith("more posts from") ||
                                lower.startsWith("http://") ||
                                lower.startsWith("https://")
                            ) {
                                break
                            }
                            if (actionButtons.contains(lower)) continue
                            if (lower == handle.lowercase() || lower == "@${handle.lowercase()}") continue
                            if (line == fullName) continue

                            bioLines.add(line)
                        }

                        if (bioLines.isNotEmpty()) {
                            bio = bioLines.joinToString("\n")
                        }
                    }
                }
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

            Log.i(TAG, "[WebViewScraper] Final parsed profile: name='$fullName', bio='$bio', following=$following, followers=$followers")

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
