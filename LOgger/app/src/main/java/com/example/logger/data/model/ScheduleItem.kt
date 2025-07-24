package com.example.logger.data.model

data class ScheduleItem(
    val id: String,
    val title: String,
    val date: String,
    val time: String,
    val location: String? = null
)
