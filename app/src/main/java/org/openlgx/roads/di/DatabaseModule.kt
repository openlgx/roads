package org.openlgx.roads.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.openlgx.roads.data.local.db.RoadsDatabase
import org.openlgx.roads.data.local.db.RoadsDatabaseMigrations
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideRoadsDatabase(
        @ApplicationContext context: Context,
    ): RoadsDatabase =
        Room.databaseBuilder(context, RoadsDatabase::class.java, "roads.db")
            .addMigrations(
                RoadsDatabaseMigrations.MIGRATION_3_4,
                RoadsDatabaseMigrations.MIGRATION_4_5,
            )
            // Only v3+ ships non-destructive migration steps; older DBs cannot step to v5 safely.
            .fallbackToDestructiveMigrationFrom(1, 2)
            .build()
}
