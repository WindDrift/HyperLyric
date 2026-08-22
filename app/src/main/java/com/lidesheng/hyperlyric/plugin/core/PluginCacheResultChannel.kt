package com.lidesheng.hyperlyric.plugin.core

import android.content.Context
import android.net.Uri
import android.os.Bundle

/**
 * App-owned response mailbox for cache operations executed in the injected SystemUI process.
 * RemotePreferences only carries App-to-SystemUI requests because its target-process view is
 * read-only; SystemUI returns the bounded result through this provider channel instead.
 */
internal object PluginCacheResultChannel {
    const val METHOD_SUBMIT = "submit_cache_operation_result"
    const val EXTRA_REQUEST_ID = "requestId"
    const val EXTRA_RESPONSE_TOKEN = "responseToken"
    const val EXTRA_RESPONSE = "response"
    const val EXTRA_ACCEPTED = "accepted"

    private const val PREFERENCES = "hyperlyric_plugin_cache_operation_mailbox"
    private const val PENDING_PREFIX = "pending."
    private const val RESPONSE_PREFIX = "response."
    private const val TOKEN_SEPARATOR = '\u001F'
    private val uri = Uri.parse("content://${PluginConstants.CACHE_RESULT_PROVIDER_AUTHORITY}")
    private val lock = Any()

    fun registerPending(context: Context, request: PluginCacheOperationRequest) {
        synchronized(lock) {
            val preferences = preferences(context)
            cleanupExpired(preferences, System.currentTimeMillis())
            preferences.edit()
                .putString(
                    PENDING_PREFIX + request.requestId,
                    "${request.responseToken}$TOKEN_SEPARATOR${request.createdAtEpochMs}"
                )
                .remove(RESPONSE_PREFIX + request.requestId)
                .apply()
        }
    }

    fun consumeResponse(
        context: Context,
        requestId: String
    ): PluginCacheOperationResponse? = synchronized(lock) {
        val preferences = preferences(context)
        val response = preferences.getString(RESPONSE_PREFIX + requestId, null)
            ?.let(PluginCacheOperationCodec::decodeResponse)
            ?.takeIf { it.requestId == requestId }
        if (response != null) {
            preferences.edit()
                .remove(PENDING_PREFIX + requestId)
                .remove(RESPONSE_PREFIX + requestId)
                .apply()
        }
        response
    }

    fun clear(context: Context, requestId: String) {
        synchronized(lock) {
            preferences(context).edit()
                .remove(PENDING_PREFIX + requestId)
                .remove(RESPONSE_PREFIX + requestId)
                .apply()
        }
    }

    fun publishFromSystemUi(
        context: Context,
        request: PluginCacheOperationRequest,
        response: PluginCacheOperationResponse
    ): Boolean = runCatching {
        val encoded = PluginCacheOperationCodec.encodeResponse(response)
        val result = context.contentResolver.call(
            uri,
            METHOD_SUBMIT,
            null,
            Bundle().apply {
                putString(EXTRA_REQUEST_ID, request.requestId)
                putString(EXTRA_RESPONSE_TOKEN, request.responseToken)
                putString(EXTRA_RESPONSE, encoded)
            }
        )
        result?.getBoolean(EXTRA_ACCEPTED, false) == true
    }.getOrDefault(false)

    fun acceptFromSystemUi(
        context: Context,
        requestId: String?,
        responseToken: String?,
        encodedResponse: String?
    ): Boolean {
        if (requestId.isNullOrBlank() || responseToken.isNullOrBlank() || encodedResponse.isNullOrBlank()) {
            return false
        }
        val response = PluginCacheOperationCodec.decodeResponse(encodedResponse)
            ?.takeIf { it.requestId == requestId }
            ?: return false
        synchronized(lock) {
            val preferences = preferences(context)
            cleanupExpired(preferences, System.currentTimeMillis())
            val pending = preferences.getString(PENDING_PREFIX + requestId, null) ?: return false
            val expectedToken = pending.substringBefore(TOKEN_SEPARATOR)
            if (expectedToken != responseToken) return false
            preferences.edit()
                .putString(RESPONSE_PREFIX + requestId, PluginCacheOperationCodec.encodeResponse(response))
                .apply()
            return true
        }
    }

    private fun preferences(context: Context) = context.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE
    )

    private fun cleanupExpired(
        preferences: android.content.SharedPreferences,
        nowEpochMs: Long
    ) {
        val editor = preferences.edit()
        preferences.all.forEach { (key, value) ->
            when {
                key.startsWith(PENDING_PREFIX) -> {
                    val createdAt = (value as? String)
                        ?.substringAfter(TOKEN_SEPARATOR, "")
                        ?.toLongOrNull()
                    if (createdAt == null || nowEpochMs - createdAt > PluginCacheOperationCodec.REQUEST_TTL_MS) {
                        editor.remove(key)
                        editor.remove(RESPONSE_PREFIX + key.removePrefix(PENDING_PREFIX))
                    }
                }

                key.startsWith(RESPONSE_PREFIX) -> {
                    val response = (value as? String)?.let(PluginCacheOperationCodec::decodeResponse)
                    if (response == null || PluginCacheOperationCodec.isResponseExpired(response, nowEpochMs)) {
                        editor.remove(key)
                    }
                }
            }
        }
        editor.apply()
    }
}
