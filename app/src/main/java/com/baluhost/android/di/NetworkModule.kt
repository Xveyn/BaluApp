package com.baluhost.android.di

import android.content.Context
import android.net.ConnectivityManager
import com.baluhost.android.BuildConfig
import com.baluhost.android.data.local.datastore.PreferencesManager
import com.baluhost.android.data.remote.api.ActivityApi
import com.baluhost.android.data.remote.api.AuthApi
import com.baluhost.android.data.remote.api.EnergyApi
import com.baluhost.android.data.remote.api.FilesApi
import com.baluhost.android.data.remote.api.MobileApi
import com.baluhost.android.data.remote.api.MonitoringApi
import com.baluhost.android.data.remote.api.SharesApi
import com.baluhost.android.data.remote.api.PluginApi
import com.baluhost.android.data.remote.api.SleepApi
import com.baluhost.android.data.remote.api.SyncApi
import com.baluhost.android.data.remote.api.SystemApi
import com.baluhost.android.data.remote.api.VpnApi
import com.baluhost.android.data.remote.interceptors.AuthInterceptor
import com.baluhost.android.data.remote.interceptors.DynamicBaseUrlInterceptor
import com.baluhost.android.data.remote.interceptors.ErrorInterceptor
import com.baluhost.android.data.remote.interceptors.SyncTriggerInterceptor
import com.baluhost.android.data.remote.interceptors.VpnAwareSocketFactory
import com.baluhost.android.util.Constants
import com.baluhost.android.util.BssidReader
import com.baluhost.android.util.NetworkMonitor
import com.baluhost.android.util.NetworkStateManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/**
 * Provides networking dependencies (Retrofit, OkHttp, API interfaces).
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    
    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
    }
    
    @Provides
    @Singleton
    fun provideAuthInterceptor(
        preferencesManager: PreferencesManager,
        authApi: dagger.Lazy<AuthApi>
    ): AuthInterceptor {
        return AuthInterceptor(preferencesManager, authApi)
    }
    
    @Provides
    @Singleton
    fun provideErrorInterceptor(): ErrorInterceptor {
        return ErrorInterceptor()
    }

    @Provides
    @Singleton
    fun provideSyncTriggerInterceptor(): SyncTriggerInterceptor {
        return SyncTriggerInterceptor()
    }

    @Provides
    @Singleton
    fun provideDynamicBaseUrlInterceptor(
        preferencesManager: PreferencesManager
    ): DynamicBaseUrlInterceptor {
        return DynamicBaseUrlInterceptor(preferencesManager, BuildConfig.BASE_URL)
    }
    
    @Provides
    @Singleton
    fun provideVpnAwareSocketFactory(
        @ApplicationContext context: Context
    ): VpnAwareSocketFactory {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return VpnAwareSocketFactory(cm)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor,
        authInterceptor: AuthInterceptor,
        errorInterceptor: ErrorInterceptor,
        dynamicBaseUrlInterceptor: DynamicBaseUrlInterceptor,
        vpnAwareSocketFactory: VpnAwareSocketFactory,
        syncTriggerInterceptor: SyncTriggerInterceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .socketFactory(vpnAwareSocketFactory)
            .addInterceptor(dynamicBaseUrlInterceptor)
            .addInterceptor(errorInterceptor)
            .addInterceptor(authInterceptor)
            .addInterceptor(syncTriggerInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(Constants.CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(Constants.READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(Constants.WRITE_TIMEOUT, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
    
    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi {
        return retrofit.create(AuthApi::class.java)
    }
    
    @Provides
    @Singleton
    fun provideFilesApi(retrofit: Retrofit): FilesApi {
        return retrofit.create(FilesApi::class.java)
    }
    
    @Provides
    @Singleton
    fun provideMobileApi(retrofit: Retrofit): MobileApi {
        return retrofit.create(MobileApi::class.java)
    }
    
    @Provides
    @Singleton
    fun provideVpnApi(retrofit: Retrofit): VpnApi {
        return retrofit.create(VpnApi::class.java)
    }
    
    @Provides
    @Singleton
    fun provideSystemApi(retrofit: Retrofit): SystemApi {
        return retrofit.create(SystemApi::class.java)
    }
    
    @Provides
    @Singleton
    fun provideSyncApi(retrofit: Retrofit): SyncApi {
        return retrofit.create(SyncApi::class.java)
    }

    @Provides
    @Singleton
    fun provideEnergyApi(retrofit: Retrofit): EnergyApi {
        return retrofit.create(EnergyApi::class.java)
    }

    @Provides
    @Singleton
    fun provideMonitoringApi(retrofit: Retrofit): MonitoringApi {
        return retrofit.create(MonitoringApi::class.java)
    }

    @Provides
    @Singleton
    fun provideSharesApi(retrofit: Retrofit): SharesApi {
        return retrofit.create(SharesApi::class.java)
    }

    @Provides
    @Singleton
    fun provideActivityApi(retrofit: Retrofit): ActivityApi {
        return retrofit.create(ActivityApi::class.java)
    }

    @Provides
    @Singleton
    fun provideNotificationsApi(retrofit: Retrofit): com.baluhost.android.data.remote.api.NotificationsApi {
        return retrofit.create(com.baluhost.android.data.remote.api.NotificationsApi::class.java)
    }

    @Provides
    @Singleton
    fun provideSleepApi(retrofit: Retrofit): SleepApi {
        return retrofit.create(SleepApi::class.java)
    }

    @Provides
    @Singleton
    fun providePluginApi(retrofit: Retrofit): PluginApi {
        return retrofit.create(PluginApi::class.java)
    }

    @Provides
    @Singleton
    @Named("websocket")
    fun provideWebSocketOkHttpClient(
        vpnAwareSocketFactory: VpnAwareSocketFactory
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .socketFactory(vpnAwareSocketFactory)
            .connectTimeout(Constants.CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout for WebSocket
            .writeTimeout(Constants.WRITE_TIMEOUT, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    
    @Provides
    @Singleton
    fun provideNetworkStateManager(
        @ApplicationContext context: Context,
        networkMonitor: NetworkMonitor,
        bssidReader: BssidReader,
        preferencesManager: PreferencesManager
    ): NetworkStateManager {
        return NetworkStateManager(context, networkMonitor, bssidReader, preferencesManager)
    }
}
