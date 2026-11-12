package com.componentcheck.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.componentcheck.R
import com.componentcheck.data.local.ProfileEntity
import com.componentcheck.databinding.ItemProfileCardBinding
import com.componentcheck.ui.history.HistoryActivity
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

class ProfileAdapter(
    private val onRefreshClicked: (ProfileEntity) -> Unit,
    private val onEditClicked: (ProfileEntity) -> Unit,
    private val onDeleteClicked: (ProfileEntity) -> Unit
) : ListAdapter<ProfileEntity, ProfileAdapter.ProfileViewHolder>(ProfileDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val binding = ItemProfileCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ProfileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ProfileViewHolder(
        private val binding: ItemProfileCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ProfileEntity) {
            val context = binding.root.context
            val formatter = NumberFormat.getNumberInstance(Locale.US)

            // Display Name & Username
            val displayName = if (item.fullName.isNotBlank() && 
                !item.fullName.equals(item.username, ignoreCase = true) && 
                !item.fullName.equals("@${item.username}", ignoreCase = true)
            ) {
                item.fullName
            } else {
                item.username
            }
            binding.tvFullName.text = displayName
            binding.tvUsername.text = "@${item.username}"
            binding.ivVerified.visibility = if (item.isVerified) View.VISIBLE else View.GONE

            // Bio (formatted exactly as on web, suppressing Instagram boilerplate)
            val cleanBio = item.bio.trim()
            val isBoilerplate = com.componentcheck.data.repository.ProfileRepository.isBoilerplateBio(cleanBio)

            if (cleanBio.isNotBlank() && !isBoilerplate) {
                binding.tvBio.visibility = View.VISIBLE
                binding.tvBio.text = cleanBio
            } else {
                binding.tvBio.visibility = View.GONE
            }

            // Avatar
            if (item.profilePicUrl.isNotBlank()) {
                binding.ivAvatar.load(item.profilePicUrl) {
                    crossfade(true)
                    placeholder(R.drawable.ic_instagram)
                    error(R.drawable.ic_instagram)
                    transformations(CircleCropTransformation())
                }
            } else {
                binding.ivAvatar.setImageResource(R.drawable.ic_instagram)
            }

            // Status Badge (Active vs Deactivated)
            if (item.isActive) {
                binding.tvStatusBadge.text = context.getString(R.string.active)
                binding.tvStatusBadge.setBackgroundResource(R.drawable.badge_status_active)
                binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.status_active))
            } else {
                binding.tvStatusBadge.text = context.getString(R.string.deactivated)
                binding.tvStatusBadge.setBackgroundResource(R.drawable.badge_status_deactivated)
                binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.status_deactivated))
            }

            // Counters
            binding.tvFollowerCount.text = formatCompactNumber(item.followerCount)
            binding.tvFollowingCount.text = formatCompactNumber(item.followingCount)
            binding.tvPostCount.text = formatCompactNumber(item.postCount)

            // Delta Badges: Followers
            val followerDelta = item.followerCount - item.previousFollowerCount
            bindDelta(binding.tvFollowerDelta, followerDelta)

            // Delta Badges: Following
            val followingDelta = item.followingCount - item.previousFollowingCount
            bindDelta(binding.tvFollowingDelta, followingDelta)

            // Delta Badges: Posts
            val postDelta = item.postCount - item.previousPostCount
            bindDelta(binding.tvPostDelta, postDelta)

            // Last checked time
            val elapsedMinutes = ((System.currentTimeMillis() - item.lastCheckedTimestamp) / 60000).toInt()
            binding.tvLastChecked.text = when {
                elapsedMinutes <= 1 -> "Checked just now"
                elapsedMinutes < 60 -> "Checked ${elapsedMinutes}m ago"
                else -> "Checked ${(elapsedMinutes / 60)}h ago"
            }

            // Actions
            binding.btnOpenWeb.setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/${item.username}/"))
                context.startActivity(intent)
            }

            binding.btnHistory.setOnClickListener {
                val intent = Intent(context, HistoryActivity::class.java).apply {
                    putExtra("EXTRA_USERNAME", item.username)
                }
                context.startActivity(intent)
            }

            binding.btnEditCounters.setOnClickListener {
                onEditClicked(item)
            }

            binding.btnRefreshSingle.setOnClickListener {
                onRefreshClicked(item)
            }

            binding.btnDelete.setOnClickListener {
                onDeleteClicked(item)
            }
        }

        private fun bindDelta(textView: android.widget.TextView, delta: Long) {
            val context = binding.root.context
            if (delta == 0L) {
                textView.visibility = View.GONE
            } else {
                textView.visibility = View.VISIBLE
                if (delta > 0) {
                    textView.text = "+$delta"
                    textView.setBackgroundResource(R.drawable.badge_delta_positive)
                    textView.setTextColor(ContextCompat.getColor(context, R.color.delta_positive))
                } else {
                    textView.text = "$delta"
                    textView.setBackgroundResource(R.drawable.badge_delta_negative)
                    textView.setTextColor(ContextCompat.getColor(context, R.color.delta_negative))
                }
            }
        }

        private fun formatCompactNumber(count: Long): String {
            return when {
                count >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", count / 1_000_000_000.0)
                count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
                count >= 10_000 -> String.format(Locale.US, "%.1fK", count / 1_000.0)
                else -> NumberFormat.getNumberInstance(Locale.US).format(count)
            }
        }
    }

    object ProfileDiffCallback : DiffUtil.ItemCallback<ProfileEntity>() {
        override fun areItemsTheSame(oldItem: ProfileEntity, newItem: ProfileEntity): Boolean =
            oldItem.username == newItem.username

        override fun areContentsTheSame(oldItem: ProfileEntity, newItem: ProfileEntity): Boolean =
            oldItem == newItem
    }
}
