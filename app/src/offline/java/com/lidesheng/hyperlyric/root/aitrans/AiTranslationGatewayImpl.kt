package com.lidesheng.hyperlyric.root.aitrans

import android.content.Context
import android.content.SharedPreferences
import com.lidesheng.hyperlyric.lyric.model.Song

class AiTranslationGatewayImpl : AiTranslationGateway.Impl {

    init {
        AiTranslationGateway.register(this)
    }

    override fun init(context: Context) {}

    override fun translateSong(song: Song, prefs: SharedPreferences, forceOverride: Boolean): Boolean = false

    override fun cancelActiveRequests() {}

    override fun clearCache(callback: (() -> Unit)?) {}
}
