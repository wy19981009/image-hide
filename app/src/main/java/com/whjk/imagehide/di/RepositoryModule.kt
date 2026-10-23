package com.whjk.imagehide.di

import com.whjk.imagehide.data.repository.AlbumRepositoryImpl
import com.whjk.imagehide.data.repository.HiddenRepositoryImpl
import com.whjk.imagehide.data.repository.MediaRepositoryImpl
import com.whjk.imagehide.domain.repository.AlbumRepository
import com.whjk.imagehide.domain.repository.HiddenRepository
import com.whjk.imagehide.domain.repository.MediaRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAlbumRepository(impl: AlbumRepositoryImpl): AlbumRepository

    @Binds
    @Singleton
    abstract fun bindMediaRepository(impl: MediaRepositoryImpl): MediaRepository

    @Binds
    @Singleton
    abstract fun bindHiddenRepository(impl: HiddenRepositoryImpl): HiddenRepository
}
