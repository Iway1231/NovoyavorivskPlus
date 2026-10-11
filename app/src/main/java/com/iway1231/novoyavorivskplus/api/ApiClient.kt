package com.iway1231.novoyavorivskplus.api

import android.content.Context
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ApiClient {
    private const val REMOTE_URL = "https://iway1231-server1.tail04685e.ts.net/"
    const val LOCAL_URL = "http://192.168.0.112:8000/"

    private var configuredApi: PowerApi? = null

    fun initialize(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (configuredApi != null) return
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .addInterceptor(AutoFallbackInterceptor())
            .build()
        configuredApi = Retrofit.Builder()
            .baseUrl(LOCAL_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PowerApi::class.java)
    }

    private class AutoFallbackInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val request = chain.request()
            return try {
                chain.proceed(request)
            } catch (localError: IOException) {
                if (request.url().host() != "192.168.0.112") throw localError
                val fallbackUrl = request.url().newBuilder()
                    .scheme("https")
                    .host("iway1231-server1.tail04685e.ts.net")
                    .port(443)
                    .build()
                chain.proceed(request.newBuilder().url(fallbackUrl).build())
            }
        }
    }

    val api: PowerApi
        get() = configuredApi ?: error("ApiClient has not been initialized")
}

