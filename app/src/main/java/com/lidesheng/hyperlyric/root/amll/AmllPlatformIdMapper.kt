package com.lidesheng.hyperlyric.root.amll

/**
 * AMLL 平台 ID 字段枚举
 *
 * 对应 AMLL TTML DataBase `/v1/lyrics/get` 接口的平台 ID 查询参数。
 */
enum class AmllPlatformIdField {
    /** 网易云音乐（ncmMusicId） */
    NCM,

    /** QQ 音乐（qqMusicId） */
    QQ,

    /** Apple Music（appleMusicId） */
    APPLE,

    /** Spotify（spotifyId） */
    SPOTIFY
}

/**
 * 包名 → AMLL 平台 ID 字段映射器
 */
object AmllPlatformIdMapper {

    private val PACKAGE_TO_FIELD = mapOf(
        "com.netease.cloudmusic" to AmllPlatformIdField.NCM,
        "com.tencent.qqmusic" to AmllPlatformIdField.QQ,
        "com.apple.android.music" to AmllPlatformIdField.APPLE,
        "com.spotify.music" to AmllPlatformIdField.SPOTIFY
    )

    /**
     * 将歌词源包名映射到 AMLL 平台 ID 字段。
     *
     * @param packageName 歌词源包名
     * @return 对应的平台字段；未知包名返回 null（调用方应回退到 search 模糊匹配）
     */
    fun mapPackageNameToAmllField(packageName: String?): AmllPlatformIdField? =
        packageName?.let { PACKAGE_TO_FIELD[it] }
}
