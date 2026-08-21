package com.lidesheng.hyperlyric.plugin.ai.translation

import com.lidesheng.hyperlyric.plugin.api.PluginLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationSchedulerTest {
    @Test
    fun completedResultStaysDeduplicatedUntilConsumerReleasesIt() {
        val scheduler = TranslationScheduler(NO_OP_LOGGER)
        val requestStarted = CountDownLatch(1)
        val allowNetworkReturn = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val caller = Executors.newSingleThreadExecutor()

        try {
            val firstFuture = caller.submit<TranslationScheduler.ScheduledTranslation> {
                scheduler.getOrEnqueue(
                    key = "same-key",
                    songName = "song"
                ) {
                    calls.incrementAndGet()
                    requestStarted.countDown()
                    allowNetworkReturn.await()
                    listOf(TranslationItem(0, "translated"))
                }
            }

            assertTrue(requestStarted.await(2, TimeUnit.SECONDS))
            allowNetworkReturn.countDown()
            val first = firstFuture.get(2, TimeUnit.SECONDS)
            // Give the worker time to run its completion/finally path. The completed job must
            // still be present because the caller has not written its persistent cache yet.
            Thread.sleep(50)

            val second = scheduler.getOrEnqueue(
                key = "same-key",
                songName = "song"
            ) {
                calls.incrementAndGet()
                listOf(TranslationItem(0, "duplicate-network"))
            }

            assertEquals(1, calls.get())
            assertEquals(first.items, second.items)
            first.release()
            second.release()
        } finally {
            scheduler.close()
            caller.shutdownNow()
        }
    }

    private companion object {
        val NO_OP_LOGGER = object : PluginLogger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String, throwable: Throwable?) = Unit
            override fun error(message: String, throwable: Throwable?) = Unit
        }
    }
}
