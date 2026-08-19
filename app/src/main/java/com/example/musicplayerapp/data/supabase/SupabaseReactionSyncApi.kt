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

        val remote = current?.let { ReactionSyncWire.remoteReaction(it.reaction) }
            ?: return deleteCurrentState(trackKey, listenerId)

        val row = requireNotNull(current)
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
     * NEUTRAL is absence, so withdrawing an opinion deletes the row.
     *
     * A delete that matches nothing is success, not an error - the desired state is
     * "no row", and there being no row already is that state. PostgREST answers 200
     * with an empty representation either way, so this needs no special case; it is
     * written down because it is the kind of thing a future reader would otherwise
     * add a guard for.
     *
     * No `updated_at` guard here. A delete carries no state to be stale *with*, and
     * the drain only ever asks for one when the current local row says NEUTRAL,
     * which it read a moment ago.
     */
    private suspend fun deleteCurrentState(trackKey: String, listenerId: String): SyncOutcome {
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
