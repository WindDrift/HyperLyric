package com.lidesheng.hyperlyric.lyric.model

/**
 * A source-independent snapshot of the media information that accompanies lyrics.
 *
 * Source implementations may only know part of this object. Missing fields are left null and
 * are filled from the current MediaSession by the root-side resolver.
 */
data class LyricMediaMetadata(
    val sourceId: String,
    val packageName: String? = null,
    val songId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null
) {

    /** Normalize source-defined text without applying player-specific parsing heuristics. */
    fun normalized(): LyricMediaMetadata = copy(
        packageName = packageName.normalizeMediaText(),
        songId = songId.normalizeMediaText(),
        title = title.normalizeMediaText(),
        artist = artist.normalizeMediaText(),
        album = album.normalizeMediaText()
    )
}

private val mediaTextWhitespace = Regex("\\s+")

private fun String?.normalizeMediaText(): String? = this
    ?.replace(mediaTextWhitespace, " ")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
