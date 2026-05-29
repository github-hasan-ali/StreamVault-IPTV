package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

class StalkerTrafficCoordinatorTest {
    @Before
    fun setUp() {
        StalkerTrafficCoordinator.resetForTests()
    }

    @After
    fun tearDown() {
        StalkerTrafficCoordinator.resetForTests()
    }

    @Test
    fun `deferCatalogFetchMillis stays deferred while playback is active`() {
        StalkerTrafficCoordinator.notePlaybackStarted(providerId = 7L)

        val deferred = StalkerTrafficCoordinator.deferCatalogFetchMillis(providerId = 7L)

        assertThat(deferred).isGreaterThan(0L)
    }

    @Test
    fun `deferCatalogFetchMillis clears immediately when playback stops`() {
        StalkerTrafficCoordinator.notePlaybackStarted(providerId = 7L)
        StalkerTrafficCoordinator.notePlaybackStopped(providerId = 7L)

        val deferred = StalkerTrafficCoordinator.deferCatalogFetchMillis(providerId = 7L)

        assertThat(deferred).isEqualTo(0L)
    }

    @Test
    fun `deferCatalogFetchMillis tracks nested playback sessions for same provider`() {
        StalkerTrafficCoordinator.notePlaybackStarted(providerId = 7L)
        StalkerTrafficCoordinator.notePlaybackStarted(providerId = 7L)
        StalkerTrafficCoordinator.notePlaybackStopped(providerId = 7L)

        val deferred = StalkerTrafficCoordinator.deferCatalogFetchMillis(providerId = 7L)

        assertThat(deferred).isGreaterThan(0L)
    }

    @Test
    fun `backstop clears a leaked playback marker after the max deferral age`() {
        // Playback "started" but a "stopped" is never delivered (e.g. a missed multi-view teardown).
        StalkerTrafficCoordinator.notePlaybackStarted(providerId = 7L, now = 0L)

        // Just before the 6h backstop: still deferring.
        assertThat(
            StalkerTrafficCoordinator.deferCatalogFetchMillis(providerId = 7L, now = SIX_HOURS_MS - 1)
        ).isGreaterThan(0L)

        // At the backstop: the stale marker is cleared so the catalog is never starved forever.
        assertThat(
            StalkerTrafficCoordinator.deferCatalogFetchMillis(providerId = 7L, now = SIX_HOURS_MS)
        ).isEqualTo(0L)
    }

    @Test
    fun `a fresh playback start re-defers after the backstop cleared a leaked marker`() {
        StalkerTrafficCoordinator.notePlaybackStarted(providerId = 7L, now = 0L)
        // Backstop clears the leaked marker.
        StalkerTrafficCoordinator.deferCatalogFetchMillis(providerId = 7L, now = SIX_HOURS_MS)

        // A genuine new playback session must defer again.
        StalkerTrafficCoordinator.notePlaybackStarted(providerId = 7L, now = SIX_HOURS_MS + 1)
        assertThat(
            StalkerTrafficCoordinator.deferCatalogFetchMillis(providerId = 7L, now = SIX_HOURS_MS + 2)
        ).isGreaterThan(0L)
    }

    @Test
    fun `clean stop leaves no stale state that could defer later`() {
        StalkerTrafficCoordinator.notePlaybackStarted(providerId = 7L, now = 0L)
        StalkerTrafficCoordinator.notePlaybackStopped(providerId = 7L, now = 1_000L)

        // Even far in the future there is nothing left to defer on (the timestamp entry was removed too).
        assertThat(
            StalkerTrafficCoordinator.deferCatalogFetchMillis(providerId = 7L, now = SIX_HOURS_MS * 10)
        ).isEqualTo(0L)
    }

    @Test
    fun `partial stop refreshes activity timestamp so a long nested watch never trips the backstop early`() {
        StalkerTrafficCoordinator.notePlaybackStarted(providerId = 7L, now = 0L)
        StalkerTrafficCoordinator.notePlaybackStarted(providerId = 7L, now = 0L)
        // One of the two streams stops well into the session, refreshing "last change" to 1_000L.
        StalkerTrafficCoordinator.notePlaybackStopped(providerId = 7L, now = 1_000L)

        // 6h measured from the original start would be stale, but the stop refreshed the timestamp,
        // so the age is measured from 1_000L instead — still actively deferring.
        assertThat(
            StalkerTrafficCoordinator.deferCatalogFetchMillis(providerId = 7L, now = 1_000L + SIX_HOURS_MS - 1)
        ).isGreaterThan(0L)
    }

    private companion object {
        const val SIX_HOURS_MS = 6L * 60 * 60 * 1000L
    }
}
