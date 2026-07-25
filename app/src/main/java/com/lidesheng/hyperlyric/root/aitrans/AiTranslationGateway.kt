package com.lidesheng.hyperlyric.root.aitrans

import android.content.Context
import android.content.SharedPreferences
import com.lidesheng.hyperlyric.lyric.model.Song

object AiTranslationGateway {
    interface Impl {
        fun init(context: Context)
        fun translateSong(song: Song, prefs: SharedPreferences, forceOverride: Boolean): Boolean
        fun cancelActiveRequests()
        fun clearCache(callback: (() -> Unit)? = null)
    }

    private var impl: Impl? = null

    fun register(implementation: Impl) {
        impl = implementation
    }

    fun init(context: Context) {
        impl?.init(context)
    }

    fun translateSong(song: Song, prefs: SharedPreferences, forceOverride: Boolean): Boolean =
        impl?.translateSong(song, prefs, forceOverride) ?: false

    fun cancelActiveRequests() {
        impl?.cancelActiveRequests()
    }

    fun clearCache(callback: (() -> Unit)? = null) {
        impl?.clearCache(callback)
    }
}
