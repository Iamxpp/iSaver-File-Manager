package com.isaver.filemanager.di

import android.content.Context
import com.isaver.filemanager.ISaverApplication
import com.isaver.filemanager.transfer.TransferDependencies
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext

@Module
@InstallIn(SingletonComponent::class)
object TransferModule {
    @Provides
    fun provideTransferDependencies(
        @ApplicationContext context: Context,
    ): TransferDependencies = (context as ISaverApplication).transferDependencies
}
