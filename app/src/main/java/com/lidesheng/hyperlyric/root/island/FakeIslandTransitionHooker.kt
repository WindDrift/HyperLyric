package com.lidesheng.hyperlyric.root.island

import android.view.View
import android.view.ViewGroup
import com.lidesheng.hyperlyric.root.LyriconDataBridge
import com.lidesheng.hyperlyric.root.island.IslandTextHookerSupport.TAG
import com.lidesheng.hyperlyric.root.mediacard.island.IslandExpandedMediaAmbientFlowHooker
import com.lidesheng.hyperlyric.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker

internal object FakeIslandTransitionHooker {
    class AppReturnToBigIslandHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            var sourceRealRoot: ViewGroup? = null
            val fakeView = runCatching {
                if (chain.args.getOrNull(1) != true) return@runCatching null
                val contentView = chain.args.firstOrNull() ?: return@runCatching null
                sourceRealRoot = contentView as? ViewGroup
                IslandTextHookerSupport.callNoArgMethodResult(
                    contentView,
                    "getFakeView"
                ) as? ViewGroup
            }.onFailure { error ->
                HookLogger.e(TAG, "应用返回前获取 fake view 失败", error)
            }.getOrNull()

            fakeView?.let { view ->
                val data = IslandTextHookerSupport.extractIslandDataFromContentOrReal(view)
                val realRoot = IslandTextHookerSupport.callNoArgMethodResult(
                    view,
                    "getRealView"
                ) as? ViewGroup ?: sourceRealRoot
                IslandPresentationCoordinator.onFakeSnapshotRequested(
                    fakeOwner = view,
                    snapshotRoot = view,
                    owner = IslandPresentationCoordinator.ownerEvidence(data),
                    realRoot = realRoot,
                    position = LyriconDataBridge.currentPosition
                )
            }
            return chain.proceed()
        }
    }

    class FreeformFakeViewCallbackHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            runCatching {
                val fakeView = chain.args.firstOrNull() as? ViewGroup
                    ?: return@runCatching
                val fakeBigIsland = IslandTextHookerSupport.callNoArgMethodResult(
                    fakeView,
                    "getFakeBigIsland"
                ) as? ViewGroup ?: return@runCatching
                if (
                    IslandTextHookerSupport.callNoArgMethodResult(
                        fakeView,
                        "getClosingAppFromFreeform"
                    ) == true
                ) {
                    IslandExpandedMediaAmbientFlowHooker.resetMiniWindowBackgroundTransform()
                }
                val data = IslandTextHookerSupport.extractIslandDataFromContentOrReal(fakeView)
                val realRoot = IslandTextHookerSupport.callNoArgMethodResult(
                    fakeView,
                    "getRealView"
                ) as? ViewGroup
                IslandPresentationCoordinator.onFakeSnapshotRequested(
                    fakeOwner = fakeView,
                    snapshotRoot = fakeBigIsland,
                    owner = IslandPresentationCoordinator.ownerEvidence(data),
                    realRoot = realRoot,
                    position = LyriconDataBridge.currentPosition
                )
            }.onFailure { error ->
                HookLogger.e(TAG, "自由小窗快照回调前冻结歌词失败", error)
            }
            return chain.proceed()
        }
    }

    class VisibilityHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val visibility = (chain.args.getOrNull(0) as? Number)?.toInt()
            val fakeView = chain.thisObject as? ViewGroup
            val realView = if (visibility == View.INVISIBLE && fakeView != null) {
                IslandTextHookerSupport.callNoArgMethodResult(
                    fakeView,
                    "getRealView"
                ) as? ViewGroup
            } else {
                null
            }

            if (fakeView != null && realView != null) {
                runCatching {
                    IslandPresentationCoordinator.onFakeTransitionHandoff(
                        fakeOwner = fakeView,
                        realRoot = realView
                    )
                }.onFailure { error ->
                    HookLogger.e(TAG, "过渡视图隐藏前准备真实岛冻结帧失败", error)
                }
            }

            val result = chain.proceed()

            if (fakeView != null && realView != null) {
                realView.postOnAnimation {
                    runCatching {
                        IslandPresentationCoordinator.onFakeTransitionEnded(
                            fakeOwner = fakeView,
                            realRoot = realView
                        )
                    }.onFailure { error ->
                        HookLogger.e(TAG, "过渡视图隐藏后恢复真实岛失败", error)
                    }
                }
            }

            return result
        }
    }
}
