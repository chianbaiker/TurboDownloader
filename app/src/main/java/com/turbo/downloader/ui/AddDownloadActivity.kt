package com.turbo.downloader.ui

import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.turbo.downloader.R
import com.turbo.downloader.databinding.ActivityAddDownloadBinding
import com.turbo.downloader.download.DownloadManager

class AddDownloadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddDownloadBinding
    private var threads = 8

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.threadSeek.max = 16
        binding.threadSeek.progress = threads
        binding.threadValue.text = "$threads threads"
        binding.threadSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                threads = progress.coerceAtLeast(1)
                binding.threadValue.text = "$threads threads"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 分享/深链带进来的 URL 预填
        intent?.getStringExtra(EXTRA_URL)?.let { binding.urlInput.setText(it) }

        binding.btnStart.setOnClickListener {
            val url = binding.urlInput.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, R.string.paste_url, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            DownloadManager.getInstance(this).enqueue(
                url = url,
                referer = binding.refererInput.text.toString().trim(),
                threadCount = threads,
                saveName = binding.nameInput.text.toString().trim().ifBlank { null }
            ) { error -> Toast.makeText(this, error, Toast.LENGTH_LONG).show() }
            Toast.makeText(this, R.string.started, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        const val EXTRA_URL = "extra_url"
    }
}
