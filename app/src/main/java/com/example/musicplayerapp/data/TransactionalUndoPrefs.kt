package com.example.musicplayerapp.data

import android.content.Context
import android.content.SharedPreferences
import com.example.musicplayerapp.service.PlaybackLog

/** A transaction over the sleep-timer Undo file's existing, typed preference keys. */
internal class TransactionalUndoPrefs(private val raw: Raw) {
    interface Raw {
        fun read(): Map<String, Any>
        /** Like SharedPreferences: the process map changes before a failed disk write returns. */
        fun edit(changes: Map<String, Any?>): Boolean
    }

    private var frozen: Map<String, Any>? = null

    val healthy: Boolean get() = synchronized(this) { frozen == null }

    fun read(): Map<String, Any> = synchronized(this) { frozen ?: raw.read() }

    fun edit(changes: Map<String, Any?>): Boolean = synchronized(this) {
        if (frozen != null) return@synchronized false

        val before = raw.read()
        if (raw.edit(changes)) return@synchronized true

        val leaked = raw.read()
        if (leaked == before) return@synchronized false

        val rollback = (leaked.keys + before.keys).associateWith { before[it] }
        if (raw.edit(rollback) && raw.read() == before) return@synchronized false

        frozen = before
        PlaybackLog.problem(
            "SLEEP_TIMER_UNDO_STORAGE_UNHEALTHY",
            "reason" to "write_not_durable_and_not_undoable",
            "outcome" to "reads_frozen_writes_refused",
        )
        false
    }

    class SharedPreferencesRaw(context: Context) : Raw {
        private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
            SleepTimerStore.UNDO_FILE, Context.MODE_PRIVATE,
        )

        override fun read(): Map<String, Any> = prefs.all.mapNotNull { (key, value) ->
            value?.let { key to it }
        }.toMap()

        override fun edit(changes: Map<String, Any?>): Boolean {
            val editor = prefs.edit()
            for ((key, value) in changes) {
                when (value) {
                    null -> editor.remove(key)
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    else -> error("Unsupported Undo preference value for $key")
                }
            }
            return editor.commit()
        }
    }
}
