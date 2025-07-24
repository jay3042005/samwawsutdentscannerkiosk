package com.arjay.logger

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arjay.logger.data.model.ScanLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ReceivedLogsDialog(context: Context) : Dialog(context) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_received_logs)
        val recyclerView = findViewById<RecyclerView>(R.id.receivedLogsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(context)
        val logs = loadReceivedLogs()
        recyclerView.adapter = ReceivedLogsAdapter(logs)
    }

    private fun loadReceivedLogs(): List<ScanLog> {
        val file = File(context.filesDir, "scan_logs_extracted.json")
        if (!file.exists()) return emptyList()
        val json = file.readText()
        val type = object : TypeToken<List<ScanLog>>() {}.type
        return Gson().fromJson(json, type)
    }
}

class ReceivedLogsAdapter(private val logs: List<ScanLog>) : RecyclerView.Adapter<ReceivedLogsAdapter.ViewHolder>() {
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_received_log, parent, false)
        return ViewHolder(view)
    }
    override fun getItemCount() = logs.size
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(logs[position])
    }
    class ViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        fun bind(log: ScanLog) {
            val nameView = itemView.findViewById<TextView>(R.id.logName)
            val dateView = itemView.findViewById<TextView>(R.id.logDate)
            val statusView = itemView.findViewById<TextView>(R.id.logStatus)
            nameView.text = log.name
            dateView.text = formatDate(log.timestamp)
            statusView.text = log.status
        }
        private fun formatDate(ts: String): String {
            return try {
                val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                val date = parser.parse(ts)
                SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(date)
            } catch (e: Exception) {
                ts
            }
        }
    }
} 