package com.lidesheng.hyperlyric.root.source

import com.lidesheng.hyperlyric.common.media.MediaIdentity
import com.lidesheng.hyperlyric.common.media.MediaMetadataHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootPluginMediaInfoTest {
    @Test
    fun pluginSourcePackageComesOnlyFromLyricMetadataArgument() {
        val resolved = MediaMetadataHelper.MediaInfo(
            title = "title",
            identity = MediaIdentity(packageName = "session.player")
        )

        val pluginInfo = resolved.toPluginMediaInfo("lyric.source.player")

        assertEquals("lyric.source.player", pluginInfo?.sourcePackageName)
    }

    @Test
    fun identityPackageDoesNotFillMissingSourcePackage() {
        val resolved = MediaMetadataHelper.MediaInfo(
            title = "title",
            identity = MediaIdentity(packageName = "session.player")
        )

        assertNull(resolved.toPluginMediaInfo(null)?.sourcePackageName)
    }
}
