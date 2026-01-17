package com.flockyou.di

import android.content.Context
import com.flockyou.data.repository.DetectionDao
import com.flockyou.data.repository.DetectionRepository
import com.flockyou.data.repository.FlockYouDatabase
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
}
