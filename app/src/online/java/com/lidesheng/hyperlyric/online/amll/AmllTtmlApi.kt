package com.lidesheng.hyperlyric.online.amll

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * AMLL TTML DataBase Retrofit 接口
 */
interface AmllTtmlApi {

    /**
     * 按 ID 精确获取歌词。平台 ID 参数为单值数组形式（如 ncmMusicId=3325283031）。
     *
     * 注意：Retrofit 接口参数不要使用 Kotlin 默认值。全默认参数会生成 $default 合成方法，
     * R8 full mode 下会导致 Retrofit 无法识别 suspend 函数，报
     * "Unable to create call adapter for class java.lang.Object"（实测踩坑）。
     * null 的 @Query 参数 Retrofit 不会拼入 URL，与默认值不传行为一致。
     */
    @GET("v1/lyrics/get")
    suspend fun getLyric(
        @Query("id") id: Long?,
        @Query("ncmMusicId") ncmMusicId: String?,
        @Query("qqMusicId") qqMusicId: String?,
        @Query("appleMusicId") appleMusicId: String?,
        @Query("spotifyId") spotifyId: String?
    ): ApiResponse<SongItem>

    /**
     * 按歌名/歌手/专辑模糊搜索。AMLL 服务端按 AND 交集匹配，空参数不传。
     *
     * 同上：参数不使用默认值，调用方显式传 null。
     */
    @GET("v1/lyrics/search")
    suspend fun searchLyrics(
        @Query("musicName") musicName: String?,
        @Query("artistName") artistName: String?,
        @Query("albumName") albumName: String?
    ): ApiResponse<SearchLyricsResponse>
}
