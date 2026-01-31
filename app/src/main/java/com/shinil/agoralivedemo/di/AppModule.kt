package com.shinil.agoralivedemo.di

import com.google.gson.Gson
import com.shinil.agoralivedemo.data.repository.AgoraChannelRepository
import com.shinil.agoralivedemo.data.repository.AgoraVideoCallRepository
import com.shinil.agoralivedemo.domain.repository.ChannelRepository
import com.shinil.agoralivedemo.domain.repository.VideoCallRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    @Singleton
    abstract fun bindChannelRepository(
        impl: AgoraChannelRepository
    ): ChannelRepository

    @Binds
    @Singleton
    abstract fun bindVideoCallRepository(
        impl: AgoraVideoCallRepository
    ): VideoCallRepository

    companion object {
        @Provides
        @Singleton
        fun provideGson(): Gson = Gson()
    }
}
