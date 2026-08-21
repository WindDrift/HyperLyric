package com.lidesheng.hyperlyric.root.plugin

import com.lidesheng.hyperlyric.common.media.MediaIdentity
import com.lidesheng.hyperlyric.plugin.api.PluginMediaInfo
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginProcessingRequestTest {
    @Test
    fun sameSongAndMediaSnapshotIsDeduplicated() {
        val tracker = PluginProcessingRequestTracker()
        val key = request(name = "same", duration = 180_000L)

        assertFalse(tracker.isDuplicate(key))
        tracker.markStarted(key)

        assertTrue(tracker.isDuplicate(key))
    }

    @Test
    fun changedSongOrMediaInputStartsAnotherRequest() {
        val tracker = PluginProcessingRequestTracker()
        val first = request(name = "first", duration = 180_000L)
        tracker.markStarted(first)

        assertFalse(tracker.isDuplicate(request(name = "second", duration = 180_000L)))
        assertFalse(tracker.isDuplicate(request(name = "first", duration = 181_000L)))
        assertFalse(
            tracker.isDuplicate(
                first.copy(mediaIdentity = MediaIdentity(packageName = "different.player"))
            )
        )
    }

    private fun request(name: String, duration: Long): PluginProcessingRequestKey =
        PluginProcessingRequestKey(
            sourceSong = PluginSong(name = name, duration = duration),
            mediaIdentity = null,
            mediaInfo = PluginMediaInfo(title = name, duration = duration)
        )
}
