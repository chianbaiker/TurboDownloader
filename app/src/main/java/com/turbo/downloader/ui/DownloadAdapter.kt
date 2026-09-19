package com.turbo.downloader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.turbo.downloader.R
import com.turbo.downloader.data.DownloadEntity
import com.turbo.downloader.databinding.ItemDownloadBinding

class DownloadAdapter(
    private val onPause: (DownloadEntity) -> Unit,
    private val onResume: (DownloadEntity) -> Unit,
    private val onCancel: (String) -> Unit
) : ListAdapter<DownloadEntity, DownloadAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemDownloadBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: DownloadEntity) {
            b.fileName.text = item.fileName
            b.statusText.text = "${statusText(item)}  •  ${DownloadUiModel.formatSpeed(item.speedBps)}  •  ETA ${DownloadUiModel.formatEta(item.etaSeconds)}"
            b.progressBar.max = 100
            val percent = if (item.totalBytes > 0)
                ((item.downloadedBytes * 100) / item.totalBytes).toInt().coerceIn(0, 100) // 修复#8
            else 0
            b.progressBar.progress = percent
            b.progressText.text = "${DownloadAdapter.formatSize(item.downloadedBytes)} / ${DownloadAdapter.formatSize(item.totalBytes)} ($percent%)"

            val isActive = item.status == DownloadEntity.STATUS_DOWNLOADING
            b.btnPauseResume.setImageResource(if (isActive) R.drawable.ic_pause else R.drawable.ic_resume)
            b.btnPauseResume.setOnClickListener { if (isActive) onPause(item) else onResume(item) }
            b.btnCancel.setOnClickListener { onCancel(item.id) }
        }

        private fun statusText(item: DownloadEntity): String = when (item.status) {
            DownloadEntity.STATUS_PENDING -> "Pending"
            DownloadEntity.STATUS_QUEUED -> "Queued"
            DownloadEntity.STATUS_DOWNLOADING -> "Downloading"
            DownloadEntity.STATUS_PAUSED -> "Paused"
            DownloadEntity.STATUS_COMPLETED -> "Completed"
            DownloadEntity.STATUS_FAILED -> "Failed: ${item.error}"
            else -> "Unknown"
        }
    }

    companion object {
        // 修复#14：以 id 为稳定性 key，列表不抖动
        private val DIFF = object : DiffUtil.ItemCallback<DownloadEntity>() {
            override fun areItemsTheSame(o: DownloadEntity, n: DownloadEntity) = o.id == n.id
            override fun areContentsTheSame(o: DownloadEntity, n: DownloadEntity) = o == n
        }

        private val df = java.text.DecimalFormat("0.##")
        fun formatSize(bytes: Long): String = when {
            bytes < 0 -> "?"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${df.format(bytes / 1024.0)} KB"
            bytes < 1024 * 1024 * 1024 -> "${df.format(bytes / (1024.0 * 1024))} MB"
            else -> "${df.format(bytes / (1024.0 * 1024 * 1024))} GB"
        }
    }
}
