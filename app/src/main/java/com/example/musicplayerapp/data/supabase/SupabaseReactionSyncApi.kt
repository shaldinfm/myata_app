package com.example.musicplayerapp.data.supabase

import android.content.Context
import com.example.musicplayerapp.data.Reaction
import com.example.musicplayerapp.data.ReactionOutboxEntry
import com.example.musicplayerapp.data.TrackReaction
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The real thing: PostgREST calls against the configured project.
 *
 * Every payload is built as a [JsonObject] by hand rather than serialised from a
 * data class. That is deliberate and it is the smaller of two designs: `@Serializable`
 * would need the kotlinx-serialization Gradle plugin, which this project does not
 * apply, and would add generated `$$serializer` classes - the classic thing R8
 * strips silently, producing a release-only failure. Eight flat columns do not need
 * a compiler plugin, and building them here puts the wire format next to the schema
 * it has to satisfy, where a reviewer can compare the two.
 *
 * The access token is not passed anywhere: the Auth plugin attaches the current
 * session's JWT to every PostgREST request, and RLS reads `auth.uid()` from it.
 * [listenerId] still travels in the body because both tables store it as a column,
 * and every policy's `with check` compares the two - so a mismatch is refused by
 * the database rather than trusted from the client.
 */
class SupabaseReactionSyncApi(private val context: Context) : ReactionSyncApi {

    private val postgrest
        get() = SupabaseModule.client(context)?.postgrest

    override suspend fun deliverEvent(
        entry: ReactionOutboxEntry,
        listenerId: String,
    ): SyncOutcome {
        val db = postgrest ?: return SyncOutcome.AuthUnavailable("no supabase client")

        val payload = buildJsonObject {
            put("event_id", entry.eventId)
            put("listener_id", listenerId)
            put("track_key", entry.trackKey)
            put("artist", entry.artist)
            put("title", entry.title)
            put("event_type", entry.eventType.wire)
            put("stream", entry.stream)
            put("occurred_at", ReactionSyncWire.timestamp(entry.occurredAt))
        }

        return runCatching {
            // ignore-duplicates, not merge-duplicates. `reaction_events` has an
            // INSERT policy and deliberately no UPDATE policy, so a merge would be
            // refused by RLS on exactly the retry path it exists to serve. Ignoring
            // duplicates is ON CONFLICT DO NOTHING: a redelivered event returns 201
            // with an empty representation, which is success.
            db.from(ReactionSyncWire.TABLE_EVENTS).upsert(payload) {
                onConflict = "event_id"
                ignoreDuplicates = true
                select()
            }
            SyncOutcome.Success
        }.getOrElse { classifyFailure(it) }
    }

    override suspend fun reconcileCurrentState(
        trackKey: String,
        current: TrackReaction?,
        listenerId: String,
    ): SyncOutcome {
        val db = postgrest ?: return SyncOutcome.AuthUnavailable("no supabase client")

        // No local row at all is not NEUTRAL - it is the row having been removed,
        // and there are no words left to write one with. That is the only delete
        // left on this path; see [dropCurrentState].
        val row = current ?: return dropCurrentState(trackKey, listenerId)

        val remote = ReactionSyncWire.remoteReaction(row.reaction)
        val updatedAt = ReactionSyncWire.timestamp(row.updatedAt)

        return runCatching {
            // Last-writer-wins in two steps, because PostgREST cannot express a
            // conditional merge in one.
            //
            //  1. UPDATE guarded by `updated_at <= ours`. If a newer row is there,
            //     this matches nothing and we have correctly declined to go
            //     backwards.
            //  2. If nothing matched, either there is no row yet or the existing one
            //     is newer. An ignore-duplicates INSERT settles it: it creates the
            //     row if it is missing, and does nothing if the newer one exists.
            //
            // Both halves were verified against the live project before this was
            // written; see docs/SUPABASE-SYNC.md.
            val updated = db.from(ReactionSyncWire.TABLE_REACTIONS).update(
                buildJsonObject {
                    put("artist", row.artist)
                    put("title", row.title)
                    put("reaction", remote)
                    put("stream", row.stream)
                    put("updated_at", updatedAt)
                }
            ) {
                select()
                filter {
                    eq("listener_id", listenerId)
                    eq("track_key", trackKey)
                    lte("updated_at", updatedAt)
                }
            }.decodeList<JsonObject>()

            if (updated.isEmpty()) {
                db.from(ReactionSyncWire.TABLE_REACTIONS).upsert(
                    buildJsonObject {
                        put("listener_id", listenerId)
                        put("track_key", trackKey)
                        put("artist", row.artist)
                        put("title", row.title)
                        put("reaction", remote)
                        put("stream", row.stream)
                        put("updated_at", updatedAt)
                    }
                ) {
                    onConflict = "listener_id,track_key"
                    ignoreDuplicates = true
                    select()
                }
            }
            SyncOutcome.Success
        }.getOrElse { classifyFailure(it) }
    }

    /**
     * One call, one server transaction: the whole of a track's pending batch.
     *
     * The payload is hand-built like every other one here, for the reason in the
     * class header. Two shapes matter and both are the schema rather than a
     * convenience:
     *
     *  - **each event carries no `track_key` and no `listener_id`.** One
     *    `p_track_key` covers the batch, so events and state cannot describe
     *    different tracks - the disagreement is unrepresentable rather than checked.
     *    Identity comes from `auth.uid()` inside the function;
     *  - **the current-state parameters come from [current]**, read from Room at
     *    snapshot time, never derived from the events. A batch that waited a week in
     *    somebody's pocket still delivers its week-old history, and still publishes
     *    what the listener thinks now.
     *
     * `updated_at` is the device's own clock, unchanged and deliberately so: 0003
     * left that column's meaning alone because pre-cutover clients still guard their
     * pushes with it, and this function keeps writing it the same way for as long as
     * they exist.
     */
    override suspend fun applyBatch(
        trackKey: String,
        events: List<ReactionOutboxEntry>,
        current: TrackReaction,
        listenerId: String,
    ): BatchOutcome {
        val db = postgrest ?: return BatchOutcome.Failed(
            SyncOutcome.AuthUnavailable("no supabase client")
        )

        // Ownership, checked here because the RPC has no column to check it with.
        //
        // The direct writes sent `listener_id` and every policy compared it against
        // auth.uid(), so a batch built as X and sent on a session that had become Y
        // was refused by the database. This function is auth.uid()-only by design,
        // which means the same batch would be stored under Y - correctly, as far as
        // the server can tell, because Y is who asked. Restoring the comparison is
        // the client's job now.
        //
        // Local: currentUserOrNull() reads the session the Auth plugin already holds
        // and makes no request. A mismatch or an absent session leaves the rows
        // exactly as they are - nothing sent, nothing settled, no revision recorded,
        // no identity created or repaired from here. Reconciliation belongs to
        // IdentityReconciler, and the sync backend must never start one.
        val session = runCatching {
            SupabaseModule.client(context)?.auth?.currentUserOrNull()?.id
        }.getOrNull()

        // Deliberately never Permanent: the batch did nothing wrong, this device is
        // simply not who it was when the batch was built, and a later corrected
        // session delivers it unchanged.
        ownershipVerdict(session, listenerId)?.let { return BatchOutcome.Failed(it) }

        val payload = buildJsonObject {
            put("p_track_key", trackKey)
            put("p_events", buildJsonArray {
                for (event in events) {
                    add(buildJsonObject {
                        put("event_id", event.eventId)
                        put("event_type", event.eventType.wire)
                        put("artist", event.artist)
                        put("title", event.title)
                        put("stream", event.stream)
                        put("occurred_at", ReactionSyncWire.timestamp(event.occurredAt))
                    })
                }
            })
            put("p_reaction", ReactionSyncWire.remoteReaction(current.reaction))
            // The server enforces liked_at present iff LIKED, and normalises it in
            // the trigger; sending the local value for any other state would be
            // asserting something the schema does not admit.
            if (current.reaction == Reaction.LIKED) {
                put("p_liked_at", ReactionSyncWire.timestamp(current.likedAt ?: current.updatedAt))
            } else {
                put("p_liked_at", JsonNull)
            }
            put("p_artist", current.artist)
            put("p_title", current.title)
            put("p_stream", current.stream)
            put("p_updated_at", ReactionSyncWire.timestamp(current.updatedAt))
        }

        return runCatching {
            val answer = db.rpc(ReactionSyncWire.RPC_APPLY_BATCH, payload)
                .decodeAs<JsonObject>()
            readOutcome(answer)
        }.getOrElse { BatchOutcome.Failed(classifyFailure(it)) }
    }

    /**
     * The function's answer, or a permanent failure if it is not one we understand.
     *
     * An unrecognised outcome is treated as permanent rather than retried: the call
     * reached the server and the server replied, so repeating it will produce the
     * same reply, and guessing at the shape is how a settled batch gets replayed.
     */
    private fun readOutcome(answer: JsonObject): BatchOutcome {
        val row = (answer["row"] as? JsonObject)?.let { readRow(it) }
        return when (answer["outcome"]?.jsonPrimitive?.contentOrNull) {
            "APPLIED" -> row?.let { BatchOutcome.Applied(it) }
                ?: BatchOutcome.Failed(SyncOutcome.Permanent(200, "APPLIED without a row"))
            "ALREADY_APPLIED" -> BatchOutcome.AlreadyApplied(row)
            else -> BatchOutcome.Failed(SyncOutcome.Permanent(200, "unknown outcome"))
        }
    }

    /** One `reactions` row out of the function's answer, or null if it is malformed. */
    private fun readRow(row: JsonObject): RemoteReaction? {
        fun text(name: String): String? = row[name]?.jsonPrimitive?.contentOrNull
        val reaction = ReactionSyncWire.localReaction(text("reaction")) ?: return null
        val rev = row["rev"]?.jsonPrimitive?.longOrNull ?: return null
        val updatedAt = ReactionSyncWire.epochMillis(text("updated_at")) ?: return null
        return RemoteReaction(
            trackKey = text("track_key") ?: return null,
            reaction = reaction,
            likedAt = ReactionSyncWire.epochMillis(text("liked_at")),
            artist = text("artist") ?: return null,
            title = text("title") ?: return null,
            // Nullable in the schema; the local column is not, and "" is what the
            // rest of the app already means by "no stream recorded".
            stream = text("stream").orEmpty(),
            updatedAt = updatedAt,
            rev = rev,
        )
    }

    override suspend fun retireAllCurrentState(listenerId: String): SyncOutcome {
        val db = postgrest ?: return SyncOutcome.AuthUnavailable("no supabase client")

        return runCatching {
            // One DELETE for the whole identity. RLS would scope this to the caller
            // anyway; the explicit filter is here so the statement says what it does
            // rather than relying on a policy to make it safe.
            db.from(ReactionSyncWire.TABLE_REACTIONS).delete {
                select()
                filter { eq("listener_id", listenerId) }
            }
            SyncOutcome.Success
        }.getOrElse { classifyFailure(it) }
    }

    /**
     * Removes the remote row, for the one case that still means it: **no local row
     * exists**.
     *
     * Withdrawing a reaction does not come here any more. Since migration 0002,
     * NEUTRAL is a value with an `updated_at`, so an UNLIKE or an UNDISLIKE goes
     * through the same guarded upsert as everything else, and the remote row stays
     * put as a tombstone. What reaches this function is a track whose Room row is
     * gone - clearing the collection, a data-removal request, the retirement half of
     * a future identity handoff - and for those, absence really is the desired state.
     * The DELETE policy exists for exactly this and is deliberately kept.
     *
     * A delete that matches nothing is success, not an error - the desired state is
     * "no row", and there being no row already is that state. PostgREST answers 200
     * with an empty representation either way, so this needs no special case; it is
     * written down because it is the kind of thing a future reader would otherwise
     * add a guard for.
     *
     * No `updated_at` guard here, because there is no local state to guard *with*.
     * That asymmetry is the price of deleting at all, and it is why nothing on the
     * ordinary reaction path does.
     */
    private suspend fun dropCurrentState(trackKey: String, listenerId: String): SyncOutcome {
        val db = postgrest ?: return SyncOutcome.AuthUnavailable("no supabase client")

        return runCatching {
            db.from(ReactionSyncWire.TABLE_REACTIONS).delete {
                select()
                filter {
                    eq("listener_id", listenerId)
                    eq("track_key", trackKey)
                }
            }
            SyncOutcome.Success
        }.getOrElse { classifyFailure(it) }
    }
}
