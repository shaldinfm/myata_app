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
 * Version 3 added [ReactionOutboxEntry], the local queue of reaction transitions
 * waiting to reach the backend. [ReactionMigration.MIGRATION_2_3] only creates that
 * table: no existing row is read, rewritten or dropped, so a Collection cannot be
 * harmed by it, and every listener starts with an empty queue rather than a
 * back-filled history of acts that were already reported elsewhere.
 *
 * The two reaction tables answer different questions and neither substitutes for the
 * other: `track_reaction` is what a listener currently thinks, `reaction_outbox` is
 * what they did and the backend has not been told yet.
 *
 * There is deliberately **no** `fallbackToDestructiveMigration`. It would turn any
 * migration mistake into every listener silently losing their Collection, which is
 * exactly the failure this database exists to avoid. A missing migration must be a
 * crash in a test, not data loss on a phone.
 *
 * `exportSchema` is on, so every version is checked into `app/schemas` and a schema
 * change is visible in review rather than implicit in a diff of annotations.
 */
@Database(
    entities = [TrackReaction::class, ReactionOutboxEntry::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun reactionDao(): ReactionDao

    abstract fun reactionOutboxDao(): ReactionOutboxDao

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
                    .addMigrations(
                        ReactionMigration.MIGRATION_1_2,
                        ReactionMigration.MIGRATION_2_3,
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
