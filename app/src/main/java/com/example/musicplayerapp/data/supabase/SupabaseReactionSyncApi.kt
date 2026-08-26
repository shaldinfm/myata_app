package com.example.musicplayerapp.data.supabase

import android.content.Context
import com.example.musicplayerapp.data.ReactionOutboxEntry
import com.example.musicplayerapp.data.TrackReaction
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
