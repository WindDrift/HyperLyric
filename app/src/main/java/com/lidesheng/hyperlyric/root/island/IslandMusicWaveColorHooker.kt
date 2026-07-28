package com.lidesheng.hyperlyric.root.island

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.lidesheng.hyperlyric.common.RootConstants
import com.lidesheng.hyperlyric.common.media.MediaMetadataHelper
import com.lidesheng.hyperlyric.root.HookEntry
import com.lidesheng.hyperlyric.root.SystemUiEnhancementGate
import com.lidesheng.hyperlyric.root.island.renderer.BaseIslandRenderer
import com.lidesheng.hyperlyric.root.mediacard.MediaArtworkSampler
import com.lidesheng.hyperlyric.root.utils.CoverColorHelper
import com.lidesheng.hyperlyric.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal object IslandMusicWaveColorHooker {
    private const val TAG = "IslandMusicWaveColorHooker"
    private const val ICON_HOLDER_CLASS =
        "miui.systemui.dynamicisland.module.IslandIconViewHolder"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val trackedLottieViews = WeakHashMap<View, Boolean>()
    private val trackedHolders = WeakHashMap<Any, Boolean>()
    private val nativeColorsByHolder = WeakHashMap<Any, WaveColors>()
    private val colorRequest = AtomicInteger()

    @Volatile
    private var colorExecutor: ExecutorService = newColorExecutor()

    @Volatile
    private var module: XposedModule? = null

    @Volatile
    private var colorAccessor: ColorAccessor? = null

    @Volatile
    private var desiredColors: WaveColors? = null

    @Volatile
    private var desiredToken: String? = null

    @Volatile
    private var pendingToken: String? = null

    @Volatile
    private var staticNativeColors: WaveColors? = null

    @Volatile
    private var overrideApplied = false

    private val prefs: SharedPreferences?
        get() = (module as? HookEntry)?.prefs

    fun hook(xposedModule: XposedModule, classLoader: ClassLoader) {
        module = xposedModule
        if (colorExecutor.isShutdown) colorExecutor = newColorExecutor()
        if (!hookedClassLoaders.add(classLoader)) return

        try {
            val holderClass = classLoader.loadClass(ICON_HOLDER_CLASS)
            colorAccessor = ColorAccessor(
                topField = holderClass.getDeclaredField("gradientTopColor").apply {
                    isAccessible = true
                },
                bottomField = holderClass.getDeclaredField("gradientBottomColor").apply {
                    isAccessible = true
                }
            )
            val dataField = holderClass.declaredFields.firstOrNull {
                it.name == "data"
            }?.apply {
                isAccessible = true
            }
            val lottieViewField = holderClass.getDeclaredField("lottieView").apply {
                isAccessible = true
            }

            val setLottieColorMethod = holderClass.declaredMethods.firstOrNull {
                it.name == "setLottieColor" &&
                        it.parameterTypes.contentEquals(arrayOf(Bitmap::class.java))
            }
            if (setLottieColorMethod != null) {
                setLottieColorMethod.isAccessible = true
                xposedModule.deoptimize(setLottieColorMethod)
                xposedModule.hook(setLottieColorMethod).intercept(
                    SetLottieColorHook(dataField, lottieViewField)
                )
            } else {
                HookLogger.w(TAG, "音频律动原生取色接口不可用: target=setLottieColor")
            }

            val picInfoField = holderClass.getDeclaredField("picInfo").apply {
                isAccessible = true
            }
            val registerCallbackMethod = holderClass.declaredMethods.firstOrNull {
                it.name == "registerLottieCallback" && it.parameterTypes.isEmpty()
            }
            if (registerCallbackMethod != null) {
                registerCallbackMethod.isAccessible = true
                xposedModule.deoptimize(registerCallbackMethod)
                xposedModule.hook(registerCallbackMethod).intercept(
                    RegisterLottieCallbackHook(dataField, lottieViewField, picInfoField)
                )
            } else {
                HookLogger.w(TAG, "音频律动刷新接口不可用: target=registerLottieCallback")
            }

            HookLogger.i(TAG, "音频律动封面色 Hook 已初始化")
        } catch (e: ClassNotFoundException) {
            hookedClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "当前插件不支持音频律动封面色: reason=${e.message}")
        } catch (e: NoSuchFieldException) {
            hookedClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "音频律动颜色字段不可用: reason=${e.message}")
        } catch (e: Throwable) {
            hookedClassLoaders.remove(classLoader)
            HookLogger.e(TAG, "初始化音频律动封面色 Hook 失败", e)
        }
    }

    fun refresh() {
        runOnMain {
            val sharedPrefs = prefs
            if (sharedPrefs == null || !isEnabled(sharedPrefs)) {
                restoreNativeColors()
            } else {
                val colorSession = CoverColorHelper.currentSession()
                val artworkRequest = CoverColorHelper.currentArtworkRequest()
                val synced = when {
                    artworkRequest != null -> applyCachedColors(sharedPrefs, artworkRequest)
                    colorSession != null -> applyCachedColors(sharedPrefs, colorSession)
                    else -> false
                }
                if (!synced) {
                    val tokenMatches = when {
                        artworkRequest != null ->
                            isTokenForArtwork(desiredToken, artworkRequest)
                        colorSession != null ->
                            isTokenForSession(desiredToken, colorSession)
                        else -> false
                    }
                    if (!tokenMatches) {
                        if (artworkRequest != null &&
                            isTokenForArtwork(pendingToken, artworkRequest)
                        ) {
                            restoreAppliedOverridePreservingPending()
                        } else {
                            restoreNativeColors()
                        }
                    } else {
                        desiredColors?.let(::applyColorsToTrackedHolders)
                        invalidateTrackedLottieViews()
                    }
                }
            }
        }
    }

    fun cleanup() {
        colorRequest.incrementAndGet()
        colorExecutor.shutdown()
        runOnMain {
            restoreNativeColors()
            synchronized(trackedLottieViews) {
                trackedLottieViews.clear()
            }
            synchronized(trackedHolders) {
                trackedHolders.clear()
            }
            staticNativeColors = null
            synchronized(nativeColorsByHolder) {
                nativeColorsByHolder.clear()
            }
            colorAccessor = null
        }
    }

    private fun scheduleOptimizedColors(
        bitmap: Bitmap,
        sharedPrefs: SharedPreferences,
        artworkRequest: CoverColorHelper.ArtworkRequest,
        holder: Any
    ) {
        if (!CoverColorHelper.isCurrentArtwork(artworkRequest)) return
        val useGradient = sharedPrefs.getBoolean(
            RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_GRADIENT,
            RootConstants.DEFAULT_HOOK_ISLAND_MUSIC_WAVE_GRADIENT
        )
        val token = tokenForArtwork(artworkRequest, useGradient)
        if (desiredToken != null && desiredToken != token) {
            restoreNativeColors()
        }
        if (desiredToken == token) {
            desiredColors?.let { applyOptimizedColors(it, token, holder) }
            return
        }
        if (pendingToken == token) return
        CoverColorHelper.getCachedColors(useGradient = true, request = artworkRequest)
            ?.second
            ?.toList()
            ?.let { colorsFromPalette(it, useGradient) }
            ?.let {
                applyOptimizedColors(it, token, holder)
                return
            }

        val sample = MediaArtworkSampler.sample(bitmap) ?: return

        if (desiredToken == token) {
            sample.recycle()
            desiredColors?.let { applyOptimizedColors(it, token, holder) }
            return
        }
        if (pendingToken == token) {
            sample.recycle()
            return
        }

        val request = colorRequest.incrementAndGet()
        pendingToken = token
        runCatching {
            colorExecutor.execute {
                if (colorRequest.get() != request || pendingToken != token) {
                    sample.recycle()
                    return@execute
                }
                val colors = try {
                    val palette = CoverColorHelper.extractColors(
                        bitmap = sample,
                        useGradient = true,
                        request = artworkRequest
                    )
                    palette?.let {
                        colorsFromPalette(
                            it.second.toList(),
                            useGradient
                        )
                    }
                } catch (e: Throwable) {
                    HookLogger.e(TAG, "提取音频律动颜色失败", e)
                    null
                } finally {
                    sample.recycle()
                }

                runOnMain {
                    if (colorRequest.get() != request || pendingToken != token) return@runOnMain
                    pendingToken = null
                    val currentPrefs = prefs
                    if (colors == null || currentPrefs == null || !isEnabled(currentPrefs)) {
                        return@runOnMain
                    }
                    if (!CoverColorHelper.isCurrentArtwork(artworkRequest)) {
                        HookLogger.d(TAG, "忽略已切歌的音频律动取色结果")
                        return@runOnMain
                    }
                    val currentGradient = currentPrefs.getBoolean(
                        RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_GRADIENT,
                        RootConstants.DEFAULT_HOOK_ISLAND_MUSIC_WAVE_GRADIENT
                    )
                    if (currentGradient != useGradient) return@runOnMain
                    applyOptimizedColors(colors, token, holder)
                }
            }
        }.onFailure { e ->
            sample.recycle()
            if (colorRequest.get() == request && pendingToken == token) pendingToken = null
            HookLogger.e(TAG, "调度音频律动取色任务失败", e)
        }
    }

    private fun applyOptimizedColors(
        colors: WaveColors,
        token: String,
        holder: Any? = null
    ) {
        desiredColors = colors
        desiredToken = token
        if (pendingToken == token) pendingToken = null
        val accessor = colorAccessor ?: return
        if (!overrideApplied) {
            val snapshotHolder = holder ?: synchronized(trackedHolders) {
                trackedHolders.keys.firstOrNull()
            }
            try {
                rememberNativeColors(snapshotHolder, accessor.read(snapshotHolder))
            } catch (_: Exception) {
            }
        }
        applyColorsToTrackedHolders(colors, holder)
        overrideApplied = true
        invalidateTrackedLottieViews()
    }

    private fun restoreNativeColors(rootView: ViewGroup? = null) {
        colorRequest.incrementAndGet()
        desiredColors = null
        desiredToken = null
        pendingToken = null
        if (overrideApplied) {
            restoreTrackedNativeColors()
            overrideApplied = false
        }
        invalidateTrackedLottieViews()
        rootView?.let(::invalidateLottieViews)
    }

    private fun restoreAppliedOverridePreservingPending() {
        desiredColors = null
        desiredToken = null
        if (overrideApplied) {
            restoreTrackedNativeColors()
            overrideApplied = false
        }
        invalidateTrackedLottieViews()
    }

    private fun isEnabled(sharedPrefs: SharedPreferences): Boolean {
        return SystemUiEnhancementGate.isEnabled() && sharedPrefs.getBoolean(
            RootConstants.KEY_HOOK_ISLAND_RIGHT_ICON,
            RootConstants.DEFAULT_HOOK_ISLAND_RIGHT_ICON
        ) && sharedPrefs.getBoolean(
            RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_COLOR,
            RootConstants.DEFAULT_HOOK_ISLAND_MUSIC_WAVE_COLOR
        )
    }

    private fun usesAlbumColors(sharedPrefs: SharedPreferences): Boolean {
        val lyricColorEnabled = sharedPrefs.getBoolean(
            RootConstants.KEY_HOOK_EXTRACT_COVER_TEXT_COLOR,
            RootConstants.DEFAULT_HOOK_EXTRACT_COVER_TEXT_COLOR
        )
        val progressColorEnabled = sharedPrefs.getBoolean(
            RootConstants.KEY_HOOK_ISLAND_PROGRESS_GLOW,
            RootConstants.DEFAULT_HOOK_ISLAND_PROGRESS_GLOW
        ) && sharedPrefs.getBoolean(
            RootConstants.KEY_HOOK_ISLAND_GLOW_EXTRACT_COLOR,
            RootConstants.DEFAULT_HOOK_ISLAND_GLOW_EXTRACT_COLOR
        )
        return lyricColorEnabled || progressColorEnabled
    }

    private fun refreshAlbumColorConsumers(
        packageName: String?,
        bitmap: Bitmap,
        artworkRequest: CoverColorHelper.ArtworkRequest,
        shouldRefresh: Boolean
    ) {
        if (!shouldRefresh || !CoverColorHelper.isCurrentArtwork(artworkRequest)) return
        val targetPackage = packageName ?: return
        HookLogger.d(TAG, "原生封面取色完成，刷新当前歌词岛动态颜色")
        BaseIslandRenderer.refreshAlbumColors(
            targetPackage,
            bitmap,
            artworkRequest
        )
    }

    private fun colorsFromPalette(colors: List<Int>, useGradient: Boolean): WaveColors? {
        val primary = colors.firstOrNull() ?: return null
        val secondary = if (useGradient) colors.getOrNull(1) ?: primary else primary
        return WaveColors(
            top = primary,
            bottom = secondary
        )
    }

    private fun applyCachedColors(
        sharedPrefs: SharedPreferences,
        colorSession: CoverColorHelper.ColorSession
    ): Boolean {
        if (!CoverColorHelper.isCurrentSession(colorSession)) return false
        val useGradient = sharedPrefs.getBoolean(
            RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_GRADIENT,
            RootConstants.DEFAULT_HOOK_ISLAND_MUSIC_WAVE_GRADIENT
        )
        val colors = CoverColorHelper
            .getCachedColors(useGradient = true, session = colorSession)
            ?.second
            ?.toList()
            ?.let { colorsFromPalette(it, useGradient) }
            ?: return false
        colorRequest.incrementAndGet()
        pendingToken = null
        applyOptimizedColors(colors, tokenForSession(colorSession, useGradient))
        return true
    }

    private fun applyCachedColors(
        sharedPrefs: SharedPreferences,
        artworkRequest: CoverColorHelper.ArtworkRequest
    ): Boolean {
        if (!CoverColorHelper.isCurrentArtwork(artworkRequest)) return false
        val useGradient = sharedPrefs.getBoolean(
            RootConstants.KEY_HOOK_ISLAND_MUSIC_WAVE_GRADIENT,
            RootConstants.DEFAULT_HOOK_ISLAND_MUSIC_WAVE_GRADIENT
        )
        val colors = CoverColorHelper
            .getCachedColors(useGradient = true, request = artworkRequest)
            ?.second
            ?.toList()
            ?.let { colorsFromPalette(it, useGradient) }
            ?: return false
        colorRequest.incrementAndGet()
        pendingToken = null
        applyOptimizedColors(colors, tokenForArtwork(artworkRequest, useGradient))
        return true
    }

    private fun tokenForSession(
        colorSession: CoverColorHelper.ColorSession,
        useGradient: Boolean
    ): String {
        return "${sessionTokenPrefix(colorSession)}cached:$useGradient"
    }

    private fun tokenForArtwork(
        artworkRequest: CoverColorHelper.ArtworkRequest,
        useGradient: Boolean
    ): String {
        return "${sessionTokenPrefix(artworkRequest.colorSession)}" +
                "artwork:${artworkRequest.revision}:$useGradient"
    }

    private fun sessionTokenPrefix(
        colorSession: CoverColorHelper.ColorSession
    ): String {
        return "${colorSession.revision}:${colorSession.mediaKey}:"
    }

    private fun isTokenForSession(
        token: String?,
        colorSession: CoverColorHelper.ColorSession
    ): Boolean {
        return token?.startsWith(sessionTokenPrefix(colorSession)) == true
    }

    private fun isTokenForArtwork(
        token: String?,
        artworkRequest: CoverColorHelper.ArtworkRequest
    ): Boolean {
        val prefix = "${sessionTokenPrefix(artworkRequest.colorSession)}" +
                "artwork:${artworkRequest.revision}:"
        return token?.startsWith(prefix) == true
    }

    private fun rememberNativeColors(holder: Any?, colors: WaveColors) {
        val accessor = colorAccessor ?: return
        if (accessor.usesStaticFields) {
            staticNativeColors = colors
        } else if (holder != null) {
            synchronized(nativeColorsByHolder) {
                nativeColorsByHolder[holder] = colors
            }
        }
    }

    private fun restoreTrackedNativeColors() {
        val accessor = colorAccessor ?: return
        if (accessor.usesStaticFields) {
            staticNativeColors?.let { accessor.write(it) }
            return
        }
        val snapshots = synchronized(nativeColorsByHolder) {
            nativeColorsByHolder.entries.map { it.key to it.value }
        }
        snapshots.forEach { (holder, colors) ->
            accessor.write(colors, holder)
        }
    }

    private fun trackHolder(holder: Any, lottieView: View?) {
        synchronized(trackedHolders) {
            trackedHolders[holder] = true
        }
        if (lottieView != null) {
            synchronized(trackedLottieViews) {
                trackedLottieViews[lottieView] = true
            }
        }
    }

    private fun applyColorsToTrackedHolders(
        colors: WaveColors,
        immediateHolder: Any? = null
    ) {
        val accessor = colorAccessor ?: return
        val holders = synchronized(trackedHolders) {
            trackedHolders.keys.toList()
        }
        var wroteColor = false
        if (immediateHolder != null) {
            accessor.write(colors, immediateHolder)
            wroteColor = true
        }
        holders.forEach { holder ->
            if (holder !== immediateHolder) {
                accessor.write(colors, holder)
                wroteColor = true
            }
        }
        if (!wroteColor) {
            // 静态字段不需要 holder；实例字段会在 holder 注册后补写。
            accessor.write(colors)
        }
    }

    private fun newColorExecutor(): ExecutorService {
        return Executors.newSingleThreadExecutor { task ->
            Thread(task, "HyperLyric-MusicWaveColor").apply { isDaemon = true }
        }
    }

    private fun invalidateTrackedLottieViews() {
        val views = synchronized(trackedLottieViews) {
            trackedLottieViews.keys.toList()
        }
        views.forEach(View::invalidate)
    }

    private fun invalidateLottieViews(view: View) {
        if (view.javaClass.name == "com.airbnb.lottie.LottieAnimationView") {
            view.invalidate()
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                invalidateLottieViews(view.getChildAt(index))
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private class SetLottieColorHook(
        private val dataField: Field?,
        private val lottieViewField: Field
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            runCatching {
                val bitmap = chain.args.firstOrNull() as? Bitmap ?: return@runCatching
                val holder = chain.thisObject ?: return@runCatching
                val lyricPackageName = holder
                    .let { dataField?.get(it) }
                    ?.let(IslandProbeUtils::extractMediaIslandInfo)
                    ?.takeIf(IslandPresentationCoordinator::isCurrentLyricOwner)
                    ?.packageName

                val sharedPrefs = prefs ?: return@runCatching
                if (lyricPackageName == null) {
                    return@runCatching
                }
                val lottieView = lottieViewField.get(holder) as? View
                trackHolder(holder, lottieView)
                colorAccessor?.read(holder)?.let {
                    rememberNativeColors(holder, it)
                }
                val context = lottieView?.context
                val mediaInfo = context?.let {
                    MediaMetadataHelper.getMediaInfo(
                        it,
                        lyricPackageName,
                        HookLogger
                    )
                }
                val artwork = mediaInfo?.albumArt?.takeUnless { it.isRecycled }
                val artworkRequest = if (mediaInfo != null && artwork != null) {
                    CoverColorHelper.resolveArtworkRequest(
                        packageName = lyricPackageName,
                        title = mediaInfo.title,
                        artist = mediaInfo.artist,
                        bitmap = artwork
                    )
                } else {
                    null
                }
                val shouldRefreshAlbumColors = usesAlbumColors(sharedPrefs)
                if (!isEnabled(sharedPrefs)) {
                    runOnMain {
                        restoreNativeColors()
                        if (artworkRequest != null && artwork != null) {
                            refreshAlbumColorConsumers(
                                lyricPackageName,
                                artwork,
                                artworkRequest,
                                shouldRefreshAlbumColors
                            )
                        }
                    }
                    return@runCatching
                }

                runOnMain {
                    if (artwork != null &&
                        artworkRequest != null &&
                        CoverColorHelper.isCurrentArtwork(artworkRequest)
                    ) {
                        runCatching {
                            scheduleOptimizedColors(
                                artwork,
                                sharedPrefs,
                                artworkRequest,
                                holder
                            )
                        }.onFailure { e ->
                            HookLogger.e(TAG, "应用音频律动颜色失败", e)
                        }
                        refreshAlbumColorConsumers(
                            lyricPackageName,
                            artwork,
                            artworkRequest,
                            shouldRefreshAlbumColors
                        )
                    } else {
                        val activeSession = CoverColorHelper.currentSession(lyricPackageName)
                        val activeArtworkRequest = CoverColorHelper.currentArtworkRequest()
                        val restoredFromCache = when {
                            activeArtworkRequest != null ->
                                applyCachedColors(sharedPrefs, activeArtworkRequest)
                            activeSession != null ->
                                applyCachedColors(sharedPrefs, activeSession)
                            else -> false
                        }
                        if (!restoredFromCache) {
                            if (activeArtworkRequest != null &&
                                isTokenForArtwork(pendingToken, activeArtworkRequest)
                            ) {
                                restoreAppliedOverridePreservingPending()
                            } else {
                                restoreNativeColors()
                            }
                        }
                        HookLogger.d(TAG, "忽略非当前歌曲的音频律动封面回调")
                    }
                }
            }.onFailure { e ->
                HookLogger.e(TAG, "读取原生音频律动颜色失败", e)
            }
            return result
        }
    }

    private class RegisterLottieCallbackHook(
        private val dataField: Field?,
        private val lottieViewField: Field,
        private val picInfoField: Field
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            runCatching {
                val holder = chain.thisObject ?: return@runCatching
                if (!isMusicWave(picInfoField.get(holder))) return@runCatching

                val lottieView = lottieViewField.get(holder) as? View ?: return@runCatching
                val sharedPrefs = prefs
                val isCurrentLyricHolder = dataField
                    ?.get(holder)
                    ?.let(IslandProbeUtils::extractMediaIslandInfo)
                    ?.let(IslandPresentationCoordinator::isCurrentLyricOwner) == true
                if (!isCurrentLyricHolder) return@runCatching
                trackHolder(holder, lottieView)
                if (sharedPrefs != null && isEnabled(sharedPrefs)) {
                    val colorSession = CoverColorHelper.currentSession()
                    if (colorSession != null &&
                        isTokenForSession(desiredToken, colorSession)
                    ) {
                        desiredColors?.let {
                            applyColorsToTrackedHolders(it, holder)
                        }
                    }
                }
                lottieView.invalidate()
            }.onFailure { e ->
                HookLogger.e(TAG, "刷新音频律动动画失败", e)
            }
            return result
        }

        private fun isMusicWave(picInfo: Any?): Boolean {
            val pic = picInfo?.javaClass?.methods
                ?.firstOrNull { it.name == "getPic" && it.parameterTypes.isEmpty() }
                ?.invoke(picInfo) as? String
            return pic == "musicWave" || pic == "musicPause"
        }
    }

    private data class WaveColors(
        val top: Int,
        val bottom: Int
    )

    private data class ColorAccessor(
        val topField: Field,
        val bottomField: Field
    ) {
        val usesStaticFields: Boolean
            get() = java.lang.reflect.Modifier.isStatic(topField.modifiers) &&
                    java.lang.reflect.Modifier.isStatic(bottomField.modifiers)

        fun read(holder: Any? = null): WaveColors = WaveColors(
            top = getInt(topField, holder),
            bottom = getInt(bottomField, holder)
        )

        fun write(colors: WaveColors, holder: Any? = null) {
            setInt(topField, colors.top, holder)
            setInt(bottomField, colors.bottom, holder)
        }

        private fun getInt(field: Field, holder: Any?): Int {
            return if (java.lang.reflect.Modifier.isStatic(field.modifiers)) {
                field.getInt(null)
            } else {
                field.getInt(holder)
            }
        }

        private fun setInt(field: Field, value: Int, holder: Any?) {
            if (java.lang.reflect.Modifier.isStatic(field.modifiers)) {
                field.setInt(null, value)
            } else if (holder != null) {
                field.setInt(holder, value)
            }
        }
    }
}
