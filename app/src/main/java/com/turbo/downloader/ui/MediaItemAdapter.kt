package com.turbo.downloader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.turbo.downloader.R
import com.turbo.downloader.databinding.ItemMediaBinding
import com.turbo.downloader.sniffer.MediaItem

class MediaItemAdapter(
    private val items: List<MediaItem>,
    private val onSelect: (MediaItem) -> Unit
) : ListAdapter<MediaItem, MediaItemAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val b: ItemMediaBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: MediaItem) {
            b.title.text = item.title ?: item.name
            b.subtitle.text = item.url
            b.badge.text = when (item.type) {
                MediaItem.TYPE_HLS -> "HLS"
                MediaItem.TYPE_DASH -> "DASH"
                MediaItem.TYPE_AUDIO -> "AUDIO"
                else -> "VIDEO"
            }
            b.badge.setBackgroundColor(
                b.root.context.getColor(
                    if (item.type == MediaItem.TYPE_HLS || item.type == MediaItem.TYPE_DASH) R.color.accent
                    else R.color.primary
                )
            )
            b.root.setOnClickListener { onSelect(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MediaItem>() {
            override fun areItemsTheSame(o: MediaItem, n: MediaItem) = o.url == n.url
            override fun areContentsTheSame(o: MediaItem, n: MediaItem) = o == n
        }
    }
}
