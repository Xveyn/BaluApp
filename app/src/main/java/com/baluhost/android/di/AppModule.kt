package com.baluhost.android.di

import android.content.Context
import com.baluhost.android.data.remote.api.MobileApiFactory
import com.baluhost.android.data.remote.api.RetrofitMobileApiFactory
import com.baluhost.android.util.AndroidBase64Decoder
import com.baluhost.android.util.Base64Decoder
import com.baluhost.android.util.Clock
import com.baluhost.android.util.NetworkMonitor
import com.baluhost.android.util.NetworkMonitorImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import javax.inject.Singleton

/**
 * Provides app-level dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideApplicationContext(
        @ApplicationContext context: Context
    ): Context = context
    
    @Provides
    @Singleton
    fun provideNetworkMonitor(
        @ApplicationContext context: Context
    ): NetworkMonitor = NetworkMonitorImpl(context)

    @Provides
    @Singleton
    fun provideBase64Decoder(): Base64Decoder = AndroidBase64Decoder()

    @Provides
    @Singleton
    fun provideMobileApiFactory(): MobileApiFactory = RetrofitMobileApiFactory()

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock { Instant.now() }
}
