package com.componentcheck.data.repository

import android.content.Context
import com.componentcheck.data.auth.SessionManager
import com.componentcheck.data.local.AppDatabase
import com.componentcheck.data.local.ChangeHistoryEntity
import com.componentcheck.data.local.ProfileDao
import com.componentcheck.data.local.ProfileEntity
import com.componentcheck.data.remote.InstagramScraper
import com.componentcheck.data.remote.ProfileResult
import com.componentcheck.data.remote.ScrapedProfile
import com.componentcheck.worker.NotificationHelper
import kotlinx.coroutines.flow.Flow
import java.text.NumberFormat
import java.util.Locale

class ProfileRepository(
    private val context: Context,
    private val dao: ProfileDao,
    private val scraper: InstagramScraper,
    val sessionManager: SessionManager
) {

    val allProfilesFlow: Flow<List<ProfileEntity>> = dao.getAllProfilesFlow()

    fun getChangesForProfileFlow(username: String): Flow<List<ChangeHistoryEntity>> =
        dao.getChangesForProfileFlow(username)

    suspend fun getTodayChangesCount(): Int {
        val startOfDay = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        return dao.getChangeCountSince(startOfDay)
    }

    suspend fun addProfile(
        handle: String,
        initialFollowers: Long? = null,
        initialPosts: Long? = null
    ): Result<ProfileEntity> {
        val clean = handle.trim().removePrefix("@").lowercase()
        if (clean.isBlank()) {
            return Result.failure(IllegalArgumentException("Username cannot be empty"))
        }

        if (!clean.matches(Regex("^[a-zA-Z0-9._]{1,30}$"))) {
            return Result.failure(IllegalArgumentException("Invalid username format. Use 1-30 letters, numbers, periods, or underscores."))
        }

        val existing = dao.getProfile(clean)
        if (existing != null) {
            return Result.failure(Exception("@$clean is already in your monitor list"))
        }

        return when (val result = scraper.fetchProfile(clean)) {
            is ProfileResult.Success -> {
                val p = result.profile
                val initialF = if (initialFollowers != null && initialFollowers > 0) initialFollowers else p.followerCount
                val entity = ProfileEntity(
                    username = p.username,
                    fullName = if (p.fullName.isNotBlank()) p.fullName else "@$clean",
                    bio = p.bio,
                    followerCount = initialF,
                    followingCount = p.followingCount,
                    postCount = p.postCount,
                    profilePicUrl = p.profilePicUrl,
                    isActive = p.isActive,
                    isPrivate = p.isPrivate,
                    isVerified = p.isVerified,
                    lastCheckedTimestamp = System.currentTimeMillis(),
                    previousFollowerCount = initialF,
                    previousFollowingCount = p.followingCount,
                    previousPostCount = p.postCount
                )
                dao.insertOrUpdateProfile(entity)
                Result.success(entity)
            }
            is ProfileResult.DeactivatedOrNotFound -> {
                val entity = ProfileEntity(
                    username = clean,
                    fullName = "@$clean",
                    bio = "",
                    followerCount = 0L,
                    followingCount = 0L,
                    postCount = 0L,
                    profilePicUrl = "",
                    isActive = false,
                    isPrivate = false,
                    isVerified = false,
                    lastCheckedTimestamp = System.currentTimeMillis(),
                    previousFollowerCount = 0L,
                    previousFollowingCount = 0L,
                    previousPostCount = 0L
                )
                dao.insertOrUpdateProfile(entity)
                Result.success(entity)
            }
            else -> {
                // If initial scrape is delayed or network error, save as initial baseline
                val defaultFollowers = initialFollowers ?: 0L
                val defaultPosts = initialPosts ?: 0L
                val entity = ProfileEntity(
                    username = clean,
                    fullName = "@$clean",
                    bio = "",
                    followerCount = defaultFollowers,
                    followingCount = 0L,
                    postCount = defaultPosts,
                    profilePicUrl = "",
                    isActive = true,
                    isPrivate = false,
                    isVerified = false,
                    lastCheckedTimestamp = System.currentTimeMillis(),
                    previousFollowerCount = defaultFollowers,
                    previousFollowingCount = 0L,
                    previousPostCount = defaultPosts
                )
                dao.insertOrUpdateProfile(entity)
                Result.success(entity)
            }
        }
    }

    suspend fun refreshProfile(username: String): Boolean {
        val existing = dao.getProfile(username) ?: return false
        val result = scraper.fetchProfile(username)

        return when (result) {
            is ProfileResult.Success -> {
                checkAndUpdateDiff(existing, result.profile)
                true
            }
            is ProfileResult.DeactivatedOrNotFound -> {
                val wasActive = existing.isActive
                val updated = existing.copy(
                    isActive = false,
                    lastCheckedTimestamp = System.currentTimeMillis()
                )
                dao.insertOrUpdateProfile(updated)
                if (wasActive) {
                    dao.insertChange(
                        ChangeHistoryEntity(
                            username = existing.username,
                            changeType = "STATUS",
                            oldValue = "Active",
                            newValue = "Deactivated"
                        )
                    )
                    NotificationHelper.showProfileChangeAlert(
                        context,
                        existing.username,
                        listOf("⚠️ @${existing.username} account was deactivated or not found")
                    )
                }
                true
            }
            else -> {
                // Network error / timeout: maintain previous status without overriding, update timestamp
                dao.insertOrUpdateProfile(existing.copy(lastCheckedTimestamp = System.currentTimeMillis()))
                false
            }
        }
    }

    suspend fun refreshAllProfiles(): Int {
        val list = dao.getAllProfiles()
        var updatedCount = 0
        for (profile in list) {
            if (refreshProfile(profile.username)) {
                updatedCount++
            }
        }
        return updatedCount
    }

    private suspend fun checkAndUpdateDiff(prev: ProfileEntity, fresh: ScrapedProfile) {
        val changes = mutableListOf<ChangeHistoryEntity>()
        val alertSummaries = mutableListOf<String>()

        val formatter = NumberFormat.getNumberInstance(Locale.US)

        // Check Follower Count
        if (fresh.followerCount != prev.followerCount) {
            val delta = fresh.followerCount - prev.followerCount
            val sign = if (delta > 0) "+$delta" else "$delta"
            changes.add(
                ChangeHistoryEntity(
                    username = prev.username,
                    changeType = "FOLLOWERS",
                    oldValue = formatter.format(prev.followerCount),
                    newValue = "${formatter.format(fresh.followerCount)} ($sign)"
                )
            )
            alertSummaries.add("📈 Followers: ${formatter.format(prev.followerCount)} ➔ ${formatter.format(fresh.followerCount)} ($sign)")
        }

        // Check Following Count
        if (fresh.followingCount != prev.followingCount) {
            val delta = fresh.followingCount - prev.followingCount
            val sign = if (delta > 0) "+$delta" else "$delta"
            changes.add(
                ChangeHistoryEntity(
                    username = prev.username,
                    changeType = "FOLLOWING",
                    oldValue = formatter.format(prev.followingCount),
                    newValue = "${formatter.format(fresh.followingCount)} ($sign)"
                )
            )
            alertSummaries.add("👥 Following: ${formatter.format(prev.followingCount)} ➔ ${formatter.format(fresh.followingCount)} ($sign)")
        }

        // Check Post Count
        if (fresh.postCount != prev.postCount) {
            val delta = fresh.postCount - prev.postCount
            val sign = if (delta > 0) "+$delta" else "$delta"
            changes.add(
                ChangeHistoryEntity(
                    username = prev.username,
                    changeType = "POSTS",
                    oldValue = formatter.format(prev.postCount),
                    newValue = "${formatter.format(fresh.postCount)} ($sign)"
                )
            )
            alertSummaries.add("📸 Posts: ${formatter.format(prev.postCount)} ➔ ${formatter.format(fresh.postCount)} ($sign)")
        }

        // Check Full Name (ignore if prev was default @handle)
        val prevNameIsDefault = prev.fullName.startsWith("@") || prev.fullName == prev.username
        if (fresh.fullName.isNotBlank() && fresh.fullName != prev.fullName) {
            if (!prevNameIsDefault) {
                changes.add(
                    ChangeHistoryEntity(
                        username = prev.username,
                        changeType = "NAME",
                        oldValue = prev.fullName,
                        newValue = fresh.fullName
                    )
                )
                alertSummaries.add("👤 Name changed: \"${prev.fullName}\" ➔ \"${fresh.fullName}\"")
            }
        }

        // Check Bio: strictly avoid boilerplate or placeholder text
        val prevBioIsBoilerplate = isBoilerplateBio(prev.bio)
        val freshBioIsBoilerplate = isBoilerplateBio(fresh.bio)

        if (!freshBioIsBoilerplate && fresh.bio != prev.bio) {
            // Only alert if previous bio was a real bio (not initial blank/placeholder)
            if (!prevBioIsBoilerplate) {
                changes.add(
                    ChangeHistoryEntity(
                        username = prev.username,
                        changeType = "BIO",
                        oldValue = prev.bio.take(80),
                        newValue = fresh.bio.take(80)
                    )
                )
                alertSummaries.add("📝 Bio updated")
            }
        }

        // Check Status
        if (fresh.isActive != prev.isActive) {
            changes.add(
                ChangeHistoryEntity(
                    username = prev.username,
                    changeType = "STATUS",
                    oldValue = if (prev.isActive) "Active" else "Deactivated",
                    newValue = if (fresh.isActive) "Active" else "Deactivated"
                )
            )
            alertSummaries.add(if (fresh.isActive) "✅ Account is active again" else "⚠️ Account was deactivated")
        }

        // Check Profile Picture (compare core asset ID to avoid false alerts from ephemeral CDN query tokens)
        val prevAssetId = extractAvatarAssetId(prev.profilePicUrl)
        val freshAssetId = extractAvatarAssetId(fresh.profilePicUrl)

        if (freshAssetId.isNotBlank() && prevAssetId.isNotBlank() && freshAssetId != prevAssetId) {
            changes.add(
                ChangeHistoryEntity(
                    username = prev.username,
                    changeType = "AVATAR",
                    oldValue = "Previous avatar",
                    newValue = "New avatar updated"
                )
            )
            alertSummaries.add("🖼️ Profile picture updated")
        }

        // If changes were detected, persist each change log and notify user
        if (changes.isNotEmpty()) {
            for (c in changes) {
                dao.insertChange(c)
            }
            NotificationHelper.showProfileChangeAlert(context, prev.username, alertSummaries)
        }

        // Update profile in DB with latest stats and preserve previous values for UI deltas
        val resolvedBio = when {
            !freshBioIsBoilerplate -> fresh.bio
            !prevBioIsBoilerplate -> prev.bio
            else -> ""
        }

        val updatedProfile = prev.copy(
            fullName = if (fresh.fullName.isNotBlank()) fresh.fullName else prev.fullName,
            bio = resolvedBio,
            followerCount = fresh.followerCount,
            followingCount = fresh.followingCount,
            postCount = fresh.postCount,
            profilePicUrl = if (fresh.profilePicUrl.isNotBlank()) fresh.profilePicUrl else prev.profilePicUrl,
            isActive = fresh.isActive,
            isPrivate = fresh.isPrivate,
            isVerified = fresh.isVerified,
            lastCheckedTimestamp = System.currentTimeMillis(),
            previousFollowerCount = prev.followerCount,
            previousFollowingCount = prev.followingCount,
            previousPostCount = prev.postCount
        )

        dao.insertOrUpdateProfile(updatedProfile)
    }

    suspend fun simulateChangeForTesting(): String? {
        val list = dao.getAllProfiles()
        if (list.isEmpty()) return null

        val target = list.first()
        val simulatedFollowers = target.followerCount + 5
        val simulatedPosts = target.postCount + 1

        val fakeFresh = ScrapedProfile(
            username = target.username,
            fullName = target.fullName,
            bio = target.bio,
            followerCount = simulatedFollowers,
            followingCount = target.followingCount,
            postCount = simulatedPosts,
            profilePicUrl = target.profilePicUrl,
            isActive = true,
            isPrivate = target.isPrivate,
            isVerified = target.isVerified
        )

        checkAndUpdateDiff(target, fakeFresh)
        return target.username
    }

    suspend fun updateBaselineCounters(username: String, followers: Long, following: Long, posts: Long) {
        val existing = dao.getProfile(username) ?: return
        val updated = existing.copy(
            followerCount = followers,
            followingCount = following,
            postCount = posts,
            previousFollowerCount = followers,
            previousFollowingCount = following,
            previousPostCount = posts,
            isActive = true
        )
        dao.insertOrUpdateProfile(updated)
    }

    suspend fun deleteProfile(username: String) {
        dao.deleteChangesForProfile(username)
        dao.deleteByUsername(username)
    }

    suspend fun clearAll() {
        dao.clearAllProfiles()
    }

    private fun extractAvatarAssetId(url: String): String {
        if (url.isBlank()) return ""
        val withoutQuery = url.substringBefore("?")
        return withoutQuery.substringAfterLast("/").trim()
    }

    companion object {
        @Volatile
        private var INSTANCE: ProfileRepository? = null

        fun isBoilerplateBio(bioText: String): Boolean {
            if (bioText.isBlank()) return true
            val lower = bioText.trim().lowercase()
            val boilerplatePatterns = listOf(
                "see instagram photos and videos",
                "see instagram photos",
                "follow to see their photos and videos",
                "follow this account to see",
                "this account is private",
                "suggested for you",
                "suggested",
                "similar accounts",
                "discover people",
                "monitoring active for",
                "no posts yet",
                "when they share photos",
                "already follow",
                "open in app",
                "use the app",
                "more posts from"
            )
            if (boilerplatePatterns.any { lower.contains(it) }) return true
            if (lower.startsWith("followers, ") || lower.contains(" followers, ")) return true
            return false
        }

        fun getInstance(context: Context): ProfileRepository {
            return INSTANCE ?: synchronized(this) {
                val db = AppDatabase.getDatabase(context)
                val sessionManager = SessionManager(context)
                val scraper = InstagramScraper(context.applicationContext, sessionManager)
                val instance = ProfileRepository(context.applicationContext, db.profileDao(), scraper, sessionManager)
                INSTANCE = instance
                instance
            }
        }
    }
}
