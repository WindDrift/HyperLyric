package com.lidesheng.hyperlyric.root.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellUtilsTest {
    @Test
    fun parsesBoundedRootCacheFileListing() {
        val parsed = ShellUtils.parsePluginCacheFiles(
            listOf(
                "file\t/data/user_de/0/com.android.systemui/files/hyperlyric_plugin_cache/demo/a.cache\ta.cache\t42",
                "legacy\t/data/user/0/com.android.systemui/shared_prefs/hyperlyric_plugin_cache_demo.xml\thyperlyric_plugin_cache_demo.xml\t256",
                "invalid"
            ).joinToString("\n")
        )

        assertEquals(2, parsed.size)
        assertEquals("a.cache", parsed[0].fileName)
        assertEquals(42L, parsed[0].sizeBytes)
        assertEquals(
            "/data/user_de/0/com.android.systemui/files/hyperlyric_plugin_cache/demo/a.cache",
            parsed[0].absolutePath
        )
        assertEquals(true, parsed[1].legacyPreferences)
    }
}
