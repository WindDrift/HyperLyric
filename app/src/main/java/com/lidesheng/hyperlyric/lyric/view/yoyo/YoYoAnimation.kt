package com.lidesheng.hyperlyric.lyric.view.yoyo

import android.animation.Animator
import android.view.View
import com.daimajia.androidanimations.library.YoYo

object YoYoAnimation {

    private const val KEY_ANIM_LOCK = 0x7F_114514
    private const val KEY_ANIM_HANDLE = 0x7F_191981

    fun <T : View> switchContent(
        target: T,
        outConfig: AnimConfig,
        inConfig: AnimConfig,
        action: (T) -> Unit
    ) {
        cancelAnimation(target)
        if (target.parent == null || !target.isAttachedToWindow) {
            action(target)
            return
        }
        val token = Any()
        target.setTag(KEY_ANIM_LOCK, token)

        val outHandle = YoYo.with(outConfig.technique)
            .duration(outConfig.duration)
            .interpolate(outConfig.interpolator)
            .withListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(p0: Animator) {}
                override fun onAnimationRepeat(p0: Animator) {}
                override fun onAnimationCancel(p0: Animator) {
                    if (target.getTag(KEY_ANIM_LOCK) === token) {
                        target.setTag(KEY_ANIM_LOCK, null)
                    }
                }

                override fun onAnimationEnd(p0: Animator) {
                    if (target.getTag(KEY_ANIM_LOCK) !== token) return

                    // 执行内容更新
                    action(target)
                    // The commit may detach the target or synchronously start another update.
                    if (target.getTag(KEY_ANIM_LOCK) !== token) return

                    val inHandle = YoYo.with(inConfig.technique)
                        .duration(inConfig.duration)
                        .interpolate(inConfig.interpolator)
                        .withListener(object : Animator.AnimatorListener {
                            override fun onAnimationStart(p0: Animator) {}
                            override fun onAnimationRepeat(p0: Animator) {}
                            override fun onAnimationCancel(p0: Animator) {
                                if (target.getTag(KEY_ANIM_LOCK) === token) {
                                    target.setTag(KEY_ANIM_LOCK, null)
                                }
                            }

                            override fun onAnimationEnd(p0: Animator) {
                                if (target.getTag(KEY_ANIM_LOCK) === token) {
                                    target.setTag(KEY_ANIM_LOCK, null)
                                    target.setTag(KEY_ANIM_HANDLE, null)
                                }
                            }
                        })
                        .playOn(target)

                    if (target.getTag(KEY_ANIM_LOCK) === token) {
                        target.setTag(KEY_ANIM_HANDLE, inHandle)
                    }
                }
            })
            .playOn(target)

        if (target.getTag(KEY_ANIM_LOCK) === token) {
            target.setTag(KEY_ANIM_HANDLE, outHandle)
        }
    }

    fun cancelAnimation(target: View) {
        val handle = target.getTag(KEY_ANIM_HANDLE) as? YoYo.YoYoString
        // Retire callbacks before stop(), which may synchronously dispatch cancel/end.
        target.setTag(KEY_ANIM_LOCK, null)
        target.setTag(KEY_ANIM_HANDLE, null)
        handle?.stop(true)
    }
}
