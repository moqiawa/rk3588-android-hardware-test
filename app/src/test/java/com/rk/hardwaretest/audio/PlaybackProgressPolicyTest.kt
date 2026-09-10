package com.rk.hardwaretest.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressPolicyTest {
    @Test fun refreshes_only_while_the_imported_player_is_playing() {
        assertTrue(shouldRefreshPlaybackProgress(true))
        assertFalse(shouldRefreshPlaybackProgress(false))
    }
}
