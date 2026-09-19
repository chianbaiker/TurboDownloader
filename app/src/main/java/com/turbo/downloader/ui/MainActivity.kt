package com.turbo.downloader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.turbo.downloader.R
import com.turbo.downloader.core.StartupRestore
import com.turbo.downloader.data.DownloadRepository
import com.turbo.downloader.data.DownloadEntity
import com.turbo.downloader.databinding.ActivityMainBinding
import com.turbo.downloader.download.DownloadManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: DownloadAdapter
    private val viewModel by lazy { ViewModelProvider(this, MainViewModelFactory(application))[MainViewModel::class.java] }
    private val manager by lazy { DownloadManager.getInstance(this) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        requestRuntimePermissions()
        StartupRestore.restoreAsync(this)

        adapter = DownloadAdapter(
            onPause = { viewModel.pause(it.id) },
            onResume = { viewModel.resume(it.id) },
            onCancel = { id -> viewModel.cancel(id, true) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiModels.collect { list ->
                    adapter.submit(list)
                    binding.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        binding.fabAdd.setOnClickListener {
            startActivity(Intent(this, AddDownloadActivity::class.java))
        }
        binding.fabBrowser.setOnClickListener {
            startActivity(Intent(this, BrowserActivity::class.java))
        }

        handleIncomingIntent(intent)
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            perms += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray())
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    /** 分享 / 浏览器调起 / 深链（对应 iOS Share Extension → Deep Link，修复#2）。 */
    private fun handleIncomingIntent(intent: Intent?) {
        val url: String? = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.let { Regex("https?://\\S+").find(it)?.value }
            Intent.ACTION_VIEW -> intent?.dataString
            else -> null
        }
        if (!url.isNullOrEmpty()) {
            startActivity(Intent(this, AddDownloadActivity::class.java).apply {
                putExtra(AddDownloadActivity.EXTRA_URL, url)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_clear_completed -> {
            lifecycleScope.launch {
                DownloadRepository.getInstance().deleteByStatus(DownloadEntity.STATUS_COMPLETED)
                Toast.makeText(this@MainActivity, R.string.clear_completed, Toast.LENGTH_SHORT).show()
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        const val FILTER_DOWNLOADING = -10
        const val FILTER_COMPLETED = -11
    }
}
