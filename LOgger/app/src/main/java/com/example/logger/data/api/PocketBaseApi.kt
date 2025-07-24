package com.arjay.logger.data.api

import com.arjay.logger.data.model.ScanLogResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface PocketBaseApi {
    @GET("api/collections/scan_logs/records")
    suspend fun getScanLogs(
        @Query("fields") fields: String = "id,student_id,student_name,entry_exit_status,timestamp",
        @Query("perPage") perPage: Int = 1000,
        @Query("sort") sort: String = "-created"
    ): ScanLogResponse
    
    @GET("api/collections/scan_logs/records")
    suspend fun getScanLogsWithFilter(
        @Query("filter") filter: String,
        @Query("perPage") perPage: Int = 1000,
        @Query("sort") sort: String = "-created"
    ): ScanLogResponse
} 