package com.studentscanner.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.text.SimpleDateFormat
import java.util.*
import org.example.project.data.SchoolSettings
import org.example.project.data.ScanLog

@Serializable
data class Student(
    val id: String = UUID.randomUUID().toString(),
    val idNo: String,
    val firstName: String,
    val lastName: String,
    val grade: String,
    val section: String,
    val guardianName: String = "",
    val guardianPhone: String = "",
    val created: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
    val updated: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
)

@Serializable
data class SchoolTime(
    val id: String = UUID.randomUUID().toString(),
    val startTime: String,
    val endTime: String,
    val isActive: Boolean = true,
    val created: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
    val updated: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
)

class LocalStorageManager(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val databaseDir = java.io.File("/storage/emulated/0/database")
    private val studentsFile = java.io.File(databaseDir, "students.json")
    private val schoolTimeFile = java.io.File(databaseDir, "schooltime.json")
    private val scanLogsFile = java.io.File(databaseDir, "scan_logs.json")

    init {
        if (!databaseDir.exists()) databaseDir.mkdirs()
        if (!studentsFile.exists()) studentsFile.writeText("[]")
        if (!schoolTimeFile.exists()) schoolTimeFile.writeText("{}") // Initialize as empty JSON object
        if (!scanLogsFile.exists()) scanLogsFile.writeText("[]")
    }

    // Import students from CSV file
    fun importStudentsFromCSV(csvContent: String): Boolean {
        return try {
            val students = getStudents().toMutableList()
            val lines = csvContent.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return false
            val header = lines.first().split(",")
            val idNoIdx = header.indexOfFirst { it.trim().equals("id_no", ignoreCase = true) }
            val firstNameIdx = header.indexOfFirst { it.trim().equals("first_name", ignoreCase = true) }
            val lastNameIdx = header.indexOfFirst { it.trim().equals("last_name", ignoreCase = true) }
            val gradeIdx = header.indexOfFirst { it.trim().equals("grade", ignoreCase = true) }
            val sectionIdx = header.indexOfFirst { it.trim().equals("section", ignoreCase = true) }
            val guardianNameIdx = header.indexOfFirst { it.trim().equals("guardian_name", ignoreCase = true) }
            val guardianPhoneIdx = header.indexOfFirst { it.trim().equals("guardian_phone", ignoreCase = true) }

            for (line in lines.drop(1)) {
                val cols = line.split(",")
                if (cols.size < 5) continue
                val student = Student(
                    idNo = cols.getOrNull(idNoIdx)?.trim() ?: "",
                    firstName = cols.getOrNull(firstNameIdx)?.trim() ?: "",
                    lastName = cols.getOrNull(lastNameIdx)?.trim() ?: "",
                    grade = cols.getOrNull(gradeIdx)?.trim() ?: "",
                    section = cols.getOrNull(sectionIdx)?.trim() ?: "",
                    guardianName = cols.getOrNull(guardianNameIdx)?.trim() ?: "",
                    guardianPhone = cols.getOrNull(guardianPhoneIdx)?.trim() ?: ""
                )
                // Avoid duplicates by id_no
                if (students.none { it.idNo == student.idNo }) {
                    students.add(student)
                }
            }
            val studentsJson = json.encodeToString(students)
            studentsFile.writeText(studentsJson)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // Student operations
    fun saveStudent(student: Student): Boolean {
        return try {
            val students = getStudents().toMutableList()
            val existingIndex = students.indexOfFirst { it.id == student.id }
            if (existingIndex >= 0) {
                students[existingIndex] = student.copy(updated = getCurrentTimestamp())
            } else {
                students.add(student)
            }
            val studentsJson = json.encodeToString(students)
            studentsFile.writeText(studentsJson)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getStudents(): List<Student> {
        return try {
            val studentsJson = studentsFile.readText()
            json.decodeFromString<List<Student>>(studentsJson)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun getStudentById(id: String): Student? {
        return getStudents().find { it.id == id }
    }

    fun getStudentByIdNo(idNo: String): Student? {
        return getStudents().find { it.idNo == idNo }
    }

    fun deleteStudent(id: String): Boolean {
        return try {
            val students = getStudents().toMutableList()
            students.removeAll { it.id == id }
            val studentsJson = json.encodeToString(students)
            studentsFile.writeText(studentsJson)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // School time operations
    fun saveSchoolTime(settings: org.example.project.data.SchoolSettings): Boolean {
        return try {
            val jsonStr = json.encodeToString(settings)
            schoolTimeFile.writeText(jsonStr)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun loadSchoolTime(): org.example.project.data.SchoolSettings? {
        return try {
            if (!schoolTimeFile.exists()) return null
            val jsonStr = schoolTimeFile.readText()
            json.decodeFromString<org.example.project.data.SchoolSettings>(jsonStr)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getSchoolTimes(): List<SchoolTime> {
        return try {
            val schoolTimesJson = schoolTimeFile.readText() // Changed to schoolTimeFile
            json.decodeFromString<List<SchoolTime>>(schoolTimesJson)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun getActiveSchoolTime(): SchoolTime? {
        return getSchoolTimes().find { it.isActive }
    }

    fun deleteSchoolTime(id: String): Boolean {
        return try {
            val schoolTimes = getSchoolTimes().toMutableList()
            schoolTimes.removeAll { it.id == id }
            val schoolTimesJson = json.encodeToString(schoolTimes)
            schoolTimeFile.writeText(schoolTimesJson) // Changed to schoolTimeFile
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // Scan log operations
    fun saveScanLog(scanLog: org.example.project.data.ScanLog): Boolean {
        return try {
            val scanLogs = getScanLogs().toMutableList()
            scanLogs.add(scanLog)
            val scanLogsJson = json.encodeToString(scanLogs)
            scanLogsFile.writeText(scanLogsJson)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getScanLogs(): List<org.example.project.data.ScanLog> {
        return try {
            val scanLogsJson = scanLogsFile.readText()
            json.decodeFromString<List<org.example.project.data.ScanLog>>(scanLogsJson)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun getScanLogsByDate(date: String): List<org.example.project.data.ScanLog> {
        return getScanLogs().filter { it.timestamp.startsWith(date) }
    }

    fun getScanLogsByStudent(studentId: String): List<org.example.project.data.ScanLog> {
        return getScanLogs().filter { it.student_id == studentId }
    }

    fun getLatestScanForStudent(studentId: String): org.example.project.data.ScanLog? {
        return getScanLogs()
            .filter { it.student_id == studentId }
            .maxByOrNull { it.timestamp }
    }

    fun clearAllScanLogs(): Boolean {
        return try {
            scanLogsFile.writeText("[]")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // Utility functions
    private fun getCurrentTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    }

    fun exportData(): String {
        return try {
            val data = mapOf(
                "students" to getStudents(),
                "schoolTimes" to getSchoolTimes(),
                "scanLogs" to getScanLogs(),
                "exportDate" to getCurrentTimestamp()
            )
            json.encodeToString(data)
        } catch (e: Exception) {
            e.printStackTrace()
            "{}"
        }
    }

    fun clearAllData(): Boolean {
        return try {
            studentsFile.writeText("[]")
            schoolTimeFile.writeText("{}") // Changed to schoolTimeFile
            scanLogsFile.writeText("[]")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // --- SchoolSettings unified save/load ---
    fun saveSchoolSettings(settings: SchoolSettings): Boolean {
        return try {
            val prefs = context.getSharedPreferences("school_settings", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("entry_start", settings.school_entry_start_time)
                .putString("entry_end", settings.school_entry_end_time)
                .putString("exit_start", settings.school_exit_start_time)
                .putString("exit_end", settings.school_exit_end_time)
                .apply()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun loadSchoolSettings(): SchoolSettings? {
        return try {
            val prefs = context.getSharedPreferences("school_settings", Context.MODE_PRIVATE)
            val entryStart = prefs.getString("entry_start", null)
            val entryEnd = prefs.getString("entry_end", null)
            val exitStart = prefs.getString("exit_start", null)
            val exitEnd = prefs.getString("exit_end", null)
            if (entryStart != null && entryEnd != null && exitStart != null && exitEnd != null) {
                SchoolSettings(
                    school_entry_start_time = entryStart,
                    school_entry_end_time = entryEnd,
                    school_exit_start_time = exitStart,
                    school_exit_end_time = exitEnd
                )
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun isDuplicateScan(studentId: String, entryExitStatus: String, schoolSettings: org.example.project.data.SchoolSettings): Boolean {
        val logs = getScanLogs().filter { it.student_id == studentId && it.entry_exit_status == entryExitStatus }
        val now = System.currentTimeMillis()
        val (startTime, endTime) = when (entryExitStatus) {
            "entry" -> schoolSettings.school_entry_start_time to schoolSettings.school_entry_end_time
            "exit" -> schoolSettings.school_exit_start_time to schoolSettings.school_exit_end_time
            else -> null to null
        }
        if (startTime == null || endTime == null) return false
        val startMinutes = parseTimeToMinutes(startTime)
        val endMinutes = parseTimeToMinutes(endTime)
        val nowMinutes = getCurrentTimeInMinutes()

        // Only check for duplicates if now is within the window
        if (nowMinutes in startMinutes..endMinutes) {
            // Find the most recent scan for this student and status, today, within the window
            val today = java.util.Calendar.getInstance()
            logs.forEach { log ->
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = log.scan_timestamp
                val sameDay = cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
                              cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)
                val minutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
                if (sameDay && minutes in startMinutes..endMinutes) {
                    return true // Duplicate found
                }
            }
        }
        return false
    }

    private fun parseTimeToMinutes(time: String): Int {
        val parts = time.split(":")
        return parts[0].toInt() * 60 + parts[1].toInt()
    }

    private fun getCurrentTimeInMinutes(): Int {
        val cal = java.util.Calendar.getInstance()
        return cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    }

    private fun isTimestampInWindow(timestamp: Long, startMinutes: Int, endMinutes: Int): Boolean {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = timestamp
        val minutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return minutes in startMinutes..endMinutes
    }
}
