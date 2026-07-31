package com.baluhost.android.data.remote.api

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Builds a MobileApi bound to a server URL that is only known at runtime.
 *
 * Device pairing points at whatever server the QR code names, so the injected
 * MobileApi — which is wired to BuildConfig.BASE_URL — is the wrong client for
 * it. Behind this interface the registration use case can be unit tested
 * without a real Retrofit reaching for the network.
 */
interface MobileApiFactory {
    /**
     * @param baseUrl the server root from the QR code, with or without a
     *   trailing slash; the "api/" segment is appended here.
     * @param tokenProvider consulted per request, so a token obtained during
     *   registration is picked up by later calls on the same client.
     */
    fun create(baseUrl: String, tokenProvider: () -> String?): MobileApi
}

class RetrofitMobileApiFactory @Inject constructor() : MobileApiFactory {

    override fun create(baseUrl: String, tokenProvider: () -> String?): MobileApi {
        val finalUrl = baseUrl.let { if (it.endsWith("/")) it else "$it/" } + "api/"

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val requestBuilder = chain.request().newBuilder()
                tokenProvider()?.let {
                    requestBuilder.header("Authorization", "Bearer $it")
                }
                chain.proceed(requestBuilder.build())
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(finalUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MobileApi::class.java)
    }
}
