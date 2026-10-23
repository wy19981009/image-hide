package com.whjk.imagehide.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.whjk.imagehide.data.room.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "image_hide.db")
            .fallbackToDestructiveMigration()   // 第 7 轮前用兜底，正式版写 Migration
            .build()
}
