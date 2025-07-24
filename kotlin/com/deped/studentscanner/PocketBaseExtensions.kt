package com.deped.studentscanner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

/**
 * Extension functions for PocketBaseApiClient to support server-based duplicate detection
 * These methods are specifically designed for the MainActivity duplicate detection system
 */

// Data class for internal student info (different from StudentResponse)
data class StudentInfo(
    val id: String,
    val name: String,
    val grade_level: Int,
    val section: String,
    val parent_phone: String?
)
