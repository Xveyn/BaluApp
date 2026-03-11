package com.baluhost.android.data.remote.api

import com.baluhost.android.data.remote.dto.ActivityFeedResponse
import com.baluhost.android.data.remote.dto.RecentFilesResponse
import com.baluhost.android.data.remote.dto.ReportActivitiesRequest
import com.baluhost.android.data.remote.dto.ReportActivitiesResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * API for file activity tracking and recent files.
 */
interface ActivityApi {

    @GET("activity/recent-files")
    suspend fun getRecentFiles(
        @Query("limit") limit: Int = 10,
        @Query("actions") actions: String? = null
    ): RecentFilesResponse

    @GET("activity/recent")
    suspend fun getActivityFeed(
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("action_types") actionTypes: String? = null,
        @Query("file_type") fileType: String? = null,
        @Query("since") since: String? = null,
        @Query("path_prefix") pathPrefix: String? = null
    ): ActivityFeedResponse

    @POST("activity/report")
    suspend fun reportActivities(
        @Body request: ReportActivitiesRequest
    ): ReportActivitiesResponse
}
