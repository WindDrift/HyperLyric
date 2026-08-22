package com.lidesheng.hyperlyric.plugin.core

import com.lidesheng.hyperlyric.plugin.api.PluginCacheEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCacheOperationTest {
    @Test
    fun replayedRequestUsesCompletedResponseWithoutAnotherExecution() {
        val tracker = PluginCacheOperationReplayTracker()
        val completed = PluginCacheOperationResponse(
            requestId = "request-12345678",
            success = true
        )

        tracker.markCompleted(completed)

        assertEquals(completed, tracker.completedResponse("request-12345678"))
    }

    @Test
    fun responseEntriesAreSanitizedBeforeEncoding() {
        val sanitized = PluginCacheOperationCodec.sanitizeEntries(
            List(PluginCacheOperationCodec.MAX_ENTRY_COUNT + 20) { index ->
                PluginCacheEntry(
                    id = "entry-$index",
                    title = "t".repeat(PluginCacheOperationCodec.MAX_TITLE_LENGTH + 20),
                    summary = "s".repeat(PluginCacheOperationCodec.MAX_SUMMARY_LENGTH + 20)
                )
            }
        )

        assertTrue(
            sanitized.size == PluginCacheOperationCodec.MAX_ENTRY_COUNT
        )
        assertTrue(
            sanitized.all {
                it.title.length <= PluginCacheOperationCodec.MAX_TITLE_LENGTH &&
                    (it.summary?.length ?: 0) <= PluginCacheOperationCodec.MAX_SUMMARY_LENGTH
            }
        )
    }

    @Test
    fun clearEntryRequestsRequireAnOpaqueEntryId() {
        val request = PluginCacheOperationRequest(
            requestId = "request-12345678",
            responseToken = "response-12345678",
            pluginId = "plugin.example",
            scopeId = "translation",
            type = PluginCacheOperationType.CLEAR_ENTRY
        )

        assertFalse(runCatching { PluginCacheOperationCodec.encodeRequest(request) }.isSuccess)
    }
}
