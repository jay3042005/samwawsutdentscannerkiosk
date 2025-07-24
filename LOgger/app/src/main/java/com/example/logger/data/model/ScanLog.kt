package com.arjay.logger.data.model

import android.os.Parcel
import android.os.Parcelable
import com.google.gson.annotations.SerializedName

data class ScanLog(
    @SerializedName("id")
    val id: String,
    
    @SerializedName("studentId")
    val studentId: String,
    
    @SerializedName("studentName")
    val studentName: String,
    
    @SerializedName("entryExitStatus")
    val entryExitStatus: String,
    
    @SerializedName("timestamp")
    val timestamp: String,
    
    @SerializedName("grade")
    val grade: String = "",
    
    @SerializedName("section")
    val section: String = "",
    
    // Legacy fields for backward compatibility
    @SerializedName("name")
    val name: String? = studentName,
    
    @SerializedName("status")
    val status: String? = entryExitStatus
) : Parcelable {
    constructor(parcel: Parcel) : this(
        id = parcel.readString() ?: "",
        studentId = parcel.readString() ?: "",
        studentName = parcel.readString() ?: "",
        entryExitStatus = parcel.readString() ?: "",
        timestamp = parcel.readString() ?: "",
        grade = parcel.readString() ?: "",
        section = parcel.readString() ?: "",
        name = parcel.readString(),
        status = parcel.readString()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeString(studentId)
        parcel.writeString(studentName)
        parcel.writeString(entryExitStatus)
        parcel.writeString(timestamp)
        parcel.writeString(grade)
        parcel.writeString(section)
        parcel.writeString(name)
        parcel.writeString(status)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<ScanLog> {
        override fun createFromParcel(parcel: Parcel): ScanLog {
            return ScanLog(parcel)
        }

        override fun newArray(size: Int): Array<ScanLog?> {
            return arrayOfNulls(size)
        }
    }
}

data class ScanLogResponse(
    val items: List<ScanLogRaw>
)

data class ScanLogRaw(
    val id: String,
    @SerializedName("student_id")
    val studentId: String,
    @SerializedName("student_name")
    val studentName: String,
    @SerializedName("entry_exit_status")
    val entryExitStatus: String,
    val timestamp: String,
    @SerializedName("grade_level")
    val gradeLevel: Int? = null,
    val section: String? = null
)