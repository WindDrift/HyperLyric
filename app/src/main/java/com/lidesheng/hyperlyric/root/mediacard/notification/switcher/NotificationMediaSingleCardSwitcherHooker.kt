package com.lidesheng.hyperlyric.root.mediacard.notification.switcher

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.lidesheng.hyperlyric.root.mediacard.MediaCardRuntimeConfig
import com.lidesheng.hyperlyric.root.mediacard.notification.NotificationMediaHostClasses
import com.lidesheng.hyperlyric.root.utils.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.LinkedHashMap
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Restores MIUI 14-style multi-session selection while retaining the HyperOS 3
 * single native media card.
 *
 * The hook intentionally does not create a second card or replace the native
 * layout. It mirrors MediaSortUtils into a small selection store and reuses
 * MiuiMediaViewControllerImpl.bindMediaData(MediaData) after a horizontal
 * swipe.
 */
internal object NotificationMediaSingleCardSwitcherHooker {
    private const val TAG = "NotificationMediaSingleCardSwitcher"
    private const val MIN_SWITCH_DISTANCE_DP = 48f
    private const val SWITCH_DISTANCE_FRACTION = 0.15f
    private const val DIRECTION_LOCK_RATIO = 1.2f
    private const val MEDIA_DATA_MANAGER =
        "com.android.systemui.media.controls.domain.pipeline.MediaDataManager"
    private const val MEDIA_DATA_MANAGER_LISTENER =
        "com.android.systemui.media.controls.domain.pipeline.MediaDataManager\$Listener"

    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val layoutStates = Collections.synchronizedMap(
        WeakHashMap<Any, ControllerState>()
    )
    private val viewStates = Collections.synchronizedMap(
        WeakHashMap<Any, ControllerState>()
    )
    private val playerStates = Collections.synchronizedMap(
        WeakHashMap<View, ControllerState>()
    )
    private val hookedTouchMethods = Collections.synchronizedSet(mutableSetOf<Method>())

    fun hook(xposedModule: XposedModule, classLoader: ClassLoader) {
        if (!hookedClassLoaders.add(classLoader)) return
        moduleForTouchHook = xposedModule

        val layoutClass = loadClass(classLoader, NotificationMediaHostClasses.LAYOUT_CONTROLLER)
        val viewControllerClass = loadClass(
            classLoader,
            NotificationMediaHostClasses.VIEW_CONTROLLER
        )
        if (layoutClass == null || viewControllerClass == null) {
            hookedClassLoaders.remove(classLoader)
            HookLogger.w(TAG, "跳过单卡片横滑 Hook: HyperOS 3 媒体类不可用")
            return
        }

        val attach = findMethod(viewControllerClass, "attach") { it.parameterCount == 1 }
        val detach = findMethod(viewControllerClass, "detach") { it.parameterCount == 0 }
        val bind = findMethod(viewControllerClass, "bindMediaData") {
            it.parameterCount == 1
        }
        var installed = 0

        layoutClass.declaredConstructors.forEach { constructor ->
            if (install(xposedModule, constructor, LayoutConstructorHook())) installed++
        }
        attach?.let {
            if (install(xposedModule, it, ViewControllerHook(Action.ATTACH))) installed++
        }
        detach?.let {
            if (install(xposedModule, it, ViewControllerHook(Action.DETACH))) installed++
        }
        bind?.let {
            if (install(xposedModule, it, ViewControllerHook(Action.BIND))) installed++
        }

        if (installed == 0 || attach == null || detach == null || bind == null) {
            hookedClassLoaders.remove(classLoader)
            HookLogger.w(
                TAG,
                "单卡片横滑 Hook 安装不完整: installed=$installed, " +
                    "attach=${attach != null}, detach=${detach != null}, bind=${bind != null}"
            )
        } else {
            HookLogger.i(TAG, "HyperOS 3 单卡片横滑 Hook 已初始化: methods=$installed")
        }
    }

    private fun registerLayoutController(controller: Any, classLoader: ClassLoader) {
        if (layoutStates.containsKey(controller)) return

        val mediaDataManager = readField(controller, "mediaDataManager")
        val viewController = readField(controller, "mediaViewController")
        val sortUtils = readField(controller, "mediaSorUtils")
            ?: readField(controller, "mediaSortUtils")
        if (mediaDataManager == null || viewController == null || sortUtils == null) {
            HookLogger.w(TAG, "跳过媒体列表注册: 原生字段缺失")
            return
        }

        val bindMethod = findMethod(viewController.javaClass, "bindMediaData") {
            it.parameterCount == 1
        }
        if (bindMethod == null) {
            HookLogger.w(TAG, "跳过媒体列表注册: bindMediaData 不可用")
            return
        }

        val state = ControllerState(
            layoutController = controller,
            viewController = viewController,
            mediaDataManager = mediaDataManager,
            sortUtils = sortUtils,
            bindMethod = bindMethod,
            accessor = ReflectedMediaDataAccessor()
        )
        layoutStates[controller] = state
        viewStates[viewController] = state

        runCatching {
            state.seedFromNativeSort()
            registerMediaDataListener(state, classLoader)
            HookLogger.d(TAG, "已接管多媒体数据: controller=${controller.javaClass.name}")
        }.onFailure { error ->
            layoutStates.remove(controller)
            viewStates.remove(viewController)
            HookLogger.e(TAG, "注册 MediaData.Listener 失败", error)
        }
    }

    private fun registerMediaDataListener(
        state: ControllerState,
        classLoader: ClassLoader
    ) {
        val listenerClass = resolveListenerClass(state.mediaDataManager, classLoader)
            ?: error("MediaDataManager.Listener unavailable")
        val addListener = findMethod(state.mediaDataManager.javaClass, "addListener") {
            it.parameterCount == 1 && isCompatibleListenerParameter(it.parameterTypes[0], listenerClass)
        } ?: error("MediaDataManager.addListener unavailable")

        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "onMediaDataLoaded" -> {
                    val data = args.getOrNull(2)
                    val key = args.getOrNull(0) as? String
                        ?: data?.let(state.accessor::notificationKey)
                    if (key != null && data != null) {
                        state.onMediaDataLoaded(
                            key = key,
                            oldKey = args.getOrNull(1) as? String,
                            data = data
                        )
                    }
                    null
                }

                "onMediaDataRemoved" -> {
                    (args.getOrNull(0) as? String)?.let(state::onMediaDataRemoved)
                    null
                }

                "toString" -> "$TAG.Listener"
                "hashCode" -> System.identityHashCode(state)
                "equals" -> false
                else -> defaultValue(method.returnType)
            }
        }
        val proxy = Proxy.newProxyInstance(
            listenerClass.classLoader ?: classLoader,
            arrayOf(listenerClass),
            handler
        )
        addListener.invoke(state.mediaDataManager, proxy)
        state.listenerProxy = proxy
    }

    private fun resolveListenerClass(manager: Any, classLoader: ClassLoader): Class<*>? {
        runCatching { classLoader.loadClass(MEDIA_DATA_MANAGER_LISTENER) }
            .getOrNull()
            ?.takeIf(Class<*>::isInterface)
            ?.let { return it }

        var current: Class<*>? = manager.javaClass
        while (current != null) {
            current.declaredClasses.firstOrNull { clazz ->
                clazz.isInterface && clazz.simpleName == "Listener" &&
                    clazz.declaredMethods.any { it.name == "onMediaDataLoaded" }
            }?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun isCompatibleListenerParameter(
        parameterClass: Class<*>,
        listenerClass: Class<*>
    ): Boolean {
        return parameterClass == listenerClass ||
            parameterClass.isAssignableFrom(listenerClass) ||
            listenerClass.isAssignableFrom(parameterClass)
    }

    private fun defaultValue(type: Class<*>): Any? {
        return when (type) {
            Boolean::class.javaPrimitiveType -> false
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Char::class.javaPrimitiveType -> '\u0000'
            else -> null
        }
    }

    private fun install(
        xposedModule: XposedModule,
        executable: Executable,
        hooker: Hooker
    ): Boolean {
        return runCatching {
            executable.isAccessible = true
            xposedModule.deoptimize(executable)
            xposedModule.hook(executable).intercept(hooker)
            true
        }.onFailure { error ->
            HookLogger.e(
                TAG,
                "安装单卡片横滑方法失败: ${executable.declaringClass.name}.${executable.name}",
                error
            )
        }.getOrDefault(false)
    }

    private fun ensureTouchHook(xposedModule: XposedModule, player: View) {
        val method = findTouchDispatchMethod(player.javaClass) ?: run {
            HookLogger.w(TAG, "跳过横滑触摸 Hook: player.dispatchTouchEvent 不可用")
            return
        }
        synchronized(hookedTouchMethods) {
            if (!hookedTouchMethods.add(method)) return
        }

        runCatching {
            method.isAccessible = true
            xposedModule.deoptimize(method)
            xposedModule.hook(method).intercept(DispatchTouchHook())
            HookLogger.d(TAG, "已安装媒体卡片触摸分发 Hook: ${method.declaringClass.name}")
        }.onFailure { error ->
            hookedTouchMethods.remove(method)
            HookLogger.e(TAG, "安装媒体卡片触摸分发 Hook 失败", error)
        }
    }

    private fun findTouchDispatchMethod(clazz: Class<*>): Method? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            current.declaredMethods.firstOrNull { method ->
                method.name == "dispatchTouchEvent" &&
                    method.parameterCount == 1 &&
                    method.parameterTypes[0] == MotionEvent::class.java &&
                    method.returnType == Boolean::class.javaPrimitiveType
            }?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun loadClass(classLoader: ClassLoader, name: String): Class<*>? {
        return runCatching { classLoader.loadClass(name) }.getOrNull()
    }

    private fun findMethod(
        clazz: Class<*>,
        name: String,
        predicate: (Method) -> Boolean
    ): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            current.declaredMethods.firstOrNull { it.name == name && predicate(it) }
                ?.apply { isAccessible = true }
                ?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun findField(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            runCatching { current.getDeclaredField(name) }
                .onSuccess { field ->
                    field.isAccessible = true
                }
                .getOrNull()
                ?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun readField(target: Any?, name: String): Any? {
        target ?: return null
        return runCatching { findField(target.javaClass, name)?.get(target) }.getOrNull()
    }

    private enum class Action {
        ATTACH,
        DETACH,
        BIND
    }

    private class LayoutConstructorHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            val controller = chain.thisObject ?: return result
            val classLoader = controller.javaClass.classLoader ?: return result
            runCatching {
                NotificationMediaSingleCardSwitcherHooker.registerLayoutController(
                    controller,
                    classLoader
                )
            }.onFailure { error ->
                HookLogger.e(TAG, "初始化媒体卡片选择器失败", error)
            }
            return result
        }
    }

    private class ViewControllerHook(
        private val action: Action
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val viewController = chain.thisObject ?: return chain.proceed()
            val state = NotificationMediaSingleCardSwitcherHooker.viewStates[viewController]

            if (action == Action.DETACH) {
                state?.let { NotificationMediaSingleCardSwitcherHooker.removePlayer(it) }
                val result = chain.proceed()
                state?.onDetached()
                return result
            }

            val result = chain.proceed()
            when (action) {
                Action.ATTACH -> {
                    val holder = chain.args.firstOrNull() ?: return result
                    state?.let {
                        NotificationMediaSingleCardSwitcherHooker.attachPlayer(it, holder)
                    }
                }

                Action.BIND -> state?.onNativeBind(chain.args.firstOrNull())
                Action.DETACH -> Unit
            }
            return result
        }
    }

    private class DispatchTouchHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val view = chain.thisObject as? View ?: return chain.proceed()
            val event = chain.args.firstOrNull() as? MotionEvent ?: return chain.proceed()
            val state = NotificationMediaSingleCardSwitcherHooker.playerStates[view]
                ?: return chain.proceed()
            return state.dispatchTouchEvent(view, event, chain)
        }
    }

    private fun attachPlayer(state: ControllerState, holder: Any) {
        val player = state.attach(holder) ?: return
        playerStates[player] = state
        state.ensureTouchHook(player)
    }

    private fun removePlayer(state: ControllerState) {
        state.player?.get()?.let { playerStates.remove(it) }
    }

    private class ControllerState(
        layoutController: Any,
        viewController: Any,
        val mediaDataManager: Any,
        sortUtils: Any,
        private val bindMethod: Method,
        val accessor: ReflectedMediaDataAccessor
    ) {
        private val layoutControllerRef = WeakReference(layoutController)
        private val viewControllerRef = WeakReference(viewController)
        private val sortUtilsRef = WeakReference(sortUtils)
        private val mainHandler = Handler(Looper.getMainLooper())
        private val bindLock = Any()
        private var bindingSelected = false

        var listenerProxy: Any? = null
        var player: WeakReference<View>? = null
            private set

        private var seekBar: WeakReference<View>? = null
        private var touchDownX = 0f
        private var touchDownY = 0f
        private var touchSlop = 0
        private var touchThreshold = 0f
        private var touchIgnored = false
        private var touchDirectionDecided = false
        private var touchHorizontal = false
        private var touchConsumed = false
        private var parentInterceptDisallowed = false

        private val selection = NotificationMediaSelectionCoordinator(
            accessor = accessor,
            nativeOrder = ::nativeOrder,
            nativeTopKey = ::nativeTopKey,
            bindSelected = ::bindSelected
        )

        fun seedFromNativeSort() {
            val initialEntries = LinkedHashMap<String, Any>()
            val sort = sortUtilsRef.get()
            val sortList = NotificationMediaSingleCardSwitcherHooker.readField(
                sort,
                "mediaDataList"
            ) as? Iterable<*>
            sortList?.forEach { sortKey ->
                if (sortKey == null) return@forEach
                val key = accessor.sortKey(sortKey) ?: return@forEach
                val data = accessor.sortData(sortKey) ?: return@forEach
                initialEntries[key] = data
            }

            if (initialEntries.isEmpty()) {
                val sortMap = NotificationMediaSingleCardSwitcherHooker.readField(
                    sort,
                    "mediaDataToSortKey"
                ) as? Map<*, *>
                sortMap?.entries?.forEach { entry ->
                    val mapKey = entry.key
                    val sortKey = entry.value
                    val key = if (mapKey is String) {
                        mapKey
                    } else if (sortKey != null) {
                        accessor.sortKey(sortKey)
                    } else {
                        null
                    }
                    val data = if (sortKey != null) accessor.sortData(sortKey) else null
                    if (key != null && data != null) initialEntries[key] = data
                }
            }
            selection.seed(initialEntries.map { it.key to it.value })
            updatePageIndicator()
        }

        fun onMediaDataLoaded(key: String, oldKey: String?, data: Any) {
            runOnMain {
                selection.onMediaDataLoaded(key, oldKey, data)
                updatePageIndicator()
            }
        }

        fun onMediaDataRemoved(key: String) {
            runOnMain {
                selection.onMediaDataRemoved(key)
                updatePageIndicator()
            }
        }

        fun onNativeBind(data: Any?) {
            runOnMain {
                selection.onNativeBind(data)
                updatePageIndicator()
            }
        }

        fun attach(holder: Any): View? {
            val currentPlayer = NotificationMediaSingleCardSwitcherHooker.readField(
                holder,
                "player"
            ) as? View ?: return null
            player = WeakReference(currentPlayer)
            seekBar = (NotificationMediaSingleCardSwitcherHooker.readField(
                holder,
                "seekBar"
            ) as? View)?.let(::WeakReference)
            touchSlop = ViewConfiguration.get(currentPlayer.context).scaledTouchSlop
            resetTouch(currentPlayer)
            pageIndicator.attach(currentPlayer)
            updatePageIndicator()
            return currentPlayer
        }

        fun ensureTouchHook(currentPlayer: View) {
            ensureTouchHookForState(currentPlayer)
        }

        fun onDetached() {
            val currentPlayer = player?.get()
            resetTouch(currentPlayer)
            pageIndicator.detach()
            player = null
            seekBar = null
            runOnMain { selection.onDetached() }
        }

        fun dispatchTouchEvent(
            view: View,
            event: MotionEvent,
            chain: Chain
        ): Any? {
            if (!MediaCardRuntimeConfig.current.enabled || selection.size < 2) {
                releaseParentIntercept(view)
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    resetTouch(view)
                }
                return chain.proceed()
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resetTouch(view)
                    touchDownX = event.x
                    touchDownY = event.y
                    touchThreshold = calculateSwitchThreshold(view)
                    touchIgnored = isSeekBarTouch(event)
                    disallowParentIntercept(view)
                    return chain.proceed()
                }

                MotionEvent.ACTION_MOVE -> {
                    if (touchIgnored) return chain.proceed()
                    if (touchConsumed) return true

                    val dx = event.x - touchDownX
                    val dy = event.y - touchDownY
                    val absDx = abs(dx)
                    val absDy = abs(dy)
                    if (!touchDirectionDecided) {
                        if (maxOf(absDx, absDy) <= touchSlop) {
                            return chain.proceed()
                        }
                        when {
                            absDx > absDy * DIRECTION_LOCK_RATIO -> {
                                touchDirectionDecided = true
                                touchHorizontal = true
                            }

                            absDy > absDx * DIRECTION_LOCK_RATIO -> {
                                touchDirectionDecided = true
                                touchHorizontal = false
                                releaseParentIntercept(view)
                                return chain.proceed()
                            }

                            else -> return chain.proceed()
                        }
                    }

                    if (!touchHorizontal || absDx < touchThreshold) {
                        return chain.proceed()
                    }

                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    runCatching {
                        chain.proceed(arrayOf<Any?>(cancel))
                    }.onFailure { error ->
                        HookLogger.w(TAG, "取消原生媒体卡片触摸目标失败", error)
                    }
                    cancel.recycle()

                    touchConsumed = true
                    val visualStep = if (dx < 0f) 1 else -1
                    val step = if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                        -visualStep
                    } else {
                        visualStep
                    }
                    runOnMain {
                        selection.selectRelative(step)
                        updatePageIndicator()
                    }
                    return true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (touchConsumed) {
                        resetTouch(view)
                        return true
                    }
                    resetTouch(view)
                }
            }
            return chain.proceed()
        }

        private fun bindSelected(data: Any) {
            if (!MediaCardRuntimeConfig.current.enabled) return
            val target = viewControllerRef.get() ?: return
            synchronized(bindLock) {
                if (bindingSelected) return
                bindingSelected = true
            }
            try {
                bindMethod.invoke(target, data)
            } catch (error: Throwable) {
                HookLogger.e(TAG, "调用原生 bindMediaData 切换媒体失败", error)
            } finally {
                synchronized(bindLock) {
                    bindingSelected = false
                }
            }
        }

        private val pageIndicator = NotificationMediaPageIndicator()

        private fun updatePageIndicator() {
            pageIndicator.update(
                pageCount = selection.size,
                selectedIndex = selection.selectedIndex,
                enabled = MediaCardRuntimeConfig.current.enabled
            )
        }

        private fun nativeOrder(): List<String> {
            val sort = sortUtilsRef.get() ?: return emptyList()
            val sortList = NotificationMediaSingleCardSwitcherHooker.readField(
                sort,
                "mediaDataList"
            ) as? Iterable<*> ?: return emptyList()
            return sortList.mapNotNull { it?.let(accessor::sortKey) }.distinct()
        }

        private fun nativeTopKey(): String? {
            val data = NotificationMediaSingleCardSwitcherHooker.readField(
                layoutControllerRef.get(),
                "topMediaData"
            ) ?: return null
            return accessor.notificationKey(data)
        }

        private fun isSeekBarTouch(event: MotionEvent): Boolean {
            val view = seekBar?.get() ?: return false
            if (!view.isShown || view.width <= 0 || view.height <= 0) return false
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val x = event.rawX
            val y = event.rawY
            return x >= location[0] && x < location[0] + view.width &&
                y >= location[1] && y < location[1] + view.height
        }

        private fun calculateSwitchThreshold(view: View): Float {
            val density = view.resources.displayMetrics.density
            return maxOf(
                touchSlop * 2f,
                MIN_SWITCH_DISTANCE_DP * density,
                view.width * SWITCH_DISTANCE_FRACTION
            )
        }

        private fun disallowParentIntercept(view: View) {
            if (parentInterceptDisallowed) return
            val parent = view.parent ?: return
            runCatching {
                parent.requestDisallowInterceptTouchEvent(true)
                parentInterceptDisallowed = true
            }.onFailure { error ->
                HookLogger.w(TAG, "禁止原生媒体卡片父容器拦截触摸失败", error)
            }
        }

        private fun releaseParentIntercept(view: View) {
            if (!parentInterceptDisallowed) return
            runCatching {
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }.onFailure { error ->
                HookLogger.w(TAG, "恢复原生媒体卡片父容器拦截失败", error)
            }
            parentInterceptDisallowed = false
        }

        private fun resetTouch(view: View? = null) {
            if (view != null) releaseParentIntercept(view)
            touchDownX = 0f
            touchDownY = 0f
            touchThreshold = 0f
            touchIgnored = false
            touchDirectionDecided = false
            touchHorizontal = false
            touchConsumed = false
        }

        private fun runOnMain(block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                block()
            } else {
                mainHandler.post(block)
            }
        }

        private fun ensureTouchHookForState(currentPlayer: View) {
            val module = NotificationMediaSingleCardSwitcherHooker.moduleForTouchHook
                ?: return
            NotificationMediaSingleCardSwitcherHooker.ensureTouchHook(module, currentPlayer)
        }
    }

    private class ReflectedMediaDataAccessor : NotificationMediaDataAccessor {
        private data class DataFields(
            val notificationKey: Field?,
            val token: Field?,
            val active: Field?
        )

        private data class SortKeyFields(
            val key: Field?,
            val data: Field?
        )

        private val dataFields = Collections.synchronizedMap(
            WeakHashMap<Class<*>, DataFields>()
        )
        private val sortKeyFields = Collections.synchronizedMap(
            WeakHashMap<Class<*>, SortKeyFields>()
        )

        override fun notificationKey(data: Any): String? =
            fields(data).notificationKey?.get(data) as? String

        override fun sessionToken(data: Any): Any? =
            fields(data).token?.get(data)

        override fun isActive(data: Any): Boolean =
            (fields(data).active?.get(data) as? Boolean) ?: true

        override fun sortKey(sortKey: Any): String? =
            sortFields(sortKey).key?.get(sortKey) as? String

        override fun sortData(sortKey: Any): Any? =
            sortFields(sortKey).data?.get(sortKey)

        private fun fields(data: Any): DataFields {
            return dataFields.getOrPut(data.javaClass) {
                DataFields(
                    notificationKey = NotificationMediaSingleCardSwitcherHooker.findField(
                        data.javaClass,
                        "notificationKey"
                    ),
                    token = NotificationMediaSingleCardSwitcherHooker.findField(
                        data.javaClass,
                        "token"
                    ),
                    active = NotificationMediaSingleCardSwitcherHooker.findField(
                        data.javaClass,
                        "active"
                    )
                )
            }
        }

        private fun sortFields(sortKey: Any): SortKeyFields {
            return sortKeyFields.getOrPut(sortKey.javaClass) {
                SortKeyFields(
                    key = NotificationMediaSingleCardSwitcherHooker.findField(
                        sortKey.javaClass,
                        "key"
                    ),
                    data = NotificationMediaSingleCardSwitcherHooker.findField(
                        sortKey.javaClass,
                        "data"
                    )
                )
            }
        }
    }

    private var moduleForTouchHook: XposedModule? = null
}
