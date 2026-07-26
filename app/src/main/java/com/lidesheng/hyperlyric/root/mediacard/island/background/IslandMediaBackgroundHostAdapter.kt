package com.lidesheng.hyperlyric.root.mediacard.island.background

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.lidesheng.hyperlyric.root.mediacard.background.MediaFlowOverlayLayout
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

internal enum class IslandMediaBackgroundHostRole {
    REAL,
    DUMMY
}

internal data class IslandMediaBackgroundHolderHost(
    val role: IslandMediaBackgroundHostRole,
    val player: ViewGroup,
    val parent: ViewGroup,
    val replacedNativeBackground: View,
    val customBackground: ImageView
)

/**
 * Owns the media-background slot in each real/dummy media holder.
 *
 * Replaces the holder's native background slot with the custom media background.
 * Fake/minibar transition sizing remains owned by Xiaomi SystemUI.
 */
internal class IslandMediaBackgroundHostAdapter(
    private val holderField: Field,
    private val dummyHolderField: Field,
    private val playerField: Field,
    private val titleTextField: Field,
    private val mediaBgViewField: Field
) {
    private val hosts = Collections.synchronizedMap(
        WeakHashMap<Any, IslandMediaBackgroundHolderHost>()
    )

    fun getOrCreateHosts(binder: Any): List<Pair<Any, IslandMediaBackgroundHolderHost>> {
        return holderEntries(binder).mapNotNull { (role, holder) ->
            getOrCreateHost(role, holder)?.let { holder to it }
        }
    }

    fun detach(binder: Any) {
        holderEntries(binder).forEach { (_, holder) ->
            val host = hosts[holder] ?: return@forEach
            host.customBackground.setImageDrawable(null)
            host.customBackground.visibility = View.INVISIBLE
            host.customBackground.scaleY = 1f
        }
    }

    /**
     * Returns the already attached holder background without creating or
     * reparenting a view.  MiniBar tracking must only transform this stable
     * dummy host, exactly like the SystemUI binder collector does.
     */
    fun findHost(holder: Any): IslandMediaBackgroundHolderHost? {
        return hosts[holder]?.takeIf { host ->
            host.customBackground.parent === host.parent
        }
    }

    private fun holderEntries(binder: Any): List<Pair<IslandMediaBackgroundHostRole, Any>> {
        return listOfNotNull(
            holderField.get(binder)?.let { IslandMediaBackgroundHostRole.REAL to it },
            dummyHolderField.get(binder)?.let { IslandMediaBackgroundHostRole.DUMMY to it }
        ).distinctBy { (_, holder) -> holder }
    }

    private fun getOrCreateHost(
        role: IslandMediaBackgroundHostRole,
        holder: Any
    ): IslandMediaBackgroundHolderHost? {
        hosts[holder]?.takeIf { host ->
            host.customBackground.parent === host.parent
        }?.let { return it }
        hosts.remove(holder)

        val player = playerField.get(holder) as? ViewGroup ?: return null
        val titleText = titleTextField.get(holder) as? View ?: return null
        val nativeBackground = mediaBgViewField.get(holder) as? View ?: return null
        val existing = findCustomBackground(player)
        val parent = (existing?.parent ?: titleText.parent) as? ViewGroup ?: return null
        val customBackground = existing ?: createBackgroundView(nativeBackground, parent)
        ?: return null

        return IslandMediaBackgroundHolderHost(
            role = role,
            player = player,
            parent = parent,
            replacedNativeBackground = nativeBackground,
            customBackground = customBackground
        ).also { hosts[holder] = it }
    }

    private fun findCustomBackground(root: View): ImageView? {
        if (root is ImageView && root.tag == CUSTOM_BACKGROUND_TAG) return root
        val group = root as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findCustomBackground(group.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun createBackgroundView(anchor: View, parent: ViewGroup): ImageView? {
        val layoutParams =
            MediaFlowOverlayLayout.createConstraintFill(anchor.layoutParams) ?: return null
        val mediaBackgroundId = anchor.resources.getIdentifier(
            "media_bg",
            "id",
            "com.android.systemui"
        )
        if (mediaBackgroundId == 0) return null
        val background = ImageView(anchor.context).apply {
            id = mediaBackgroundId
            tag = CUSTOM_BACKGROUND_TAG
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = anchor.outlineProvider
            clipToOutline = anchor.clipToOutline
            setPadding(0, 0, 0, 0)
            visibility = View.INVISIBLE
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val index = parent.indexOfChild(anchor).coerceIn(0, parent.childCount)
        parent.addView(background, index, layoutParams)
        parent.removeView(anchor)
        return background
    }

    private companion object {
        const val CUSTOM_BACKGROUND_TAG = "hyperlyric.island_media_holder_background"
    }
}
