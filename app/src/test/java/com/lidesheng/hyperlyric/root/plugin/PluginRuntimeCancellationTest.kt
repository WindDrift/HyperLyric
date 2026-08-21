package com.lidesheng.hyperlyric.root.plugin

import com.lidesheng.hyperlyric.plugin.api.LyricProcessorExtension
import com.lidesheng.hyperlyric.plugin.api.PluginProcessingContext
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PluginRuntimeCancellationTest {
    @Test
    fun cancellingProcessingInterruptsTheSubmittedPluginTask() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        try {
            val processor = object : LyricProcessorExtension {
                override val id: String = "blocking"

                override fun processResult(
                    song: PluginSong,
                    processingContext: PluginProcessingContext
                ) = try {
                    started.countDown()
                    Thread.sleep(TimeUnit.SECONDS.toMillis(10))
                    null
                } catch (_: InterruptedException) {
                    interrupted.countDown()
                    null
                }
            }

            val processingJob = launch(Dispatchers.Default) {
                runPluginProcessorCancellable(
                    executor = executor,
                    processor = processor,
                    song = PluginSong(name = "stale"),
                    processingContext = PluginProcessingContext(),
                    timeoutMs = TimeUnit.SECONDS.toMillis(10),
                    onPluginFailure = {},
                    onTimeout = {}
                )
            }

            assertTrue(started.await(1, TimeUnit.SECONDS))
            processingJob.cancelAndJoin()

            assertTrue(interrupted.await(1, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }
}
