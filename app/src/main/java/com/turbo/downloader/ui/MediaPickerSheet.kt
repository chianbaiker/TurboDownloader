package com.turbo.downloader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.turbo.downloader.databinding.SheetMediaPickerBinding
import com.turbo.downloader.sniffer.MediaItem
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class MediaPickerSheet(
    private val items: List<MediaItem>,
    private val onSelect: (MediaItem) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: SheetMediaPickerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetMediaPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding?.let { b ->
            b.recycler.layoutManager = LinearLayoutManager(requireContext())
            b.recycler.adapter = MediaItemAdapter(items, onSelect)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "MediaPickerSheet"
    }
}
