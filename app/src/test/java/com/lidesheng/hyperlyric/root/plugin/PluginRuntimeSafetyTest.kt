package com.lidesheng.hyperlyric.root.plugin

import com.lidesheng.hyperlyric.plugin.api.LyricProcessorExtension
import com.lidesheng.hyperlyric.plugin.api.PluginProcessingContext
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PluginRuntimeSafetyTest {
    @Test
    fun processorExceptionProducesNoResultAndKeepsOriginalSong() {
        val original = PluginSong(name = "original")
        var failureReported = false
        val processor = object : LyricProcessorExtension {
            override val id: String = "throwing"

            override fun processResult(
                song: PluginSong,
                processingContext: PluginProcessingContext
            ) = error("plugin failure")
        }

        val result = invokePluginProcessorSafely(
            processor = processor,
            song = original,
            processingContext = PluginProcessingContext()
        ) {
            failureReported = true
        }

        assertNull(result)
        assertTrue(failureReported)
        assertEquals("original", original.name)
    }

    @Test
    fun timedOutPluginDoesNotPreventTheNextPluginFromRunning() = runBlocking {
        val executor = Executors.newCachedThreadPool()
        try {
            val blocking = object : LyricProcessorExtension {
                override val id: String = "blocking"

                override fun processResult(song: PluginSong, processingContext: PluginProcessingContext) = try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(10))
                    null
                } catch (_: InterruptedException) {
                    null
                }
            }
            val next = object : LyricProcessorExtension {
                override val id: String = "next"

                override fun processResult(song: PluginSong, processingContext: PluginProcessingContext) =
                    PluginSongResult(song, emptySet())
            }

            val timedOut = runPluginProcessorCancellable(
                executor = executor,
                processor = blocking,
                song = PluginSong(name = "original"),
                processingContext = PluginProcessingContext(),
                timeoutMs = 10L,
                onPluginFailure = {},
                onTimeout = {}
            )
            val nextResult = invokePluginProcessorSafely(
                processor = next,
                song = PluginSong(name = "original"),
                processingContext = PluginProcessingContext(),
                onFailure = {}
            )

            assertNull(timedOut)
            assertNotNull(nextResult)
        } finally {
            executor.shutdownNow()
        }
    }
}
