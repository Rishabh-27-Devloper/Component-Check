package com.componentcheck.ui.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.componentcheck.data.local.ChangeHistoryEntity
import com.componentcheck.databinding.ItemChangeLogBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChangeHistoryAdapter :
    ListAdapter<ChangeHistoryEntity, ChangeHistoryAdapter.HistoryViewHolder>(HistoryDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemChangeLogBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class HistoryViewHolder(
        private val binding: ItemChangeLogBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val dateFormat = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault())

        fun bind(item: ChangeHistoryEntity) {
            binding.tvChangeType.text = when (item.changeType) {
                "FOLLOWERS" -> "Followers Count Changed"
                "FOLLOWING" -> "Following Count Changed"
                "POSTS" -> "Post Count Changed"
                "BIO" -> "Biography Updated"
                "NAME" -> "Display Name Changed"
                "STATUS" -> "Account Status Changed"
                "AVATAR" -> "Profile Picture Changed"
                else -> item.changeType
            }

            binding.tvChangeTimestamp.text = dateFormat.format(Date(item.timestamp))
            binding.tvOldValue.text = "Previous: ${item.oldValue}"
            binding.tvNewValue.text = "New: ${item.newValue}"
        }
    }

    object HistoryDiffCallback : DiffUtil.ItemCallback<ChangeHistoryEntity>() {
        override fun areItemsTheSame(oldItem: ChangeHistoryEntity, newItem: ChangeHistoryEntity): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ChangeHistoryEntity, newItem: ChangeHistoryEntity): Boolean =
            oldItem == newItem
    }
}
