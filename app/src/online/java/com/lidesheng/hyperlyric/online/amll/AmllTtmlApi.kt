package com.lidesheng.hyperlyric.online.amll

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * AMLL TTML DataBase Retrofit 接口
 *
 * 注意：本接口在 LSPosed 宿主进程（systemui）内运行。Retrofit 识别 suspend 函数
 * 依赖对 Continuation 泛型签名的反射读取，在宿主进程的混合类加载环境下
 * （systemui 自带 kotlin-stdlib，模块也打包了自己的 stdlib/Retrofit）不可靠，
 * 实测报 "Unable to create call adapter for class java.lang.Object"。
 * 因此返回类型使用 Call<T>，由 AmllTtmlClient 通过 await() 挂起等待，
 * 异常语义与 suspend 函数等价（HttpException/IOException/CancellationException）。
 * NeApi/QmApi 虽为 suspend，但运行在模块自身 App 进程，不受此问题影响。
 */
interface AmllTtmlApi {

    /**
     * 按 ID 精确获取歌词。平台 ID 参数为单值数组形式（如 ncmMusicId=3325283031）。
     * null 的 @Query 参数 Retrofit 不会拼入 URL。
     */
    @GET("v1/lyrics/get")
    fun getLyric(
        @Query("id") id: Long?,
        @Query("ncmMusicId") ncmMusicId: String?,
        @Query("qqMusicId") qqMusicId: String?,
        @Query("appleMusicId") appleMusicId: String?,
        @Query("spotifyId") spotifyId: String?
    ): Call<ApiResponse<SongItem>>

    /**
     * 按歌名/歌手/专辑模糊搜索。AMLL 服务端按 AND 交集匹配，空参数不传。
     */
    @GET("v1/lyrics/search")
    fun searchLyrics(
        @Query("musicName") musicName: String?,
        @Query("artistName") artistName: String?,
        @Query("albumName") albumName: String?
    ): Call<ApiResponse<SearchLyricsResponse>>
}
