package com.componentcheck.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.componentcheck.R
import com.componentcheck.data.auth.SessionManager
import com.componentcheck.data.repository.ProfileRepository
import com.componentcheck.databinding.ActivitySettingsBinding
import com.componentcheck.ui.auth.InstagramLoginActivity
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var sessionManager: SessionManager
    private lateinit var repository: ProfileRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)
        repository = ProfileRepository.getInstance(this)

        binding.toolbarSettings.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbarSettings.setNavigationOnClickListener {
            finish()
        }

        updateAuthUI()

        binding.btnConnectAccount.setOnClickListener {
            val intent = Intent(this, InstagramLoginActivity::class.java)
            startActivity(intent)
        }

        binding.btnLogoutAccount.setOnClickListener {
            sessionManager.clearSession()
            Toast.makeText(this, "Disconnected Instagram account", Toast.LENGTH_SHORT).show()
            updateAuthUI()
        }

        binding.btnTestNotification.setOnClickListener {
            lifecycleScope.launch {
                val updatedHandle = repository.simulateChangeForTesting()
                if (updatedHandle != null) {
                    Toast.makeText(
                        this@SettingsActivity,
                        "Simulated +5 followers on @$updatedHandle! Alert dispatched.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this@SettingsActivity,
                        "Add at least one profile first on the main screen to test alerts.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateAuthUI()
    }

    private fun updateAuthUI() {
        if (sessionManager.isLoggedIn()) {
            binding.tvAuthStatusDetail.text = "Connected! Background checks have full authenticated access."
            binding.btnConnectAccount.visibility = View.GONE
            binding.btnLogoutAccount.visibility = View.VISIBLE
        } else {
            binding.tvAuthStatusDetail.text = "Running in Guest Mode. Connect an Instagram account to unlock unthrottled background checks without rate limits."
            binding.btnConnectAccount.visibility = View.VISIBLE
            binding.btnLogoutAccount.visibility = View.GONE
        }
    }
}
