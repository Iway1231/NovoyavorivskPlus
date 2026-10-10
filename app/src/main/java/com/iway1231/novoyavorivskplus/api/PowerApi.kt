package com.iway1231.novoyavorivskplus.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import retrofit2.http.GET
import retrofit2.http.Query

interface PowerApi {

    @GET("api/v1/health")
    suspend fun getHealth(): JsonObject

    @GET("api/v1/status")
    suspend fun getPowerStatus(): JsonObject

    @GET("api/v1/app/bootstrap")
    suspend fun getBootstrap(): JsonObject

    @GET("api/v1/naftogaz/groups")
    suspend fun getNaftogazGroups(): List<JsonObject>

    
    @GET("api/v1/loe/cities")
    suspend fun getLoeCities(): JsonElement

    @GET("api/v1/loe/streets")
    suspend fun getLoeStreets(@Query("city") city: String): JsonElement

    @GET("api/v1/loe/buildings")
    suspend fun getLoeBuildings(
        @Query("city") city: String,
        @Query("street") street: String
    ): JsonElement

    @GET("api/v1/loe/lookup")
    suspend fun lookupLoe(
        @Query("city") city: String,
        @Query("street") street: String,
        @Query("building") building: String
    ): JsonObject
    @GET("api/v1/mobile/home")
    suspend fun getMobileHome(
        @Query("operator") operator: String = "naftogaz",
        @Query("group") group: String = "2.1"
    ): JsonObject
}
