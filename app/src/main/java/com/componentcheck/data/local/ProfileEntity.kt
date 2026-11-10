package com.componentcheck.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "monitored_profiles")
data class ProfileEntity(
    @PrimaryKey
    val username: String, // Normalized lowercase handle without @
    val fullName: String,
    val bio: String,
    val followerCount: Long,
    val followingCount: Long,
    val postCount: Long,
    val profilePicUrl: String,
    val isActive: Boolean, // True if profile exists and active, False if deactivated/not found
    val isPrivate: Boolean = false,
    val isVerified: Boolean = false,
    val lastCheckedTimestamp: Long = System.currentTimeMillis(),
    
    // Previous counts for delta computation
    val previousFollowerCount: Long = followerCount,
    val previousFollowingCount: Long = followingCount,
    val previousPostCount: Long = postCount
)
