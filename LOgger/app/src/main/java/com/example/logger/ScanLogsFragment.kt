package com.example.logger

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arjay.logger.adapter.ScanLogsAdapter
import com.arjay.logger.data.model.ScanLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import com.arjay.logger.R

class ScanLogsFragment : Fragment() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var viewLogsButton: Button
    private val scanLogs = mutableListOf<ScanLog>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_scan_logs, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        recyclerView = view.findViewById(R.id.scanLogsRecyclerView)
        viewLogsButton = view.findViewById(R.id.viewLogsButton)
        
        setupRecyclerView()
        setupClickListeners()
        loadScanLogs()
    }
    
    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        updateAdapter()
    }
    
    private fun updateAdapter() {
        val adapter = ScanLogsAdapter(scanLogs)
        recyclerView.adapter = adapter
    }
    
    private fun setupClickListeners() {
        viewLogsButton.setOnClickListener {
            loadScanLogs()
        }
    }
    
    private fun loadScanLogs() {
        try {
            val logsDir = File(requireContext().filesDir, "received_logs")
            if (!logsDir.exists()) {
                return
            }
            
            val allLogs = mutableListOf<ScanLog>()
            logsDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".json")) {
                    try {
                        val jsonContent = file.readText()
                        val gson = Gson()
                        val logListType = object : TypeToken<List<ScanLog>>() {}.type
                        val logs: List<ScanLog> = gson.fromJson(jsonContent, logListType)
                        allLogs.addAll(logs)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            
            scanLogs.clear()
            scanLogs.addAll(allLogs.sortedByDescending { it.timestamp })
            updateAdapter()
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
