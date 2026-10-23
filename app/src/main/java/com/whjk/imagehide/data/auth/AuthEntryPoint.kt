package com.whjk.imagehide.data.auth

import com.whjk.imagehide.data.datastore.UserPrefs
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AuthEntryPoint {
    fun biometricHelper(): BiometricHelper
    fun userPrefs(): UserPrefs
}
