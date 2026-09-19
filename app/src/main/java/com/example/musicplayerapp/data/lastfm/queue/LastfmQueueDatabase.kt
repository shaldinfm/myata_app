package com.example.musicplayerapp.data.lastfm.queue

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The durable Last.fm scrobble queue (G6b P5) - a database of its own, file
 * `lastfm_queue`, deliberately **not** a table in `AppDatabase`.
 *
 *  - **Backup.** `myata_database` is backed up, and should be: it holds Collections.
 *    Pending scrobbles must not be: restored onto another device they would be sent
 *    from there, duplicating what this device already sent. Backup rules exclude
 *    whole files, so the queue needs a file of its own - excluded, with its `-wal`
 *    and `-shm`, alongside `lastfm_session.xml`.
 *  - **Blast radius.** A mistake here cannot stop a listener's Collection opening:
 *    `AppDatabase` is untouched, at version 4, with no new migration.
 *  - **Independence.** Deleting the Myata account clears Myata data; it does not
 *    touch this database, just as it does not touch the Last.fm session.
 *
 * No `fallbackToDestructiveMigration`, for the reason `AppDatabase` gives: a missing
 * migration must be a crash in a test, not silently lost scrobbles. `exportSchema`
 * is on; the schemas are checked into `app/schemas`.
 *
 *  - **Version 1** (P5): `scrobble_queue`.
 *  - **Version 2** (P6b): adds `scrobble_finalized` ([ScrobbleFinalized]), created
 *    empty by [MIGRATION_1_2] - a v1 row is pending, not finalized, and its own
 *    primary key already dedupes it while it waits.
 */
@Database(
    entities = [ScrobbleQueueEntry::class, ScrobbleFinalized::class],
    version = 2,
    exportSchema = true,
)
abstract class LastfmQueueDatabase : RoomDatabase() {

    abstract fun scrobbleQueueDao(): ScrobbleQueueDao

    companion object {

        /** The file name. The backup rules exclude it, `-wal` and `-shm`. */
        const val FILE = "lastfm_queue"

        @Volatile
        private var instance: LastfmQueueDatabase? = null

        @Volatile
        private var override: LastfmQueueDatabase? = null

        /**
         * Instrumentation only: hand [getDatabase] a database the test owns, and
         * `null` to restore the real one. Nothing in `src/main` calls this.
         */
        fun overrideForInstrumentation(database: LastfmQueueDatabase?) {
            override = database
        }

        /** The process's one queue database. Opening it touches disk: never on main. */
        fun getDatabase(context: Context): LastfmQueueDatabase {
            override?.let { return it }
            return instance ?: synchronized(this) {
                instance ?: build(context.applicationContext, FILE).also { instance = it }
            }
        }

        /** The production builder, also used by tests that need a real file. */
        fun build(context: Context, name: String): LastfmQueueDatabase =
            Room.databaseBuilder(context, LastfmQueueDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .build()

        /** Exactly what Room exports for [ScrobbleFinalized] in `2.json`. */
        const val CREATE_SCROBBLE_FINALIZED =
            "CREATE TABLE IF NOT EXISTS `scrobble_finalized` (`lastfm_username` TEXT NOT NULL, " +
                "`stream_id` TEXT NOT NULL, `started_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`lastfm_username`, `stream_id`))"

        /**
         * 1 -> 2: creates `scrobble_finalized`, empty. Reads no row and deliberately
         * back-fills nothing - every v1 row is still pending.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_SCROBBLE_FINALIZED)
            }
        }
    }
}
