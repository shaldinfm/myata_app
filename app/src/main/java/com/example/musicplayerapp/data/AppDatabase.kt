package com.example.musicplayerapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Main Room database for the app.
 *
 * Version 2 replaced the `favorites` table with [TrackReaction], the three-state
 * reaction model. [ReactionMigration.MIGRATION_1_2] carries existing collections
 * across; see its KDoc for the merge rules.
 *
 * There is deliberately **no** `fallbackToDestructiveMigration`. It would turn any
 * migration mistake into every listener silently losing their Collection, which is
 * exactly the failure this database exists to avoid. A missing migration must be a
 * crash in a test, not data loss on a phone.
 *
 * `exportSchema` is on, so every version is checked into `app/schemas` and a schema
 * change is visible in review rather than implicit in a diff of annotations.
 */
@Database(entities = [TrackReaction::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {

    abstract fun reactionDao(): ReactionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "myata_database"
                )
                    .addMigrations(ReactionMigration.MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
