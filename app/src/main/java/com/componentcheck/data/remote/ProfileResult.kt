package com.componentcheck.data.remote

data class ScrapedProfile(
    val username: String,
    val fullName: String,
    val bio: String,
    val followerCount: Long,
    val followingCount: Long,
    val postCount: Long,
    val profilePicUrl: String,
    val isActive: Boolean,
    val isPrivate: Boolean,
    val isVerified: Boolean
)

sealed class ProfileResult {
    data class Success(val profile: ScrapedProfile) : ProfileResult()
    data class DeactivatedOrNotFound(val username: String) : ProfileResult()
    data class RateLimited(val message: String) : ProfileResult()
    data class Error(val message: String) : ProfileResult()
}
