package com.componentcheck.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.componentcheck.R
import com.componentcheck.databinding.ActivityMainBinding
import com.componentcheck.ui.auth.InstagramLoginActivity
import com.componentcheck.ui.settings.SettingsActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel

    private lateinit var profileAdapter: ProfileAdapter
    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) {
                Toast.makeText(this, "Enable notifications in app settings to get change alerts!", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        checkNotificationPermission()
        setupSwipeRefresh()
        setupRecyclerView()
        setupListeners()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        updateAuthChip()
        viewModel.loadTodayStats()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setColorSchemeColors(
            Color.parseColor("#833AB4"),
            Color.parseColor("#FD1D1D"),
            Color.parseColor("#FCB045")
        )
        binding.swipeRefreshLayout.setOnRefreshListener {
            viewModel.refreshAll()
        }
    }

    private fun setupRecyclerView() {
        profileAdapter = ProfileAdapter(
            onRefreshClicked = { profile ->
                viewModel.refreshSingle(profile)
            },
            onEditClicked = { profile ->
                showEditCountersDialog(profile)
            },
            onDeleteClicked = { profile ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Remove @${profile.username}?")
                    .setMessage("Are you sure you want to stop monitoring this account and delete its history?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove") { _, _ ->
                        viewModel.deleteProfile(profile)
                    }
                    .show()
            }
        )

        binding.recyclerViewProfiles.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewProfiles.adapter = profileAdapter
    }

    private fun showEditCountersDialog(profile: com.componentcheck.data.local.ProfileEntity) {
        val dialogBinding = com.componentcheck.databinding.DialogEditCountersBinding.inflate(layoutInflater)
        dialogBinding.tvEditTitle.text = "Baseline: @${profile.username}"
        dialogBinding.etEditFollowers.setText(profile.followerCount.toString())
        dialogBinding.etEditFollowing.setText(profile.followingCount.toString())
        dialogBinding.etEditPosts.setText(profile.postCount.toString())

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnCancelEdit.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnSaveEdit.setOnClickListener {
            val f = dialogBinding.etEditFollowers.text?.toString()?.toLongOrNull() ?: profile.followerCount
            val ing = dialogBinding.etEditFollowing.text?.toString()?.toLongOrNull() ?: profile.followingCount
            val p = dialogBinding.etEditPosts.text?.toString()?.toLongOrNull() ?: profile.postCount

            viewModel.updateBaselineCounters(profile.username, f, ing, p)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun setupListeners() {
        binding.fabAddProfile.setOnClickListener {
            showAddProfileDialog()
        }

        binding.btnAddFirstProfile.setOnClickListener {
            showAddProfileDialog()
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.chipAuthStatus.setOnClickListener {
            startActivity(Intent(this, InstagramLoginActivity::class.java))
        }
    }

    private fun showAddProfileDialog() {
        AddProfileDialog(this) { handle, initialFollowers, onComplete ->
            viewModel.addProfile(handle, initialFollowers, onComplete)
        }.show()
    }

    private fun updateAuthChip() {
        if (viewModel.isInstagramConnected()) {
            binding.chipAuthStatus.text = getString(R.string.connected_badge)
            binding.chipAuthStatus.setChipBackgroundColorResource(R.color.status_active_bg)
            binding.chipAuthStatus.setTextColor(ContextCompat.getColor(this, R.color.status_active))
        } else {
            binding.chipAuthStatus.text = getString(R.string.guest_badge)
            binding.chipAuthStatus.setChipBackgroundColorResource(R.color.surface_light)
            binding.chipAuthStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.profiles.collectLatest { profiles ->
                        profileAdapter.submitList(profiles)

                        binding.tvTotalMonitored.text = profiles.size.toString()

                        if (profiles.isEmpty()) {
                            binding.layoutEmptyState.visibility = View.VISIBLE
                            binding.recyclerViewProfiles.visibility = View.GONE
                            binding.tvLastCheckTime.text = "Never"
                        } else {
                            binding.layoutEmptyState.visibility = View.GONE
                            binding.recyclerViewProfiles.visibility = View.VISIBLE

                            val maxTimestamp = profiles.maxOfOrNull { it.lastCheckedTimestamp } ?: 0L
                            if (maxTimestamp > 0) {
                                binding.tvLastCheckTime.text = timeFormat.format(Date(maxTimestamp))
                            }
                        }
                    }
                }

                launch {
                    viewModel.isRefreshing.collectLatest { isRefreshing ->
                        binding.swipeRefreshLayout.isRefreshing = isRefreshing
                    }
                }

                launch {
                    viewModel.todayChangesCount.collectLatest { count ->
                        binding.tvRecentChanges.text = count.toString()
                    }
                }

                launch {
                    viewModel.userMessage.collectLatest { message ->
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
