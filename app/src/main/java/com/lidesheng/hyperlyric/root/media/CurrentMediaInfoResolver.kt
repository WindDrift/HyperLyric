package com.lidesheng.hyperlyric.root.media

import android.content.Context
import com.lidesheng.hyperlyric.common.HyperLogger
import com.lidesheng.hyperlyric.common.media.MediaMetadataHelper
import com.lidesheng.hyperlyric.lyric.model.LyricMediaMetadata
import com.lidesheng.hyperlyric.root.LyriconDataBridge

/**
 * Resolves the media snapshot consumed by root-side surfaces.
 *
 * Source-provided fields are preferred individually. MediaSession remains the fallback for
 * fields a lyric source does not expose, and remains authoritative for artwork.
 */
internal object CurrentMediaInfoResolver {

    fun getMediaInfo(
        context: Context,
        packageName: String,
        logger: HyperLogger? = null,
        sourceMetadata: LyricMediaMetadata? = null
    ): MediaMetadataHelper.MediaInfo {
        val normalizedPackageName = packageName.trim()
        val sourceInfo = (sourceMetadata ?: LyriconDataBridge.currentLyricMediaMetadata)
            ?.normalized()
            ?.takeIf {
                it.packageName.isNullOrEmpty() || it.packageName == normalizedPackageName
            }
        val sessionInfo = MediaMetadataHelper.getMediaInfo(
            context = context,
            packageName = normalizedPackageName,
            logger = logger,
            preferredSessionToken = sourceInfo?.sessionToken
        )

        if (sourceInfo == null) return normalize(sessionInfo)

        return sessionInfo.copy(
            title = sourceInfo.title ?: normalizeText(sessionInfo.title),
            artist = sourceInfo.artist ?: normalizeText(sessionInfo.artist),
            album = sourceInfo.album ?: normalizeText(sessionInfo.album),
            identity = sourceInfo.toIdentity(normalizedPackageName)
                .fillMissingFrom(sessionInfo.identity)
        )
    }

    private fun normalize(info: MediaMetadataHelper.MediaInfo): MediaMetadataHelper.MediaInfo =
        info.copy(
            title = normalizeText(info.title),
            artist = normalizeText(info.artist),
            album = normalizeText(info.album),
            identity = info.identity.normalized()
        )

    private fun normalizeText(value: String): String = value
        .replace(WHITESPACE, " ")
        .trim()

    private val WHITESPACE = Regex("\\s+")
}
