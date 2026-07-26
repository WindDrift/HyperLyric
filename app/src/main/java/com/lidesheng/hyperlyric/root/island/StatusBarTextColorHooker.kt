package com.lidesheng.hyperlyric.root.island

import android.graphics.Color
import android.widget.TextView
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.root.HookEntry
import com.lidesheng.hyperlyric.root.island.renderer.BaseIslandRenderer
import com.lidesheng.hyperlyric.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks the effective SystemUI status-bar text tint used by the clock.
 *
 * The captured color is consumed only by HyperLyric's injected island lyric views.
 */
internal object StatusBarTextColorHooker {
    private const val TAG = "StatusBarTextColorHooker"

    private val hookedClasses = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    )
    private val hookedTextViewSetColor = AtomicBoolean(false)

    @Volatile
    private var textColor: Int = Color.WHITE

    fun hook(module: XposedModule, classLoader: ClassLoader) {
        var installedCount = if (hookTextViewSetColor(module)) 1 else 0
        STATUS_BAR_TEXT_CLASSES.forEach { className ->
            val targetClass = try {
                classLoader.loadClass(className)
            } catch (_: ClassNotFoundException) {
                null
            } catch (e: Exception) {
                HookLogger.e(TAG, "加载状态栏文字类失败: class=$className", e)
                null
            } ?: return@forEach

            if (!hookedClasses.add(targetClass)) return@forEach
            val methods = targetClass.declaredMethods.filter(::isTintCallback)
            if (methods.isEmpty()) {
                hookedClasses.remove(targetClass)
                return@forEach
            }

            var installedForClass = false
            methods.forEach { method ->
                try {
                    method.isAccessible = true
                    module.deoptimize(method)
                    module.hook(method).intercept(StatusBarTintHooker())
                    installedCount++
                    installedForClass = true
                } catch (e: Exception) {
                    HookLogger.e(
                        TAG,
                        "安装状态栏文字颜色 Hook 失败: target=${targetClass.name}#${method.name}",
                        e
                    )
                }
            }
            if (!installedForClass) hookedClasses.remove(targetClass)
        }

        if (installedCount > 0) {
            HookLogger.i(TAG, "状态栏文字颜色 Hook 已初始化: methods=$installedCount")
        } else if (hookedClasses.isEmpty()) {
            HookLogger.w(TAG, "未找到受支持的状态栏文字颜色回调")
        }
    }

    fun currentTextColor(): Int = textColor

    fun restoreTextColor(color: Int) {
        textColor = color
    }

    fun createReplacement(method: Method): Hooker? {
        if (isTextViewSetColor(method)) {
            hookedTextViewSetColor.set(true)
            return StatusBarSetTextColorHooker()
        }
        if (isTintCallback(method)) {
            hookedClasses.add(method.declaringClass)
            return StatusBarTintHooker()
        }
        return null
    }

    private fun isTintCallback(method: Method): Boolean {
        return method.declaringClass.name in STATUS_BAR_TEXT_CLASSES &&
                method.name in TINT_CALLBACK_NAMES
    }

    private fun hookTextViewSetColor(module: XposedModule): Boolean {
        if (!hookedTextViewSetColor.compareAndSet(false, true)) return false
        val method = TextView::class.java.declaredMethods.firstOrNull(::isTextViewSetColor)
        if (method == null) {
            hookedTextViewSetColor.set(false)
            return false
        }
        return try {
            method.isAccessible = true
            module.deoptimize(method)
            module.hook(method).intercept(StatusBarSetTextColorHooker())
            true
        } catch (e: Exception) {
            hookedTextViewSetColor.set(false)
            HookLogger.e(TAG, "安装 TextView#setTextColor 状态栏取色 Hook 失败", e)
            false
        }
    }

    private fun isTextViewSetColor(method: Method): Boolean {
        return method.declaringClass == TextView::class.java &&
                method.name == "setTextColor" &&
                method.parameterTypes.contentEquals(arrayOf(Integer.TYPE))
    }

    internal class StatusBarTintHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            val textView = chain.thisObject as? TextView ?: return result
            updateTextColor(textView.currentTextColor)
            return result
        }
    }

    internal class StatusBarSetTextColorHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            val textView = chain.thisObject as? TextView ?: return result
            if (isStatusBarTextView(textView)) {
                updateTextColor(textView.currentTextColor)
            }
            return result
        }
    }

    private fun isStatusBarTextView(textView: TextView): Boolean {
        if (textView.javaClass.name in STATUS_BAR_TEXT_CLASSES) return true

        var parent = textView.parent
        repeat(MAX_STATUS_BAR_PARENT_DEPTH) {
            val currentParent = parent ?: return false
            if (currentParent.javaClass.name in STATUS_BAR_TEXT_PARENT_CLASSES) return true
            parent = currentParent.parent
        }
        return false
    }

    private fun updateTextColor(color: Int) {
        if (textColor == color) return
        textColor = color

        val prefs = HookEntry.instance?.prefs ?: return
        if (
            prefs.getBoolean(
                RootConstants.KEY_HOOK_FOLLOW_STATUS_BAR_TEXT_COLOR,
                RootConstants.DEFAULT_HOOK_FOLLOW_STATUS_BAR_TEXT_COLOR
            )
        ) {
            BaseIslandRenderer.refreshActiveIsland()
        }
    }

    private val STATUS_BAR_TEXT_CLASSES = setOf(
        "com.android.systemui.statusbar.policy.Clock",
        "com.android.systemui.statusbar.views.MiuiClock",
        "com.android.systemui.statusbar.OperatorNameView"
    )

    private val STATUS_BAR_TEXT_PARENT_CLASSES = setOf(
        "com.android.systemui.battery.BatteryMeterView",
        "com.android.systemui.statusbar.views.MiuiBatteryMeterView",
        "com.android.systemui.statusbar.views.NetworkSpeedView"
    )

    private val TINT_CALLBACK_NAMES = setOf(
        "onDarkChanged",
        "onLightDarkTintChanged"
    )

    private const val MAX_STATUS_BAR_PARENT_DEPTH = 5
}
