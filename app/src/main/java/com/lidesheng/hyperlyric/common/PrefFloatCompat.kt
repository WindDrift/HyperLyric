package com.lidesheng.hyperlyric.common

import android.content.SharedPreferences

/** 只读兼容浮点偏好；修复写入由 App 侧负责，Hook 侧远程偏好不可写。 */
fun getFloatCompat(prefs: SharedPreferences, key: String, defValue: Float): Float {
    val value = try {
        prefs.getFloat(key, defValue)
    } catch (_: ClassCastException) {
        val raw = prefs.all[key]
        FloatPreferencePolicy.normalizeStoredFloat(key, raw)?.let { return it }
        when (raw) {
            is Number -> raw.toFloat()
            is String -> raw.toFloatOrNull() ?: defValue
            else -> defValue
        }
    }
    return FloatPreferencePolicy.normalizeReadFloat(key, value, defValue)
}
