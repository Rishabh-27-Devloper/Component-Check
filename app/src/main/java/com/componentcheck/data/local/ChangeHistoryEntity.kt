package com.componentcheck.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "change_history",
    indices = [Index(value = ["username"])]
)
data class ChangeHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val username: String,
    val changeType: String, // "FOLLOWERS", "FOLLOWING", "POSTS", "BIO", "NAME", "STATUS", "AVATAR"
    val oldValue: String,
    val newValue: String,
    val timestamp: Long = System.currentTimeMillis()
)
