package com.lidesheng.hyperlyric.root.island

import com.lidesheng.hyperlyric.root.island.IslandTextHookerSupport.TAG
import com.lidesheng.hyperlyric.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker

internal object IslandModuleRestoreHooker {

    /**
     * The first big-island build is asynchronous on newer SystemUI versions.
     * bindData is the first synchronous point after a module holder has been
     * created, added to the template, initialized, and bound.
     */
    class AdapterBindDataHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()

            runCatching {
                if (!IslandProbeUtils.isSuperIslandEnabled()) return@runCatching
                val moduleType = chain.args.getOrNull(0) as? String
                val data = chain.args.getOrNull(1)
                val mediaInfo = IslandProbeUtils.extractMediaIslandInfo(data) ?: return@runCatching
                if (!IslandTextHookerSupport.isCurrentLyricIsland(mediaInfo)) return@runCatching

                val holderRoot = IslandProbeUtils.getHolderRootView(
                    IslandProbeUtils.getHolder(chain.thisObject, moduleType)
                ) ?: return@runCatching
                if (!IslandProbeUtils.isRealBigIslandModuleArea(holderRoot)) return@runCatching
                if (!IslandTextHookerSupport.shouldRenderInjectedIsland()) {
                    IslandHostFacade.clearInjectedViews(holderRoot)
                    return@runCatching
                }

                IslandTextHookerSupport.injectBoundAdapterModule(
                    holderRoot,
                    moduleType,
                    "adapter.bindData"
                )
            }.onFailure { e ->
                HookLogger.e(TAG, "adapter.bindData 后准备歌词视图失败", e)
            }

            return result
        }
    }

    class AdapterUpdateViewHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()

            runCatching {
                if (!IslandProbeUtils.isSuperIslandEnabled()) return@runCatching
                val moduleType = chain.args.getOrNull(0) as? String
                val data = chain.args.getOrNull(2)
                val mediaInfo = IslandProbeUtils.extractMediaIslandInfo(data) ?: return@runCatching

                if (!IslandTextHookerSupport.isCurrentLyricIsland(mediaInfo)) return@runCatching
                val holderRoot = IslandProbeUtils.getHolderRootView(
                    IslandProbeUtils.getHolder(chain.thisObject, moduleType)
                )
                if (!IslandTextHookerSupport.shouldRenderInjectedIsland()) {
                    holderRoot?.let { IslandHostFacade.clearInjectedViews(it) }
                    return@runCatching
                }

                IslandTextHookerSupport.restoreAdapterModule(
                    chain.thisObject,
                    moduleType,
                    "adapter.updateView"
                )
            }.onFailure { e ->
                HookLogger.e(TAG, "adapter.updateView 后恢复歌词视图失败", e)
            }

            return result
        }
    }

    class UpdateModuleViewHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()

            runCatching {
                if (!IslandProbeUtils.isSuperIslandEnabled()) return@runCatching
                val moduleType = chain.args.getOrNull(0) as? String
                val data = chain.args.getOrNull(2)
                val mediaInfo = IslandProbeUtils.extractMediaIslandInfo(data) ?: return@runCatching

                if (!IslandTextHookerSupport.isCurrentLyricIsland(mediaInfo)) return@runCatching
                val adapter =
                    IslandTextHookerSupport.findFieldValue(chain.thisObject, "islandAdapter")
                val holderRoot = IslandProbeUtils.getHolderRootView(
                    IslandProbeUtils.getHolder(adapter, moduleType)
                )
                if (!IslandTextHookerSupport.shouldRenderInjectedIsland()) {
                    holderRoot?.let { IslandHostFacade.clearInjectedViews(it) }
                    return@runCatching
                }

                IslandTextHookerSupport.restoreAdapterModule(
                    adapter,
                    moduleType,
                    "updateModuleView"
                )
            }.onFailure { e ->
                HookLogger.e(TAG, "updateModuleView 后恢复歌词视图失败", e)
            }

            return result
        }
    }
}
