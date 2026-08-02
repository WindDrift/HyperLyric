package com.lidesheng.hyperlyric.root.mediacard.notification.aod

import android.view.View
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker

/** Prevents the Full AOD transition from temporarily compressing a media card. */
internal class NotificationMediaFullAodAnimatedHeightHook(
    private val keepExpanded: () -> Boolean
) : Hooker {
    override fun intercept(chain: Chain): Any? {
        if (!keepExpanded()) return chain.proceed()

        val mediaHeader = chain.thisObject as? View ?: return chain.proceed()
        val expandedHeight = mediaHeader.expandedMediaHeightOrNull() ?: return chain.proceed()
        return chain.proceed(arrayOf<Any?>(expandedHeight))
    }

    @Suppress("DiscouragedApi")
    private fun View.expandedMediaHeightOrNull(): Int? {
        val resourceId = resources.getIdentifier(
            "qs_media_session_height_expanded",
            "dimen",
            context.packageName
        )
        return resourceId.takeIf { it != 0 }?.let(resources::getDimensionPixelSize)
    }
}
