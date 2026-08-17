package com.lidesheng.hyperlyric.online.amll

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * AMLL TTML DataBase Retrofit 接口
 */
interface AmllTtmlApi {

    /**
     * 按 ID 精确获取歌词。平台 ID 参数为单值数组形式（如 ncmMusicId=3325283031）。
     */
    @GET("v1/lyrics/get")
    suspend fun getLyric(
        @Query("id") id: Long? = null,
        @Query("ncmMusicId") ncmMusicId: String? = null,
        @Query("qqMusicId") qqMusicId: String? = null,
        @Query("appleMusicId") appleMusicId: String? = null,
        @Query("spotifyId") spotifyId: String? = null
    ): ApiResponse<SongItem>

    /**
     * 按歌名/歌手/专辑模糊搜索。AMLL 服务端按 AND 交集匹配，空参数不传。
     */
    @GET("v1/lyrics/search")
    suspend fun searchLyrics(
        @Query("musicName") musicName: String? = null,
        @Query("artistName") artistName: String? = null,
        @Query("albumName") albumName: String? = null
    ): ApiResponse<SearchLyricsResponse>
}
