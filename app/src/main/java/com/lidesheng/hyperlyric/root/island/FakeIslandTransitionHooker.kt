package com.lidesheng.hyperlyric.root.island

import android.view.View
import android.view.ViewGroup
import com.lidesheng.hyperlyric.root.island.IslandTextHookerSupport.TAG
import com.lidesheng.hyperlyric.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker

internal object FakeIslandTransitionHooker {

    class AppReturnToBigIslandHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val fakeView = runCatching {
                if (chain.args.getOrNull(1) != true) return@runCatching null
                val contentView = chain.args.firstOrNull() ?: return@runCatching null
                IslandTextHookerSupport.callNoArgMethodResult(
                    contentView,
                    "getFakeView"
                ) as? ViewGroup
            }.onFailure { error ->
                HookLogger.e(TAG, "应用返回前获取 fake view 失败", error)
            }.getOrNull()

            fakeView?.let { view ->
                IslandTextHookerSupport.prepareFrozenFakeIslandForTransition(
                    view,
                    "before delegate.fakeViewToBigIsland"
                )
            }
            return chain.proceed()
        }
    }

    class VisibilityHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val visibility = (chain.args.getOrNull(0) as? Number)?.toInt()
            val result = chain.proceed()

            if (visibility == View.INVISIBLE) {
                runCatching {
                    val fakeView = chain.thisObject as? ViewGroup ?: return@runCatching
                    IslandTextHookerSupport.restoreRealIslandAfterFakeTransition(
                        fakeView,
                        "after fake.setVisibility(INVISIBLE)"
                    )
                }.onFailure { e ->
                    HookLogger.e(TAG, "过渡视图隐藏后恢复真实岛失败", e)
                }
            }

            return result
        }
    }
}
