package com.turbo.downloader.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.turbo.downloader.R
import com.turbo.downloader.databinding.ActivityBrowserBinding
import com.turbo.downloader.sniffer.MediaDownloadCoordinator
import com.turbo.downloader.sniffer.MediaItem
import com.turbo.downloader.sniffer.Sniffer
import kotlinx.coroutines.launch

class BrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowserBinding
    private lateinit var coordinator: MediaDownloadCoordinator
    private var currentUrl: String = "https://www.google.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        coordinator = MediaDownloadCoordinator(this)

        setupWebView()
        setupControls()

        intent?.getStringExtra(EXTRA_URL)?.let { currentUrl = it }
        binding.webView.loadUrl(currentUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                currentUrl = request.url.toString()
                updateUrlBar()
                return false
            }
            override fun onPageFinished(view: WebView, url: String) {
                currentUrl = url
                updateUrlBar()
                binding.fabDetect.visibility = View.VISIBLE
            }
        }
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
            }
        }
    }

    private fun setupControls() {
        binding.btnGo.setOnClickListener {
            var url = binding.urlBar.text.toString().trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
            currentUrl = url
            binding.webView.loadUrl(url)
        }

        binding.fabDetect.setOnClickListener {
            binding.fabDetect.isEnabled = false
            binding.progressBar.visibility = View.VISIBLE
            lifecycleScope.launch {
                try {
                    val items = Sniffer.sniff(currentUrl, referer = currentUrl)
                    if (items.isEmpty()) {
                        Toast.makeText(this@BrowserActivity, R.string.no_media_found, Toast.LENGTH_SHORT).show()
                    } else {
                        showPicker(items)
                    }
                } catch (t: Throwable) {
                    Toast.makeText(this@BrowserActivity, "嗅探失败: ${t.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    binding.progressBar.visibility = View.GONE
                    binding.fabDetect.isEnabled = true
                }
            }
        }
    }

    private fun showPicker(items: List<MediaItem>) {
        MediaPickerSheet(items) { item ->
            coordinator.enqueue(item) // 自动路由 HLS/DASH/直链（修复闭环）
            Toast.makeText(this, R.string.started, Toast.LENGTH_SHORT).show()
        }.show(supportFragmentManager, MediaPickerSheet::TAG)
    }

    private fun updateUrlBar() {
        if (binding.urlBar.text.toString() != currentUrl) binding.urlBar.setText(currentUrl)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) binding.webView.goBack() else super.onBackPressed()
    }

    companion object {
        const val EXTRA_URL = "extra_url"
    }
}
