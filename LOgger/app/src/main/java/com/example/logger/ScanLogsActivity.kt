package com.arjay.logger

import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.arjay.logger.adapter.ScanLogsAdapter
import com.arjay.logger.data.model.ScanLog
import com.arjay.logger.databinding.ActivityScanLogsBinding
import com.arjay.logger.databinding.FragmentLogsBinding
import com.arjay.logger.util.FileUtils
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.gson.Gson
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.LinkedHashMap

class ScanLogsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityScanLogsBinding
    private lateinit var pagerAdapter: ViewPagerAdapter
    private val logsByDate = LinkedHashMap<String, MutableList<ScanLog>>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
    private val displayDateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Setup ViewPager and TabLayout
        val viewPager = binding.viewPager
        val tabLayout = binding.tabLayout
        
        // Setup FAB click listener
        binding.fabExport.setOnClickListener {
            exportLogs()
        }
        
        setupViewPager()
        loadScanLogs()
    }
    
    private fun setupViewPager() {
        pagerAdapter = ViewPagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter
        
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "All Logs"
                1 -> "By Grade"
                2 -> "By Date"
                else -> "Tab ${position + 1}"
            }
        }.attach()
    }
    
    private fun loadScanLogs() {
        binding.progressBar.visibility = View.VISIBLE
        
        try {
            val allLogs = mutableListOf<ScanLog>()
            
            // Check for logs in the root files directory first (where they're being saved by Bluetooth transfer)
            val rootLogFile = File(filesDir, "scan_logs_extracted.json")
            if (rootLogFile.exists()) {
                try {
                    val fileContent = rootLogFile.readText()
                    val logs = Gson().fromJson(fileContent, Array<ScanLog>::class.java).toList()
                    allLogs.addAll(logs)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            // Also check the logs subdirectory for any additional logs
            val logsDir = File(filesDir, "logs")
            if (logsDir.exists()) {
                val logFiles = logsDir.listFiles { _, name ->
                    name.endsWith(".json")
                }?.toList() ?: emptyList()
                
                logFiles.sortedByDescending { it.lastModified() }.forEach { file ->
                    try {
                        val fileContent = file.readText()
                        val logs = Gson().fromJson(fileContent, Array<ScanLog>::class.java).toList()
                        allLogs.addAll(logs)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            
            if (allLogs.isEmpty()) {
                showNoLogsMessage()
                return
            }
            
            // Organize logs by date
            organizeLogsByDate(allLogs)
            
            // Update UI
            runOnUiThread {
                updateLogsDisplay()
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
            showError("Error loading logs: ${e.message}")
        } finally {
            binding.progressBar.visibility = View.GONE
        }
    }
    
    private fun organizeLogsByDate(logs: List<ScanLog>) {
        logsByDate.clear()
        
        // Define the ISO 8601 date format with timezone
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        
        val sortedLogs = logs.sortedByDescending { log ->
            try {
                dateFormat.parse(log.timestamp)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
        }
        
        // Group logs by date
        for (log in sortedLogs) {
            try {
                // Try to parse ISO 8601 format first (with microseconds)
                val date = try {
                    // Handle ISO 8601 format with microseconds: 2025-07-18T09:55:53.459599Z
                    val cleanTimestamp = log.timestamp
                        .replace("Z", "")
                        .substringBefore(".") // Remove microseconds
                    isoDateFormat.parse(cleanTimestamp) ?: Date()
                } catch (e: Exception) {
                    // Fallback to original format
                    dateFormat.parse(log.timestamp) ?: Date()
                }
                
                val dateKey = displayDateFormat.format(date)
                
                if (!logsByDate.containsKey(dateKey)) {
                    logsByDate[dateKey] = mutableListOf()
                }
                logsByDate[dateKey]?.add(log)
            } catch (e: Exception) {
                e.printStackTrace()
                // If parsing fails, add to a default date
                val defaultKey = "Unknown Date"
                if (!logsByDate.containsKey(defaultKey)) {
                    logsByDate[defaultKey] = mutableListOf()
                }
                logsByDate[defaultKey]?.add(log)
            }
        }
        
        // Update the adapter with the organized logs
        pagerAdapter.updateData(logsByDate)
        updateLogsDisplay()
    }
    
    private fun updateLogsDisplay() {
        // Update the ViewPager adapter with the loaded data
        (binding.viewPager.adapter as? ViewPagerAdapter)?.updateData(logsByDate)
    }
    
    private fun exportLogs() {
        // TODO: Implement export functionality
        Toast.makeText(this, "Export functionality coming soon", Toast.LENGTH_SHORT).show()
    }
    
    private fun showNoLogsMessage() {
        runOnUiThread {
            // TODO: Show a message when no logs are found
            Toast.makeText(this, "No scan logs found", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onBackPressed() {
        super.onBackPressed()
        // Add reverse slide animation
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}

// Fragment for showing logs
class LogsFragment : Fragment() {
    private var _binding: FragmentLogsBinding? = null
    private val binding get() = _binding!!
    private val scanLogs = mutableListOf<ScanLog>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupRecyclerView()
        
        // Load logs after adapter is initialized
        arguments?.getParcelableArrayList<ScanLog>("logs")?.let { logsList ->
            updateLogs(ArrayList(logsList))  // Convert to mutable list
        }
    }
    
    private fun setupRecyclerView() {
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
        }
        updateAdapter()
    }
    
    private fun updateAdapter() {
        val adapter = ScanLogsAdapter(scanLogs)
        binding.recyclerView.adapter = adapter
    }
    
    fun updateLogs(newLogs: List<ScanLog>) {
        scanLogs.clear()
        scanLogs.addAll(newLogs)
        updateAdapter()
        
        if (scanLogs.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.emptyView.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }
    }
    
    companion object {
        @JvmStatic
        fun newInstance(logs: List<ScanLog>): LogsFragment {
            return LogsFragment().apply {
                arguments = Bundle().apply {
                    putParcelableArrayList("logs", ArrayList(logs))
                }
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// Adapter for ViewPager2
class ViewPagerAdapter(fragmentActivity: FragmentActivity) : 
    FragmentStateAdapter(fragmentActivity) {
    
    private val fragments = mutableListOf<Fragment>()
    private val logsByDate = LinkedHashMap<String, MutableList<ScanLog>>()
    
    init {
        // Initialize with empty fragments using newInstance
        fragments.add(LogsFragment.newInstance(emptyList()))
        fragments.add(LogsFragment.newInstance(emptyList()))
        fragments.add(LogsFragment.newInstance(emptyList()))
    }
    
    fun updateData(newData: Map<String, MutableList<ScanLog>>) {
        logsByDate.clear()
        logsByDate.putAll(newData)
        
        // Update all fragments with appropriate data
        fragments.forEachIndexed { index, fragment ->
            val logs = when (index) {
                0 -> logsByDate.values.flatten() // All logs
                1 -> logsByDate.values.flatten().filter { it.grade != null } // By grade
                2 -> logsByDate.values.flatten() // By date (already grouped)
                else -> emptyList()
            }
            (fragment as? LogsFragment)?.updateLogs(logs)
        }
        
        notifyDataSetChanged()
    }
    
    override fun getItemCount(): Int = fragments.size
    
    override fun createFragment(position: Int): Fragment {
        // Always create a new fragment instance for the given position
        val logs = when (position) {
            0 -> logsByDate.values.flatten() // All logs
            1 -> logsByDate.values.flatten().filter { it.grade != null } // By grade
            2 -> logsByDate.values.flatten() // By date (already grouped)
            else -> emptyList()
        }
        return LogsFragment.newInstance(logs)
    }
}

class ScanLogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    private val dateText: TextView = view.findViewById(R.id.dateText)
    private val logCountText: TextView = view.findViewById(R.id.logCountText)
    private val logsRecyclerView: RecyclerView = view.findViewById(R.id.logsRecyclerView)
    
    fun bind(date: String, logs: List<ScanLog>) {
        dateText.text = date
        logCountText.text = "${logs.size} scans"
        
        logsRecyclerView.layoutManager = LinearLayoutManager(itemView.context)
        val adapter = ScanLogsAdapter(logs)
        logsRecyclerView.adapter = adapter
    }
}
