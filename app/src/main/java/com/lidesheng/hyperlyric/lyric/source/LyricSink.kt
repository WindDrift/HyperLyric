package com.lidesheng.hyperlyric.lyric.source

import com.lidesheng.hyperlyric.lyric.model.LyricMediaMetadata

interface LyricSink {
    fun onSongChanged(song: Any?)
    fun onLyricLine(line: Any?)
    fun onPlainText(text: String?)
    fun onStop()
    fun onMetadata(metadata: LyricMediaMetadata?)
    fun onPlaybackStateChanged(isPlaying: Boolean, playbackSpeed: Float = Float.NaN)
    fun onPositionChanged(position: Long, playbackSpeed: Float = Float.NaN)
}
