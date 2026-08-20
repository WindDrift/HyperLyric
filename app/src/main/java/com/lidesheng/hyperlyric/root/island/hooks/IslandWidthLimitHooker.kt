package com.lidesheng.hyperlyric.root.island.hooks

import android.content.Context
import android.view.View
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.root.HookEntry
import com.lidesheng.hyperlyric.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/**
 * Removes Xiaomi's native maximum-width calculation when explicitly enabled.
 *
 * The native implementation has two separate limits: the max width supplied to the real island
 * and an additional clock/battery calculation used when a small island is present. Both hooks
 * keep the native clock/battery measurements intact and only replace the maximum width input
 * with the current screen width.
 */
internal object IslandWidthLimitHooker {
    private const val TAG = "IslandWidthLimitHooker"
    private const val BASE_CONTENT_VIEW_CLASS =
        "miui.systemui.dynamicisland.window.content.DynamicIslandBaseContentView"
    private const val PHONE_HELPER_CLASS =
        "miui.systemui.dynamicisland.window.content.helpers.DynamicIslandContentViewPhoneHelper"
    private const val DYNAMIC_ISLAND_UTILS_CLASS =
        "miui.systemui.dynamicisland.DynamicIslandUtils"

    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )

    fun hook(module: XposedModule, cl: ClassLoader) {
        if (!hookedClassLoaders.add(cl)) return

        var installed = 0
        try {
            val baseContentViewClass = cl.loadClass(BASE_CONTENT_VIEW_CLASS)
            val getMaxWidth = baseContentViewClass.declaredMethods.firstOrNull {
                it.name == "getMaxWidth" &&
                        it.parameterTypes.isEmpty() &&
                        it.returnType == Float::class.javaPrimitiveType
            }
            if (getMaxWidth != null) {
                getMaxWidth.isAccessible = true
                module.deoptimize(getMaxWidth)
                module.hook(getMaxWidth).intercept(
                    GetMaxWidthHook(ScreenWidthAccessor(cl))
                )
                installed++
            } else {
                HookLogger.w(TAG, "未找到 DynamicIslandBaseContentView.getMaxWidth()")
            }

            val phoneHelperClass = cl.loadClass(PHONE_HELPER_CLASS)
            val calculateMaxWidthWithSmall = phoneHelperClass.declaredMethods.firstOrNull {
                it.name == "calculateMaxWidthWithSmall" &&
                        it.parameterTypes.size == 2 &&
                        it.parameterTypes[1] == StringBuilder::class.java &&
                        it.returnType == Float::class.javaPrimitiveType
            }
            if (calculateMaxWidthWithSmall != null) {
                calculateMaxWidthWithSmall.isAccessible = true
                module.deoptimize(calculateMaxWidthWithSmall)
                module.hook(calculateMaxWidthWithSmall).intercept(
                    CalculateMaxWidthWithSmallHook(
                        ScreenWidthAccessor(cl, calculateMaxWidthWithSmall)
                    )
                )
                installed++
            } else {
                HookLogger.w(
                    TAG,
                    "未找到 DynamicIslandContentViewPhoneHelper.calculateMaxWidthWithSmall"
                )
            }

            if (installed > 0) {
                HookLogger.i(TAG, "解除超级岛长度限制 Hook 已初始化: hooks=$installed")
            } else {
                hookedClassLoaders.remove(cl)
            }
        } catch (e: ClassNotFoundException) {
            hookedClassLoaders.remove(cl)
            throw e
        } catch (e: Exception) {
            hookedClassLoaders.remove(cl)
            throw e
        }
    }

    /**
     * Re-enters Xiaomi's width calculation for an already-created real island. The preference
     * hook changes what the calculation reads; this call makes an existing instance consume the
     * new value without requiring the island to be destroyed and recreated.
     */
    fun refresh(contentView: Any): Boolean {
        return runCatching {
            val method = contentView.javaClass.methods.firstOrNull {
                it.name == "updateBigIslandViewWidth" && it.parameterTypes.isEmpty()
            } ?: return@runCatching false
            method.isAccessible = true
            method.invoke(contentView)
            true
        }.onFailure { error ->
            HookLogger.w(TAG, "刷新现有超级岛宽度失败", error)
        }.getOrDefault(false)
    }

    private fun isEnabled(): Boolean {
        return runCatching {
            HookEntry.instance?.prefs?.getBoolean(
                RootConstants.KEY_HOOK_ISLAND_DISABLE_WIDTH_LIMIT,
                RootConstants.DEFAULT_HOOK_ISLAND_DISABLE_WIDTH_LIMIT
            ) == true
        }.getOrDefault(false)
    }

    private class ScreenWidthAccessor(
        classLoader: ClassLoader,
        calculationMethod: Method? = null
    ) {
        private val screenWidthMethod: Method?
        private val screenWidthOwner: Any?
        private val calculationScreenWidthMethod: Method?
        private val helperContextField: Field?
        @Volatile
        private var cachedDisplayWidthPx = -1
        @Volatile
        private var cachedWidth: Float? = null
        @Volatile
        private var cachedHelper: Any? = null
        @Volatile
        private var cachedHelperContext: Context? = null

        init {
            val utilsClass = runCatching {
                classLoader.loadClass(DYNAMIC_ISLAND_UTILS_CLASS)
            }.getOrNull()
            val candidateMethod = utilsClass?.methods?.firstOrNull {
                it.name == "getScreenWidthOld" &&
                        it.parameterTypes.size == 1 &&
                        Context::class.java.isAssignableFrom(it.parameterTypes[0])
            }
            candidateMethod?.isAccessible = true
            screenWidthMethod = candidateMethod
            screenWidthOwner = if (candidateMethod != null &&
                !Modifier.isStatic(candidateMethod.modifiers)
            ) {
                runCatching {
                    utilsClass.getDeclaredField("INSTANCE").apply {
                        isAccessible = true
                    }.get(null)
                }.getOrNull()
            } else {
                null
            }

            val paramsClass = calculationMethod?.parameterTypes?.firstOrNull()
            calculationScreenWidthMethod = paramsClass?.methods?.firstOrNull {
                it.name == "getScreenWidth" && it.parameterTypes.isEmpty()
            }?.apply { isAccessible = true }

            helperContextField = calculationMethod?.declaringClass?.declaredFields?.firstOrNull {
                Context::class.java.isAssignableFrom(it.type)
            }?.apply { isAccessible = true }
        }

        fun screenWidth(context: Context): Float? {
            val displayWidthPx = context.resources.displayMetrics.widthPixels
            val previousWidth = cachedWidth
            if (displayWidthPx > 0 && displayWidthPx == cachedDisplayWidthPx && previousWidth != null) {
                return previousWidth
            }

            val reflectedWidth = runCatching {
                screenWidthMethod?.invoke(screenWidthOwner, context) as? Number
            }.getOrNull()?.toFloat()
            val width = if (reflectedWidth != null && reflectedWidth > 0f) {
                reflectedWidth
            } else {
                displayWidthPx.toFloat().takeIf { it > 0f }
            }
            cachedDisplayWidthPx = displayWidthPx
            cachedWidth = width
            return width
        }

        private fun contextOf(helper: Any): Context? {
            if (cachedHelper === helper) return cachedHelperContext
            val context = runCatching {
                helperContextField?.get(helper) as? Context
            }.getOrNull()
            cachedHelper = helper
            cachedHelperContext = context
            return context
        }

        fun screenWidth(params: Any, helper: Any): Float? {
            contextOf(helper)?.let { return screenWidth(it) }

            val paramsWidth = runCatching {
                calculationScreenWidthMethod?.invoke(params) as? Number
            }.getOrNull()?.toFloat()
            if (paramsWidth != null && paramsWidth > 0f) return paramsWidth
            return null
        }
    }

    private class GetMaxWidthHook(
        private val screenWidthAccessor: ScreenWidthAccessor
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (!isEnabled()) return chain.proceed()
            val view = chain.thisObject as? View ?: return chain.proceed()
            val width = screenWidthAccessor.screenWidth(view.context)
                ?.takeIf { it > 0f }
                ?: return chain.proceed()
            return width
        }
    }

    private class CalculateMaxWidthWithSmallHook(
        private val screenWidthAccessor: ScreenWidthAccessor
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (!isEnabled()) return chain.proceed()
            val params = chain.args.getOrNull(0) ?: return chain.proceed()
            val width = screenWidthAccessor.screenWidth(params, chain.thisObject)
                ?.takeIf { it > 0f }
                ?: return chain.proceed()
            return width
        }
    }
}
