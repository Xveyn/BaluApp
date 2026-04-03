package com.baluhost.android.data.remote.interceptors

import com.baluhost.android.domain.model.sync.SyncTrigger
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

class SyncTriggerInterceptor @Inject constructor() : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val trigger = request.tag(SyncTrigger::class.java)
        if (trigger != null) {
            val newRequest = request.newBuilder()
                .header("X-Sync-Trigger", trigger.headerValue)
                .build()
            return chain.proceed(newRequest)
        }
        return chain.proceed(request)
    }
}
