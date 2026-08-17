package com.lidesheng.hyperlyric.online.amll

import kotlinx.serialization.Serializable

/**
 * AMLL TTML DataBase API 响应模型
 *
 * 按 AMLL 官方 schema 映射（https://api.amll.dev）。
 */
@Serializable
data class ApiResponse<T>(
    val status: Int? = null,
    val data: T? = null
)

/**
 * AMLL 歌词条目
 *
 * search 接口返回的条目不含 [lyrics]；get 接口返回完整 TTML 字符串。
 */
@Serializable
data class SongItem(
    val id: Long? = null,
    val musicNames: List<String>? = null,
    val artistNames: List<String>? = null,
    val albumNames: List<String>? = null,
    val lyrics: String? = null
)

/**
 * AMLL 搜索响应分页信息载体
 */
@Serializable
data class SearchLyricsResponse(
    val items: List<SongItem>? = null,
    val total: Long? = null,
    val page: Int? = null,
    val pageSize: Int? = null
)
