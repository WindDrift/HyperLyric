package com.lidesheng.hyperlyric.root.amll

import android.content.Context
import android.content.SharedPreferences
import com.lidesheng.hyperlyric.lyric.model.LyricMediaMetadata
import com.lidesheng.hyperlyric.lyric.model.Song

/**
 * AMLL TTML 增强层 Offline 占位实现
 *
 * 不发起任何网络请求，所有方法空操作。
 */
class AmllTtmlGatewayImpl : AmllTtmlGateway.Impl {

    init {
        AmllTtmlGateway.register(this)
    }

    override fun init(context: Context) {}

    override fun fetchTtml(
        song: Song,
        metadata: LyricMediaMetadata?,
        prefs: SharedPreferences,
        onResult: (Song?) -> Unit
    ): Boolean {
        // 返回 false 表示未受理（不发起回调），由调用方回落原流程
        return false
    }

    override fun cancelActiveRequests() {}

    override fun clearCache(callback: (() -> Unit)?) {}
}
