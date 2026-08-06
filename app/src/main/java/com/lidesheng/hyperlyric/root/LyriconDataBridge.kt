package com.lidesheng.hyperlyric.root

import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.lyric.model.RichLyricLine
import com.lidesheng.hyperlyric.lyric.model.Song
import com.lidesheng.hyperlyric.lyric.model.extensions.TimingNavigator
import com.lidesheng.hyperlyric.lyric.model.interfaces.IRichLyricLine
import com.lidesheng.hyperlyric.lyric.source.StateResetter
import com.lidesheng.hyperlyric.lyric.view.InterludeTracker
import com.lidesheng.hyperlyric.lyric.view.SongPreprocessor
import com.lidesheng.hyperlyric.lyric.view.TimedLine
import com.lidesheng.hyperlyric.lyric.view.TitleSlot
import com.lidesheng.hyperlyric.root.utils.HookLogger

object LyriconDataBridge : StateResetter {

    private const val TAG = "LyriconDataBridge"

    val versionCounter = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    var currentSong: Song? = null

    @Volatile
    var currentSongName: String? = null

    @Volatile
    var currentLyric: String? = null

    @Volatile
    var currentLyricLine: IRichLyricLine? = null

    @Volatile
    var currentNextLyricLine: IRichLyricLine? = null

    @Volatile
    var currentPosition: Long = 0L

    @Volatile
    var activePackageName: String? = null

    @Volatile
    var currentLyricPackageName: String? = null

    /** 是否处于纯文本模式（椒盐音乐等通过 onSendText 推送） */
    @Volatile
    var isTextMode: Boolean = false

    /** AI 翻译完成后的回调，由 LyriconSource 设置 */
    var onAiTranslationComplete: (() -> Unit)? = null

    @Volatile
    private var placeholderFormat = RootConstants.DEFAULT_HOOK_PLACEHOLDER_FORMAT

    fun updateLyricPackage(packageName: String?) {
        activePackageName = packageName
        currentLyricPackageName = packageName
    }

    private var timingNavigator: TimingNavigator<TimedLine> = TimingNavigator(emptyArray())
    private var interludeTracker = InterludeTracker(8_000L)

    fun updateSong(
        song: Song?,
        placeholderFormat: Int = RootConstants.DEFAULT_HOOK_PLACEHOLDER_FORMAT
    ) {
        HookLogger.d(TAG, "歌曲变更: ${song?.name}")
        isTextMode = false
        currentSong = song
        currentSongName = song?.name
        currentLyric = null
        currentLyricLine = null
        currentNextLyricLine = null
        this.placeholderFormat = normalizePlaceholderFormat(placeholderFormat)

        versionCounter.incrementAndGet()

        if (song != null) {
            rebuildTimeline(song, selectCurrentPosition = false)
        } else {
            timingNavigator = TimingNavigator(emptyArray())
        }
    }

    fun applyTranslation(translatedSong: Song) {
        currentSong = translatedSong
        rebuildTimeline(translatedSong, selectCurrentPosition = true)
    }

    fun updatePlaceholderFormat(format: Int): Boolean {
        val normalizedFormat = normalizePlaceholderFormat(format)
        if (placeholderFormat == normalizedFormat) return false
        placeholderFormat = normalizedFormat

        val song = currentSong ?: return false
        rebuildTimeline(song, selectCurrentPosition = true)
        return true
    }

    fun updatePosition(position: Long): Boolean {
        currentPosition = position
        if (isTextMode) return false
        val song = currentSong ?: return false
        val lyrics = song.lyrics
        if (lyrics.isNullOrEmpty()) return false

        // 使用 TimingNavigator 高效定位当前歌词行
        var foundLine: TimedLine? = null
        timingNavigator.forEachAtOrPrevious(position) { timedLine ->
            foundLine = timedLine
        }

        val previousLine = currentLyricLine
        currentLyricLine = foundLine
        currentNextLyricLine = foundLine?.next
        // 间奏时保持最后一行歌词，不回退到歌名
        val newText = foundLine?.text ?: currentLyric ?: ""
        // 占位符圆点没有文本，不能只靠文本变化判断是否需要刷新。
        // 切歌或切换到同文本歌词时，新的歌词行仍然需要传给渲染器。
        val lineChanged = foundLine != null && foundLine !== previousLine

        if (lineChanged || newText != currentLyric) {
            currentLyric = newText
            return true
        }
        return false
    }

    fun updateLyric(text: String?) {
        isTextMode = true
        currentLyric = text
        currentLyricLine = if (!text.isNullOrBlank()) {
            val lines = text.lines()
            RichLyricLine(
                text = lines.first(),
                translation = lines.getOrNull(1)
            )
        } else {
            null
        }
        currentNextLyricLine = null
    }

    fun updateLyricLine(line: IRichLyricLine) {
        isTextMode = false
        currentLyricLine = line
        currentNextLyricLine = null
        currentLyric = line.text
    }

    override fun clearState() {
        currentSong = null
        currentSongName = null
        currentLyric = null
        currentLyricLine = null
        currentNextLyricLine = null
        currentPosition = 0L
        activePackageName = null
        currentLyricPackageName = null
        isTextMode = false
        timingNavigator = TimingNavigator(emptyArray())

        versionCounter.incrementAndGet()
    }

    private fun rebuildTimeline(song: Song, selectCurrentPosition: Boolean) {
        val processor = SongPreprocessor(resolveTitleSlot(placeholderFormat))
        val lines = processor.prepare(song.deepCopy())
        timingNavigator = TimingNavigator(lines.toTypedArray())
        interludeTracker = InterludeTracker(8_000L)

        if (selectCurrentPosition) {
            currentLyric = null
            currentLyricLine = null
            currentNextLyricLine = null
            updatePosition(currentPosition)
        }
    }

    private fun normalizePlaceholderFormat(format: Int): Int {
        return when (format) {
            RootConstants.PLACEHOLDER_FORMAT_NONE,
            RootConstants.PLACEHOLDER_FORMAT_TITLE_ARTIST,
            RootConstants.PLACEHOLDER_FORMAT_TITLE,
            RootConstants.PLACEHOLDER_FORMAT_COUNTDOWN -> format

            else -> RootConstants.DEFAULT_HOOK_PLACEHOLDER_FORMAT
        }
    }

    private fun resolveTitleSlot(format: Int): TitleSlot {
        return when (format) {
            RootConstants.PLACEHOLDER_FORMAT_NONE -> TitleSlot.NONE
            RootConstants.PLACEHOLDER_FORMAT_TITLE -> TitleSlot.NAME
            RootConstants.PLACEHOLDER_FORMAT_COUNTDOWN -> TitleSlot.COUNTDOWN
            else -> TitleSlot.NAME_ARTIST
        }
    }

}


