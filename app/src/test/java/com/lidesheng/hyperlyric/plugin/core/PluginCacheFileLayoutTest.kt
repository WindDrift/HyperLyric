package com.lidesheng.hyperlyric.plugin.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCacheFileLayoutTest {
    @Test
    fun cachePathUsesBoundedPluginIdAndOpaqueStableFileName() {
        assertTrue(PluginCacheFileLayout.isValidPluginId("hyperlyric.ai.translation"))
        assertFalse(PluginCacheFileLayout.isValidPluginId("../systemui"))
        assertEquals(
            "files/hyperlyric_plugin_cache/hyperlyric.ai.translation",
            PluginCacheFileLayout.rootRelativeDirectory("hyperlyric.ai.translation")
        )
        assertEquals(
            PluginCacheFileLayout.fileNameForKey("cache.index.v3"),
            PluginCacheFileLayout.fileNameForKey("cache.index.v3")
        )
    }
}
