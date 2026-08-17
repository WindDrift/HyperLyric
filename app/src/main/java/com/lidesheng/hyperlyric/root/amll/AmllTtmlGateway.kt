package com.lidesheng.hyperlyric.root.amll

import android.content.Context
import android.content.SharedPreferences
import com.lidesheng.hyperlyric.lyric.model.LyricMediaMetadata
import com.lidesheng.hyperlyric.lyric.model.Song

/**
 * AMLL TTML 增强层网关（main 层接口，Online/Offline flavor 各自注册实现）
 */
object AmllTtmlGateway {
    interface Impl {
        fun init(context: Context)

        /**
         * 异步抓取 AMLL TTML 并解析为 [Song]。
         *
         * 契约：返回 true 表示请求已受理，保证 [onResult] 恰好回调一次（主线程）；
         * 返回 false 表示未受理（开关关闭/未初始化），**不发起回调**，由调用方回落原流程。
         */
        fun fetchTtml(
            song: Song,
            metadata: LyricMediaMetadata?,
            prefs: SharedPreferences,
            onResult: (Song?) -> Unit
        ): Boolean

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

    fun fetchTtml(
        song: Song,
        metadata: LyricMediaMetadata?,
        prefs: SharedPreferences,
        onResult: (Song?) -> Unit
    ): Boolean = impl?.fetchTtml(song, metadata, prefs, onResult) ?: false

    fun cancelActiveRequests() {
        impl?.cancelActiveRequests()
    }

    fun clearCache(callback: (() -> Unit)? = null) {
        impl?.clearCache(callback)
    }
}
