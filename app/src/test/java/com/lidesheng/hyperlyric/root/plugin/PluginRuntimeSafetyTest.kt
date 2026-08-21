package com.lidesheng.hyperlyric.root.plugin

import com.lidesheng.hyperlyric.plugin.api.LyricProcessorExtension
import com.lidesheng.hyperlyric.plugin.api.PluginProcessingContext
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
