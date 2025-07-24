package com.arjay.logger.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.arjay.logger.R
import com.arjay.logger.data.model.ScanLog
import java.text.SimpleDateFormat
import java.util.*

class ScanLogsAdapter(private val scanLogs: List<ScanLog>) : RecyclerView.Adapter<ScanLogsAdapter.ScanLogViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScanLogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_scan_log_entry, parent, false)
        return ScanLogViewHolder(view)
    }

    override fun onBindViewHolder(holder: ScanLogViewHolder, position: Int) {
        val log = scanLogs[position]
        holder.bind(log)
    }

    override fun getItemCount(): Int = scanLogs.size

    class ScanLogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val timeText: TextView = view.findViewById(R.id.timeText)
        private val studentIdText: TextView = view.findViewById(R.id.studentIdText)
        private val nameText: TextView = view.findViewById(R.id.nameText)
        private val gradeText: TextView = view.findViewById(R.id.gradeText)
        private val sectionText: TextView = view.findViewById(R.id.sectionText)
        private val statusText: TextView = view.findViewById(R.id.statusText)

        fun bind(log: ScanLog) {
            try {
                val date = try {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
                        .parse(log.timestamp) ?: Date()
                } catch (e: Exception) {
                    Date()
                }
                
                timeText.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(date)
                studentIdText.text = log.studentId
                nameText.text = log.studentName.ifEmpty { log.name ?: "Unknown" }
                gradeText.text = "Grade ${log.grade}"
                sectionText.text = log.section.ifEmpty { "N/A" }
                
                // Set status text and color based on status
                val status = log.entryExitStatus?.takeIf { it.isNotBlank() } ?: "unknown"
                statusText.text = status
                val statusColor = when (status.lowercase()) {
                    "in" -> Color.GREEN
                    "out" -> Color.RED
                    else -> Color.GRAY
                }
                statusText.setTextColor(statusColor)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
