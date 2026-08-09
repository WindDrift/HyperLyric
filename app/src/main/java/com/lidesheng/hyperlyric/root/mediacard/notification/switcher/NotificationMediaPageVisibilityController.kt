package com.lidesheng.hyperlyric.root.mediacard.notification.switcher

import android.view.View
import android.view.ViewGroup

/**
 * Owns which carousel pages are allowed to draw during an interaction.
 *
 * The renderer still owns page geometry and scrolling. This class only keeps
 * the visibility policy and invalidates delayed hide callbacks when a new
 * gesture or page rebuild starts.
 */
internal class NotificationMediaPageVisibilityController(
    private val pageContainer: () -> ViewGroup?,
    private val onBleedChanged: (Boolean) -> Unit
) {
    private var revealedForGesture = false
    private var generation = 0

    val isRevealedForGesture: Boolean
        get() = revealedForGesture

    fun revealForGesture(currentIndex: Int, direction: Int) {
        val pages = pageContainer() ?: return
        val count = pages.childCount
        if (count == 0) return

        revealedForGesture = true
        generation++
        onBleedChanged(true)

        val visibleIndices = mutableSetOf<Int>()
        if (currentIndex in 0 until count) {
            visibleIndices += currentIndex
            val adjacentIndex = currentIndex + direction
            if (direction != 0 && adjacentIndex in 0 until count) {
                visibleIndices += adjacentIndex
            }
        } else {
            visibleIndices += 0
        }

        for (index in 0 until count) {
            pages.getChildAt(index).visibility = if (index in visibleIndices) {
                View.VISIBLE
            } else {
                View.INVISIBLE
            }
        }
    }

    fun hideExcept(index: Int) {
        val pages = pageContainer()
        if (pages == null || pages.childCount == 0) {
            invalidate()
            return
        }

        val target = index.coerceIn(0, pages.childCount - 1)
        for (childIndex in 0 until pages.childCount) {
            pages.getChildAt(childIndex).visibility = if (childIndex == target) {
                View.VISIBLE
            } else {
                View.INVISIBLE
            }
        }
        revealedForGesture = false
        generation++
        onBleedChanged(false)
    }

    fun beginHideSchedule(): Int {
        generation++
        return generation
    }

    fun isScheduleCurrent(token: Int): Boolean = token == generation

    fun invalidate() {
        revealedForGesture = false
        generation++
        onBleedChanged(false)
    }
}
