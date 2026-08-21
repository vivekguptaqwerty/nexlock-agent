package com.nexlock.agent.data.api

import com.nexlock.agent.data.model.*
import retrofit2.Response
import retrofit2.http.*

interface MdmApiService {

    @POST("api/v1/mdm/enroll/handshake")
    suspend fun performHandshake(
        @Body request: HandshakeRequest
    ): Response<ApiResponse<HandshakeData>>

    @POST("api/v1/mdm/devices/heartbeat")
    suspend fun sendHeartbeat(
        @Header("Authorization") bearerToken: String,
        @Body request: HeartbeatRequest
    ): Response<ApiResponse<HeartbeatData>>

    @GET("api/v1/mdm/commands/pending")
    suspend fun getPendingCommands(
        @Header("Authorization") bearerToken: String
    ): Response<ApiResponse<List<CommandItem>>>

    @POST("api/v1/mdm/commands/{id}/ack")
    suspend fun acknowledgeCommand(
        @Header("Authorization") bearerToken: String,
        @Path("id") commandId: String,
        @Body request: AckRequest
    ): Response<ApiResponse<Any>>

    @GET("api/v1/mdm/devices/me/lock-info")
    suspend fun getLockInfo(
        @Header("Authorization") bearerToken: String
    ): Response<ApiResponse<LockInfoData>>

    // Public — fetched before the enrollment handshake, when there's no device token yet.
    @GET("api/v1/legal/privacy-policy")
    suspend fun getPrivacyPolicy(): Response<ApiResponse<LegalContentData>>

    @GET("api/v1/legal/terms")
    suspend fun getTerms(): Response<ApiResponse<LegalContentData>>
}
