package com.iamxpp.isaver.di

import android.content.Context
import com.iamxpp.isaver.ISaverApplication
import com.iamxpp.isaver.transfer.TransferDependencies
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
