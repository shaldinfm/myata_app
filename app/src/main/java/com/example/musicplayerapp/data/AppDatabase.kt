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

        @Volatile
        private var override: AppDatabase? = null

        /**
         * Instrumentation only: make [getDatabase] hand back a database the test
         * owns, and `null` to restore the real one.
         *
         * The same seam as `ReactionSyncBackend`, one layer down, and it exists for a
         * reason that arrived with G-A4b2. `EmailAuthRepository` and
         * `IdentityReconciler` are asked for by a screen, not constructed with
         * collaborators, so they reach the database themselves - which is right for
         * production and would otherwise mean a test of registration writing reaction
         * rows into the real `myata_database` on the device. Those rows are somebody's
         * Collection on a developer's phone, and the app's own startup drain would
         * find them.
         *
         * Nothing in `src/main` calls this. With no override installed - every state
         * a shipped build can be in - [getDatabase] is exactly what it always was.
         */
        fun overrideForInstrumentation(database: AppDatabase?) {
            override = database
        }

        fun getDatabase(context: Context): AppDatabase {
            override?.let { return it }

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
