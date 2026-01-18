package com.flockyou.di

import android.content.Context
import com.flockyou.data.OuiSettingsRepository
import com.flockyou.data.oui.OuiDownloader
import com.flockyou.data.oui.OuiLookupService
import com.flockyou.data.repository.DetectionDao
import com.flockyou.data.repository.DetectionRepository
import com.flockyou.data.repository.FlockYouDatabase
import com.flockyou.data.repository.OuiDao
import com.flockyou.data.repository.OuiRepository
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
    fun provideDatabase(@ApplicationContext context: Context): FlockYouDatabase {
        return FlockYouDatabase.getDatabase(context)
    }

    @Provides
    @Singleton
    fun provideDetectionDao(database: FlockYouDatabase): DetectionDao {
        return database.detectionDao()
    }

    @Provides
    @Singleton
    fun provideDetectionRepository(detectionDao: DetectionDao): DetectionRepository {
        return DetectionRepository(detectionDao)
    }

    @Provides
    @Singleton
    fun provideOuiDao(database: FlockYouDatabase): OuiDao {
        return database.ouiDao()
    }

    @Provides
    @Singleton
    fun provideOuiRepository(ouiDao: OuiDao): OuiRepository {
        return OuiRepository(ouiDao)
    }

    @Provides
    @Singleton
    fun provideOuiSettingsRepository(@ApplicationContext context: Context): OuiSettingsRepository {
        return OuiSettingsRepository(context)
    }

    @Provides
    @Singleton
    fun provideOuiDownloader(@ApplicationContext context: Context): OuiDownloader {
        return OuiDownloader(context)
    }

    @Provides
    @Singleton
    fun provideOuiLookupService(ouiRepository: OuiRepository): OuiLookupService {
        return OuiLookupService(ouiRepository)
    }
}
