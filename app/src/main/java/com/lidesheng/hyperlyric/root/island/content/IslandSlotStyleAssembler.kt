package com.lidesheng.hyperlyric.root.island.content

import android.content.SharedPreferences
import android.view.View
import com.lidesheng.hyperlyric.common.media.MediaMetadataHelper
import com.lidesheng.hyperlyric.lyric.view.RichLyricLineView
import com.lidesheng.hyperlyric.lyric.view.SpaceGateRichLyricLineView
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.island.config.IslandSlotRuntimeConfig
import com.lidesheng.hyperlyric.root.island.effects.color.StatusBarTextColorHooker
import com.lidesheng.hyperlyric.root.utils.CoverColorHelper
import com.lidesheng.hyperlyric.root.utils.LyricStyleHelper
import com.lidesheng.hyperlyric.root.utils.TranslationHelper
import java.util.WeakHashMap

internal object IslandSlotStyleAssembler {
    private val lastStyleSignatures = WeakHashMap<View, String>()
    private val lastColorSignatures = WeakHashMap<View, String>()

    fun invalidate(view: View? = null) {
        if (view == null) {
            synchronized(lastStyleSignatures) { lastStyleSignatures.clear() }
            synchronized(lastColorSignatures) { lastColorSignatures.clear() }
            return
        }
        synchronized(lastStyleSignatures) { lastStyleSignatures.remove(view) }
        synchronized(lastColorSignatures) { lastColorSignatures.remove(view) }
    }

    fun configureView(
        view: View,
        prefs: SharedPreferences,
        config: IslandSlotRuntimeConfig,
        mode: Int,
        mediaInfo: MediaMetadataHelper.MediaInfo,
        nextLinePreviewEnabled: Boolean,
        force: Boolean
    ) {
        val disableAll = TranslationHelper.isTranslationDisabled(prefs) || nextLinePreviewEnabled
        val translationOnly = TranslationHelper.isTranslationOnly(prefs)
        val lyricPackage = LyriconDataBridge.currentLyricPackageName.orEmpty()
        val colorSession = CoverColorHelper.currentSession(lyricPackage)
        val albumBitmap = mediaInfo.albumArt
        val artworkRequest = albumBitmap
            ?.takeIf { config.extractCoverTextColor }
            ?.let {
                CoverColorHelper.ensureArtworkColors(
                    packageName = lyricPackage,
                    title = mediaInfo.title,
                    artist = mediaInfo.artist,
                    bitmap = it
                )
            }
        val statusBarTextColor = if (config.followStatusBarTextColor) {
            StatusBarTextColorHooker.currentTextColor()
        } else {
            null
        }
        val styleSignature = listOf(
            config.styleSignature,
            mode,
            mediaInfo.title,
            mediaInfo.artist,
            mediaInfo.album
        ).joinToString("|")
        val colorSignature = listOf(
            config.textColorStyle,
            statusBarTextColor,
            colorSession?.revision,
            colorSession?.mediaKey,
            artworkRequest?.revision,
            albumBitmap?.generationId ?: 0
        ).joinToString("|")

        val styleChanged = force || lastStyleSignatures[view] != styleSignature
        val colorChanged = force || lastColorSignatures[view] != colorSignature
        if (!styleChanged && !colorChanged) return

        val style = LyricStyleHelper.buildStyle(
            prefs = prefs,
            res = view.resources,
            mode = mode,
            colorSession = colorSession,
            artworkRequest = artworkRequest,
            textColorOverride = statusBarTextColor
        )
        when (view) {
            is RichLyricLineView -> {
                if (styleChanged) {
                    view.displayTranslation = !disableAll
                    view.displayRoma = !disableAll && !translationOnly
                    view.setStyle(style)
                } else {
                    view.updateColor(
                        style.primary.color,
                        style.highlight.background,
                        style.highlight.foreground
                    )
                }
            }

            is SpaceGateRichLyricLineView -> {
                if (styleChanged) {
                    view.displayTranslation = !disableAll
                    view.displayRoma = !disableAll && !translationOnly
                    view.setStyle(
                        style,
                        isLeftSplitSide = config.isLeftTag(view.tag as? String ?: "")
                    )
                } else {
                    view.updateColor(
                        style.primary.color,
                        style.highlight.background,
                        style.highlight.foreground
                    )
                }
            }
        }
        lastStyleSignatures[view] = styleSignature
        lastColorSignatures[view] = colorSignature
    }
}
