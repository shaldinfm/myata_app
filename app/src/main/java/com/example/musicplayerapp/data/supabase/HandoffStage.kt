package com.example.musicplayerapp.data.supabase

/**
 * How far an identity handoff got, persisted so a process death can be undone.
 *
 * Three stages, and each one is the answer to "what is now true remotely that this
 * device would otherwise forget". Anything that is not remotely irreversible does
 * not get a stage, which is why draining has none: a drain that dies changes nothing
 * a later drain cannot redo.
 *
 * The stages are written **before** the action they describe, never after. A stage
 * written afterwards would leave the window this whole record exists to close:
 * the action succeeds, the process dies, the disk says nothing happened.
 */
enum class HandoffStage {

    /**
     * A handoff from the source uid is about to begin, or is under way, and its
     * remote current state may be intact, partly retired or wholly retired.
     *
     * One stage for all three because retiring is idempotent and the recovery is the
     * same in every case: re-adopt the local rows into the source identity and clear.
     * A separate `RETIRED` would only let a resume skip one redundant DELETE, which
     * is not worth a row in a recovery table.
     */
    PREPARED,

    /**
     * The destination identity is about to be authenticated or created.
     *
     * The stage that makes the ambiguous case *detectable*: if the process dies here
     * the disk cannot say whether the destination exists, but the restored session
     * can - a session for a different uid means the switch took.
     */
    SWITCH_PENDING,

    /**
     * The destination is authenticated and the local identity has been committed to
     * it. Adoption of the local reaction state is owed, or was interrupted part-way.
     *
     * Adoption is idempotent, so "interrupted part-way" and "not started" need no
     * distinction and get no stage of their own.
     */
    SWITCHED,
}

/**
 * The handoff in flight.
 *
 * @property to the destination uid, known only from [HandoffStage.SWITCHED] onwards.
 */
data class HandoffRecord(
    val stage: HandoffStage,
    val from: String,
    val to: String?,
)
