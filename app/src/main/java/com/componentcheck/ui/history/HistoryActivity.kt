package com.componentcheck.ui.history

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.componentcheck.data.local.AppDatabase
import com.componentcheck.databinding.ActivityHistoryBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val adapter = ChangeHistoryAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val username = intent.getStringExtra("EXTRA_USERNAME") ?: ""

        binding.toolbarHistory.title = if (username.isNotBlank()) "@$username History" else "Change History"
        binding.toolbarHistory.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbarHistory.setNavigationOnClickListener {
            finish()
        }

        binding.recyclerViewHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewHistory.adapter = adapter

        val dao = AppDatabase.getDatabase(this).profileDao()

        lifecycleScope.launch {
            dao.getChangesForProfileFlow(username).collectLatest { changes ->
                adapter.submitList(changes)
                if (changes.isEmpty()) {
                    binding.layoutEmptyHistory.visibility = View.VISIBLE
                    binding.recyclerViewHistory.visibility = View.GONE
                } else {
                    binding.layoutEmptyHistory.visibility = View.GONE
                    binding.recyclerViewHistory.visibility = View.VISIBLE
                }
            }
        }
    }
}
