package com.example.logger

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

import android.widget.Button
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.logger.adapter.ScheduleAdapter
import com.example.logger.data.model.ScheduleItem
import com.arjay.logger.R

class ScheduleFragment : Fragment() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ScheduleAdapter
    private lateinit var addButton: Button

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_schedule, container, false)
        recyclerView = view.findViewById(R.id.scheduleRecyclerView)
        addButton = view.findViewById(R.id.addScheduleButton)
        val mockSchedules = listOf(
            ScheduleItem("1", "Morning Attendance", "2025-07-21", "08:00 AM", "Room 101"),
            ScheduleItem("2", "Afternoon Log", "2025-07-21", "01:00 PM", "Room 102"),
            ScheduleItem("3", "Evening Dismissal", "2025-07-21", "05:00 PM", "Main Gate")
        )
        adapter = ScheduleAdapter(mockSchedules)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        // TODO: Add button logic for adding schedule
        return view
    }
}

