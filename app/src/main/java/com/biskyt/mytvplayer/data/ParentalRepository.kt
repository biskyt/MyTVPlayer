package com.biskyt.mytvplayer.data

import androidx.room.withTransaction
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.data.db.ParentalEntity
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Parental controls. Restricted categories (adult ones by default) are left out of the lists and the TV guide while
 * locked, which is whenever a PIN is set and it hasn't been entered since the app was last left ([lock]). Their items are
 * also left out of search when that setting is on, and what is watched in them isn't kept for recently watched or "Carry
 * on watching" when those settings are on.
 *
 * The state is kept in the parental table, which the list queries read: Room then reloads every open list when it
 * changes, so unlocking shows the restricted items at once.
 */
class ParentalRepository(private val db: AppDatabase, private val settings: SettingsRepository, scope: CoroutineScope) {
    private val unlocked = MutableStateFlow(false)

    /**
     * Whether a PIN is set. Opening Parental controls and removing a restriction ask for it each time, even while
     * restricted content is unlocked.
     */
    val hasPin: StateFlow<Boolean> = settings.settings.map { it.pinHash.isNotEmpty() }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Whether restricted content is hidden now: a PIN is set and it hasn't been entered. */
    val locked: StateFlow<Boolean> = combine(hasPin, unlocked) { pin, open -> pin && !open }
        .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, true)

    init {
        scope.launch {
            // From the settings as loaded (not [locked], which starts unlocked-looking before they load).
            combine(settings.settings, unlocked) { s, open ->
                ParentalEntity(
                    locked = s.pinHash.isNotEmpty() && !open,
                    hideRecent = s.hideRestrictedRecent,
                    hideCarryOn = s.hideRestrictedCarryOn,
                    hideSearch = s.hideRestrictedSearch
                )
            }.distinctUntilChanged().collect { state ->
                try {
                    // Only a change: a write reloads every open list (they read this row), e.g. at each start.
                    if (db.parental().get() != state) {
                        // Before a hide setting goes off, with it still on: nothing hidden until now shows after.
                        db.parental().forgetRestricted()
                        db.parental().set(state)
                    }
                    // A hide setting turned on forgets what was already watched in restricted categories. This also
                    // runs at each start, which catches history kept before 1.9.1.
                    db.parental().forgetRestricted()
                } catch (e: IllegalStateException) {
                    // The database closed under an update already cancelled (tests close it as the app ends).
                    if (currentCoroutineContext().isActive) throw e
                }
            }
        }
    }

    /** Whether [pin] is the one set (true when none is set). Entering it unlocks restricted content until [lock]. */
    suspend fun unlock(pin: String): Boolean {
        val hash = settings.current().pinHash
        val ok = hash.isEmpty() || Pin.matches(pin, hash)
        if (ok) unlocked.value = true
        return ok
    }

    /** Restricts category [id], or removes that (callers ask for the PIN first to remove it, when one is set). */
    suspend fun setRestricted(playlistId: Long, type: String, id: String, restricted: Boolean) = db.withTransaction {
        db.categories().setRestricted(playlistId, type, id, restricted)
        // What was watched in it goes, as if it had been restricted then (while the hide settings are on).
        if (restricted) db.parental().forgetRestricted(playlistId)
    }

    /** Emits when restricted content is hidden again after it was shown: screens open on it are closed (see AppRoot). */
    val relocked = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Hides restricted content again (when the app is left). */
    fun lock() {
        if (unlocked.value && hasPin.value) relocked.tryEmit(Unit)
        unlocked.value = false
    }

    /** Sets a new PIN (empty: none). Whoever sets it knows it, so it stays unlocked until the app is left. */
    suspend fun setPin(pin: String) {
        settings.update { it.copy(pinHash = if (pin.isEmpty()) "" else Pin.hash(pin)) }
        unlocked.value = true
    }
}

/** A PIN kept as a salted SHA-256 hash ("salt:hash", in hex), never as itself. */
object Pin {
    const val LENGTH = 4

    fun hash(pin: String, salt: String = newSalt()): String = "$salt:${sha256("$salt:$pin")}"

    fun matches(pin: String, stored: String): Boolean {
        val salt = stored.substringBefore(':', "")
        return salt.isNotEmpty() && MessageDigest.isEqual(hash(pin, salt).toByteArray(), stored.toByteArray())
    }

    private fun newSalt() = ByteArray(16).also(SecureRandom()::nextBytes).toHex()

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
