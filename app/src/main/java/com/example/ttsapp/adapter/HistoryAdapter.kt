package com.example.ttsapp.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.ttsapp.data.HistoryEntity
import com.example.ttsapp.databinding.ItemHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val onItemClick: (HistoryEntity) -> Unit,
    private val onDeleteClick: (HistoryEntity) -> Unit
) : ListAdapter<HistoryEntity, HistoryAdapter.ViewHolder>(DIFF_CALLBACK) {

    inner class ViewHolder(private val binding: ItemHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HistoryEntity) {
            val displayText = if (item.text.length > 50) {
                item.text.take(50) + "..."
            } else {
                item.text
            }
            binding.tvHistoryText.text = displayText

            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            binding.tvHistoryTime.text = sdf.format(Date(item.timestamp))

            binding.root.setOnClickListener { onItemClick(item) }
            binding.btnDelete.setOnClickListener { onDeleteClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<HistoryEntity>() {
            override fun areItemsTheSame(old: HistoryEntity, new: HistoryEntity) =
                old.id == new.id

            override fun areContentsTheSame(old: HistoryEntity, new: HistoryEntity) =
                old == new
        }
    }
}
