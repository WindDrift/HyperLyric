package com.lidesheng.hyperlyric.root.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellUtilsTest {
    @Test
    fun parsesBoundedRootCacheFileListing() {
        val parsed = ShellUtils.parsePluginCacheFiles(
            listOf(
                "file\ta.cache\t42",
                "legacy\thyperlyric_plugin_cache_demo.xml\t256",
                "invalid"
            ).joinToString("\n")
        )

        assertEquals(2, parsed.size)
        assertEquals("a.cache", parsed[0].fileName)
        assertEquals(42L, parsed[0].sizeBytes)
        assertEquals(true, parsed[1].legacyPreferences)
    }
}
