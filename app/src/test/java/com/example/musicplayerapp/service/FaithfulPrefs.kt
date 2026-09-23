package com.example.musicplayerapp.service

/**
 * App-private storage as Android's `SharedPreferences` actually behaves, on the JVM.
 *
 * ## Why the earlier fake was wrong
 *
 * The fake these tests used to run against refused the edit *before* touching its map,
 * so a commit that failed left the map exactly as it had been. Android does not do
 * that. An editor's changes are applied to the process's own map first and the file is
 * written afterwards, so `commit()`'s answer is about the file and nothing else:
 *
 * ```
 *   edit(changes)  -> map  := changes      always, before anything is written
 *                     file := changes      only when the commit reaches the file
 *                     answer: did the file get them
 * ```
 *
 * Two consequences follow, and a fake that returns early cannot show either of them:
 * the failed changes are **visible in this process**, and the next commit that succeeds
 * writes the whole map - so a value that never reached the file can become durable
 * later through a write that has nothing to do with it. That is the P1 these tests are
 * about: a queue that read the failed change back would let an unaccepted command
 * supersede an accepted one, and a queue that did not undo it would let a later
 * unrelated write make it permanent.
 *
 * ## The two halves
 *
 * [QueueFile] is the file, and it outlives the process - it is what a later process
 * reads, which is what makes "plant a command, then drain it from a new inbox" honest.
 * [FaithfulPrefs] is *one process's* `SharedPreferences` over that file: its own map,
 * loaded once when the process opened it, and [failedCommits], which is the disk's
 * willingness to take a write.
 *
 * Nothing here models what the queue *does* about any of it. The rollback, the read-back
 * that verifies it and the failure state are production code in [PlaybackCommandInbox],
 * and these tests drive that code through this fake rather than re-implementing it.
 */
internal class QueueFile {

    /** What the file holds: the state a process that has not run yet would read. */
    val entries = mutableMapOf<String, String>()
}

/**
 * One process's view of a [QueueFile], with a disk that can be told to refuse.
 *
 * [failedCommits] is how many of the next commits do not reach the file. A refused
 * commit still changes [read]'s answer, which is the property the queue's rollback
 * exists for; setting it to one fails a single write and leaves the undo after it
 * working, and setting it to two fails the write *and* the restore.
 */
internal class FaithfulPrefs(private val file: QueueFile) : PlaybackCommandInbox.RawSlot {

    /** This process's map: what `SharedPreferences` loads when the file is opened. */
    private val map = file.entries.toMutableMap()

    /** How many of the next commits do not reach the file. */
    var failedCommits = 0

    override fun read(): Map<String, String> = map.toMap()

    override fun edit(changes: Map<String, String?>): Boolean {
        apply(changes, map)
        if (failedCommits > 0) {
            failedCommits--
            return false
        }
        apply(changes, file.entries)
        return true
    }

    /** Test-only: what this process sees, which a refused commit has already changed. */
    fun visible(): Map<String, String> = map.toMap()

    /** Test-only: what the file holds, which a refused commit did not change. */
    fun onDisk(): Map<String, String> = file.entries.toMap()

    private fun apply(changes: Map<String, String?>, target: MutableMap<String, String>) {
        for ((key, value) in changes) {
            if (value == null) target.remove(key) else target[key] = value
        }
    }
}
