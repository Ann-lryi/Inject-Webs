package com.aho.streambrowser.di

import android.content.Context
import androidx.room.Room
import com.aho.streambrowser.data.db.AppDatabase
import com.aho.streambrowser.data.db.BookmarkDao
import com.aho.streambrowser.data.db.StreamHistoryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "streambrowser.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideBookmarkDao(db: AppDatabase): BookmarkDao = db.bookmarkDao()
    @Provides fun provideStreamHistoryDao(db: AppDatabase): StreamHistoryDao = db.streamHistoryDao()
}
