package com.componentcheck.ui.main

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import com.componentcheck.databinding.DialogAddProfileBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AddProfileDialog(
    private val context: Context,
    private val onSave: (String, Long?, (Boolean, String?) -> Unit) -> Unit
) {

    private var dialog: Dialog? = null

    fun show() {
        val binding = DialogAddProfileBinding.inflate(LayoutInflater.from(context))

        dialog = MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .setCancelable(true)
            .create()

        binding.btnCancel.setOnClickListener {
            dialog?.dismiss()
        }

        binding.btnSave.setOnClickListener {
            val rawHandle = binding.etHandle.text?.toString()?.trim() ?: ""
            var cleanHandle = rawHandle
                .replace("https://www.instagram.com/", "")
                .replace("https://instagram.com/", "")
                .replace("http://www.instagram.com/", "")
                .replace("http://instagram.com/", "")
                .replace("instagram.com/", "")
                .replace("/", "")
                .trim()

            while (cleanHandle.startsWith("@")) {
                cleanHandle = cleanHandle.removePrefix("@").trim()
            }
            cleanHandle = cleanHandle.lowercase()

            if (cleanHandle.isBlank()) {
                binding.tilHandle.error = "Please enter an Instagram username"
                return@setOnClickListener
            }

            if (!cleanHandle.matches(Regex("^[a-zA-Z0-9._]{1,30}$"))) {
                binding.tilHandle.error = "Invalid format: use letters, numbers, periods, or underscores"
                return@setOnClickListener
            }

            binding.tilHandle.error = null
            binding.progressBarFetch.visibility = View.VISIBLE
            binding.tvStatusMessage.visibility = View.GONE
            binding.btnSave.isEnabled = false
            binding.btnCancel.isEnabled = false

            val followersInput = binding.etInitialFollowers.text?.toString()?.trim()
            val initialFollowers = followersInput?.toLongOrNull()

            onSave(cleanHandle, initialFollowers) { success, errorMessage ->
                binding.progressBarFetch.visibility = View.GONE
                binding.btnSave.isEnabled = true
                binding.btnCancel.isEnabled = true

                if (success) {
                    Toast.makeText(context, "Added @$cleanHandle to Component Check monitor!", Toast.LENGTH_SHORT).show()
                    dialog?.dismiss()
                } else {
                    binding.tvStatusMessage.visibility = View.VISIBLE
                    binding.tvStatusMessage.text = errorMessage ?: "Failed to add account"
                }
            }
        }

        dialog?.show()
    }
}
