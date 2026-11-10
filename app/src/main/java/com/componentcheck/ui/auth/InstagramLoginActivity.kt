package com.componentcheck.ui.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.componentcheck.data.auth.SessionManager
import com.componentcheck.databinding.ActivityLoginBinding

class InstagramLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var sessionManager: SessionManager

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)

        binding.toolbarLogin.setNavigationOnClickListener {
            finish()
        }

        setupWebView()
        binding.webViewInstagram.loadUrl("https://www.instagram.com/accounts/login/")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webSettings: WebSettings = binding.webViewInstagram.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true
        webSettings.useWideViewPort = true
        webSettings.loadWithOverviewMode = true
        webSettings.userAgentString =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webViewInstagram, true)

        binding.webViewInstagram.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    binding.progressBarWeb.visibility = View.VISIBLE
                    binding.progressBarWeb.progress = newProgress
                } else {
                    binding.progressBarWeb.visibility = View.GONE
                }
            }
        }

        binding.webViewInstagram.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                checkSessionCookies(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                checkSessionCookies(url)
            }
        }
    }

    private fun checkSessionCookies(url: String?) {
        if (url == null) return
        val saved = sessionManager.saveCookiesFromUrl(url)
        if (saved && sessionManager.isLoggedIn()) {
            Toast.makeText(this, "Instagram account connected successfully!", Toast.LENGTH_LONG).show()
            setResult(RESULT_OK)
            finish()
        }
    }
}
