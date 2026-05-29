package com.streamvault.data.remote.stalker

import java.util.concurrent.ConcurrentHashMap

internal object StalkerTrafficCoordinator {
    private const val ACTIVE_PLAYBACK_RECHECK_MILLIS = 3_000L

    // Safety backstop: if a "playback started" is never balanced by a "stopped" (e.g. a missed
    // teardown callback in multi-view), the counter would otherwise stay positive for the whole
    // process lifetime and starve catalog syncing. After this long with no start/stop change we
    // treat the marker as stale and stop deferring. Set well beyond any realistic single playback
    // session so it never trips during a legitimate long watch.
    private const val MAX_DEFERRAL_AGE_MILLIS = 6L * 60 * 60 * 1000L

    private val activePlaybackCountsByProvider = ConcurrentHashMap<Long, Int>()
    private val lastChangeAtByProvider = ConcurrentHashMap<Long, Long>()

    fun notePlaybackStarted(providerId: Long, now: Long = System.currentTimeMillis()) {
        if (providerId <= 0L) return
        activePlaybackCountsByProvider.compute(providerId) { _, current ->
            (current ?: 0) + 1
        }
        lastChangeAtByProvider[providerId] = now
    }

    fun notePlaybackStopped(providerId: Long, now: Long = System.currentTimeMillis()) {
        if (providerId <= 0L) return
        val remaining = activePlaybackCountsByProvider.compute(providerId) { _, current ->
            when {
                current == null || current <= 1 -> null
                else -> current - 1
            }
        }
        if (remaining == null) {
            // Last stream for this provider stopped — drop the timestamp too so the map doesn't
            // retain a stale entry indefinitely (the 6h backstop never runs once the count is 0).
            lastChangeAtByProvider.remove(providerId)
        } else {
            lastChangeAtByProvider[providerId] = now
        }
    }

    fun shouldDeferCatalogFetch(providerId: Long, now: Long = System.currentTimeMillis()): Boolean =
        deferCatalogFetchMillis(providerId, now) > 0L

    fun deferCatalogFetchMillis(providerId: Long, now: Long = System.currentTimeMillis()): Long {
        if (providerId <= 0L) return 0L
        if ((activePlaybackCountsByProvider[providerId] ?: 0) <= 0) return 0L
        val lastChangeAt = lastChangeAtByProvider[providerId] ?: now
        if (now - lastChangeAt >= MAX_DEFERRAL_AGE_MILLIS) {
            // Stuck marker — clear it so the catalog is never starved indefinitely.
            activePlaybackCountsByProvider.remove(providerId)
            lastChangeAtByProvider.remove(providerId)
            return 0L
        }
        return ACTIVE_PLAYBACK_RECHECK_MILLIS
    }

    internal fun resetForTests() {
        activePlaybackCountsByProvider.clear()
        lastChangeAtByProvider.clear()
    }
}