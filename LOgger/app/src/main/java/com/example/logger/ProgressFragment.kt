package com.example.logger

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

import android.os.Handler
import android.os.Looper
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arjay.logger.adapter.ScanLogsAdapter
import com.arjay.logger.data.model.ScanLog
import com.google.gson.Gson
import java.io.File
import com.arjay.logger.R

class ProgressFragment : Fragment() {
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var recyclerView: RecyclerView
    private val scanLogs = mutableListOf<ScanLog>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_progress, container, false)
        progressBar = view.findViewById(R.id.overallProgressBar)
        progressText = view.findViewById(R.id.progressDetail)
        recyclerView = view.findViewById(R.id.progressRecyclerView)
        setupRecyclerView()
        loadLogsAsync()
        return view
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        updateAdapter()
    }

    private fun updateAdapter() {
        val adapter = ScanLogsAdapter(scanLogs)
        recyclerView.adapter = adapter
    }

    private fun loadLogsAsync() {
        Handler(Looper.getMainLooper()).post {
            val logs = loadLogsFromStorage()
            scanLogs.clear()
            scanLogs.addAll(logs)
            updateAdapter()
            // Example: progress out of 10
            val progress = logs.size
            val max = 10 // Or derive max from other logic
            progressBar.max = max
            progressBar.progress = progress
            progressText.text = "$progress of $max logs scanned"
        }
    }

    private fun loadLogsFromStorage(): List<ScanLog> {
        val logs = mutableListOf<ScanLog>()
        val context = requireContext()
        try {
            // Use similar logic as ScanLogsActivity
            val rootLogFile = File(context.filesDir, "scan_logs_extracted.json")
            if (rootLogFile.exists()) {
                val fileContent = rootLogFile.readText()
                val parsed = Gson().fromJson(fileContent, Array<ScanLog>::class.java).toList()
                logs.addAll(parsed)
            }
            val logsDir = File(context.filesDir, "logs")
            if (logsDir.exists()) {
                val logFiles = logsDir.listFiles { _, name ->
                    name.endsWith(".json")
                }?.toList() ?: emptyList()
                logFiles.sortedByDescending { it.lastModified() }.forEach { file ->
                    val fileContent = file.readText()
                    val parsed = Gson().fromJson(fileContent, Array<ScanLog>::class.java).toList()
                    logs.addAll(parsed)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return logs
    }
}

