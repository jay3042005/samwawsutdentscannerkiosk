package com.example.logger.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.arjay.logger.R
import com.example.logger.data.model.ScheduleItem

class ScheduleAdapter(private val items: List<ScheduleItem>) : RecyclerView.Adapter<ScheduleAdapter.ScheduleViewHolder>() {
    class ScheduleViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val titleText: TextView = itemView.findViewById(R.id.scheduleTitleText)
        val dateText: TextView = itemView.findViewById(R.id.scheduleDateText)
        val timeText: TextView = itemView.findViewById(R.id.scheduleTimeText)
        val locationText: TextView = itemView.findViewById(R.id.scheduleLocationText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScheduleViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_schedule, parent, false)
        return ScheduleViewHolder(view)
    }

    override fun onBindViewHolder(holder: ScheduleViewHolder, position: Int) {
        val item = items[position]
        holder.titleText.text = item.title
        holder.dateText.text = item.date
        holder.timeText.text = item.time
        holder.locationText.text = item.location ?: "-"
    }

    override fun getItemCount(): Int = items.size
}
