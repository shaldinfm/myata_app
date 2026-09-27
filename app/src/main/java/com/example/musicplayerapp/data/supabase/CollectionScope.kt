package com.example.musicplayerapp.data.supabase

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.example.musicplayerapp.data.AppDatabase
import com.example.musicplayerapp.data.ReactionWriteGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * One registered account = one Collection, and the local database only ever
 * *shows* one of them.
 *
 * ## The model
 *
 * The active tables (`track_reaction`, `reaction_outbox`) hold exactly one [Scope]:
 * the listener's own [Scope.Device] Collection, or one [Scope.Account]. Every other
 * scope's rows and pending acts are **parked** - kept, stamped with their owner, and
 * invisible to the Collection, the PLAYER, the drain and the pull. See
 * `CollectionScopeTables.kt` for the storage.
 *
 * ```
 * active            entering account U
 * ----------------  ---------------------------------------------------------------
 * U                 resume - nothing moves
 * account X         park X (rows and pending acts, intact); bring U's back if parked
 * device            park it; bring U's back; and ADOPT the device rows into U only
 *                   when the install was never an account before this
 *                   authentication (a guest, or the anonymous handoff). A track U
 *                   already holds keeps U's row; the device's row for it stays
 *                   parked under `device`. Nothing is overwritten, nothing deleted
 *
 * active            the account leaves (sign-out, or its stored session is gone)
 * ----------------  ---------------------------------------------------------------
 * account U         park U intact; bring the parked device Collection back, or none
 * ```
 *
 * So an account switch is never destructive: logging into Z never shows, uploads or
 * deletes Y's rows, and logging back into Y brings them back with every unsynced act
 * still bound to Y. Signing out hides Y at once, and whatever a guest does next is the
 * device's, kept apart from Y unless a never-account install later adopts it. Nothing
 * waits on the network, so all of it works offline.
 *
 * ## Visibility
 *
 * The screens read the active tables, so they are only allowed to while the scope is
 * one the current identity may see: the device Collection, or an account the install
 * is registered as ([isVisible]). [visibility] is that rule as a flow; `FavoritesViewModel`
 * and the PLAYER's reaction combine with it, and a local write first asks
 * [prepareLocalWrite], so a guest can neither see nor change an account's rows in the
 * moments before [ensureScope] has parked them.
 *
 * ## Legacy data whose owner cannot be proven
 *
 * A database written by a pre-v5 build carries no owner, and the migration marks it
 * `migrated` rather than guessing. It is settled once, before anything syncs:
 *
 * ```
 * evidence                                            the pre-v5 rows become
 * --------------------------------------------------  --------------------------------
 * a fresh install (never upgraded in place): the      LEGACY - parked, unclaimed
 *   database can only have come from a backup or a
 *   device transfer, and its identity did not
 * upgraded in place, handoff pending / no account     the device Collection
 * upgraded in place, registered or signed out as U,   U's
 *   and U is the only account ever read here
 * upgraded in place, but another account was read     LEGACY
 *   here too
 * ```
 *
 * [Scope.Legacy] is never active and never uploaded, and no account adopts it
 * automatically. It is kept so that an explicit, confirmed import can offer it later -
 * a product decision, not something this class may take on the listener's behalf.
 *
 * ## Locks
 *
 * Every read-and-decide runs under [ReactionWriteGate] and inside one database
 * transaction, so a tap lands wholly before or after a transition and a scope is never
 * half-moved. The `HoldingLease` variants expect the caller to hold [SyncLease] (the
 * drain, the pull, direct authentication and the handoff all do, and the mutex is not
 * reentrant) - moving rows under a drain that is mid-delivery would let its settlement
 * write one scope's server revision into another scope's row. The others take it.
 */
object CollectionScope {

    private const val TAG = "CollectionScope"

    /** Whose rows. See `CollectionScopeTables.kt` for the stored keys. */
    sealed interface Scope {
        val key: String

        /** This install's own guest / anonymous Collection. */
        data object Device : Scope {
            override val key = "device"
        }

        /** Exactly one account's Collection and unsynced acts. */
        data class Account(val uid: String) : Scope {
            override val key: String get() = "$ACCOUNT_PREFIX$uid"
        }

        /** Pre-v5 rows whose owner cannot be proven. Parked only, never active. */
        data object Legacy : Scope {
            override val key = "legacy"
        }

        companion object {
            private const val ACCOUNT_PREFIX = "account:"

            fun parse(key: String): Scope = when {
                key == Device.key -> Device
                key.startsWith(ACCOUNT_PREFIX) -> Account(key.removePrefix(ACCOUNT_PREFIX))
                else -> Legacy
            }
        }
    }

    /**
     * The baseline an adopted device row carries: "the server had no row for this
     * track". Server revisions start at 1, so 0 names no real revision - the causal
     * guard (migration 0005) therefore applies an adopted act only if the account
     * still has no row, and the local-only upload inserts adopted state only where it
     * is absent. Either way the account's existing reaction wins over pre-sign-in
     * guest state. Distinct from NULL ("no baseline yet"), which the initial restore
     * rebases - that is reserved for acts made after signing in.
     */
    const val ADOPTED_BASELINE = 0L

    /** Written by `MIGRATION_4_5`: pre-v5 rows are present and have no owner yet. */
    const val MIGRATED = "migrated"

    /** Device rows held back from an adoption because the account already had the track. */
    private const val DEVICE_HELD = "device-held"

    /** How this install came to hold a pre-v5 database. */
    enum class Provenance {
        /** The app was updated in place: the database was written here. */
        IN_PLACE_UPGRADE,

        /** Installed at a v5 build and never updated: a pre-v5 database was restored or transferred. */
        FRESH_INSTALL,
    }

    /** How a database without a settled scope is settled. */
    data class Settlement(val active: Scope, val parkAsLegacy: Boolean)

    /** What [enterAccount] did. */
    sealed interface Entry {
        data object Resumed : Entry

        /**
         * Switched from [from]. [restored] rows came back from this account's parking;
         * [adopted] device rows became the account's.
         */
        data class Entered(val from: Scope, val restored: Int, val adopted: Int) : Entry
    }

    /** Who the push drain may deliver as. */
    sealed interface Delivery {
        data object AnyIdentity : Delivery
        data class Only(val uid: String) : Delivery
        data class Blocked(val reason: String) : Delivery

        /**
         * Account [uid]'s rows, but not yet: this install has never completed a pull of
         * that account, so a pending act has no known server baseline and the causal
         * guard would answer CONFLICT and replace the listener's fresh tap with older
         * server state. The acts wait for the initial restore, which rebases them onto
         * the revisions it learns (see `ReactionPullEngine`), and then go out.
         */
        data class AwaitingRestore(val uid: String) : Delivery
    }

    /**
     * Where the install came from. Replaceable by tests, which need both answers;
     * nothing in `src/main` sets it.
     */
    @Volatile
    internal var provenance: (Context) -> Provenance = ::installProvenance

    /**
     * The settlement rule as a total function of the evidence.
     *
     * @param migrated the database carries the `MIGRATION_4_5` marker, i.e. pre-v5 rows.
     * @param hasRows the active tables hold anything at all.
     * @param accountsReadHere every uid this install has completed a pull for.
     */
    fun settle(
        migrated: Boolean,
        provenance: Provenance,
        hasRows: Boolean,
        state: IdentityState,
        handoffPending: Boolean,
        accountsReadHere: Set<String>,
    ): Settlement {
        val identity: Scope = when {
            handoffPending -> Scope.Device
            state is IdentityState.Registered -> Scope.Account(state.uid)
            state is IdentityState.SignedOut -> Scope.Account(state.lastUid)
            else -> Scope.Device
        }

        // A database this build created, or nothing to attribute: there is no old
        // row whose owner could be wrong.
        if (!migrated || !hasRows) return Settlement(identity, parkAsLegacy = false)

        // Pre-v5 rows on an install that never ran a pre-v5 build: restored or
        // transferred from elsewhere, and their identity was not. Unprovable.
        if (provenance == Provenance.FRESH_INSTALL) return Settlement(identity, parkAsLegacy = true)

        return when (identity) {
            is Scope.Account ->
                Settlement(identity, parkAsLegacy = (accountsReadHere - identity.uid).isNotEmpty())
            else -> Settlement(Scope.Device, parkAsLegacy = false)
        }
    }

    /** The active scope, settling an unsettled database on the way. */
    suspend fun activeScope(context: Context): Scope {
        val database = AppDatabase.getDatabase(context)
        return ReactionWriteGate.withOwnershipCutover {
            database.withTransaction { resolve(context.applicationContext, database) }
        }
    }

    /** [enterAccountHoldingLease] for a caller that does not hold [SyncLease]. */
    suspend fun enterAccount(context: Context, uid: String, adoptDevice: Boolean): Entry =
        SyncLease.withExclusive { enterAccountHoldingLease(context, uid, adoptDevice) }

    /**
     * Makes account [uid]'s Collection the active one. Idempotent.
     *
     * Call it at the moment the install becomes [uid] - after the server has
     * authenticated it and before anything can push or pull as it.
     *
     * @param adoptDevice whether an active device Collection becomes [uid]'s. True
     *   only for the genuine device -> account transition: an install that was never
     *   an account before this authentication (identity `None`), or the anonymous
     *   handoff. False when signing back in after a sign-out or a lost session - the
     *   device rows then belong to whoever used the app as a guest in between, and
     *   they stay parked under `device`.
     */
    suspend fun enterAccountHoldingLease(context: Context, uid: String, adoptDevice: Boolean): Entry {
        val app = context.applicationContext
        val database = AppDatabase.getDatabase(app)
        val target = Scope.Account(uid)

        val entry = ReactionWriteGate.withOwnershipCutover {
            database.withTransaction {
                val active = resolve(app, database)
                if (active == target) return@withTransaction Entry.Resumed

                val scopes = database.collectionScopeDao()
                park(database, active)

                val restored = scopes.parkedReactionCount(target.key)
                unpark(database, target)

                var adopted = 0
                if (active == Scope.Device && adoptDevice) {
                    // The account's own rows win a shared track; the device's row for
                    // it, and any act pending on it, stay parked rather than vanish.
                    scopes.holdReactionConflicts(Scope.Device.key, DEVICE_HELD)
                    scopes.holdOutboxConflicts(Scope.Device.key, DEVICE_HELD)
                    adopted = scopes.parkedReactionCount(Scope.Device.key)
                    scopes.unparkOutbox(Scope.Device.key)
                    scopes.unparkReactionsAsAdopted(Scope.Device.key)
                    scopes.deleteParkedOutbox(Scope.Device.key)
                    scopes.deleteParkedReactions(Scope.Device.key)
                    scopes.renameParkedReactions(DEVICE_HELD, Scope.Device.key)
                    scopes.renameParkedOutbox(DEVICE_HELD, Scope.Device.key)
                }

                scopes.setScope(target.key)
                Entry.Entered(active, restored, adopted)
            }
        }

        if (entry is Entry.Entered) {
            Log.d(
                TAG,
                "entered an account from ${entry.from::class.simpleName}: " +
                    "${entry.restored} row(s) restored from parking, ${entry.adopted} device row(s) adopted",
            )
        }
        return entry
    }

    /**
     * Who the drain may deliver as, right now. Caller holds [SyncLease].
     *
     * A device Collection with a registered identity on disk is **not** delivered and
     * **not** adopted here: that pairing means the account's stored session was found
     * missing and its rows parked, and the device rows are a guest's. Sending them as
     * the account would be an adoption nobody asked for. [ensureScope] brings the
     * account back when its session is.
     */
    suspend fun deliveryHoldingLease(context: Context): Delivery {
        val state = IdentityStore.state(context)

        return when (val scope = activeScope(context)) {
            Scope.Device ->
                if (state is IdentityState.Registered) {
                    Delivery.Blocked("a device collection is active while an account is on disk")
                } else {
                    Delivery.AnyIdentity
                }

            is Scope.Account ->
                if (state is IdentityState.Registered && state.uid == scope.uid) {
                    if (LastSyncStore.isInitialRestoreComplete(context, scope.uid)) {
                        Delivery.Only(scope.uid)
                    } else {
                        Delivery.AwaitingRestore(scope.uid)
                    }
                } else {
                    Delivery.Blocked("the collection belongs to an account that is not signed in")
                }

            Scope.Legacy -> Delivery.Blocked("the collection's owner is unknown")
        }
    }

    /** [leaveAccountHoldingLease] for a caller that does not hold [SyncLease]. */
    suspend fun leaveAccount(context: Context): Boolean =
        SyncLease.withExclusive { leaveAccountHoldingLease(context) }

    /**
     * Parks the active account's rows and pending acts under that account and makes
     * the device Collection active again - the parked one if there is one, otherwise an
     * empty one. A no-op when the device Collection is already active.
     *
     * @return whether anything moved.
     */
    suspend fun leaveAccountHoldingLease(context: Context): Boolean {
        val app = context.applicationContext
        val database = AppDatabase.getDatabase(app)

        val left = ReactionWriteGate.withOwnershipCutover {
            database.withTransaction {
                val active = resolve(app, database)
                if (active == Scope.Device) return@withTransaction null
                park(database, active)
                unpark(database, Scope.Device)
                database.collectionScopeDao().setScope(Scope.Device.key)
                active
            }
        }

        if (left != null) Log.d(TAG, "left ${left::class.simpleName}: its rows are parked, the device Collection is active")
        return left != null
    }

    /**
     * Makes the active scope agree with who this install provably is. Takes [SyncLease].
     *
     * ```
     * active       identity and stored session              action
     * -----------  ---------------------------------------  -----------------------------
     * account U    registered as U, a session for U stored  none
     * account U    anything else (signed out, none, another leave: park U, device active
     *              account, U's stored session gone)
     * device       registered as U, a session for U stored  enter U, without adopting
     * ```
     *
     * "Stored", not "live": an offline listener whose token cannot be refreshed yet
     * keeps their session in storage and keeps their Collection. It goes only when the
     * session does - signed out, or refused by the server.
     *
     * Runs at every start once the identity has been reconciled, after a sign-out, and
     * before a local write that finds the active scope invisible. Left alone while an
     * account deletion or an identity handoff is unresolved: those own the tables until
     * they finish.
     */
    suspend fun ensureScope(context: Context) {
        val app = context.applicationContext
        if (IdentityStore.deletionInFlight(app) || IdentityStore.handoffInProgress(app)) return

        val stored = runCatching { EmailAuthBackend.api(app).storedSessionUid() }.getOrNull()

        SyncLease.withExclusive {
            val state = IdentityStore.state(app)
            val provenAccount = (state as? IdentityState.Registered)?.uid?.takeIf { it == stored }

            when (val active = activeScope(app)) {
                is Scope.Account ->
                    if (active.uid != provenAccount) leaveAccountHoldingLease(app)

                Scope.Device ->
                    if (provenAccount != null) enterAccountHoldingLease(app, provenAccount, adoptDevice = false)

                Scope.Legacy -> leaveAccountHoldingLease(app)
            }
        }
    }

    /** [ensureScope] in the background, for callers that must not wait. */
    fun ensureScopeInBackground(context: Context): Job {
        val app = context.applicationContext
        return CoroutineScope(Dispatchers.IO).launch {
            runCatching { ensureScope(app) }
                .onFailure { Log.w(TAG, "scope check failed: ${it.message}") }
        }
    }

    /**
     * Whether the screens may show the active tables to [state].
     *
     * An unsettled database (no scope row, created by this build) is read as the
     * scope it will settle to. A `migrated` one is hidden until it has settled.
     */
    fun isVisible(rawScope: String?, state: IdentityState): Boolean {
        val scope = when (rawScope) {
            MIGRATED -> return false
            null -> when (state) {
                is IdentityState.Registered -> Scope.Account(state.uid)
                is IdentityState.SignedOut -> Scope.Account(state.lastUid)
                else -> Scope.Device
            }
            else -> Scope.parse(rawScope)
        }
        return when (scope) {
            Scope.Device -> true
            is Scope.Account -> state is IdentityState.Registered && state.uid == scope.uid
            Scope.Legacy -> false
        }
    }

    /** [isVisible], as the scope and the identity change. */
    fun visibility(context: Context): Flow<Boolean> {
        val app = context.applicationContext
        return combine(
            AppDatabase.getDatabase(app).collectionScopeDao().observeScope(),
            IdentityStore.stateFlow(app),
        ) { raw, state -> isVisible(raw, state) }.distinctUntilChanged()
    }

    /**
     * Call before a local reaction write. Free when the active scope is visible; when
     * it is not, settles it first, so the write lands in the device Collection rather
     * than in rows belonging to an account that is not here.
     */
    suspend fun prepareLocalWrite(context: Context) {
        val app = context.applicationContext
        val raw = AppDatabase.getDatabase(app).collectionScopeDao().scope()
        if (!isVisible(raw, IdentityStore.state(app))) ensureScope(app)
    }

    /**
     * The active scope, from inside a caller's own gate and transaction.
     * `AccountDeletionCleanup` only, which already holds both.
     */
    internal suspend fun activeScopeWithinTransaction(context: Context, database: AppDatabase): Scope =
        resolve(context.applicationContext, database)

    /** Moves the active tables, whole, under [scope]. */
    private suspend fun park(database: AppDatabase, scope: Scope) {
        val scopes = database.collectionScopeDao()
        scopes.parkReactions(scope.key)
        scopes.parkOutbox(scope.key)
        database.reactionOutboxDao().clearAll()
        database.reactionDao().clearAll()
    }

    /** Brings [scope]'s parked rows into the (empty) active tables, in their original order. */
    private suspend fun unpark(database: AppDatabase, scope: Scope) {
        val scopes = database.collectionScopeDao()
        scopes.unparkReactions(scope.key)
        scopes.unparkOutbox(scope.key)
        scopes.deleteParkedOutbox(scope.key)
        scopes.deleteParkedReactions(scope.key)
    }

    /** The active scope, settling it first if unsettled. Inside the gate and a transaction. */
    private suspend fun resolve(context: Context, database: AppDatabase): Scope {
        val scopes = database.collectionScopeDao()
        val raw = scopes.scope()
        if (raw != null && raw != MIGRATED) return Scope.parse(raw)

        val migrated = raw == MIGRATED
        val hasRows = database.collectionScopeDao().hasActiveRows()

        val settlement = settle(
            migrated = migrated,
            provenance = if (migrated && hasRows) provenance(context) else Provenance.IN_PLACE_UPGRADE,
            hasRows = hasRows,
            state = IdentityStore.state(context),
            handoffPending = IdentityStore.handoffInProgress(context),
            accountsReadHere = LastSyncStore.accountsReadHere(context),
        )

        if (settlement.parkAsLegacy) {
            park(database, Scope.Legacy)
            Log.w(
                TAG,
                "pre-v5 rows whose owner cannot be proven were set aside as unclaimed: " +
                    "${scopes.parkedReactionCount(Scope.Legacy.key)} reaction(s), " +
                    "${scopes.parkedOutboxCount(Scope.Legacy.key)} pending event(s)",
            )
        }

        scopes.setScope(settlement.active.key)
        Log.d(TAG, "collection scope settled: ${settlement.active::class.simpleName}")
        return settlement.active
    }

    /**
     * An update keeps `firstInstallTime` and moves `lastUpdateTime`; a fresh install -
     * the only way a backup restore or a device transfer arrives - has them equal.
     * Unreadable is treated as fresh: the fail-closed answer parks, it never adopts.
     */
    @Suppress("DEPRECATION")
    private fun installProvenance(context: Context): Provenance = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (info.lastUpdateTime > info.firstInstallTime) Provenance.IN_PLACE_UPGRADE else Provenance.FRESH_INSTALL
    }.getOrDefault(Provenance.FRESH_INSTALL)

    /** Test-only: back to the real provenance. */
    internal fun resetForTest() {
        provenance = ::installProvenance
    }
}
